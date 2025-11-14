package sa.com.cloudsolutions.antikythera.examples;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates queries and cardinality data by repository for batch processing.
 * Groups RepositoryQuery objects from the same repository into QueryBatch objects
 * for efficient AI service processing.
 */
public class QueryBatchProcessor {
    private static final Logger logger = LoggerFactory.getLogger(QueryBatchProcessor.class);
    private final Map<String, QueryBatch> repositoryBatches;

    public QueryBatchProcessor() {
        this.repositoryBatches = new HashMap<>();
    }

    /**
     * Extracts cardinality information from the query result.
     * Returns a map of column names to their cardinality levels.
     */
    private Map<String, CardinalityLevel> extractCardinalityInformation(QueryOptimizationResult result) {
        Map<String, CardinalityLevel> cardinalityData = new HashMap<>();
        String tableName = result.query().getPrimaryTable();
        
        // Extract cardinality for WHERE clause columns
        for (WhereCondition condition : result.whereConditions()) {
            String columnName = condition.columnName();
            CardinalityLevel cardinality = condition.cardinality();
            
            if (columnName != null && cardinality != null) {
                cardinalityData.put(columnName, cardinality);
                logger.debug("Extracted cardinality info: {} -> {} for table {}", 
                        columnName, cardinality, tableName);
            }
        }
        
        // Also analyze any additional columns that might be referenced in the query
        // but not captured in WHERE conditions
        if (tableName != null) {
            Map<String, CardinalityLevel> additionalCardinalities = analyzeAdditionalColumns(tableName, result);
            cardinalityData.putAll(additionalCardinalities);
        }
        
        return cardinalityData;
    }

    /**
     * Analyzes additional columns that might be referenced in the query
     * to provide comprehensive cardinality information.
     * Returns a map of column names to their cardinality levels.
     */
    private Map<String, CardinalityLevel> analyzeAdditionalColumns(String tableName, QueryOptimizationResult result) {
        // Leverage existing RepositoryQuery infrastructure to extract column information
        return extractColumnsFromQuery(tableName, result);
    }

    /**
     * Extracts column names from query using existing RepositoryQuery infrastructure.
     * Leverages the existing query parameter analysis to identify referenced columns.
     * Returns a map of column names to their cardinality levels.
     */
    private Map<String, CardinalityLevel> extractColumnsFromQuery(String tableName, QueryOptimizationResult result) {
        Map<String, CardinalityLevel> cardinalityData = new HashMap<>();
        
        // Use the existing RepositoryQuery's method parameter analysis
        // to identify columns that are referenced in the query
        RepositoryQuery query = result.query();
        
        // Extract columns from method parameters which map to query placeholders
        for (var parameter : query.getMethodParameters()) {
            String columnName = parameter.getColumnName();
            if (columnName != null) {
                CardinalityLevel cardinality = CardinalityAnalyzer.analyzeColumnCardinality(tableName, columnName);
                if (cardinality != null) {
                    cardinalityData.put(columnName, cardinality);
                    logger.debug("Extracted parameter column cardinality: {} -> {}", columnName, cardinality);
                }
            }
        }
        
        return cardinalityData;
    }

    /**
     * Gets all repository batches that have been collected.
     */
    public List<QueryBatch> getAllBatches() {
        return new ArrayList<>(repositoryBatches.values());
    }

    /**
     * Gets a specific repository batch by name.
     */
    public QueryBatch getBatch(String repositoryName) {
        return repositoryBatches.get(repositoryName);
    }

    /**
     * Gets the number of repository batches.
     */
    public int getBatchCount() {
        return repositoryBatches.size();
    }

    /**
     * Gets the total number of queries across all batches.
     */
    public int getTotalQueryCount() {
        return repositoryBatches.values().stream()
                .mapToInt(QueryBatch::size)
                .sum();
    }

    /**
     * Clears all collected batches.
     */
    public void clear() {
        repositoryBatches.clear();
    }

    /**
     * Returns a summary of the collected batches for logging/debugging.
     */
    public String getSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append(String.format("QueryBatchProcessor Summary: %d repositories, %d total queries%n",
                getBatchCount(), getTotalQueryCount()));
        
        for (Map.Entry<String, QueryBatch> entry : repositoryBatches.entrySet()) {
            QueryBatch batch = entry.getValue();
            summary.append(String.format("  - %s: %d queries, %d cardinality entries%n",
                    entry.getKey(), batch.size(), batch.getColumnCardinalities().size()));
        }
        
        return summary.toString();
    }
}
