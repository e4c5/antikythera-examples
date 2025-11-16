package sa.com.cloudsolutions.antikythera.examples;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for condition extractors that provides common visitor pattern implementations
 * and helper methods for extracting table and column information from SQL expressions.
 * 
 * This eliminates code duplication between ExpressionConditionExtractor and JoinConditionExtractor.
 * 
 * @param <T> The type of condition being extracted (WhereCondition or JoinCondition)
 */
public abstract class BaseConditionExtractor<T> extends ExpressionVisitorAdapter<Void> {

    private static final Logger logger = LoggerFactory.getLogger(BaseConditionExtractor.class);

    protected int positionCounter;

    protected BaseConditionExtractor() {
        this.positionCounter = 0;
    }

    /**
     * Template method for extracting conditions. Subclasses implement the specific logic.
     */
    protected void resetState() {
        positionCounter = 0;
    }

    // Common visitor pattern implementations for logical operators

    @Override
    public <S> Void visit(AndExpression andExpr, S context) {
        andExpr.getLeftExpression().accept(this, context);
        andExpr.getRightExpression().accept(this, context);
        return null;
    }

    // Common visitor pattern implementations for comparison operators

    @Override
    public <S> Void visit(EqualsTo equalsTo, S context) {
        handleComparison(equalsTo);
        return null;
    }

    @Override
    public <S> Void visit(NotEqualsTo notEqualsTo, S context) {
        handleComparison(notEqualsTo);
        return null;
    }

    @Override
    public <S> Void visit(GreaterThan greaterThan, S context) {
        handleComparison(greaterThan);
        return null;
    }

    @Override
    public <S> Void visit(GreaterThanEquals greaterThanEquals, S context) {
        handleComparison(greaterThanEquals);
        return null;
    }

    @Override
    public <S> Void visit(MinorThan minorThan, S context) {
        handleComparison(minorThan);
        return null;
    }

    @Override
    public <S> Void visit(MinorThanEquals minorThanEquals, S context) {
        handleComparison(minorThanEquals);
        return null;
    }

    /**
     * Abstract method for handling comparison operators.
     * Subclasses implement this to extract their specific condition type.
     */
    protected abstract void handleComparison(ComparisonOperator comparison);

    // Common helper methods

    /**
     * Gets the clean column name from a Column object.
     * JSQLParser's Column.getColumnName() returns just the column name without qualifiers.
     */
    protected String getColumnName(Column column) {
        return column.getColumnName();
    }

    /**
     * Gets the table name for a column, using the table qualifier if present.
     */
    protected String getTableName(Column column) {
        Table table = column.getTable();
        if (table != null && table.getName() != null) {
            return table.getName();
        }
        return null;
    }

    protected Logger getLogger() {
        return logger;
    }
}
