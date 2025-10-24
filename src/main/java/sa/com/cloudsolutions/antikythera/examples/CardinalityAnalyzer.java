package sa.com.cloudsolutions.antikythera.examples;

import sa.com.cloudsolutions.liquibase.Indexes;

import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.Set;

/**
 * Analyzes column cardinality based on database metadata from Liquibase IndexInfo data.
 * Determines whether columns are high, medium, or low cardinality to support query optimization.
 */
public class CardinalityAnalyzer {
    
    private final Map<String, List<Indexes.IndexInfo>> indexMap;
    // Optional map of table -> (column -> data type) for accurate low-cardinality detection
    private final Map<String, Map<String, ColumnDataType>> columnTypeMap;

    // User-provided overrides for column cardinality (lower-cased column names)
    private static Set<String> USER_DEFINED_LOW = Collections.emptySet();
    private static Set<String> USER_DEFINED_HIGH = Collections.emptySet();

    /**
     * Column data type categories for cardinality purposes.
     */
    public enum ColumnDataType {
        BOOLEAN,
        ENUM,
        OTHER
    }

    /**
     * Configure user-defined low/high cardinality columns.
     * Provided column names should be lower-cased; this method does not modify case.
     */
    public static void configureUserDefinedCardinality(Set<String> low, Set<String> high) {
        USER_DEFINED_LOW = low != null ? low : Collections.emptySet();
        USER_DEFINED_HIGH = high != null ? high : Collections.emptySet();
    }
    
    /**
     * Creates a new CardinalityAnalyzer with the provided index information.
     * 
     * @param indexMap Map of table names to their index information
     */
    public CardinalityAnalyzer(Map<String, List<Indexes.IndexInfo>> indexMap) {
        this(indexMap, null);
    }

    /**
     * Creates a new CardinalityAnalyzer with index information and optional column type information.
     * Table and column names should be lower-cased in the map for consistent matching.
     *
     * @param indexMap Map of table names to their index information
     * @param columnTypeMap Optional map of table -> (column -> ColumnDataType)
     */
    public CardinalityAnalyzer(Map<String, List<Indexes.IndexInfo>> indexMap,
                               Map<String, Map<String, ColumnDataType>> columnTypeMap) {
        this.indexMap = indexMap;
        this.columnTypeMap = columnTypeMap;
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
            return CardinalityLevel.MEDIUM;
        }
        
        String normalizedTableName = tableName.toLowerCase();
        String normalizedColumnName = columnName.toLowerCase();

        // Honor user-defined overrides first (high takes precedence if listed in both)
        if (USER_DEFINED_HIGH.contains(normalizedColumnName)) {
            return CardinalityLevel.HIGH;
        }
        if (USER_DEFINED_LOW.contains(normalizedColumnName)) {
            return CardinalityLevel.LOW;
        }
        
        // Check if column is a primary key (highest cardinality)
        if (isPrimaryKey(normalizedTableName, normalizedColumnName)) {
            return CardinalityLevel.HIGH;
        }
        
        // Check if column has unique constraint (high cardinality)
        if (hasUniqueConstraint(normalizedTableName, normalizedColumnName)) {
            return CardinalityLevel.HIGH;
        }
        
        // Prefer entity metadata: boolean or enum columns are low cardinality
        if (isBooleanOrEnumByType(normalizedTableName, normalizedColumnName)) {
            return CardinalityLevel.LOW;
        }

        // Fallback heuristic only if no type metadata is available
        if (!hasTypeMetadata(normalizedTableName, normalizedColumnName) &&
            isBooleanColumn(normalizedTableName, normalizedColumnName)) {
            return CardinalityLevel.LOW;
        }
        
        // If the column cannot be readily identified as LOW or HIGH, mark as MEDIUM
        return CardinalityLevel.MEDIUM;
    }
    
    private boolean hasTypeMetadata(String tableName, String columnName) {
        if (columnTypeMap == null) return false;
        Map<String, ColumnDataType> cols = columnTypeMap.get(tableName);
        if (cols == null) return false;
        return cols.containsKey(columnName);
    }

    /**
     * Determines if the column type (from entity metadata) implies low cardinality.
     * Returns true for BOOLEAN or ENUM types.
     */
    public boolean isBooleanOrEnumByType(String tableName, String columnName) {
        if (columnTypeMap == null) return false;
        Map<String, ColumnDataType> cols = columnTypeMap.get(tableName);
        if (cols == null) return false;
        ColumnDataType type = cols.get(columnName);
        if (type == null) return false;
        return type == ColumnDataType.BOOLEAN || type == ColumnDataType.ENUM;
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

    /**
     * Checks if there exists any index on the table where the specified column is the leading column.
     * Considers primary key, unique indexes/constraints, and regular indexes.
     *
     * @param tableName the table name (any case)
     * @param columnName the column name (any case)
     * @return true if there is an index whose first column matches the given column
     */
    public boolean hasIndexWithLeadingColumn(String tableName, String columnName) {
        if (tableName == null || columnName == null) {
            return false;
        }
        String t = tableName.toLowerCase();
        String c = columnName.toLowerCase();
        List<Indexes.IndexInfo> indexes = indexMap.get(t);
        if (indexes == null) return false;
        for (Indexes.IndexInfo idx : indexes) {
            if (idx.columns == null || idx.columns.isEmpty()) continue;
            String first = idx.columns.get(0);
            if (first != null && first.equalsIgnoreCase(c)) {
                return true;
            }
        }
        return false;
    }
}
