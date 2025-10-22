package sa.com.cloudsolutions.antikythera.examples;

import sa.com.cloudsolutions.liquibase.Indexes;

import java.util.List;
import java.util.Map;

/**
 * Analyzes column cardinality based on database metadata from Liquibase IndexInfo data.
 * Determines whether columns are high, medium, or low cardinality to support query optimization.
 */
public class CardinalityAnalyzer {
    
    private final Map<String, List<Indexes.IndexInfo>> indexMap;
    
    /**
     * Creates a new CardinalityAnalyzer with the provided index information.
     * 
     * @param indexMap Map of table names to their index information
     */
    public CardinalityAnalyzer(Map<String, List<Indexes.IndexInfo>> indexMap) {
        this.indexMap = indexMap;
    }
    
    /**
     * Analyzes the cardinality level of a specific column in a table.
     * 
     * @param tableName the name of the table
     * @param columnName the name of the column to analyze
     * @return the cardinality level of the column
     */
    public CardinalityLevel analyzeColumnCardinality(String tableName, String columnName) {
        if (tableName == null || columnName == null) {
            return CardinalityLevel.LOW;
        }
        
        String normalizedTableName = tableName.toLowerCase();
        String normalizedColumnName = columnName.toLowerCase();
        
        // Check if column is a primary key (highest cardinality)
        if (isPrimaryKey(normalizedTableName, normalizedColumnName)) {
            return CardinalityLevel.HIGH;
        }
        
        // Check if column has unique constraint (high cardinality)
        if (hasUniqueConstraint(normalizedTableName, normalizedColumnName)) {
            return CardinalityLevel.HIGH;
        }
        
        // Check if column is boolean type (low cardinality)
        if (isBooleanColumn(normalizedTableName, normalizedColumnName)) {
            return CardinalityLevel.LOW;
        }
        
        // Check if column is indexed (medium cardinality)
        if (hasRegularIndex(normalizedTableName, normalizedColumnName)) {
            return CardinalityLevel.MEDIUM;
        }
        
        // Default to low cardinality for unindexed columns
        return CardinalityLevel.LOW;
    }
    
    /**
     * Checks if a column is a primary key.
     * 
     * @param tableName the name of the table (should be normalized to lowercase)
     * @param columnName the name of the column (should be normalized to lowercase)
     * @return true if the column is a primary key, false otherwise
     */
    public boolean isPrimaryKey(String tableName, String columnName) {
        List<Indexes.IndexInfo> indexes = indexMap.get(tableName);
        if (indexes == null) {
            return false;
        }
        
        return indexes.stream()
                .filter(index -> "PRIMARY_KEY".equals(index.type))
                .flatMap(index -> index.columns.stream())
                .anyMatch(col -> col.toLowerCase().equals(columnName));
    }
    
    /**
     * Checks if a column is a boolean type based on naming conventions.
     * This method uses heuristics to identify boolean columns since type information
     * may not be available in the index metadata.
     * 
     * @param tableName the name of the table (should be normalized to lowercase)
     * @param columnName the name of the column (should be normalized to lowercase)
     * @return true if the column appears to be boolean type, false otherwise
     */
    public boolean isBooleanColumn(String tableName, String columnName) {
        // Common boolean column naming patterns
        return columnName.startsWith("is_") || 
               columnName.startsWith("has_") || 
               columnName.startsWith("can_") || 
               columnName.startsWith("should_") ||
               columnName.endsWith("_flag") ||
               columnName.endsWith("_enabled") ||
               columnName.endsWith("_active") ||
               columnName.equals("active") ||
               columnName.equals("enabled") ||
               columnName.equals("deleted") ||
               columnName.equals("visible");
    }
    
    /**
     * Checks if a column has a unique constraint or unique index.
     * 
     * @param tableName the name of the table (should be normalized to lowercase)
     * @param columnName the name of the column (should be normalized to lowercase)
     * @return true if the column has a unique constraint, false otherwise
     */
    public boolean hasUniqueConstraint(String tableName, String columnName) {
        List<Indexes.IndexInfo> indexes = indexMap.get(tableName);
        if (indexes == null) {
            return false;
        }
        
        return indexes.stream()
                .filter(index -> "UNIQUE_CONSTRAINT".equals(index.type) || "UNIQUE_INDEX".equals(index.type))
                .flatMap(index -> index.columns.stream())
                .anyMatch(col -> col.toLowerCase().equals(columnName));
    }
    
    /**
     * Checks if a column has a regular (non-unique) index.
     * 
     * @param tableName the name of the table (should be normalized to lowercase)
     * @param columnName the name of the column (should be normalized to lowercase)
     * @return true if the column has a regular index, false otherwise
     */
    private boolean hasRegularIndex(String tableName, String columnName) {
        List<Indexes.IndexInfo> indexes = indexMap.get(tableName);
        if (indexes == null) {
            return false;
        }
        
        return indexes.stream()
                .filter(index -> "INDEX".equals(index.type))
                .flatMap(index -> index.columns.stream())
                .anyMatch(col -> col.toLowerCase().equals(columnName));
    }
}