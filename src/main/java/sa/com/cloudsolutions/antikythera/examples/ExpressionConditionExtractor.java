package sa.com.cloudsolutions.antikythera.examples;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.schema.Column;

import java.util.ArrayList;
import java.util.List;

/**
 * Proper expression condition extractor that properly extends JSQLParser's ExpressionVisitorAdapter.
 * This implementation follows the visitor pattern correctly by overriding visit methods for each
 * expression type, allowing JSQLParser to dispatch to the appropriate handler.
 *
 * This class improves upon OptimizationAnalysisVisitor by:
 * - Properly using the visitor pattern (not instanceof checks)
 * - Better separation of concerns
 * - More comprehensive operator support
 * - Cleaner method organization
 * - Enhanced documentation
 * 
 * Extends BaseConditionExtractor to reuse common visitor pattern implementations.
 */
public class ExpressionConditionExtractor extends BaseConditionExtractor<WhereCondition> {

    private final List<WhereCondition> conditions;
    private String defaultTableName;

    public ExpressionConditionExtractor() {
        super();
        this.conditions = new ArrayList<>();
    }

    /**
     * Extracts WHERE conditions from the given expression.
     * This is the main entry point that triggers the visitor pattern.
     */
    public List<WhereCondition> extractConditions(Expression whereExpression) {
        return extractConditions(whereExpression, null);
    }

    /**
     * Extracts WHERE conditions from the given expression with a default table name.
     * The default table name is used when a column doesn't have an explicit table qualifier.
     * 
     * @param whereExpression the WHERE clause expression to analyze
     * @param defaultTableName the table name to use for unqualified columns (can be null)
     * @return list of extracted WHERE conditions
     */
    public List<WhereCondition> extractConditions(Expression whereExpression, String defaultTableName) {
        conditions.clear();
        resetState();
        this.defaultTableName = defaultTableName;

        if (whereExpression != null) {
            whereExpression.accept(this, null);
        }

        return new ArrayList<>(conditions);
    }

    // OR expression handling (specific to WHERE conditions)

    @Override
    public <S> Void visit(OrExpression orExpr, S context) {
        // For OR expressions, both sides are equally important for optimization
        orExpr.getLeftExpression().accept(this, context);
        orExpr.getRightExpression().accept(this, context);
        return null;
    }

    // Visitor pattern implementations for special operators (WHERE-specific)

    @Override
    public <S> Void visit(Between between, S context) {
        extractConditionFromBetween(between);
        return null;
    }

    @Override
    public <S> Void visit(InExpression inExpr, S context) {
        getLogger().debug("Visiting InExpression: {}", inExpr);
        extractConditionFromIn(inExpr);
        return null;
    }

    @Override
    public <S> Void visit(IsNullExpression isNull, S context) {
        extractConditionFromIsNull(isNull);
        return null;
    }

    @Override
    public <S> Void visit(LikeExpression likeExpr, S context) {
        extractConditionFromLike(likeExpr);
        return null;
    }

    /**
     * Handles comparison operators by extracting WHERE conditions.
     */
    @Override
    protected void handleComparison(ComparisonOperator comparison) {
        if (comparison.getLeftExpression() instanceof Column column) {
            createAndAddCondition(column, comparison.getStringExpression());
        }
    }

    // Extraction methods for special operators

    private void extractConditionFromBetween(Between between) {
        if (between.getLeftExpression() instanceof Column column) {
            createAndAddCondition(column, "BETWEEN");
        }
    }

    private void extractConditionFromIn(InExpression inExpr) {
        if (inExpr.getLeftExpression() instanceof Column column) {
            createAndAddCondition(column, "IN");
        }
    }

    private void extractConditionFromIsNull(IsNullExpression isNull) {
        if (isNull.getLeftExpression() instanceof Column column) {
            String operator = isNull.isNot() ? "IS NOT NULL" : "IS NULL";
            createAndAddCondition(column, operator);
        }
    }

    private void extractConditionFromLike(LikeExpression likeExpr) {
        if (likeExpr.getLeftExpression() instanceof Column column) {
            String operator = likeExpr.isNot() ? "NOT LIKE" : "LIKE";
            createAndAddCondition(column, operator);
        }
    }

    /**
     * Central method to create and add a WhereCondition.
     * Encapsulates the common logic of extracting table/column names,
     * mapping parameters, analyzing cardinality, and adding to the list.
     */
    private void createAndAddCondition(Column column, String operator) {
        String columnName = getColumnName(column);
        String tableName = getTableName(column);
        
        // Use default table name if column doesn't have an explicit table qualifier
        if (tableName == null && defaultTableName != null) {
            tableName = defaultTableName;
        }

        WhereCondition condition = new WhereCondition(
            tableName, columnName, operator, positionCounter++
        );
        conditions.add(condition);

        getLogger().debug("Extracted {} condition on {}.{}", operator, tableName, columnName);
    }
}

