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
import net.sf.jsqlparser.statement.select.SetOperationList;
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
        // Handle different select types safely by unwraping the select body
        Object selectBody = (select != null) ? select.getSelectBody() : null;
        
        if (selectBody instanceof PlainSelect plainSelect) {
            processPlainSelect(plainSelect);
        } else if (selectBody instanceof SetOperationList setOpList) {
            processSetOperationList(setOpList);
        } else if (selectBody instanceof ParenthesedSelect parenthesedSelect) {
            processFromItem(parenthesedSelect);
        } else if (select != null) {
            // Fallback: try getPlainSelect() for simple cases, but catch ClassCastException
            try {
                PlainSelect plainSelect = select.getPlainSelect();
                if (plainSelect != null) {
                    processPlainSelect(plainSelect);
                }
            } catch (ClassCastException e) {
                // This can happen with SetOperationList - already handled if identified via selectBody
            }
        }
        return null;
    }

    @Override
    public <S> Void visit(Update update, S context) {
        // Determine the default table for UPDATE WHERE clause conditions.
        String defaultTable = resolveUpdateTargetTable(update);
        if (update.getWhere() != null) {
            List<WhereCondition> conditions = extractWhereConditionsFromExpression(update.getWhere(), defaultTable);
            whereConditions.addAll(conditions);
        }
        // Process potential FROM item (subquery/CTE style updates) for completeness
        try {
            FromItem fromItem = update.getFromItem();
            if (fromItem != null) {
                processFromItem(fromItem);
            }
        } catch (NoSuchMethodError | Exception ignored) {
            // getFromItem may not exist on some versions; ignore safely
        }

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
            }  catch (Exception e) {
                // ignore unexpected reflection issues to avoid breaking parsing
            }
        }
        return null;
    }

    /**
     * Attempts to resolve the update target table name or alias across JSqlParser versions.
     */
    private String resolveUpdateTargetTable(Update update) {
        // Try the common getTable() first
        try {
            Method m = update.getClass().getMethod("getTable");
            Object res = m.invoke(update);
            if (res instanceof Table table) {
                // Prefer alias if present
                if (table.getAlias() != null) {
                    return table.getAlias().getName();
                }
                return table.getName();
            }
        }  catch (Exception ignored) {
            // ignore reflection problems
        }
        // Some versions expose multiple target tables (e.g., UPDATE table SET ... FROM ...)
        try {
            Method m = update.getClass().getMethod("getTables");
            Object res = m.invoke(update);
            if (res instanceof List<?>) {
                for (Object o : (List<?>) res) {
                    if (o instanceof Table table) {
                        if (table.getAlias() != null) {
                            return table.getAlias().getName();
                        }
                        return table.getName();
                    }
                }
            }
        }  catch (Exception ignored) {
            // ignore
        }
        // Fallback to FromItem if available
        try {
            FromItem fromItem = update.getFromItem();
            return extractTableName(fromItem);
        } catch (NoSuchMethodError | Exception ignored) {
            // not available
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
     * Handles both plain selects and set operations (UNION, UNION ALL, INTERSECT, EXCEPT).
     */
    private void processFromItem(Object fromItem) {
        if (fromItem instanceof ParenthesedSelect parenthesedSelect) {
            // Get the select safely without calling getPlainSelect() which can throw ClassCastException
            Select innerSelect = parenthesedSelect.getSelect();
            if (innerSelect instanceof PlainSelect plainSelect) {
                processPlainSelect(plainSelect);
            } else if (innerSelect instanceof SetOperationList setOpList) {
                // Handle UNION, UNION ALL, INTERSECT, EXCEPT - process each select in the set
                processSetOperationList(setOpList);
            } else if (innerSelect instanceof ParenthesedSelect nestedParenthesed) {
                // Handle nested parenthesized selects
                processFromItem(nestedParenthesed);
            }
        }
    }

    /**
     * Processes a SetOperationList (UNION, UNION ALL, INTERSECT, EXCEPT).
     * Recursively processes each select in the set operation.
     */
    private void processSetOperationList(SetOperationList setOpList) {
        if (setOpList.getSelects() != null) {
            for (Select select : setOpList.getSelects()) {
                if (select instanceof PlainSelect plainSelect) {
                    processPlainSelect(plainSelect);
                } else if (select instanceof ParenthesedSelect parenthesedSelect) {
                    processFromItem(parenthesedSelect);
                } else if (select instanceof SetOperationList nestedSetOp) {
                    // Handle nested set operations
                    processSetOperationList(nestedSetOp);
                }
            }
        }
    }
}
