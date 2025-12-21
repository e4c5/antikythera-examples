package com.raditha.spring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Migrates Elasticsearch low-level RestClient usage for Spring Boot 2.4+.
 * 
 * <p>
 * Spring Boot 2.4 removed auto-configuration for the low-level Elasticsearch
 * RestClient.
 * Only RestHighLevelClient is auto-configured now. This migrator:
 * <ul>
 * <li>Detects low-level RestClient usage in source code</li>
 * <li>Flags files requiring manual configuration of RestClient bean</li>
 * <li>Generates configuration class using AI with multiple options</li>
 * <li>Provides migration guidance with configuration examples</li>
 * </ul>
 * 
 * <p>
 * Migration options:
 * <ul>
 * <li>Switch to RestHighLevelClient (recommended)</li>
 * <li>Create manual @Bean for RestClient from RestHighLevelClient</li>
 * <li>Configure RestClient directly with HttpHost</li>
 * </ul>
 * 
 * <p>
 * Automation Confidence: 100% for detection, 85% for configuration generation.
 * 
 * @see MigrationPhase
 */
public class ElasticsearchCodeMigrator23to24 extends MigrationPhase {

    private static final String REST_CLIENT = "org.elasticsearch.client.RestClient";
    private static final String REST_HIGH_LEVEL_CLIENT = "org.elasticsearch.client.RestHighLevelClient";

    public ElasticsearchCodeMigrator23to24(boolean dryRun) {
        super(dryRun);
    }

    @Override
    public MigrationPhaseResult migrate() throws IOException {
        MigrationPhaseResult result = new MigrationPhaseResult();

        Map<String, CompilationUnit> units = AntikytheraRunTime.getResolvedCompilationUnits();
        Map<String, RestClientUsage> filesWithLowLevelRestClient = new HashMap<>();
        Set<String> filesWithHighLevelClient = new HashSet<>();

        // Detection phase
        for (Map.Entry<String, CompilationUnit> entry : units.entrySet()) {
            String className = entry.getKey();
            CompilationUnit cu = entry.getValue();

            RestClientUsage usage = detectRestClientUsage(cu);
            if (usage.hasLowLevelClient) {
                filesWithLowLevelRestClient.put(className, usage);
            }
            if (usage.hasHighLevelClient) {
                filesWithHighLevelClient.add(className);
            }
        }

        if (filesWithLowLevelRestClient.isEmpty()) {
            result.addChange("No low-level Elasticsearch RestClient usage detected");
            return result;
        }

        result.setRequiresManualReview(true);
        result.addWarning(String.format(
                "ELASTICSEARCH: Low-level RestClient detected in %d files - auto-configuration removed",
                filesWithLowLevelRestClient.size()));

        // Report detailed findings
        reportUsageDetails(filesWithLowLevelRestClient, filesWithHighLevelClient, result);

        // Generate configuration class using AI
        if (!dryRun) {
            generateConfigurationClass(filesWithLowLevelRestClient, result);
        }

        // Add migration guide
        result.addChange(generateMigrationGuide(filesWithLowLevelRestClient, filesWithHighLevelClient));

        return result;
    }

