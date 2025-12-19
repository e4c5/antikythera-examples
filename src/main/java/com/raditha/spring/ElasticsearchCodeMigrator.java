package com.raditha.spring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Detects Elasticsearch TransportClient usage and generates migration guide for
 * REST Client.
 * 
 * <p>
 * This migrator focuses on <b>detection and guidance</b> rather than automatic
 * transformation
 * due to the complexity of REST client migration.
 * 
 * <p>
 * Major changes in Elasticsearch client:
 * <ul>
 * <li>TransportClient deprecated in Elasticsearch 7.x, removed in 8.x</li>
 * <li>REST High Level Client is the recommended replacement</li>
 * <li>API changes: Different request/response objects</li>
 * <li>Connection management changes</li>
 * <li>Query builder changes</li>
 * </ul>
 * 
 * @see MigrationPhase
 */
public class ElasticsearchCodeMigrator implements MigrationPhase {
    private static final Logger logger = LoggerFactory.getLogger(ElasticsearchCodeMigrator.class);

    private final boolean dryRun;

    public ElasticsearchCodeMigrator(boolean dryRun) {
        this.dryRun = dryRun;
    }

    @Override
    public MigrationPhaseResult migrate() {
        MigrationPhaseResult result = new MigrationPhaseResult();

        Map<String, CompilationUnit> units = AntikytheraRunTime.getResolvedCompilationUnits();
        List<String> filesWithTransportClient = new ArrayList<>();

        for (Map.Entry<String, CompilationUnit> entry : units.entrySet()) {
            String className = entry.getKey();
            CompilationUnit cu = entry.getValue();

            if (cu == null) {
                continue;
            }

            // Check for TransportClient imports
            for (ImportDeclaration imp : cu.findAll(ImportDeclaration.class)) {
                String importName = imp.getNameAsString();

                // TransportClient and related classes
                if (importName.contains("TransportClient") ||
                        importName.startsWith("org.elasticsearch.client.transport") ||
                        importName.equals("org.elasticsearch.common.settings.Settings") ||
                        importName.startsWith("org.elasticsearch.common.transport")) {
                    filesWithTransportClient.add(className);
                    break;
                }
            }
        }

        if (filesWithTransportClient.isEmpty()) {
            result.addChange("No Elasticsearch TransportClient usage detected");
            logger.info("No TransportClient imports found");
            return result;
        }

        // Generate migration guide
        result.addWarning(String.format(
                "ELASTICSEARCH: Detected TransportClient usage in %d files",
                filesWithTransportClient.size()));

        result.addWarning(
                "Elasticsearch REST High Level Client migration required - Transport Client deprecated");

        // Add specific files that need attention
        for (String className : filesWithTransportClient) {
            result.addChange("TransportClient detected in: " + className);
        }

        // Generate detailed migration guide
        StringBuilder guide = new StringBuilder();
        guide.append("\n=== ELASTICSEARCH REST CLIENT MIGRATION GUIDE ===\n\n");
        guide.append("TransportClient is deprecated and must be migrated to REST High Level Client:\n\n");
        guide.append("1. DEPENDENCY CHANGES:\n");
        guide.append("   REMOVE: org.elasticsearch.client:transport\n");
        guide.append("   ADD:    org.elasticsearch.client:elasticsearch-rest-high-level-client\n\n");
        guide.append("2. CLIENT INITIALIZATION:\n");
        guide.append("   OLD: TransportClient client = new PreBuiltTransportClient(settings)\n");
        guide.append("            .addTransportAddress(new TransportAddress(...));\n");
        guide.append("   NEW: RestHighLevelClient client = new RestHighLevelClient(\n");
        guide.append("            RestClient.builder(new HttpHost(\"localhost\", 9200, \"http\")));\n\n");
        guide.append("3. INDEX OPERATIONS:\n");
        guide.append("   OLD: client.prepareIndex(\"index\", \"type\", \"id\")\n");
        guide.append("            .setSource(jsonMap).get();\n");
        guide.append("   NEW: IndexRequest request = new IndexRequest(\"index\")\n");
        guide.append("            .id(\"id\").source(jsonMap);\n");
        guide.append("        client.index(request, RequestOptions.DEFAULT);\n\n");
        guide.append("4. SEARCH OPERATIONS:\n");
        guide.append("   OLD: SearchResponse response = client.prepareSearch(\"index\")\n");
        guide.append("            .setQuery(QueryBuilders.matchAllQuery()).get();\n");
        guide.append("   NEW: SearchRequest searchRequest = new SearchRequest(\"index\");\n");
        guide.append("        SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();\n");
        guide.append("        sourceBuilder.query(QueryBuilders.matchAllQuery());\n");
        guide.append("        searchRequest.source(sourceBuilder);\n");
        guide.append("        SearchResponse response = client.search(searchRequest, RequestOptions.DEFAULT);\n\n");
        guide.append("FILES REQUIRING CHANGES:\n");
        for (String className : filesWithTransportClient) {
            guide.append("  - ").append(className).append("\n");
        }
        guide.append(
                "\nREFERENCE: https://www.elastic.co/guide/en/elasticsearch/client/java-rest/current/java-rest-high.html\n");

        logger.warn("\n{}", guide);
        result.addChange(guide.toString());

        // Elasticsearch REST client migration requires manual review
        result.setRequiresManualReview(true);

        return result;
    }

    @Override
    public String getPhaseName() {
        return "Elasticsearch REST Client Migration";
    }

    @Override
    public int getPriority() {
        return 41;
    }
}
