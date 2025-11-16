package sa.com.cloudsolutions.antikythera.examples;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts JOIN ON conditions from SQL expressions.
 * This class is specifically designed to handle JOIN ON clauses, which typically
 * involve equality comparisons between columns from different tables.
 */
public class JoinConditionExtractor extends ExpressionVisitorAdapter<Void> {

    private static final Logger logger = LoggerFactory.getLogger(JoinConditionExtractor.class);

    private final List<JoinCondition> conditions;
    private int positionCounter;

    public JoinConditionExtractor() {
        this.conditions = new ArrayList<>();
        this.positionCounter = 0;
    }

    /**
     * Extracts JOIN ON conditions from the given expression.
     * This is the main entry point that triggers the visitor pattern.
     */
    public List<JoinCondition> extractConditions(Expression onExpression) {
        conditions.clear();
        positionCounter = 0;

        if (onExpression != null) {
            onExpression.accept(this, null);
        }

        return new ArrayList<>(conditions);
    }

    // Visitor pattern implementations for logical operators

    @Override
    public <S> Void visit(AndExpression andExpr, S context) {
        andExpr.getLeftExpression().accept(this, context);
        andExpr.getRightExpression().accept(this, context);
        return null;
    }

    // Visitor pattern implementations for comparison operators

    @Override
    public <S> Void visit(EqualsTo equalsTo, S context) {
        extractJoinCondition(equalsTo);
        return null;
    }

    @Override
    public <S> Void visit(NotEqualsTo notEqualsTo, S context) {
        extractJoinCondition(notEqualsTo);
        return null;
    }

    @Override
    public <S> Void visit(GreaterThan greaterThan, S context) {
        extractJoinCondition(greaterThan);
        return null;
    }

    @Override
    public <S> Void visit(GreaterThanEquals greaterThanEquals, S context) {
        extractJoinCondition(greaterThanEquals);
        return null;
    }

    @Override
    public <S> Void visit(MinorThan minorThan, S context) {
        extractJoinCondition(minorThan);
        return null;
    }

    @Override
    public <S> Void visit(MinorThanEquals minorThanEquals, S context) {
        extractJoinCondition(minorThanEquals);
        return null;
    }

    /**
     * Extracts a JOIN condition from a comparison operator.
     * JOIN conditions typically have columns on both sides of the comparison.
     */
    private void extractJoinCondition(ComparisonOperator comparison) {
        if (comparison.getLeftExpression() instanceof Column leftColumn &&
            comparison.getRightExpression() instanceof Column rightColumn) {
            
            String leftTable = getTableName(leftColumn);
            String leftCol = getColumnName(leftColumn);
            String rightTable = getTableName(rightColumn);
            String rightCol = getColumnName(rightColumn);
            String operator = comparison.getStringExpression();

            JoinCondition condition = new JoinCondition(
                leftTable, leftCol, rightTable, rightCol, operator, positionCounter++
            );
            conditions.add(condition);

            logger.debug("Extracted JOIN condition: {}.{} {} {}.{}", 
                leftTable, leftCol, operator, rightTable, rightCol);
        }
    }

    /**
     * Gets the clean column name from a Column object.
     */
    private String getColumnName(Column column) {
        return column.getColumnName();
    }

    /**
     * Gets the table name for a column, using the table qualifier if present.
     */
    private String getTableName(Column column) {
        Table table = column.getTable();
        if (table != null && table.getName() != null) {
            return table.getName();
        }
        return null;
    }
}
