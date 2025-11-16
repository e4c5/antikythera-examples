package sa.com.cloudsolutions.antikythera.examples;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.relational.ComparisonOperator;
import net.sf.jsqlparser.schema.Column;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts JOIN ON conditions from SQL expressions.
 * This class is specifically designed to handle JOIN ON clauses, which typically
 * involve equality comparisons between columns from different tables.
 * 
 * Extends BaseConditionExtractor to reuse common visitor pattern implementations.
 */
public class JoinConditionExtractor extends BaseConditionExtractor<JoinCondition> {

    private final List<JoinCondition> conditions;

    public JoinConditionExtractor() {
        super();
        this.conditions = new ArrayList<>();
    }

    /**
     * Extracts JOIN ON conditions from the given expression.
     * This is the main entry point that triggers the visitor pattern.
     */
    public List<JoinCondition> extractConditions(Expression onExpression) {
        conditions.clear();
        resetState();

        if (onExpression != null) {
            onExpression.accept(this, null);
        }

        return new ArrayList<>(conditions);
    }

    /**
     * Handles comparison operators by extracting JOIN conditions.
     * JOIN conditions typically have columns on both sides of the comparison.
     */
    @Override
    protected void handleComparison(ComparisonOperator comparison) {
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

            getLogger().debug("Extracted JOIN condition: {}.{} {} {}.{}", 
                leftTable, leftCol, operator, rightTable, rightCol);
        }
    }
}
