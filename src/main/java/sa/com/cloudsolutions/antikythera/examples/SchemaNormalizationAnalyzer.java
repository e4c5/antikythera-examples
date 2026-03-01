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
import sa.com.cloudsolutions.antikythera.examples.util.DataMigrationPlanValidator;
import sa.com.cloudsolutions.antikythera.examples.util.InsteadOfTriggerGenerator;
import sa.com.cloudsolutions.antikythera.examples.util.LiquibaseGenerator;
import sa.com.cloudsolutions.antikythera.examples.util.NormalizedTableDDLGenerator;
import sa.com.cloudsolutions.antikythera.examples.util.TopologicalSorter;

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
import java.util.LinkedHashMap;
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

    public record EntityProfile(
            String entityName,
            String tableName,
            List<FieldProfile> fields,
            List<RelationshipProfile> relationships) {}

    public record FieldProfile(String javaName, String columnName, boolean isId,
                        boolean isNullable, String columnType) {}

    public record RelationshipProfile(String javaName, String annotationType,
                               String joinColumn, String referencedColumn,
                               String targetEntity) {}

    // -------------------------------------------------------------------------
    // Result records — deserialised from the LLM response
    // -------------------------------------------------------------------------

    /**
     * Structured data migration plan returned by the LLM for issues that involve
     * splitting or extracting a table.  Maps directly to {@link InsteadOfTriggerGenerator.ViewDescriptor}.
     */
    public record DataMigrationPlan(
            String sourceTable,
            String baseTable,
            List<String> newTables,
            List<ColumnMappingEntry> columnMappings,
            List<ForeignKeyEntry> foreignKeys) {

        /** Maps a column in the old (source) table to a column in one of the new tables. */
        public record ColumnMappingEntry(String viewColumn, String targetTable, String targetColumn) {}

        /** FK edge between two of the new normalized tables. */
        public record ForeignKeyEntry(String fromTable, String fromColumn, String toTable, String toColumn) {}
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
     * Holds a discovered reference from another table to the table being normalized.
     *
     * @param referencingTable table that holds the FK column pointing at the source table
     * @param fkColumn         column in the referencing table that is the FK
     * @param constraintName   generated constraint name for Liquibase drop changeset
     */
    private record ExternalFKReference(String referencingTable, String fkColumn, String constraintName) {}

    /**
     * Scans all collected {@link EntityProfile}s to discover every table that has a FK
     * referencing {@code sourceTable}. Used to generate DROP CONSTRAINT changesets before
     * renaming the old table.
     *
     * @param sourceTable the table being normalized (will be renamed)
     * @return list of external FK references; empty if none found
     */
    private List<ExternalFKReference> discoverExternalFKs(String sourceTable) {
        // Resolve sourceTable → entity name
        String sourceEntityName = allProfiles.stream()
                .filter(p -> p.tableName().equals(sourceTable))
                .map(EntityProfile::entityName)
                .findFirst()
                .orElse(null);
        if (sourceEntityName == null) return List.of();

        List<ExternalFKReference> refs = new ArrayList<>();
        for (EntityProfile p : allProfiles) {
            if (p.tableName().equals(sourceTable)) continue;
            for (RelationshipProfile r : p.relationships()) {
                if (sourceEntityName.equals(r.targetEntity())) {
                    String joinCol = r.joinColumn();
                    String constraintName = "fk_" + p.tableName() + "_" + sourceTable;
                    refs.add(new ExternalFKReference(p.tableName(), joinCol, constraintName));
                }
            }
        }
        return refs;
    }

    /**
     * Generates Liquibase changesets to drop every FK constraint referencing the old source
     * table, followed by one changeset to rename the old table to its backup name.
     *
     * @param sourceTable      the table being normalized
     * @param renameOldTableTo backup name template; {@code {sourceTable}} is substituted
     * @param generator        {@link LiquibaseGenerator} used to build raw-SQL changesets
     * @return list of changeset XML strings; may be empty if no external FKs exist and rename
     *         name is blank
     */
    private List<String> buildDropFkAndRenameChangesets(String sourceTable,
                                                         String renameOldTableTo,
                                                         LiquibaseGenerator generator) {
        List<String> changesets = new ArrayList<>();

        // Drop every external FK referencing this table
        List<ExternalFKReference> externalFKs = discoverExternalFKs(sourceTable);
        for (ExternalFKReference ref : externalFKs) {
            String dropFkCs = buildDropFkChangeset(ref, generator);
            if (dropFkCs != null) changesets.add(dropFkCs);
        }

        // Rename old table to backup name
        if (renameOldTableTo != null && !renameOldTableTo.isBlank()) {
            String backupName = renameOldTableTo.replace("{sourceTable}", sourceTable);
            String renameCs = buildRenameTableChangeset(sourceTable, backupName, generator);
            if (renameCs != null) changesets.add(renameCs);
        }

        return changesets;
    }

    /**
     * Builds a single DROP FOREIGN KEY Liquibase changeset using the built-in
     * {@code <dropForeignKeyConstraint>} tag (cross-database compatible).
     */
    private String buildDropFkChangeset(ExternalFKReference ref, LiquibaseGenerator generator) {
        if (ref.fkColumn() == null || ref.fkColumn().isBlank()) {
            logger.warn("Skipping drop-FK for {} — join column unknown", ref.referencingTable());
            return null;
        }
        String id = "drop_fk_" + ref.referencingTable() + "_" + ref.constraintName();
        return generator.createDropForeignKeyChangeset(id, ref.referencingTable(), ref.constraintName());
    }

    /**
     * Builds a Liquibase {@code <renameTable>} changeset.
     */
    private String buildRenameTableChangeset(String oldName, String newName,
                                              LiquibaseGenerator generator) {
        String id = "rename_table_" + oldName + "_to_" + newName;
        return generator.createRenameTableChangeset(id, oldName, newName);
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

        // Build JOINs in topological order so each referenced parent table is already in scope.
        // This fixes multi-level FK chains (e.g. customer → address → phone).
        List<String> order = TopologicalSorter.sort(view.tables(), view.foreignKeys());
        // Index FKs by fromTable for O(1) lookup
        Map<String, InsteadOfTriggerGenerator.ForeignKey> fkByFromTable = new LinkedHashMap<>();
        for (InsteadOfTriggerGenerator.ForeignKey fk : view.foreignKeys()) {
            fkByFromTable.put(fk.fromTable(), fk);
        }
        // Skip the first element (the base table, which is already in the FROM clause)
        for (int i = 1; i < order.size(); i++) {
            String table = order.get(i);
            InsteadOfTriggerGenerator.ForeignKey fk = fkByFromTable.get(table);
            if (fk != null) {
                sb.append(" JOIN ").append(fk.fromTable())
                  .append(" ON ").append(fk.fromTable()).append(".").append(fk.fromColumn())
                  .append(" = ").append(fk.toTable()).append(".").append(fk.toColumn());
            }
        }
        return sb.toString();
    }

    /**
     * For each normalization issue with a structured {@link DataMigrationPlan}, validates the
     * plan and then generates and writes Liquibase changesets in this order per plan:
     * <ol>
     *   <li>CREATE TABLE for each new normalized table (topological order)</li>
     *   <li>Data migration INSERT-SELECT statements (one per normalized table)</li>
     *   <li>Drop every FK constraint referencing the old table</li>
     *   <li>Rename old table to backup name</li>
     *   <li>A compatibility view named after the original table</li>
     *   <li>INSTEAD OF triggers for INSERT, UPDATE, and DELETE on the view</li>
     * </ol>
     * <p>If {@code liquibase_master_file} is not configured, the changeset XML is printed
     * to stdout instead.</p>
     */
    @SuppressWarnings("unchecked")
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

        // Read schema_normalization config
        Map<String, Object> normConfig =
                (Map<String, Object>) Settings.getProperty("schema_normalization");
        String ddlMode = normConfig != null
                ? (String) normConfig.getOrDefault("ddl_mode", NormalizedTableDDLGenerator.MODE_LIQUIBASE)
                : NormalizedTableDDLGenerator.MODE_LIQUIBASE;
        String renameOldTableTo = normConfig != null
                ? (String) normConfig.get("rename_old_table_to")
                : null;

        // Build LiquibaseGenerator with schema_normalization config; fall back to query_optimizer
        // for liquibase_master_file if not overridden in schema_normalization
        LiquibaseGenerator.ChangesetConfig csConfig =
                LiquibaseGenerator.ChangesetConfig.fromConfiguration("schema_normalization");
        if (csConfig.liquibaseMasterFile() == null) {
            LiquibaseGenerator.ChangesetConfig fallback =
                    LiquibaseGenerator.ChangesetConfig.fromConfiguration("query_optimizer");
            if (fallback.liquibaseMasterFile() != null) {
                csConfig = new LiquibaseGenerator.ChangesetConfig(
                        csConfig.author(), csConfig.supportedDialects(),
                        csConfig.includePreconditions(), csConfig.includeRollback(),
                        fallback.liquibaseMasterFile(), csConfig.filePrefix());
            }
        }

        LiquibaseGenerator liquibaseGenerator = new LiquibaseGenerator(csConfig);
        DataMigrationGenerator dataMigrationGenerator = new DataMigrationGenerator();
        InsteadOfTriggerGenerator triggerGenerator = new InsteadOfTriggerGenerator();
        NormalizedTableDDLGenerator ddlGenerator = new NormalizedTableDDLGenerator();

        List<String> allChangesets = new ArrayList<>();

        for (DataMigrationPlan plan : plans) {
            // Locate the source entity profile
            EntityProfile sourceProfile = allProfiles.stream()
                    .filter(p -> p.tableName().equals(plan.sourceTable()))
                    .findFirst()
                    .orElse(null);

            // Validate the plan before generating artifacts
            DataMigrationPlanValidator.ValidationResult validationResult =
                    DataMigrationPlanValidator.validate(plan, sourceProfile);
            if (!validationResult.valid()) {
                logger.warn("Skipping plan for '{}' — validation failed: {}",
                        plan.sourceTable(), validationResult.errors());
                continue;
            }
            if (!validationResult.warnings().isEmpty()) {
                logger.warn("Plan for '{}' has warnings: {}",
                        plan.sourceTable(), validationResult.warnings());
            }

            InsteadOfTriggerGenerator.ViewDescriptor view = toViewDescriptor(plan);
            System.out.printf("  📦 %s → [%s]%n", plan.sourceTable(),
                    String.join(", ", plan.newTables()));

            List<String> planChangesets = buildChangesetsForPlan(
                    plan, view, sourceProfile, ddlMode, renameOldTableTo,
                    liquibaseGenerator, dataMigrationGenerator, triggerGenerator, ddlGenerator);
            allChangesets.addAll(planChangesets);

            // Write mapping artifact JSON
            writeMappingArtifact(plan, normConfig);
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

    /**
     * Builds the full list of changesets for a single migration plan in the correct order:
     * <ol>
     *   <li>CREATE TABLE changesets (topological order, from NormalizedTableDDLGenerator)</li>
     *   <li>Data migration INSERT-SELECT changesets</li>
     *   <li>Drop FK constraints referencing the old table</li>
     *   <li>Rename old table to backup name</li>
     *   <li>Compatibility view changeset</li>
     *   <li>INSTEAD OF trigger changesets (insert, update, delete)</li>
     * </ol>
     */
    private List<String> buildChangesetsForPlan(DataMigrationPlan plan,
                                                  InsteadOfTriggerGenerator.ViewDescriptor view,
                                                  EntityProfile sourceProfile,
                                                  String ddlMode,
                                                  String renameOldTableTo,
                                                  LiquibaseGenerator liquibaseGenerator,
                                                  DataMigrationGenerator dataMigrationGenerator,
                                                  InsteadOfTriggerGenerator triggerGenerator,
                                                  NormalizedTableDDLGenerator ddlGenerator) {
        List<String> changesets = new ArrayList<>();

        // 1. CREATE TABLE changesets for each new normalized table (topological order)
        List<String> createTableDdls = ddlGenerator.generate(view, sourceProfile, ddlMode);
        changesets.addAll(createTableDdls);

        // 2. Data migration INSERT-SELECT statements
        List<String> migrationSqls = dataMigrationGenerator.generateMigrationSql(view);
        for (int i = 0; i < migrationSqls.size(); i++) {
            changesets.add(liquibaseGenerator.createRawSqlChangeset(
                    "migrate_data_" + plan.sourceTable() + "_" + i,
                    migrationSqls.get(i)));
        }

        // 3 + 4. Drop FKs referencing old table, then rename old table to backup
        List<String> dropFkAndRenameCs = buildDropFkAndRenameChangesets(
                plan.sourceTable(), renameOldTableTo, liquibaseGenerator);
        changesets.addAll(dropFkAndRenameCs);

        // 5. Compatibility view
        String viewSql = buildCompatibilityViewSql(view);
        changesets.add(liquibaseGenerator.createViewChangeset(view.viewName(), viewSql));

        // 6. INSTEAD OF triggers with rollback DDL
        Map<LiquibaseGenerator.DatabaseDialect, String> insertRollback =
                triggerGenerator.generateInsertRollback(view);
        Map<LiquibaseGenerator.DatabaseDialect, String> updateRollback =
                triggerGenerator.generateUpdateRollback(view);
        Map<LiquibaseGenerator.DatabaseDialect, String> deleteRollback =
                triggerGenerator.generateDeleteRollback(view);

        changesets.add(liquibaseGenerator.createDialectSqlChangesetWithRollback(
                "trigger_insert_" + view.viewName(),
                triggerGenerator.generateInsert(view), insertRollback));
        changesets.add(liquibaseGenerator.createDialectSqlChangesetWithRollback(
                "trigger_update_" + view.viewName(),
                triggerGenerator.generateUpdate(view), updateRollback));
        changesets.add(liquibaseGenerator.createDialectSqlChangesetWithRollback(
                "trigger_delete_" + view.viewName(),
                triggerGenerator.generateDelete(view), deleteRollback));

        return changesets;
    }

    /**
     * Writes a normalization mapping JSON artifact for a plan to
     * {@code <base_path>/<mapping_output_dir>/normalization-mapping-<sourceTable>.json}.
     */
    @SuppressWarnings("unchecked")
    private void writeMappingArtifact(DataMigrationPlan plan,
                                       Map<String, Object> normConfig) throws IOException {
        String basePath = normConfig != null
                ? (String) normConfig.get("base_path")
                : null;
        if (basePath == null) {
            Object topLevel = Settings.getProperty("base_path");
            if (topLevel != null) basePath = topLevel.toString();
        }
        if (basePath == null) {
            logger.warn("base_path not configured — skipping mapping artifact for {}", plan.sourceTable());
            return;
        }

        String mappingDir = normConfig != null
                ? (String) normConfig.getOrDefault("mapping_output_dir", "docs")
                : "docs";

        Path outputDir = Path.of(basePath, mappingDir);
        Files.createDirectories(outputDir);
        Path outputFile = outputDir.resolve("normalization-mapping-" + plan.sourceTable() + ".json");

        // Resolve source entity name
        String sourceEntityName = allProfiles.stream()
                .filter(p -> p.tableName().equals(plan.sourceTable()))
                .map(EntityProfile::entityName)
                .findFirst()
                .orElse(plan.sourceTable());

        // Build JSON using ObjectMapper
        ObjectNode root = objectMapper.createObjectNode();
        root.put("sourceTable", plan.sourceTable());
        root.put("sourceEntity", sourceEntityName);
        root.put("viewName", plan.sourceTable());

        ArrayNode newTables = root.putArray("newTables");
        plan.newTables().forEach(newTables::add);

        ArrayNode newEntities = root.putArray("newEntities");
        plan.newTables().forEach(t -> newEntities.add(toPascalCase(t)));

        ArrayNode columnMappings = root.putArray("columnMappings");
        for (DataMigrationPlan.ColumnMappingEntry cm : plan.columnMappings()) {
            ObjectNode node = columnMappings.addObject();
            node.put("viewColumn",    cm.viewColumn());
            node.put("targetTable",   cm.targetTable());
            node.put("targetColumn",  cm.targetColumn());
        }

        ArrayNode foreignKeys = root.putArray("foreignKeys");
        for (DataMigrationPlan.ForeignKeyEntry fk : plan.foreignKeys()) {
            ObjectNode node = foreignKeys.addObject();
            node.put("fromTable",  fk.fromTable());
            node.put("fromColumn", fk.fromColumn());
            node.put("toTable",    fk.toTable());
            node.put("toColumn",   fk.toColumn());
        }

        objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), root);
        System.out.printf("  📄 Mapping artifact written: %s%n", outputFile);
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
