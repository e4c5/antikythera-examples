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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    /** Default number of entity profiles to include per LLM request. */
    private static final int DEFAULT_BATCH_SIZE = 20;

    private final String normalizationSystemPrompt;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private int batchSize = DEFAULT_BATCH_SIZE;

    /** Phase-1 accumulator: profiles built from source code, no LLM call yet. */
    private final List<EntityProfile> allProfiles = new ArrayList<>();

    /** Phase-2 accumulator: reports returned from the LLM. */
    private final List<EntityNormalizationReport> allReports = new ArrayList<>();

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

    record NormalizationIssue(
            String normalizationForm,
            String violation,
            List<String> affectedFields,
            String proposal,
            List<String> suggestedEntities,
            String liquibaseMigrationHint) {}

    record EntityNormalizationReport(String entityName, List<NormalizationIssue> issues) {}

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Creates a new analyzer. The Liquibase path parameter is accepted for
     * consistency with {@link QueryOptimizationChecker} but is not required for
     * pure normalization analysis.
     *
     * @param liquibaseXmlPath path to the Liquibase master changelog (may be
     *                         {@code null} if Liquibase output is not needed)
     */
    @SuppressWarnings("unchecked")
    public SchemaNormalizationAnalyzer(File liquibaseXmlPath) throws IOException {
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

        if (!quietMode) {
            System.out.printf("  📊 Profiling entity: %s (table: %s, %d fields, %d relationships)%n",
                    profile.entityName(), profile.tableName(),
                    profile.fields().size(), profile.relationships().size());
        }
    }

    /**
     * Phase 2: send all collected profiles to the LLM in batches, then report.
     * Called by the base-class template method after the entity loop finishes.
     */
    @Override
    protected void afterAnalysisLoop() throws IOException, InterruptedException {
        if (allProfiles.isEmpty()) {
            System.out.println("No entity profiles collected — nothing to send to LLM.");
            return;
        }

        System.out.printf("%n🤖 Sending %d entity profiles to LLM (batch size: %d)…%n",
                allProfiles.size(), batchSize);

        for (int i = 0; i < allProfiles.size(); i += batchSize) {
            int end = Math.min(allProfiles.size(), i + batchSize);
            List<EntityProfile> batch = allProfiles.subList(i, end);
            int batchNum = (i / batchSize) + 1;
            int totalBatches = (int) Math.ceil((double) allProfiles.size() / batchSize);

            System.out.printf("  Batch %d/%d: %d entities%n", batchNum, totalBatches, batch.size());

            List<EntityNormalizationReport> batchReports = sendBatchToLLM(batch);
            allReports.addAll(batchReports);

            TokenUsage tokenUsage = aiService.getLastTokenUsage();
            cumulativeTokenUsage.add(tokenUsage);
            if (!quietMode) {
                System.out.printf("  🤖 AI analysis (batch %d/%d): %s%n",
                        batchNum, totalBatches, tokenUsage.getFormattedReport());
            }
        }

        // Print per-entity results
        for (EntityNormalizationReport report : allReports) {
            String fqn = allProfiles.stream()
                    .filter(p -> p.entityName().equals(report.entityName()))
                    .findFirst()
                    .map(p -> p.tableName())
                    .orElse("?");
            reportEntityResults(report.entityName(), fqn, report.issues());
            totalRecommendations += report.issues().size();
        }
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
        root.put("model", "gpt-4o");

        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content", normalizationSystemPrompt);
        messages.addObject().put("role", "user").put("content", userContent);

        root.putObject("response_format").put("type", "json_object");

        return objectMapper.writeValueAsString(root);
    }

    /**
     * Parses the LLM response into a list of per-entity reports.
     * Falls back to empty issues for any entity not found in the response.
     */
    private List<EntityNormalizationReport> parseBatchResponse(
            String responseBody, List<EntityProfile> batch) throws IOException {

        List<EntityNormalizationReport> reports = new ArrayList<>();
        if (responseBody == null || responseBody.isBlank()) {
            batch.forEach(p -> reports.add(new EntityNormalizationReport(p.entityName(), List.of())));
            return reports;
        }

        JsonNode root = objectMapper.readTree(responseBody);
        String jsonText;

        if (aiService instanceof GeminiAIService) {
            JsonNode candidates = root.path("candidates");
            if (!candidates.isArray() || candidates.isEmpty()) {
                batch.forEach(p -> reports.add(new EntityNormalizationReport(p.entityName(), List.of())));
                return reports;
            }
            jsonText = candidates.get(0).path("content").path("parts").get(0).path("text").asText();
        } else {
            // OpenAI / fallback
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                batch.forEach(p -> reports.add(new EntityNormalizationReport(p.entityName(), List.of())));
                return reports;
            }
            jsonText = choices.get(0).path("message").path("content").asText();
        }

        if (jsonText == null || jsonText.isBlank()) {
            batch.forEach(p -> reports.add(new EntityNormalizationReport(p.entityName(), List.of())));
            return reports;
        }

        JsonNode parsed = objectMapper.readTree(jsonText);
        // Unwrap {"results": [...]} or similar wrapper if present
        if (parsed.isObject()) {
            for (String key : List.of("results", "entities", "data")) {
                if (parsed.has(key) && parsed.get(key).isArray()) {
                    parsed = parsed.get(key);
                    break;
                }
            }
        }

        if (!parsed.isArray()) {
            logger.warn("Unexpected normalization batch response structure; expected JSON array");
            batch.forEach(p -> reports.add(new EntityNormalizationReport(p.entityName(), List.of())));
            return reports;
        }

        // Build a map from entityName → issues so we can match by name
        Map<String, List<NormalizationIssue>> issuesByEntity = new HashMap<>();
        for (JsonNode entityNode : parsed) {
            String entityName = entityNode.path("entityName").asText("");
            List<NormalizationIssue> issues = new ArrayList<>();
            JsonNode issuesNode = entityNode.path("issues");
            if (issuesNode.isArray()) {
                for (JsonNode issueNode : issuesNode) {
                    issues.add(parseIssue(issueNode));
                }
            }
            issuesByEntity.put(entityName, issues);
        }

        // Preserve batch order; fall back to empty issues if entity is missing from response
        for (EntityProfile profile : batch) {
            List<NormalizationIssue> issues = issuesByEntity.getOrDefault(
                    profile.entityName(), List.of());
            if (!issuesByEntity.containsKey(profile.entityName())) {
                logger.warn("LLM response did not include entity: {}", profile.entityName());
            }
            reports.add(new EntityNormalizationReport(profile.entityName(), issues));
        }

        return reports;
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

        return new NormalizationIssue(form, violation, affectedFields, proposal,
                suggestedEntities, migrationHint);
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

        SchemaNormalizationAnalyzer analyzer = new SchemaNormalizationAnalyzer(getLiquibasePath());
        analyzer.analyze();
        analyzer.generateReport();

        System.exit(0);
    }
}
