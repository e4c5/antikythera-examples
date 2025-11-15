package sa.com.cloudsolutions.antikythera.examples;

import sa.com.cloudsolutions.antikythera.generator.QueryMethodParameter;
import java.util.Objects;

/**
 * Represents a parsed WHERE clause condition from a repository query.
 * Contains information about the table, column, operator, cardinality, and position
 * within the WHERE clause for optimization analysis.
 *
 * Enhanced to support JOINs by tracking which table each column belongs to.
 */
public final class WhereCondition {
    private String tableName;
    private final String columnName;
    private final String operator;
    private CardinalityLevel cardinality;
    private final int position;
    private final QueryMethodParameter parameter;

    public WhereCondition(String tableName,
                          String columnName,
                          String operator,
                          int position,
                          QueryMethodParameter parameter) {
        this.tableName = tableName;
        this.columnName = columnName;
        this.operator = operator;
        this.position = position;
        this.parameter = parameter;
    }

    public String getTableName() {
        return tableName;
    }

    public String getColumnName() {
        return columnName;
    }

    public String getOperator() {
        return operator;
    }

    public CardinalityLevel getCardinality() {
        return cardinality;
    }

    public int getPosition() {
        return position;
    }

    public QueryMethodParameter getParameter() {
        return parameter;
    }

    // Backward-compatible record-style accessors for existing code/tests
    public String tableName() { return getTableName(); }
    public String columnName() { return getColumnName(); }
    public String operator() { return getOperator(); }
    public CardinalityLevel cardinality() { return getCardinality(); }
    public int position() { return getPosition(); }
    public QueryMethodParameter parameter() { return getParameter(); }

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
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WhereCondition that = (WhereCondition) o;
        return position == that.position &&
                Objects.equals(tableName, that.tableName) &&
                Objects.equals(columnName, that.columnName) &&
                Objects.equals(operator, that.operator) &&
                cardinality == that.cardinality &&
                Objects.equals(parameter, that.parameter);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tableName, columnName, operator, cardinality, position, parameter);
    }

    @Override
    public String toString() {
        return "WhereCondition{" +
                "tableName='" + tableName + '\'' +
                ", columnName='" + columnName + '\'' +
                ", operator='" + operator + '\'' +
                ", cardinality=" + cardinality +
                ", position=" + position +
                ", parameter=" + parameter +
                '}';
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public void setCardinality(CardinalityLevel cardinality) {
        this.cardinality = cardinality;
    }
}
