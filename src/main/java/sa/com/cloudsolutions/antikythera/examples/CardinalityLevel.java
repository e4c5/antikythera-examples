package sa.com.cloudsolutions.antikythera.examples;

/**
 * Enum representing the cardinality level of database columns.
 * Used to determine the selectivity of columns for query optimization.
 */
public enum CardinalityLevel {
    /**
     * High cardinality columns with many unique values.
     * Examples: primary keys, unique constraints, non-boolean columns.
     */
    HIGH,
    
    /**
     * Medium cardinality columns with moderate unique values.
     * Examples: regular indexed columns without unique constraints.
     */
    MEDIUM,
    
    /**
     * Low cardinality columns with few unique values.
     * Examples: boolean columns, status fields with limited options.
     */
    LOW
}