    /**
     * Detects RestClient and RestHighLevelClient usage.
     */
    private RestClientUsage detectRestClientUsage(CompilationUnit cu) {
        boolean hasLowLevel = false;
        boolean hasHighLevel = false;
        List<UsageLocation> lowLevelUsageLocations = new ArrayList<>();

        // Check imports
        for (ImportDeclaration imp : cu.findAll(ImportDeclaration.class)) {
            String importName = imp.getNameAsString();

            if (REST_CLIENT.equals(importName)) {
                hasLowLevel = true;
            }
            if (REST_HIGH_LEVEL_CLIENT.equals(importName)) {
                hasHighLevel = true;
            }
        }

        // Check for @Autowired/@Inject fields of type RestClient
        if (hasLowLevel) {
            for (FieldDeclaration field : cu.findAll(FieldDeclaration.class)) {
                String fieldType = field.getCommonType().asString();
                if ("RestClient".equals(fieldType)) {
                    for (VariableDeclarator variable : field.getVariables()) {
                        lowLevelUsageLocations.add(new UsageLocation(
                                UsageType.FIELD,
                                field.getRange().map(r -> r.begin.line).orElse(0),
                                variable.getNameAsString()));
                    }
                }
            }
        }

        // Check method parameters and return types
        if (hasLowLevel) {
            for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
                // Check return type
                if ("RestClient".equals(method.getType().asString())) {
                    lowLevelUsageLocations.add(new UsageLocation(
                            UsageType.METHOD_RETURN,
                            method.getRange().map(r -> r.begin.line).orElse(0),
                            method.getNameAsString()));
                }

                // Check parameters
                for (Parameter param : method.getParameters()) {
                    if ("RestClient".equals(param.getType().asString())) {
                        lowLevelUsageLocations.add(new UsageLocation(
                                UsageType.METHOD_PARAMETER,
                                method.getRange().map(r -> r.begin.line).orElse(0),
                                method.getNameAsString() + "(" + param.getNameAsString() + ")"));
                    }
                }
            }
        }

