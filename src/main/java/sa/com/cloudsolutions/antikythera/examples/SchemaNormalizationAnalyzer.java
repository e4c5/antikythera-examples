package sa.com.cloudsolutions.antikythera.examples;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.generator.TypeWrapper;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;
import sa.com.cloudsolutions.antikythera.parser.converter.EntityMappingResolver;
import sa.com.cloudsolutions.antikythera.parser.converter.EntityMetadata;
import com.raditha.hql.converter.JoinMapping;

import sa.com.cloudsolutions.antikythera.examples.util.DataMigrationGenerator;
import sa.com.cloudsolutions.antikythera.examples.util.InsteadOfTriggerGenerator;
import sa.com.cloudsolutions.antikythera.examples.util.LiquibaseGenerator;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AI-powered analyzer that inspects {@code @Entity} classes for database
 * normalization violations (1NF, 2NF, 3NF, BCNF, cross-entity, etc.) and
 * proposes refactoring strategies including Liquibase migration hints.
 *
 * <p>All entity profiles are collected first (Phase 1) and then sent to the
 * LLM in a single batch call (Phase 2), so the model can reason across entity
 * boundaries and detect cross-entity violations such as denormalized copies
 * and missing FK relationships.</p>
 *
 * <p>Extends {@link AbstractRepositoryAnalyzer} to reuse the AI-service wiring
 * and type-iteration infrastructure. Checkpointing is intentionally bypassed
 * for this analyzer because profile extraction is cheap and the LLM call is a
 * single batch operation at the end.</p>
 */
