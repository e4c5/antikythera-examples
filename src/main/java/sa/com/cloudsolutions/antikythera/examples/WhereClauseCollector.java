package sa.com.cloudsolutions.antikythera.examples;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.statement.StatementVisitorAdapter;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;

import java.util.List;

/**
 * StatementVisitorAdapter that recursively collects WHERE clauses from various SQL statements.
 * Handles SELECT, UPDATE, DELETE statements, and their subqueries.
 */
class WhereClauseCollector extends StatementVisitorAdapter<Void> {
    private final RepositoryQuery repositoryQuery;
    private final List<WhereCondition> conditions;

    public WhereClauseCollector(RepositoryQuery repositoryQuery, List<WhereCondition> conditions) {
        this.repositoryQuery = repositoryQuery;
        this.conditions = conditions;
    }

    /**
     * Extracts conditions from a WHERE expression using the parser infrastructure.
     */
    public List<WhereCondition> extractConditionsFromExpression(Expression whereExpression,
                                                                RepositoryQuery repositoryQuery) {
        OptimizationAnalysisVisitor visitor = new OptimizationAnalysisVisitor(repositoryQuery);

        return visitor.extractConditions(whereExpression);
    }

    @Override
    public <S> Void visit(Select select, S context) {
        // Process PlainSelect statements
        if (select.getPlainSelect() != null) {
            processPlainSelect(select.getPlainSelect());
        }
        return null;
    }

    @Override
    public <S> Void visit(Update update, S context) {
        extractConditions(update.getWhere(), update.getFromItem());

        // Process joins if present
        if (update.getJoins() != null) {
            for (Join join : update.getJoins()) {
                processFromItem(join.getRightItem());
            }
        }
        return null;
    }

    private void extractConditions(Expression whereClause, FromItem update1) {
        if (whereClause != null) {
            List<WhereCondition> updateConditions = extractConditionsFromExpression(whereClause, repositoryQuery);
            conditions.addAll(updateConditions);
        }

        if (update1 != null) {
            processFromItem(update1);
        }
    }

    @Override
    public <S> Void visit(Delete delete, S context) {
        Expression whereClause = delete.getWhere();
        if (whereClause != null) {
            List<WhereCondition> deleteConditions = extractConditionsFromExpression(whereClause, repositoryQuery);
            conditions.addAll(deleteConditions);
        }

        if (delete.getJoins() != null) {
            for (Join join : delete.getJoins()) {
                processFromItem(join.getRightItem());
            }
        }
        return null;
    }

    /**
     * Processes a PlainSelect statement, extracting WHERE conditions and recursively
     * processing any subqueries in FROM and JOIN clauses.
     */
    private void processPlainSelect(PlainSelect plainSelect) {
        // Process WHERE clause
        extractConditions(plainSelect.getWhere(), plainSelect.getFromItem());

        // Process JOINs for subqueries and ON conditions
        if (plainSelect.getJoins() != null) {
            for (Join join : plainSelect.getJoins()) {
                processFromItem(join.getRightItem());

                // Also check ON conditions in joins
                if (join.getOnExpressions() != null) {
                    for (Expression onExpr : join.getOnExpressions()) {
                        List<WhereCondition> joinConditions = extractConditionsFromExpression(onExpr, repositoryQuery);
                        conditions.addAll(joinConditions);
                    }
                }
            }
        }
    }

    /**
     * Processes a FromItem, recursively handling subqueries (ParenthesedSelect).
     */
    private void processFromItem(Object fromItem) {
        if (fromItem instanceof ParenthesedSelect parenthesedSelect && parenthesedSelect.getPlainSelect() != null) {
            processPlainSelect(parenthesedSelect.getPlainSelect());
        }
    }
}
