package sa.com.cloudsolutions.antikythera.examples;

import sa.com.cloudsolutions.liquibase.Indexes;

import java.util.HashMap;
import java.util.Map;
import java.util.Collections;
import java.util.Set;

/**
 * Analyzes column cardinality based on database metadata from Liquibase IndexInfo data.
 * Determines whether columns are high, medium, or low cardinality to support query optimization.
 *
 * This analysis does not consider the keys defined with in the entity. When liquibase is used for
 * Schema migrations, what matters is the definitions in the liquibase xml files rather than the
 * entities.
 */
public class CardinalityAnalyzer {
    
    private static Map<String, Set<Indexes.IndexInfo>> indexMap;
    // Optional map of table -> (column -> data type) for accurate low-cardinality detection
    private static final Map<String, Map<String, ColumnDataType>> columnTypeMap = new HashMap<>();

    // User-provided overrides for column cardinality (lower-cased column names)
    private static Set<String> userDefinedLow = Collections.emptySet();
    private static Set<String> userDefinedHigh = Collections.emptySet();

    public static Map<String, Set<Indexes.IndexInfo>> getIndexMap() {
        return indexMap;
    }

    /**
     * Column data type categories for cardinality purposes.
     */
    public enum ColumnDataType {
        BOOLEAN,
        ENUM,
        OTHER
    }

    public static void setIndexMap(Map<String, Set<Indexes.IndexInfo>> indexMap) {
        CardinalityAnalyzer.indexMap = indexMap;
    }
    /**
     * Configure user-defined low/high cardinality columns.
     * Provided column names should be lower-cased; this method does not modify case.
     */
    public static void configureUserDefinedCardinality(Set<String> low, Set<String> high) {
        userDefinedLow = low != null ? low : Collections.emptySet();
        userDefinedHigh = high != null ? high : Collections.emptySet();
    }

    /**
     * Analyzes the cardinality level of a specific column in a table.
     * 
     * @param tableName the name of the table
     * @param columnName the name of the column to analyze
     * @return the cardinality level of the column
     */
    public static CardinalityLevel analyzeColumnCardinality(String tableName, String columnName) {
        if (tableName == null || columnName == null) {
            return CardinalityLevel.MEDIUM;
        }
        
        String normalizedTableName = tableName.toLowerCase();
        String normalizedColumnName = columnName.toLowerCase();

        // Honor user-defined overrides first (high takes precedence if listed in both)
        if (userDefinedHigh.contains(normalizedColumnName)) {
            return CardinalityLevel.HIGH;
        }
        if (userDefinedLow.contains(normalizedColumnName)) {
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
            isBooleanColumn(normalizedColumnName)) {
            return CardinalityLevel.LOW;
        }
        
        // If the column cannot be readily identified as LOW or HIGH, mark as MEDIUM
        return CardinalityLevel.MEDIUM;
    }
    
    private static boolean hasTypeMetadata(String tableName, String columnName) {
        Map<String, ColumnDataType> cols = columnTypeMap.get(tableName);
        return cols != null && cols.containsKey(columnName);
    }

    /**
     * Determines if the column type (from entity metadata) implies low cardinality.
     * Returns true for BOOLEAN or ENUM types.
     */
    public static boolean isBooleanOrEnumByType(String tableName, String columnName) {
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
    public static boolean isPrimaryKey(String tableName, String columnName) {
        if (indexMap == null) {
            return false;
        }
        Set<Indexes.IndexInfo> indexes = indexMap.get(tableName);
        if (indexes == null) {
            return false;
        }

        return indexes.stream()
                .filter(index -> "PRIMARY_KEY".equals(index.type()))
                .flatMap(index -> index.columns().stream())
                .anyMatch(col -> col.toLowerCase().equals(columnName));
    }
    
    /**
     * Checks if a column is a boolean type based on naming conventions.
     * This method uses heuristics to identify boolean columns since type information
     * may not be available in the index metadata.
     * 
     * @param columnName the name of the column (should be normalized to lowercase)
     * @return true if the column appears to be boolean type, false otherwise
     */
    public static boolean isBooleanColumn(String columnName) {
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
    public static boolean hasUniqueConstraint(String tableName, String columnName) {
        Set<Indexes.IndexInfo> indexes = indexMap.get(tableName);
        if (indexes == null) {
            return false;
        }
        
        return indexes.stream()
                .filter(index -> "UNIQUE_CONSTRAINT".equals(index.type()) || "UNIQUE_INDEX".equals(index.type()))
                .flatMap(index -> index.columns().stream())
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
    public static boolean hasIndexWithLeadingColumn(String tableName, String columnName) {
        String t = tableName.toLowerCase();
        String c = columnName.toLowerCase();
        Set<Indexes.IndexInfo> indexes = indexMap.get(t);
        if (indexes == null) return false;
        for (Indexes.IndexInfo idx : indexes) {
            if (idx.columns() == null || idx.columns().isEmpty()) continue;
            String first = idx.columns().getFirst();
            if (first != null && first.equalsIgnoreCase(c)) {
                return true;
            }
        }
        return false;
    }

}
