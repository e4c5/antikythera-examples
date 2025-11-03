package sa.com.cloudsolutions.antikythera.examples;

import sa.com.cloudsolutions.antikythera.generator.QueryMethodParameter;

/**
 * Represents a parsed WHERE clause condition from a repository query.
 * Contains information about the table, column, operator, cardinality, and position
 * within the WHERE clause for optimization analysis.
 * 
 * Enhanced to support JOINs by tracking which table each column belongs to.
 */
public record WhereCondition(String tableName, String columnName, String operator, CardinalityLevel cardinality, int position,
                             QueryMethodParameter parameter) {
    /**
     * Checks if this condition uses a high cardinality column.
     *
     * @return true if the column has high cardinality, false otherwise
     */
    public boolean isHighCardinality() {
        return cardinality == CardinalityLevel.HIGH;
    }

    /**
     * Checks if this condition uses a low cardinality column.
     *
     * @return true if the column has low cardinality, false otherwise
     */
    public boolean isLowCardinality() {
        return cardinality == CardinalityLevel.LOW;
    }

}
