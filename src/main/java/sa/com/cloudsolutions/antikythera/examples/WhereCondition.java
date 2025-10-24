package sa.com.cloudsolutions.antikythera.examples;

import sa.com.cloudsolutions.antikythera.generator.QueryMethodParameter;

/**
 * Represents a parsed WHERE clause condition from a repository query.
 * Contains information about the column, operator, cardinality, and position
 * within the WHERE clause for optimization analysis.
 */
public record WhereCondition(String columnName, String operator, CardinalityLevel cardinality, int position,
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

    @Override
    public String toString() {
        return String.format("WhereCondition{columnName='%s', operator='%s', cardinality=%s, position=%d}",
                columnName, operator, cardinality, position);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;

        WhereCondition that = (WhereCondition) obj;
        return position == that.position &&
                columnName.equals(that.columnName) &&
                operator.equals(that.operator) &&
                cardinality == that.cardinality;
    }

    @Override
    public int hashCode() {
        int result = columnName.hashCode();
        result = 31 * result + operator.hashCode();
        result = 31 * result + cardinality.hashCode();
        result = 31 * result + position;
        return result;
    }
}
