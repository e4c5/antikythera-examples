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
    
    private final CardinalityAnalyzer cardinalityAnalyzer;
    private final Map<String, QueryBatch> repositoryBatches;

    public QueryBatchProcessor(CardinalityAnalyzer cardinalityAnalyzer) {
        this.cardinalityAnalyzer = cardinalityAnalyzer;
        this.repositoryBatches = new HashMap<>();
    }

    /**
     * Adds a query result to the appropriate repository batch.
     * Extracts cardinality information for columns involved in the query.
     */
    public void addQueryResult(QueryOptimizationResult result) {
        if (result == null || result.getQuery() == null) {
            return;
        }

        String repositoryName = result.getRepositoryClass();
        if (repositoryName == null) {
            repositoryName = "UnknownRepository";
        }

        // Get or create batch for this repository
        QueryBatch batch = repositoryBatches.computeIfAbsent(repositoryName, QueryBatch::new);
        
        // Add the query to the batch
        batch.addQuery(result.getQuery());
        
        // Extract and add cardinality information for WHERE clause columns
        extractCardinalityInformation(batch, result);
        
        logger.debug("Added query {} to batch for repository {}", 
                result.getMethodName(), repositoryName);
    }

    /**
     * Extracts cardinality information from the query result and adds it to the batch.
     */
    private void extractCardinalityInformation(QueryBatch batch, QueryOptimizationResult result) {
        String tableName = result.getQuery().getTable();
        
        // Extract cardinality for WHERE clause columns
        for (WhereCondition condition : result.getWhereConditions()) {
            String columnName = condition.columnName();
            CardinalityLevel cardinality = condition.cardinality();
            
            if (columnName != null && cardinality != null) {
                batch.addColumnCardinality(columnName, cardinality);
                logger.debug("Added cardinality info: {} -> {} for table {}", 
                        columnName, cardinality, tableName);
            }
        }
        
        // Also analyze any additional columns that might be referenced in the query
        // but not captured in WHERE conditions
        if (tableName != null) {
            analyzeAdditionalColumns(batch, tableName, result);
        }
    }

    /**
     * Analyzes additional columns that might be referenced in the query
     * to provide comprehensive cardinality information.
     */
    private void analyzeAdditionalColumns(QueryBatch batch, String tableName, QueryOptimizationResult result) {
        // This is a simple implementation - in a more complete version,
        // we would parse the query to extract all referenced columns
        String queryText = result.getQuery().getOriginalQuery();
        if (queryText != null) {
            // Extract column names from common patterns in queries
            extractColumnsFromQuery(batch, tableName, queryText);
        }
    }

    /**
     * Simple extraction of column names from query text.
     * This is a basic implementation that could be enhanced with proper SQL parsing.
     */
    private void extractColumnsFromQuery(QueryBatch batch, String tableName, String queryText) {
        // Simple regex-based extraction for common column patterns
        // This is intentionally simple for the basic implementation
        String[] commonColumns = {"id", "created_date", "updated_date", "status", "active"};
        
        for (String column : commonColumns) {
            if (queryText.toLowerCase().contains(column.toLowerCase())) {
                CardinalityLevel cardinality = cardinalityAnalyzer.analyzeColumnCardinality(tableName, column);
                if (cardinality != null && !batch.getColumnCardinalities().containsKey(column)) {
                    batch.addColumnCardinality(column, cardinality);
                    logger.debug("Extracted additional column cardinality: {} -> {}", column, cardinality);
                }
            }
        }
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
        summary.append(String.format("QueryBatchProcessor Summary: %d repositories, %d total queries\n", 
                getBatchCount(), getTotalQueryCount()));
        
        for (Map.Entry<String, QueryBatch> entry : repositoryBatches.entrySet()) {
            QueryBatch batch = entry.getValue();
            summary.append(String.format("  - %s: %d queries, %d cardinality entries\n", 
                    entry.getKey(), batch.size(), batch.getColumnCardinalities().size()));
        }
        
        return summary.toString();
    }
}