package sa.com.cloudsolutions.antikythera.examples;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.StatementVisitorAdapter;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;

import java.lang.reflect.Method;
import java.util.List;

/**
 * StatementVisitorAdapter that recursively collects WHERE clauses and JOIN ON conditions
 * from various SQL statements. Handles SELECT, UPDATE, DELETE statements, and their subqueries.
 * 
 * Updated to separate WHERE conditions from JOIN ON conditions for more precise query analysis.
 */
class WhereClauseCollector extends StatementVisitorAdapter<Void> {
    private final List<WhereCondition> whereConditions;
    private final List<JoinCondition> joinConditions;

    public WhereClauseCollector(List<WhereCondition> whereConditions, List<JoinCondition> joinConditions) {
        this.whereConditions = whereConditions;
        this.joinConditions = joinConditions;
    }

    /**
     * Extracts WHERE conditions from a WHERE expression using the improved extractor.
     * Now uses ExpressionConditionExtractor which provides better structure than OptimizationAnalysisVisitor.
     */
    public List<WhereCondition> extractWhereConditionsFromExpression(Expression whereExpression) {
        return extractWhereConditionsFromExpression(whereExpression, null);
    }

    /**
     * Extracts WHERE conditions from a WHERE expression with a default table name.
     * The default table name is used when columns don't have explicit table qualifiers.
     */
    public List<WhereCondition> extractWhereConditionsFromExpression(Expression whereExpression, String defaultTableName) {
        ExpressionConditionExtractor extractor = new ExpressionConditionExtractor();
        return extractor.extractConditions(whereExpression, defaultTableName);
    }

    /**
     * Extracts JOIN ON conditions from an ON expression.
     * Uses JoinConditionExtractor to properly identify column-to-column comparisons.
     */
    public List<JoinCondition> extractJoinConditionsFromExpression(Expression onExpression) {
        JoinConditionExtractor extractor = new JoinConditionExtractor();
        return extractor.extractConditions(onExpression);
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
        extractWhereConditions(update.getWhere(), update.getFromItem());

        // Process joins if present via known API
        if (update.getJoins() != null) {
            for (Join join : update.getJoins()) {
                processJoin(join);
            }
        }
        // Some JSqlParser versions expose UPDATE joins via different getters. Try them reflectively.
        for (String methodName : new String[]{"getStartJoins", "getFromItemJoins"}) {
            try {
                Method m = update.getClass().getMethod(methodName);
                Object res = m.invoke(update);
                if (res instanceof List<?>) {
                    for (Object o : (List<?>) res) {
                        if (o instanceof Join j) {
                            processJoin(j);
                        }
                    }
                }
            } catch (NoSuchMethodException ignored) {
                // method not present in this version
            } catch (Exception e) {
                // ignore unexpected reflection issues to avoid breaking parsing
            }
        }
        return null;
    }

    private void extractWhereConditions(Expression whereClause, FromItem fromItem) {
        if (whereClause != null) {
            String tableName = extractTableName(fromItem);
            List<WhereCondition> conditions = extractWhereConditionsFromExpression(whereClause, tableName);
            whereConditions.addAll(conditions);
        }

        if (fromItem != null) {
            processFromItem(fromItem);
        }
    }

    /**
     * Extracts the table name or alias from a FromItem.
     * Returns the alias if present (e.g., "o" from "orders o"), otherwise the table name.
     */
    private String extractTableName(FromItem fromItem) {
        if (fromItem == null) {
            return null;
        }
        
        if (fromItem instanceof Table table) {
            // Prefer alias if present, otherwise use table name
            if (table.getAlias() != null) {
                return table.getAlias().getName();
            }
            return table.getName();
        }
        
        // For other FromItem types (subqueries, etc.), we can't extract a simple table name
        return null;
    }

    @Override
    public <S> Void visit(Delete delete, S context) {
        String tableName = extractTableName(delete.getTable());
        if (delete.getWhere() != null) {
            List<WhereCondition> conditions = extractWhereConditionsFromExpression(delete.getWhere(), tableName);
            whereConditions.addAll(conditions);
        }

        if (delete.getJoins() != null) {
            for (Join join : delete.getJoins()) {
                processJoin(join);
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
        extractWhereConditions(plainSelect.getWhere(), plainSelect.getFromItem());

        // Process JOINs - both subqueries and ON conditions
        if (plainSelect.getJoins() != null) {
            for (Join join : plainSelect.getJoins()) {
                processJoin(join);
            }
        }
    }

    /**
     * Processes a JOIN, extracting ON conditions separately from WHERE conditions.
     */
    private void processJoin(Join join) {
        // Process any subqueries in the join's right item
        processFromItem(join.getRightItem());

        // Extract JOIN ON conditions separately. JSqlParser may expose a single onExpression
        // or a list of onExpressions depending on statement type/version.
        if (join.getOnExpressions() != null) {
            for (Expression onExpr : join.getOnExpressions()) {
                List<JoinCondition> conditions = extractJoinConditionsFromExpression(onExpr);
                joinConditions.addAll(conditions);
            }
        } else if (join.getOnExpression() != null) { // handle single ON expression (e.g., UPDATE ... JOIN ... ON ...)
            List<JoinCondition> conditions = extractJoinConditionsFromExpression(join.getOnExpression());
            joinConditions.addAll(conditions);
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