@SuppressWarnings("java:S106")
public class SchemaNormalizationAnalyzer extends AbstractRepositoryAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(SchemaNormalizationAnalyzer.class);

    private final String normalizationSystemPrompt;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Phase-1 accumulator: profiles built from source code, no LLM call yet. */
    private final List<EntityProfile> allProfiles = new ArrayList<>();

    /** Phase-2 accumulator: reports returned from the LLM. */
    private final List<EntityNormalizationReport> allReports = new ArrayList<>();

    /** Set by parseBatchResponse; true when the last response contained malformed/truncated JSON. */
    private boolean lastResponseTruncated = false;

    /** Maps entity simple name → Java package name, populated during analyzeType(). */
    private final Map<String, String> entityPackageMap = new HashMap<>();

    /**
     * Persistence import package detected from the source entities.
     * Defaults to {@code javax.persistence}; updated to {@code jakarta.persistence}
     * if any scanned entity imports from that namespace.
     */
    private String persistencePackage = "javax.persistence";

    // -------------------------------------------------------------------------
    // Profile records — serialised as JSON and sent to the LLM
    // -------------------------------------------------------------------------

    record EntityProfile(
            String entityName,
            String tableName,
            List<FieldProfile> fields,
            List<RelationshipProfile> relationships) {}

    record FieldProfile(String javaName, String columnName, boolean isId,
                        boolean isNullable, String columnType) {}

    record RelationshipProfile(String javaName, String annotationType,
                               String joinColumn, String referencedColumn,
                               String targetEntity) {}

    // -------------------------------------------------------------------------
    // Result records — deserialised from the LLM response
    // -------------------------------------------------------------------------

    /**
     * Structured data migration plan returned by the LLM for issues that involve
     * splitting or extracting a table.  Maps directly to {@link InsteadOfTriggerGenerator.ViewDescriptor}.
     */
    record DataMigrationPlan(
            String sourceTable,
            String baseTable,
            List<String> newTables,
            List<ColumnMappingEntry> columnMappings,
            List<ForeignKeyEntry> foreignKeys) {

        /** Maps a column in the old (source) table to a column in one of the new tables. */
        record ColumnMappingEntry(String viewColumn, String targetTable, String targetColumn) {}

        /** FK edge between two of the new normalized tables. */
        record ForeignKeyEntry(String fromTable, String fromColumn, String toTable, String toColumn) {}
    }

    record NormalizationIssue(
            String normalizationForm,
            String violation,
            List<String> affectedFields,
            String proposal,
            List<String> suggestedEntities,
            String liquibaseMigrationHint,
            DataMigrationPlan dataMigrationPlan) {}

    record EntityNormalizationReport(String entityName, List<NormalizationIssue> issues) {}

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Creates a new analyzer. The Liquibase path parameter is accepted for
     * consistency with {@link QueryOptimizationChecker} but is not required for
     * pure normalization analysis.
     */
    @SuppressWarnings("unchecked")
    public SchemaNormalizationAnalyzer() throws IOException {
        Map<String, Object> aiConfig = (Map<String, Object>) Settings.getProperty("ai_service");
        if (aiConfig == null) {
            aiConfig = new HashMap<>();
        }
        this.aiService = AIServiceFactory.create(aiConfig);
        this.aiService.configure(aiConfig);
        // Checkpoint manager is required by the base class but is effectively
        // bypassed for this analyzer (shouldSkipType always returns false for
        // checkpoint checks, and the LLM call happens as one batch at the end).
        this.checkpointManager = new CheckpointManager(new File(".normalization-checkpoint.json"));
        this.normalizationSystemPrompt = loadNormalizationSystemPrompt();
    }

    private String loadNormalizationSystemPrompt() throws IOException {
        try (InputStream is = getClass().getResourceAsStream(
                "/ai-prompts/normalization-system-prompt.md")) {
            if (is == null) {
                throw new IllegalStateException(
                        "Normalization system prompt not found: /ai-prompts/normalization-system-prompt.md");
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // -------------------------------------------------------------------------
    // AbstractRepositoryAnalyzer implementation
    // -------------------------------------------------------------------------

    @Override
    protected boolean shouldProcess(TypeWrapper typeWrapper) {
        return EntityMappingResolver.isEntity(typeWrapper);
    }

    /**
     * Phase 1: build the entity profile and add it to {@link #allProfiles}.
     * No LLM call is made here; that happens in {@link #afterAnalysisLoop()}.
     */
    @Override
    protected void analyzeType(TypeWrapper typeWrapper) throws IOException {
        String fqn = typeWrapper.getFullyQualifiedName();
        EntityMetadata metadata = EntityMappingResolver.buildOnTheFly(typeWrapper);
        if (metadata == null) {
            logger.warn("Could not build entity metadata for {}", fqn);
            return;
        }

        EntityProfile profile = buildEntityProfile(typeWrapper, metadata);
        allProfiles.add(profile);

        // Store package for entity generation (derived from FQN)
        if (fqn.contains(".")) {
            entityPackageMap.put(profile.entityName(), fqn.substring(0, fqn.lastIndexOf('.')));
        }

        // Detect javax vs jakarta persistence from the compilation unit (check once)
        if ("javax.persistence".equals(persistencePackage)) {
            typeWrapper.getType().findCompilationUnit().ifPresent(cu ->
                cu.getImports().stream()
                    .map(id -> id.getNameAsString())
                    .filter(name -> name.startsWith("jakarta") && name.contains("persistence"))
                    .findFirst()
                    .ifPresent(ignored -> persistencePackage = "jakarta.persistence"));
        }

        if (!quietMode) {
            System.out.printf("  📊 Profiling entity: %s (table: %s, %d fields, %d relationships)%n",
                    profile.entityName(), profile.tableName(),
                    profile.fields().size(), profile.relationships().size());
        }
    }

    /**
     * Phase 2: send all collected profiles to the LLM in a single request.
     *
     * <p>If the response is malformed (e.g. the model wrapped the JSON in markdown fences
     * that were not stripped, or a genuine network/format error occurred), the same full
     * set of profiles is retried up to {@code max_continuations} times. Entities absent
     * from a well-formed response are treated as clean (no violations), as the system
     * prompt instructs the model to omit them.</p>
     */
    @Override
    protected void afterAnalysisLoop() throws IOException, InterruptedException {
        if (allProfiles.isEmpty()) {
            System.out.println("No entity profiles collected — nothing to send to LLM.");
            return;
        }

        int maxAttempts = getMaxContinuations();
        int attempt = 0;

        System.out.printf("%n🤖 Sending all %d entity profiles to LLM…%n", allProfiles.size());

        while (attempt < maxAttempts) {
            if (attempt > 0) {
                System.out.printf("  ↩ Retry %d/%d…%n", attempt, maxAttempts - 1);
            }

            List<EntityNormalizationReport> reports = sendBatchToLLM(allProfiles);

            TokenUsage tokenUsage = aiService.getLastTokenUsage();
            cumulativeTokenUsage.add(tokenUsage);
            if (!quietMode) {
                System.out.printf("  🤖 AI analysis (attempt %d): %s%n",
                        attempt + 1, tokenUsage.getFormattedReport());
            }

            if (!lastResponseTruncated) {
                allReports.addAll(reports);
                break;
            }

            logger.warn("Response malformed on attempt {}; retrying full request", attempt + 1);
            attempt++;
        }

        if (lastResponseTruncated) {
            logger.warn("LLM response was malformed after {} attempts — no results recorded", maxAttempts);
        }

        // Report only entities that had violations (clean entities are omitted per prompt)
        for (EntityNormalizationReport report : allReports) {
            String tableName = allProfiles.stream()
                    .filter(p -> p.entityName().equals(report.entityName()))
                    .findFirst()
                    .map(EntityProfile::tableName)
                    .orElse("?");
            reportEntityResults(report.entityName(), tableName, report.issues());
            totalRecommendations += report.issues().size();
        }

        generateMigrationArtifacts();
    }

    /**
     * Reads {@code schema_normalization.max_continuations} from settings.
     * Defaults to 3 if not configured.
     */
    @SuppressWarnings("unchecked")
    private int getMaxContinuations() {
        Map<String, Object> normConfig =
                (Map<String, Object>) Settings.getProperty("schema_normalization");
        if (normConfig != null) {
            Object v = normConfig.get("max_continuations");
            if (v instanceof Integer i) return i;
            if (v instanceof String s) return Integer.parseInt(s);
        }
        return 10; // enough for several binary splits of a large entity set
    }

    /**
     * Bypass checkpoint-based skipping so that all entity profiles are always
     * collected. Cross-entity analysis requires the full picture; profile
     * extraction is cheap enough to redo on every run.
     *
     * <p>The target_class / skip_class filters are still honoured.</p>
     */
    @Override
    protected boolean shouldSkipType(String fullyQualifiedName) {
        if (targetClass != null && !targetClass.equals(fullyQualifiedName)) {
            logger.debug("Skipping type (target_class filter): {}", fullyQualifiedName);
            return true;
        }
        if (skipClass != null && skipClass.equals(fullyQualifiedName)) {
            logger.debug("Skipping type (skip_class filter): {}", fullyQualifiedName);
            return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Entity profile extraction
    // -------------------------------------------------------------------------

    private EntityProfile buildEntityProfile(TypeWrapper typeWrapper, EntityMetadata metadata) {
        List<FieldProfile> fields = new ArrayList<>();
        List<RelationshipProfile> relations = new ArrayList<>();

        TypeDeclaration<?> typeDecl = typeWrapper.getType();
        if (typeDecl != null) {
            for (FieldDeclaration fd : typeDecl.getFields()) {
                if (isTransient(fd)) continue;
                if (isRelationshipField(fd)) {
                    buildRelationshipProfiles(fd, metadata, relations);
                } else {
                    buildFieldProfiles(fd, metadata, fields);
                }
            }
        }

        return new EntityProfile(typeWrapper.getName(), metadata.tableName(), fields, relations);
    }

    private boolean isTransient(FieldDeclaration fd) {
        return fd.isTransient() || fd.isStatic() || fd.getAnnotationByName("Transient").isPresent();
    }

    private boolean isRelationshipField(FieldDeclaration fd) {
        return fd.getAnnotationByName("OneToOne").isPresent()
                || fd.getAnnotationByName("OneToMany").isPresent()
                || fd.getAnnotationByName("ManyToOne").isPresent()
                || fd.getAnnotationByName("ManyToMany").isPresent();
    }

    private String getRelationshipAnnotationType(FieldDeclaration fd) {
        for (String ann : List.of("OneToOne", "OneToMany", "ManyToOne", "ManyToMany")) {
            if (fd.getAnnotationByName(ann).isPresent()) return ann;
        }
        return "Unknown";
    }

    private void buildRelationshipProfiles(FieldDeclaration fd, EntityMetadata metadata,
                                           List<RelationshipProfile> relations) {
        String annotationType = getRelationshipAnnotationType(fd);
        for (VariableDeclarator vd : fd.getVariables()) {
            String javaName = vd.getNameAsString();
            JoinMapping jm = metadata.relationshipMap().get(javaName);
            relations.add(new RelationshipProfile(
                    javaName,
                    annotationType,
                    jm != null ? jm.joinColumn() : null,
                    jm != null ? jm.referencedColumn() : null,
                    jm != null ? jm.targetEntity() : null));
        }
    }

    private void buildFieldProfiles(FieldDeclaration fd, EntityMetadata metadata,
                                    List<FieldProfile> fields) {
        boolean isId = fd.getAnnotationByName("Id").isPresent();
        boolean isNullable = extractNullable(fd);
        for (VariableDeclarator vd : fd.getVariables()) {
            String javaName = vd.getNameAsString();
            String columnName = metadata.propertyToColumnMap().get(javaName);
            if (columnName == null) {
                columnName = AbstractCompiler.camelToSnakeCase(javaName);
            }
            fields.add(new FieldProfile(javaName, columnName, isId, isNullable, vd.getTypeAsString()));
        }
    }

    private boolean extractNullable(FieldDeclaration fd) {
        Optional<AnnotationExpr> colAnn = fd.getAnnotationByName("Column");
        if (colAnn.isPresent()) {
            Map<String, Expression> attrs = AbstractCompiler.extractAnnotationAttributes(colAnn.get());
            Expression nullable = attrs.get("nullable");
            if (nullable != null && "false".equals(nullable.toString())) {
                return false;
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // LLM interaction
    // -------------------------------------------------------------------------

    private List<EntityNormalizationReport> sendBatchToLLM(List<EntityProfile> batch)
            throws IOException, InterruptedException {
        String userContent = objectMapper.writeValueAsString(batch);
        String payload = buildLLMRequest(userContent);
        String response = aiService.sendRawRequest(payload);
        logger.info("=== LLM RAW RESPONSE ===\n{}", response);
        return parseBatchResponse(response, batch);
    }

    /**
     * Builds the provider-specific API request payload that carries the
     * normalization system prompt and the batch of entity profiles as user content.
     */
    private String buildLLMRequest(String userContent) throws IOException {
        if (aiService instanceof GeminiAIService) {
            return buildGeminiRequest(userContent);
        }
        return buildOpenAIRequest(userContent);
    }

    private String buildGeminiRequest(String userContent) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();

        // system_instruction
        ObjectNode sysInstr = root.putObject("system_instruction");
        sysInstr.putArray("parts").addObject().put("text", normalizationSystemPrompt);

        // contents (user message)
        ObjectNode userMsg = root.putArray("contents").addObject();
        userMsg.put("role", "user");
        userMsg.putArray("parts").addObject().put("text", userContent);

        // generationConfig — request JSON output
        root.putObject("generationConfig").put("responseMimeType", "application/json");

        return objectMapper.writeValueAsString(root);
    }

    private String buildOpenAIRequest(String userContent) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();

        // Use the model from config; fall back to a sensible default
        String model = aiService.getConfigString("model", "gpt-4o");
        root.put("model", model);

        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content", normalizationSystemPrompt);
        messages.addObject().put("role", "user").put("content", userContent);

        // response_format json_object is added only for native OpenAI.
        // It ensures strict JSON output but forbids top-level arrays, so GPT models
        // wrap the result in an object — which we unwrap below.
        // OpenRouter (and other compatible providers) must NOT receive this flag:
        // their models may wrap the array with an unpredictable key that breaks parsing.
        if (aiService instanceof OpenAIService && !(aiService instanceof OpenRouterService)) {
            root.putObject("response_format").put("type", "json_object");
        }

        return objectMapper.writeValueAsString(root);
    }

    /**
     * Parses the LLM response and returns a report for every entity that appeared in it.
     *
     * <p>Entities absent from the response are <em>not</em> added here — the caller's
     * continuation loop handles them. If the JSON is malformed (truncated output), an
     * empty list is returned so the caller knows the entire batch must be retried.</p>
     */
    private List<EntityNormalizationReport> parseBatchResponse(
            String responseBody, List<EntityProfile> batch) throws IOException {

        List<EntityNormalizationReport> reports = new ArrayList<>();
        if (responseBody == null || responseBody.isBlank()) return reports;

        String jsonText = extractJsonText(responseBody);
        if (jsonText == null || jsonText.isBlank()) return reports;

        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(jsonText);
            lastResponseTruncated = false;
        } catch (Exception e) {
            lastResponseTruncated = true;
            logger.warn("LLM response appears truncated (malformed JSON); {} entities will be retried",
                    batch.size());
            logger.debug("Truncated content prefix: {}",
                    jsonText.substring(0, Math.min(200, jsonText.length())));
            return reports; // empty — signals all in this batch are uncovered
        }

        // Unwrap a single-key object wrapper (e.g. {"results":[...]} or {"analyses":[...]})
        // that json_object mode forces GPT models to emit. Try any field that is an array.
        if (parsed.isObject()) {
            java.util.Iterator<JsonNode> values = parsed.elements();
            while (values.hasNext()) {
                JsonNode candidate = values.next();
                if (candidate.isArray()) {
                    parsed = candidate;
                    break;
                }
            }
        }

        if (!parsed.isArray()) {
            lastResponseTruncated = true;
            logger.warn("Unexpected normalization response structure; expected JSON array — batch will be retried");
            return reports;
        }
        lastResponseTruncated = false;

        for (JsonNode entityNode : parsed) {
            String entityName = entityNode.path("entityName").asText("");
            if (entityName.isBlank()) continue;
            List<NormalizationIssue> issues = new ArrayList<>();
            JsonNode issuesNode = entityNode.path("issues");
            if (issuesNode.isArray()) {
                for (JsonNode issueNode : issuesNode) {
                    issues.add(parseIssue(issueNode));
                }
            }
            reports.add(new EntityNormalizationReport(entityName, issues));
        }

        return reports;
    }

    /**
     * Extracts the inner JSON text from the provider envelope (Gemini candidates array
     * or OpenAI choices array), then strips any markdown code fences the model may have
     * added around the JSON (e.g. {@code ```json ... ```}).
     * Returns {@code null} if the envelope is empty.
     */
    private String extractJsonText(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        String jsonText;
        if (aiService instanceof GeminiAIService) {
            JsonNode candidates = root.path("candidates");
            if (!candidates.isArray() || candidates.isEmpty()) return null;
            jsonText = candidates.get(0).path("content").path("parts").get(0).path("text").asText();
        } else {
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) return null;
            jsonText = choices.get(0).path("message").path("content").asText();
        }
        jsonText = stripMarkdownFences(jsonText);
        logger.info("=== LLM EXTRACTED JSON ===\n{}", jsonText == null ? "null" : jsonText);
        return jsonText;
    }

    /**
     * Removes markdown code fences that some models wrap around JSON output,
     * e.g. {@code ```json\n[...]\n```} → {@code [...]}.
     * Also trims any leading/trailing whitespace or prose outside the JSON block.
     */
    private String stripMarkdownFences(String text) {
        if (text == null || text.isBlank()) return text;
        String trimmed = text.strip();
        // Remove opening fence (```json or ```)
        if (trimmed.startsWith("```")) {
            int newline = trimmed.indexOf('\n');
            if (newline != -1) {
                trimmed = trimmed.substring(newline + 1).strip();
            }
        }
        // Remove closing fence
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.lastIndexOf("```")).strip();
        }
        return trimmed;
    }

    private NormalizationIssue parseIssue(JsonNode node) {
        String form = node.path("normalizationForm").asText("");
        String violation = node.path("violation").asText("");
        String proposal = node.path("proposal").asText("");
        String migrationHint = node.path("liquibaseMigrationHint").asText("");

        List<String> affectedFields = new ArrayList<>();
        node.path("affectedFields").forEach(n -> affectedFields.add(n.asText()));

        List<String> suggestedEntities = new ArrayList<>();
        node.path("suggestedEntities").forEach(n -> suggestedEntities.add(n.asText()));

        DataMigrationPlan plan = parseDataMigrationPlan(node.path("dataMigrationPlan"));

        return new NormalizationIssue(form, violation, affectedFields, proposal,
                suggestedEntities, migrationHint, plan);
    }

    /**
     * Deserialises the optional {@code dataMigrationPlan} node from the LLM response.
     * Returns {@code null} when the node is absent or not a JSON object.
     */
    private DataMigrationPlan parseDataMigrationPlan(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isObject()) {
            return null;
        }

        String sourceTable = node.path("sourceTable").asText("");
        String baseTable   = node.path("baseTable").asText("");

        List<String> newTables = new ArrayList<>();
        node.path("newTables").forEach(n -> newTables.add(n.asText()));

        List<DataMigrationPlan.ColumnMappingEntry> columnMappings = new ArrayList<>();
        for (JsonNode cm : node.path("columnMappings")) {
            columnMappings.add(new DataMigrationPlan.ColumnMappingEntry(
                    cm.path("viewColumn").asText(""),
                    cm.path("targetTable").asText(""),
                    cm.path("targetColumn").asText("")));
        }

        List<DataMigrationPlan.ForeignKeyEntry> foreignKeys = new ArrayList<>();
        for (JsonNode fk : node.path("foreignKeys")) {
            foreignKeys.add(new DataMigrationPlan.ForeignKeyEntry(
                    fk.path("fromTable").asText(""),
                    fk.path("fromColumn").asText(""),
                    fk.path("toTable").asText(""),
                    fk.path("toColumn").asText("")));
        }

        return new DataMigrationPlan(sourceTable, baseTable, newTables, columnMappings, foreignKeys);
    }

    // -------------------------------------------------------------------------
    // Migration artifact generation
    // -------------------------------------------------------------------------

    /**
     * Converts a {@link DataMigrationPlan} from the LLM response into an
     * {@link InsteadOfTriggerGenerator.ViewDescriptor} suitable for the code generators.
     */
    private InsteadOfTriggerGenerator.ViewDescriptor toViewDescriptor(DataMigrationPlan plan) {
        List<InsteadOfTriggerGenerator.ColumnMapping> mappings = plan.columnMappings().stream()
                .map(cm -> new InsteadOfTriggerGenerator.ColumnMapping(
                        cm.viewColumn(), cm.targetTable(), cm.targetColumn()))
                .toList();

        List<InsteadOfTriggerGenerator.ForeignKey> fks = plan.foreignKeys().stream()
                .map(fk -> new InsteadOfTriggerGenerator.ForeignKey(
                        fk.fromTable(), fk.fromColumn(), fk.toTable(), fk.toColumn()))
                .toList();

        return new InsteadOfTriggerGenerator.ViewDescriptor(
                plan.sourceTable(),
                plan.baseTable(),
                plan.newTables(),
                mappings,
                fks);
    }

    /**
     * Generates a SELECT statement for a compatibility view that re-exposes all original
     * columns by joining the new normalized tables, preserving backward compatibility.
     */
    private String buildCompatibilityViewSql(InsteadOfTriggerGenerator.ViewDescriptor view) {
        List<String> selects = view.columnMappings().stream()
                .map(cm -> cm.sourceTable() + "." + cm.sourceColumn() + " AS " + cm.viewColumn())
                .toList();

        StringBuilder sb = new StringBuilder("SELECT ");
        sb.append(String.join(", ", selects));
        sb.append(" FROM ").append(view.baseTable());

        for (InsteadOfTriggerGenerator.ForeignKey fk : view.foreignKeys()) {
            sb.append(" JOIN ").append(fk.fromTable())
              .append(" ON ").append(fk.fromTable()).append(".").append(fk.fromColumn())
              .append(" = ").append(fk.toTable()).append(".").append(fk.toColumn());
        }
        return sb.toString();
    }

    /**
     * For each normalization issue with a structured {@link DataMigrationPlan}, generates
     * and writes Liquibase changesets containing:
     * <ol>
     *   <li>Data migration INSERT-SELECT statements (one per normalized table)</li>
     *   <li>A compatibility view named after the original table</li>
     *   <li>INSTEAD OF triggers for INSERT, UPDATE, and DELETE on the view</li>
     * </ol>
     * <p>If {@code liquibase_master_file} is not configured, the changeset XML is printed
     * to stdout instead.</p>
     */
    private void generateMigrationArtifacts() throws IOException {
        Set<String> processedSourceTables = new HashSet<>();
        List<DataMigrationPlan> plans = new ArrayList<>();

        for (EntityNormalizationReport report : allReports) {
            for (NormalizationIssue issue : report.issues()) {
                DataMigrationPlan plan = issue.dataMigrationPlan();
                if (plan == null || plan.newTables().isEmpty() || plan.columnMappings().isEmpty()) {
                    continue;
                }
                // Deduplicate — multiple issues on the same entity may share the same plan
                if (processedSourceTables.add(plan.sourceTable())) {
                    plans.add(plan);
                }
            }
        }

        if (plans.isEmpty()) {
            System.out.println("  ℹ️  No structured migration plans found — skipping artifact generation.");
            return;
        }

        System.out.printf("%n🔧 Generating migration artifacts for %d plan(s)…%n", plans.size());

        LiquibaseGenerator liquibaseGenerator =
                new LiquibaseGenerator(LiquibaseGenerator.ChangesetConfig.fromConfiguration());
        DataMigrationGenerator dataMigrationGenerator = new DataMigrationGenerator();
        InsteadOfTriggerGenerator triggerGenerator = new InsteadOfTriggerGenerator();

        List<String> allChangesets = new ArrayList<>();

        for (DataMigrationPlan plan : plans) {
            InsteadOfTriggerGenerator.ViewDescriptor view = toViewDescriptor(plan);
            System.out.printf("  📦 %s → [%s]%n", plan.sourceTable(),
                    String.join(", ", plan.newTables()));

            // 1. Data migration INSERT-SELECT statements (one per new table)
            List<String> migrationSqls = dataMigrationGenerator.generateMigrationSql(view);
            for (int i = 0; i < migrationSqls.size(); i++) {
                allChangesets.add(liquibaseGenerator.createRawSqlChangeset(
                        "migrate_data_" + plan.sourceTable() + "_" + i,
                        migrationSqls.get(i)));
            }

            // 2. Compatibility view (old table name → SELECT joining all new tables)
            String viewSql = buildCompatibilityViewSql(view);
            allChangesets.add(liquibaseGenerator.createViewChangeset(view.viewName(), viewSql));

            // 3. INSTEAD OF triggers so existing DML through the view still works
            allChangesets.add(liquibaseGenerator.createDialectSqlChangeset(
                    "trigger_insert_" + view.viewName(), triggerGenerator.generateInsert(view)));
            allChangesets.add(liquibaseGenerator.createDialectSqlChangeset(
                    "trigger_update_" + view.viewName(), triggerGenerator.generateUpdate(view)));
            allChangesets.add(liquibaseGenerator.createDialectSqlChangeset(
                    "trigger_delete_" + view.viewName(), triggerGenerator.generateDelete(view)));
        }

        String composite = liquibaseGenerator.createCompositeChangeset(allChangesets);
        try {
            LiquibaseGenerator.WriteResult result =
                    liquibaseGenerator.writeChangesetToConfiguredFile(composite);
            if (result.wasWritten()) {
                System.out.printf("  ✅ Changesets written to: %s%n",
                        result.getChangesFile().getAbsolutePath());
            }
        } catch (IllegalStateException e) {
            System.out.println("  ⚠️  liquibase_master_file not configured — printing changeset to stdout:");
            System.out.println(composite);
        }

        generateNewEntities(plans);
    }

    // -------------------------------------------------------------------------
    // New entity generation
    // -------------------------------------------------------------------------

    /**
     * For each normalized table in each migration plan, generates a JPA {@code @Entity}
     * source file and writes it to {@code <base_path>/src/main/java/<package>/normalized/}.
     * A {@code normalized} subpackage is used to avoid name collisions with the original entities.
     * Files that already exist are skipped with a warning.
     */
    private void generateNewEntities(List<DataMigrationPlan> plans) throws IOException {
        Object basePathProp = Settings.getProperty("base_path");
        if (basePathProp == null) {
            System.out.println("  ⚠️  base_path not configured — cannot write new entity files.");
            return;
        }
        Path sourceRoot = Path.of(basePathProp.toString(), "src", "main", "java");

        System.out.printf("%n📝 Generating new entity classes (persistence: %s)…%n", persistencePackage);

        for (DataMigrationPlan plan : plans) {
            // Locate the source entity profile by its table name
            EntityProfile sourceProfile = allProfiles.stream()
                    .filter(p -> p.tableName().equals(plan.sourceTable()))
                    .findFirst()
                    .orElse(null);
            if (sourceProfile == null) {
                logger.warn("No EntityProfile found for source table '{}' — skipping entity generation",
                        plan.sourceTable());
                continue;
            }

            // Place generated entities in a `normalized` subpackage to avoid collisions
            String originalPackage = entityPackageMap.getOrDefault(sourceProfile.entityName(), "");
            String targetPackage = originalPackage.isBlank()
                    ? "normalized"
                    : originalPackage + ".normalized";

            // Index outgoing FKs by child table name for quick lookup
            Map<String, List<DataMigrationPlan.ForeignKeyEntry>> fksByTable = new HashMap<>();
            for (DataMigrationPlan.ForeignKeyEntry fk : plan.foreignKeys()) {
                fksByTable.computeIfAbsent(fk.fromTable(), k -> new ArrayList<>()).add(fk);
            }

            for (String newTable : plan.newTables()) {
                List<DataMigrationPlan.ColumnMappingEntry> tableMappings = plan.columnMappings().stream()
                        .filter(cm -> cm.targetTable().equals(newTable))
                        .toList();
                if (tableMappings.isEmpty()) {
                    logger.warn("No column mappings returned by LLM for table '{}' (plan source: '{}') " +
                            "— entity will not be generated. Check the LLM response log for details.",
                            newTable, plan.sourceTable());
                    continue;
                }

                List<DataMigrationPlan.ForeignKeyEntry> outgoingFks =
                        fksByTable.getOrDefault(newTable, List.of());

                String entityName = toPascalCase(newTable);
                String source = buildEntitySource(targetPackage, entityName, newTable,
                        tableMappings, outgoingFks, sourceProfile);

                Path packageDir = sourceRoot.resolve(targetPackage.replace('.', File.separatorChar));
                Files.createDirectories(packageDir);
                Path entityFile = packageDir.resolve(entityName + ".java");

                if (Files.exists(entityFile)) {
                    System.out.printf("  ⚠️  Skipping %s — already exists: %s%n", entityName, entityFile);
                } else {
                    Files.writeString(entityFile, source, StandardCharsets.UTF_8);
                    System.out.printf("  ✅ Generated: %s%n", entityFile);
                }
            }
        }
    }

    /**
     * Generates a Java source file for a single normalized entity.
     * FK columns are emitted as {@code @ManyToOne} relationships; all other columns
     * become {@code @Column} scalar fields. The first {@code @Id} field in the source
     * profile is annotated with {@code @Id @GeneratedValue}.
     */
    private String buildEntitySource(String packageName,
                                     String entityName,
                                     String tableName,
                                     List<DataMigrationPlan.ColumnMappingEntry> tableMappings,
                                     List<DataMigrationPlan.ForeignKeyEntry> outgoingFks,
                                     EntityProfile sourceProfile) {
        // Column names in this table that are FK columns (become @ManyToOne, not scalars)
        Set<String> fkTargetColumns = outgoingFks.stream()
                .map(DataMigrationPlan.ForeignKeyEntry::fromColumn)
                .collect(Collectors.toSet());

        // Build fields into a separate buffer so we can prepend a synthetic PK if needed
        StringBuilder fields = new StringBuilder();
        boolean idEmitted = false;

        for (DataMigrationPlan.ColumnMappingEntry cm : tableMappings) {
            if (fkTargetColumns.contains(cm.targetColumn())) {
                // FK column → @ManyToOne relationship
                DataMigrationPlan.ForeignKeyEntry fk = outgoingFks.stream()
                        .filter(f -> f.fromColumn().equals(cm.targetColumn()))
                        .findFirst()
                        .orElse(null);
                if (fk != null) {
                    String parentEntityName = toPascalCase(fk.toTable());
                    String fieldName = toCamelCase(fk.toTable());
                    fields.append("    @ManyToOne\n");
                    fields.append("    @JoinColumn(name = \"").append(cm.targetColumn()).append("\")\n");
                    fields.append("    private ").append(parentEntityName).append(" ")
                          .append(fieldName).append(";\n\n");
                }
            } else {
                boolean isId = isIdColumn(cm.viewColumn(), sourceProfile);
                boolean nullable = isNullableColumn(cm.viewColumn(), sourceProfile);
                String javaType = findJavaType(cm.viewColumn(), sourceProfile);
                String fieldName = toCamelCase(cm.targetColumn());

                if (isId) {
                    idEmitted = true;
                    fields.append("    @Id\n");
                    fields.append("    @GeneratedValue(strategy = GenerationType.IDENTITY)\n");
                    fields.append("    @Column(name = \"").append(cm.targetColumn()).append("\")\n");
                } else {
                    fields.append("    @Column(name = \"").append(cm.targetColumn()).append("\"");
                    if (!nullable) fields.append(", nullable = false");
                    fields.append(")\n");
                }
                fields.append("    private ").append(javaType).append(" ").append(fieldName).append(";\n\n");
            }
        }

        // If no mapped column carried @Id (e.g. a child table whose PK is new),
        // prepend a synthetic surrogate key so the entity is valid JPA.
        if (!idEmitted) {
            logger.warn("No @Id column found for table '{}' — inserting synthetic surrogate key 'id'", tableName);
            String syntheticPk =
                    "    @Id\n" +
                    "    @GeneratedValue(strategy = GenerationType.IDENTITY)\n" +
                    "    @Column(name = \"id\")\n" +
                    "    private Long id;\n\n";
            fields.insert(0, syntheticPk);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(packageName).append(";\n\n");
        sb.append("import ").append(persistencePackage).append(".*;\n\n");
        sb.append("@Entity\n");
        sb.append("@Table(name = \"").append(tableName).append("\")\n");
        sb.append("public class ").append(entityName).append(" {\n\n");
        sb.append(fields);
        sb.append("}\n");
        return sb.toString();
    }

    /** Returns the Java type of the field whose DB column name matches {@code viewColumn}. */
    private String findJavaType(String viewColumn, EntityProfile sourceProfile) {
        return sourceProfile.fields().stream()
                .filter(f -> f.columnName().equals(viewColumn))
                .map(FieldProfile::columnType)
                .findFirst()
                .orElse("Object");
    }

    /** Returns true if the field whose DB column is {@code viewColumn} carries {@code @Id}. */
    private boolean isIdColumn(String viewColumn, EntityProfile sourceProfile) {
        return sourceProfile.fields().stream()
                .anyMatch(f -> f.columnName().equals(viewColumn) && f.isId());
    }

    /** Returns the nullable flag of the field whose DB column is {@code viewColumn}. */
    private boolean isNullableColumn(String viewColumn, EntityProfile sourceProfile) {
        return sourceProfile.fields().stream()
                .filter(f -> f.columnName().equals(viewColumn))
                .map(FieldProfile::isNullable)
                .findFirst()
                .orElse(true);
    }

    /** Converts a snake_case table/column name to PascalCase (e.g. {@code patient_address → PatientAddress}). */
    private String toPascalCase(String snakeCase) {
        if (snakeCase == null || snakeCase.isBlank()) return snakeCase;
        return Arrays.stream(snakeCase.split("[_\\s]+"))
                .filter(w -> !w.isEmpty())
                .map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1).toLowerCase())
                .collect(Collectors.joining());
    }

    /** Converts a snake_case name to camelCase (e.g. {@code patient_address → patientAddress}). */
    private String toCamelCase(String snakeCase) {
        String pascal = toPascalCase(snakeCase);
        if (pascal == null || pascal.isEmpty()) return pascal;
        return Character.toLowerCase(pascal.charAt(0)) + pascal.substring(1);
    }

    // -------------------------------------------------------------------------
    // Reporting
    // -------------------------------------------------------------------------

    private void reportEntityResults(String entityName, String tableName,
                                     List<NormalizationIssue> issues) {
        System.out.println("\n" + "=".repeat(80));
        System.out.printf("Entity: %s (table: %s)%n", entityName, tableName);
        System.out.println("=".repeat(80));

        if (issues.isEmpty()) {
            System.out.printf("  ✅ No normalization violations found%n");
            return;
        }

        System.out.printf("  ⚠️  %d normalization issue(s):%n", issues.size());
        for (NormalizationIssue issue : issues) {
            System.out.printf("    [%s] %s%n", issue.normalizationForm(), issue.violation());
            if (!issue.affectedFields().isEmpty()) {
                System.out.printf("      Affected fields : %s%n",
                        String.join(", ", issue.affectedFields()));
            }
            if (issue.proposal() != null && !issue.proposal().isBlank()) {
                System.out.printf("      Proposal        : %s%n", issue.proposal());
            }
            if (issue.liquibaseMigrationHint() != null
                    && !issue.liquibaseMigrationHint().isBlank()) {
                System.out.printf("      Migration hint  : %s%n", issue.liquibaseMigrationHint());
            }
            if (issue.dataMigrationPlan() != null) {
                DataMigrationPlan plan = issue.dataMigrationPlan();
                System.out.printf("      Migration plan  : %s → %s (tables: %s)%n",
                        plan.sourceTable(), plan.baseTable(),
                        String.join(", ", plan.newTables()));
            }
        }
    }

    /**
     * Prints a consolidated summary report after all entities have been analyzed.
     * Call this after {@link #analyze()}.
     */
    public void generateReport() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("SCHEMA NORMALIZATION ANALYSIS REPORT");
        System.out.println("=".repeat(80));

        int totalEntities = allReports.size();
        long entitiesWithIssues = allReports.stream()
                .filter(r -> !r.issues().isEmpty()).count();

        System.out.printf("Entities analyzed  : %d%n", totalEntities);
        System.out.printf("Entities w/ issues : %d%n", entitiesWithIssues);
        System.out.printf("Total violations   : %d%n", totalRecommendations);

        if (entitiesWithIssues > 0) {
            System.out.println("\nEntities with normalization violations:");
            for (EntityNormalizationReport report : allReports) {
                if (!report.issues().isEmpty()) {
                    System.out.printf("  %-60s  %d violation(s)%n",
                            report.entityName(), report.issues().size());
                }
            }
        }

        if (cumulativeTokenUsage.getTotalTokens() > 0) {
            System.out.printf("%n🤖 Total AI Usage: %s%n",
                    cumulativeTokenUsage.getFormattedReport());
        }
    }

    // -------------------------------------------------------------------------
    // Configuration and entry point
    // -------------------------------------------------------------------------

    /**
     * Reads {@code schema_normalization} settings from {@code generator.yml}.
     * Call this from {@code main()} after {@link Settings#loadConfigMap()}.
     */
    @SuppressWarnings("unchecked")
    public static void configureFromSettings() {
        Map<String, Object> normConfig =
                (Map<String, Object>) Settings.getProperty("schema_normalization");
        if (normConfig != null) {
            Object targetClassValue = normConfig.get("target_class");
            if (targetClassValue instanceof String s && !s.isBlank()) {
                String[] parts = s.split("#");
                targetClass = parts[0];
                if (parts.length == 2) {
                    targetMethod = parts[1];
                    System.out.printf("🎯 Target class filter: %s, method: %s%n",
                            targetClass, targetMethod);
                } else {
                    System.out.printf("🎯 Target class filter: %s%n", targetClass);
                }
            } else {
                System.out.println("ℹ️ No target_class filter specified (processing all entities)");
            }

            Object skipClassValue = normConfig.get("skip_class");
            if (skipClassValue instanceof String s && !s.isBlank()) {
                skipClass = s;
                System.out.printf("🚫 Skip class filter: %s%n", s);
            }
        } else {
            System.out.println("ℹ️ No schema_normalization section in settings (processing all entities)");
        }
    }

    public static void main(String[] args) throws Exception {
        Settings.loadConfigMap();
        AbstractCompiler.preProcess();
        configureFromSettings();

        SchemaNormalizationAnalyzer analyzer = new SchemaNormalizationAnalyzer();
        analyzer.analyze();
        analyzer.generateReport();

        System.exit(0);
    }
}