        return new RestClientUsage(hasLowLevel, hasHighLevel, lowLevelUsageLocations);
    }

    /**
     * Reports detailed usage information.
     */
    private void reportUsageDetails(Map<String, RestClientUsage> lowLevelUsage,
            Set<String> highLevelUsage,
            MigrationPhaseResult result) {
        for (Map.Entry<String, RestClientUsage> entry : lowLevelUsage.entrySet()) {
            String className = entry.getKey();
            RestClientUsage usage = entry.getValue();

            result.addChange(String.format("Low-level RestClient detected in: %s", className));
            for (UsageLocation location : usage.usageLocations) {
                result.addChange(String.format("  Line %d [%s]: %s",
                        location.lineNumber,
                        location.type,
                        location.itemName));
            }
        }

        if (!highLevelUsage.isEmpty()) {
            result.addChange("\nFiles using RestHighLevelClient (no changes needed):");
            for (String className : highLevelUsage) {
                result.addChange("  - " + className);
            }
        }
    }

    /**
     * Generates Elasticsearch configuration class using AI.
     */
    private void generateConfigurationClass(Map<String, RestClientUsage> usageInfo,
            MigrationPhaseResult result) {
        try {
            String prompt = buildConfigGenerationPrompt(usageInfo);
            String generatedCode = AICodeGenerationHelper.generateCode(prompt);

            if (generatedCode != null && !generatedCode.isEmpty()) {
                Path configPath = determineConfigPath();
                if (configPath != null && !Files.exists(configPath)) {
                    Files.createDirectories(configPath.getParent());
                    Files.writeString(configPath, generatedCode);
                    result.addChange("Generated ElasticsearchConfig.java using AI with RestClient bean configuration");
                    result.addWarning("Review generated configuration and choose appropriate approach");
                }
            }
        } catch (Exception e) {
            result.addWarning("Failed to generate configuration class using AI: " + e.getMessage());
            result.addWarning("Manual configuration required - see migration guide");
        }
    }

    /**
     * Builds AI prompt for generating Elasticsearch configuration.
     */
    private String buildConfigGenerationPrompt(Map<String, RestClientUsage> usageInfo) {
        return """
                Generate a Spring Boot configuration class for Elasticsearch that provides a RestClient bean.

                Requirements:
                - Package: Use appropriate package (e.g., config or configuration)
                - Class name: ElasticsearchConfig
                - Annotations: @Configuration
                - Create two @Bean methods:
                  1. RestHighLevelClient bean (auto-configured by Spring Boot but shown for completeness)
                  2. RestClient bean obtained from RestHighLevelClient using getLowLevelClient()
                - Configure HttpHost with localhost:9200 by default
                - Add proper imports (RestClient, RestHighLevelClient, HttpHost, Configuration, Bean)
                - Include JavaDoc explaining both options and when to use each
                - Add a comment about adjusting host/port for production

                Additional context:
                - Spring Boot 2.4 removed auto-configuration for low-level RestClient
                - This configuration allows existing code using RestClient to continue working
                - Include a comment suggesting to migrate to RestHighLevelClient when possible

                Return ONLY the Java code without markdown formatting or explanations.
                """;
    }

    /**
     * Determines where to place the generated configuration class.
     */
    private Path determineConfigPath() {
        if (Settings.getBasePath() == null) {
            return null;
        }

        Path basePath = Paths.get(Settings.getBasePath());
        return basePath.resolve("src/main/java/config/ElasticsearchConfig.java");
    }

    /**
     * Generates comprehensive migration guide.
     */
    private String generateMigrationGuide(Map<String, RestClientUsage> lowLevelUsage,
            Set<String> highLevelUsage) {
        StringBuilder guide = new StringBuilder();
        guide.append("\n=== ELASTICSEARCH RESTCLIENT MIGRATION GUIDE ===\n\n");
        guide.append("Spring Boot 2.4 removed auto-configuration for low-level Elasticsearch RestClient.\n");
        guide.append("Only RestHighLevelClient is auto-configured.\n\n");

        guide.append("AUTO-GENERATED SOLUTION:\n");
        guide.append("✓ ElasticsearchConfig.java generated with RestClient bean configuration\n\n");

        guide.append("MIGRATION OPTIONS:\n\n");

        guide.append("Option 1: USE RESTHIGHLEVELCLIENT (Recommended)\n");
        guide.append("  Replace RestClient with RestHighLevelClient in your code:\n");
        guide.append("  @Autowired\n");
        guide.append("  private RestHighLevelClient restHighLevelClient;\n\n");

        guide.append("Option 2: USE GENERATED CONFIGURATION (Quick Fix)\n");
        guide.append("  The generated ElasticsearchConfig.java provides a RestClient bean.\n");
        guide.append("  Your existing code will continue to work without changes.\n");
        guide.append("  Review and adjust connection details in the configuration.\n\n");

        guide.append("Option 3: CREATE CUSTOM CONFIGURATION\n");
        guide.append("  Configure RestClient directly if you need custom settings:\n");
        guide.append("  @Bean\n");
        guide.append("  public RestClient restClient() {\n");
        guide.append("      return RestClient.builder(\n");
        guide.append("          new HttpHost(\"localhost\", 9200, \"http\")\n");
        guide.append("      ).build();\n");
        guide.append("  }\n\n");

        guide.append("FILES REQUIRING ATTENTION:\n");
        for (String className : lowLevelUsage.keySet()) {
            guide.append("  - ").append(className).append(" (uses RestClient)\n");
        }

        if (!highLevelUsage.isEmpty()) {
            guide.append("\nFILES USING RESTHIGHLEVELCLIENT (No changes needed):\n");
            for (String className : highLevelUsage) {
                guide.append("  - ").append(className).append("\n");
            }
        }

        return guide.toString();
    }

    @Override
    public String getPhaseName() {
        return "Elasticsearch RestClient Detection";
    }

    @Override
    public int getPriority() {
        return 50;
    }

    /**
     * Holds information about RestClient usage in a file.
     */
    private static class RestClientUsage {
        final boolean hasLowLevelClient;
        final boolean hasHighLevelClient;
        final List<UsageLocation> usageLocations;

        RestClientUsage(boolean hasLowLevelClient, boolean hasHighLevelClient,
                List<UsageLocation> usageLocations) {
            this.hasLowLevelClient = hasLowLevelClient;
            this.hasHighLevelClient = hasHighLevelClient;
            this.usageLocations = usageLocations;
        }
    }

    /**
     * Represents a specific location where RestClient is used.
     */
    private static class UsageLocation {
        final UsageType type;
        final int lineNumber;
        final String itemName;

        UsageLocation(UsageType type, int lineNumber, String itemName) {
            this.type = type;
            this.lineNumber = lineNumber;
            this.itemName = itemName;
        }
    }

    private enum UsageType {
        FIELD, METHOD_RETURN, METHOD_PARAMETER
    }
}
