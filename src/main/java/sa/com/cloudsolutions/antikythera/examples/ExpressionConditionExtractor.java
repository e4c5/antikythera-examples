package sa.com.cloudsolutions.antikythera.examples;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.generator.QueryMethodParameter;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;

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
 */
public class ExpressionConditionExtractor extends ExpressionVisitorAdapter<Void> {

    private static final Logger logger = LoggerFactory.getLogger(ExpressionConditionExtractor.class);

    private final List<WhereCondition> conditions;
    private final RepositoryQuery repositoryQuery;
    private final String primaryTable;
    private int positionCounter;

    public ExpressionConditionExtractor(RepositoryQuery repositoryQuery) {
        this.repositoryQuery = repositoryQuery;
        this.conditions = new ArrayList<>();
        this.primaryTable = repositoryQuery.getPrimaryTable();
        this.positionCounter = 0;
    }

    /**
     * Extracts WHERE conditions from the given expression.
     * This is the main entry point that triggers the visitor pattern.
     */
    public List<WhereCondition> extractConditions(Expression whereExpression) {
        conditions.clear();
        positionCounter = 0;

        if (whereExpression != null) {
            logger.debug("Extracting conditions from expression type: {}", whereExpression.getClass().getSimpleName());
            whereExpression.accept(this, null);
        }

        logger.debug("Extracted {} WHERE conditions", conditions.size());
        return new ArrayList<>(conditions);
    }

    // Visitor pattern implementations for logical operators

    @Override
    public <S> Void visit(AndExpression andExpr, S context) {
        andExpr.getLeftExpression().accept(this, context);
        andExpr.getRightExpression().accept(this, context);
        return null;
    }

    @Override
    public <S> Void visit(OrExpression orExpr, S context) {
        // For OR expressions, both sides are equally important for optimization
        orExpr.getLeftExpression().accept(this, context);
        orExpr.getRightExpression().accept(this, context);
        return null;
    }

    // Visitor pattern implementations for comparison operators

    @Override
    public <S> Void visit(EqualsTo equalsTo, S context) {
        extractConditionFromComparison(equalsTo);
        return null;
    }

    @Override
    public <S> Void visit(NotEqualsTo notEqualsTo, S context) {
        extractConditionFromComparison(notEqualsTo);
        return null;
    }

    @Override
    public <S> Void visit(GreaterThan greaterThan, S context) {
        extractConditionFromComparison(greaterThan);
        return null;
    }

    @Override
    public <S> Void visit(GreaterThanEquals greaterThanEquals, S context) {
        extractConditionFromComparison(greaterThanEquals);
        return null;
    }

    @Override
    public <S> Void visit(MinorThan minorThan, S context) {
        extractConditionFromComparison(minorThan);
        return null;
    }

    @Override
    public <S> Void visit(MinorThanEquals minorThanEquals, S context) {
        extractConditionFromComparison(minorThanEquals);
        return null;
    }

    // Visitor pattern implementations for special operators

    @Override
    public <S> Void visit(Between between, S context) {
        extractConditionFromBetween(between);
        return null;
    }

    @Override
    public <S> Void visit(InExpression inExpr, S context) {
        logger.debug("Visiting InExpression: {}", inExpr);
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

    // Extraction methods - simplified by delegating common logic to createAndAddCondition

    private void extractConditionFromComparison(ComparisonOperator comparison) {
        if (comparison.getLeftExpression() instanceof Column column) {
            createAndAddCondition(column, comparison.getStringExpression(), comparison.getRightExpression());
        }
    }

    private void extractConditionFromBetween(Between between) {
        if (between.getLeftExpression() instanceof Column column) {
            createAndAddCondition(column, "BETWEEN", between.getBetweenExpressionStart());
        }
    }

    private void extractConditionFromIn(InExpression inExpr) {
        if (inExpr.getLeftExpression() instanceof Column column) {
            createAndAddCondition(column, "IN", inExpr.getRightExpression());
        }
    }

    private void extractConditionFromIsNull(IsNullExpression isNull) {
        if (isNull.getLeftExpression() instanceof Column column) {
            String operator = isNull.isNot() ? "IS NOT NULL" : "IS NULL";
            createAndAddCondition(column, operator, null);
        }
    }

    private void extractConditionFromLike(LikeExpression likeExpr) {
        if (likeExpr.getLeftExpression() instanceof Column column) {
            String operator = likeExpr.isNot() ? "NOT LIKE" : "LIKE";
            createAndAddCondition(column, operator, likeExpr.getRightExpression());
        }
    }

    /**
     * Central method to create and add a WhereCondition.
     * Encapsulates the common logic of extracting table/column names,
     * mapping parameters, analyzing cardinality, and adding to the list.
     */
    private void createAndAddCondition(Column column, String operator, Expression rightExpression) {
        String columnName = getColumnName(column);
        String tableName = getTableName(column);

        // Leverage RepositoryQuery's existing parameter mapping
        QueryMethodParameter parameter = mapParameter(columnName, rightExpression);

        // Use CardinalityAnalyzer for cardinality determination
        CardinalityLevel cardinality = CardinalityAnalyzer.analyzeColumnCardinality(tableName, columnName);

        WhereCondition condition = new WhereCondition(
            tableName, columnName, operator, cardinality, positionCounter++, parameter
        );
        conditions.add(condition);

        logger.debug("Extracted {} condition on {}.{}", operator, tableName, columnName);
    }

    // Core helper methods leveraging JSQLParser and Antikythera infrastructure

    /**
     * Gets the clean column name from a Column object.
     * JSQLParser's Column.getColumnName() returns just the column name without qualifiers.
     */
    private String getColumnName(Column column) {
        return column.getColumnName();
    }

    /**
     * Gets the table name for a column, using the table qualifier if present,
     * otherwise falling back to the primary table from RepositoryQuery.
     */
    private String getTableName(Column column) {
        Table table = column.getTable();
        if (table != null && table.getName() != null) {
            // Column has table qualifier - could be alias or actual table
            // For now, use as-is; EntityMappingResolver can help resolve if needed
            return table.getName();
        }
        return primaryTable;
    }

    /**
     * Maps a column to its corresponding method parameter.
     * Looks for parameters that already have the column name mapped,
     * without modifying the RepositoryQuery state.
     */
    private QueryMethodParameter mapParameter(String columnName, Expression rightExpression) {
        if (rightExpression == null) {
            return null;
        }

        List<QueryMethodParameter> params = repositoryQuery.getMethodParameters();
        if (params == null || params.isEmpty()) {
            return null;
        }

        // Find parameter that already has this column name mapped
        return params.stream()
            .filter(p -> columnName.equals(p.getColumnName()))
            .findFirst()
            .orElse(null);
    }
}

