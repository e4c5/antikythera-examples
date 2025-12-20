package com.raditha.spring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Detects Elasticsearch RestClient usage for Spring Boot 2.4.
 * 
 * <p>
 * Spring Boot 2.4 removed auto-configuration for the low-level Elasticsearch
 * RestClient.
 * Only RestHighLevelClient is auto-configured now.
 * 
 * <p>
 * This migrator:
 * <ul>
 * <li>Detects low-level RestClient usage in source code</li>
 * <li>Flags files requiring manual configuration of RestClient bean</li>
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
 * @see MigrationPhase
 */
public class ElasticsearchCodeMigrator23to24 extends MigrationPhase {


    public ElasticsearchCodeMigrator23to24(boolean dryRun) {
        super(dryRun);
    }

    @Override
    public MigrationPhaseResult migrate() {
        MigrationPhaseResult result = new MigrationPhaseResult();

        Map<String, CompilationUnit> units = AntikytheraRunTime.getResolvedCompilationUnits();
        List<String> filesWithLowLevelRestClient = new ArrayList<>();
        List<String> filesWithHighLevelClient = new ArrayList<>();

        for (Map.Entry<String, CompilationUnit> entry : units.entrySet()) {
            String className = entry.getKey();
            CompilationUnit cu = entry.getValue();

            if (cu == null) {
                continue;
            }

            boolean hasLowLevel = false;
            boolean hasHighLevel = false;

            // Check imports
            for (ImportDeclaration imp : cu.findAll(ImportDeclaration.class)) {
                String importName = imp.getNameAsString();

                if ("org.elasticsearch.client.RestClient".equals(importName)) {
                    hasLowLevel = true;
                }
                if ("org.elasticsearch.client.RestHighLevelClient".equals(importName)) {
                    hasHighLevel = true;
                }
            }

            // Check for @Autowired fields of type RestClient
            if (hasLowLevel) {
                for (FieldDeclaration field : cu.findAll(FieldDeclaration.class)) {
                    String fieldType = field.getCommonType().asString();
                    if ("RestClient".equals(fieldType)) {
                        filesWithLowLevelRestClient.add(className);
                        break;
                    }
                }
            }

            if (hasHighLevel) {
                filesWithHighLevelClient.add(className);
            }
        }

        if (filesWithLowLevelRestClient.isEmpty()) {
            result.addChange("No low-level Elasticsearch RestClient usage detected");
            logger.info("No low-level RestClient imports found");
            return result;
        }

        // Generate migration guide
        result.setRequiresManualReview(true);
        result.addWarning(String.format(
                "ELASTICSEARCH: Low-level RestClient detected in %d files - auto-configuration removed",
                filesWithLowLevelRestClient.size()));

        // Add specific files that need attention
        for (String className : filesWithLowLevelRestClient) {
            result.addChange("Low-level RestClient detected in: " + className);
        }

        // Generate detailed migration guide
        StringBuilder guide = new StringBuilder();
        guide.append("\n=== ELASTICSEARCH RESTCLIENT MIGRATION GUIDE ===\n\n");
        guide.append("Spring Boot 2.4 removed auto-configuration for low-level Elasticsearch RestClient.\n");
        guide.append("Only RestHighLevelClient is auto-configured.\n\n");

        guide.append("MIGRATION OPTIONS:\n\n");

        guide.append("Option 1: USE RESTHIGHLEVELCLIENT (Recommended)\n");
        guide.append("  @Autowired\n");
        guide.append("  private RestHighLevelClient restHighLevelClient;\n\n");

        guide.append("Option 2: CREATE RESTCLIENT BEAN FROM RESTHIGHLEVELCLIENT\n");
        guide.append("  @Configuration\n");
        guide.append("  public class ElasticsearchConfig {\n");
        guide.append("      @Bean\n");
        guide.append("      public RestClient restClient(RestHighLevelClient restHighLevelClient) {\n");
        guide.append("          return restHighLevelClient.getLowLevelClient();\n");
        guide.append("      }\n");
        guide.append("  }\n\n");

        guide.append("Option 3: CONFIGURE RESTCLIENT DIRECTLY\n");
        guide.append("  @Bean\n");
        guide.append("  public RestClient restClient() {\n");
        guide.append("      return RestClient.builder(\n");
        guide.append("          new HttpHost(\"localhost\", 9200, \"http\")\n");
        guide.append("      ).build();\n");
        guide.append("  }\n\n");

        guide.append("FILES REQUIRING CHANGES:\n");
        for (String className : filesWithLowLevelRestClient) {
            guide.append("  - ").append(className).append("\n");
        }

        if (!filesWithHighLevelClient.isEmpty()) {
            guide.append("\nFILES USING RESTHIGHLEVELCLIENT (No changes needed):\n");
            for (String className : filesWithHighLevelClient) {
                guide.append("  - ").append(className).append("\n");
            }
        }

        logger.warn("\n{}", guide);
        result.addChange(guide.toString());

        return result;
    }

    @Override
    public String getPhaseName() {
        return "Elasticsearch RestClient Detection";
    }

    @Override
    public int getPriority() {
        return 50;
    }
}
