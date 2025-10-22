package sa.com.cloudsolutions.antikythera.examples;

import sa.com.cloudsolutions.antikythera.generator.QueryMethodParameter;

/**
 * Represents a parsed WHERE clause condition from a repository query.
 * Contains information about the column, operator, cardinality, and position
 * within the WHERE clause for optimization analysis.
 */
public class WhereCondition {
    
    private final String columnName;
    private final String operator;
    private final CardinalityLevel cardinality;
    private final int position;
    private final QueryMethodParameter parameter;
    
    /**
     * Creates a new WhereCondition instance.
     * 
     * @param columnName the name of the column in the condition
     * @param operator the comparison operator (=, >, <, IN, etc.)
     * @param cardinality the cardinality level of the column
     * @param position the position of this condition in the WHERE clause (0-based)
     * @param parameter the query method parameter associated with this condition (may be null)
     */
    public WhereCondition(String columnName, String operator, CardinalityLevel cardinality, 
                         int position, QueryMethodParameter parameter) {
        this.columnName = columnName;
        this.operator = operator;
        this.cardinality = cardinality;
        this.position = position;
        this.parameter = parameter;
    }
    
    /**
     * Gets the column name for this condition.
     * 
     * @return the column name
     */
    public String getColumnName() {
        return columnName;
    }
    
    /**
     * Gets the comparison operator for this condition.
     * 
     * @return the operator (=, >, <, IN, BETWEEN, etc.)
     */
    public String getOperator() {
        return operator;
    }
    
    /**
     * Gets the cardinality level of the column in this condition.
     * 
     * @return the cardinality level
     */
    public CardinalityLevel getCardinality() {
        return cardinality;
    }
    
    /**
     * Gets the position of this condition in the WHERE clause.
     * 
     * @return the 0-based position
     */
    public int getPosition() {
        return position;
    }
    
    /**
     * Gets the query method parameter associated with this condition.
     * 
     * @return the parameter, or null if not available
     */
    public QueryMethodParameter getParameter() {
        return parameter;
    }
    
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