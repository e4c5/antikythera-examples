package sa.com.cloudsolutions.antikythera.examples;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursively process queries with subqueries, UPDATE statements, and DELETE statements
 * using StatementVisitorAdapter pattern to extract WHERE clause conditions and JOIN ON conditions
 * for optimization analysis.
 * 
 * Updated to provide separate extraction of WHERE conditions and JOIN ON conditions.
 */
public class QueryOptimizationExtractor {

    private QueryOptimizationExtractor() {
        /* use this as a utility class */
    }

    /**
     * Extracts WHERE conditions from a SQL Statement using the existing parser infrastructure.
     * This method ONLY returns WHERE clause conditions, excluding JOIN ON conditions.
     * 
     * Enhanced to handle SELECT, UPDATE, DELETE statements, and their subqueries recursively.
     *
     * @param statement the parsed SQL statement
     * @return list of WHERE conditions (excluding JOIN ON conditions)
     */
    public static List<WhereCondition> extractWhereConditions(Statement statement) {
        List<WhereCondition> whereConditions = new ArrayList<>();
        List<JoinCondition> joinConditions = new ArrayList<>();

        // Use StatementVisitorAdapter to handle different statement types
        WhereClauseCollector collector = new WhereClauseCollector(whereConditions, joinConditions);
        statement.accept(collector, null);

        return whereConditions;
    }

    /**
     * Extracts JOIN ON conditions from a SQL Statement.
     * This method ONLY returns JOIN ON conditions, excluding WHERE clause conditions.
     * 
     * @param statement the parsed SQL statement
     * @return list of JOIN ON conditions (excluding WHERE conditions)
     */
    public static List<JoinCondition> extractJoinConditions(Statement statement) {
        List<WhereCondition> whereConditions = new ArrayList<>();
        List<JoinCondition> joinConditions = new ArrayList<>();

        // Use StatementVisitorAdapter to handle different statement types
        WhereClauseCollector collector = new WhereClauseCollector(whereConditions, joinConditions);
        statement.accept(collector, null);

        return joinConditions;
    }

    /**
     * Extracts both WHERE and JOIN conditions from a SQL Statement.
     * This is a convenience method that returns both types of conditions in a result object.
     * 
     * @param statement the parsed SQL statement
     * @return result containing both WHERE and JOIN conditions
     */
    public static ConditionExtractionResult extractAllConditions(Statement statement) {
        List<WhereCondition> whereConditions = new ArrayList<>();
        List<JoinCondition> joinConditions = new ArrayList<>();

        // Use StatementVisitorAdapter to handle different statement types
        WhereClauseCollector collector = new WhereClauseCollector(whereConditions, joinConditions);
        statement.accept(collector, null);

        return new ConditionExtractionResult(whereConditions, joinConditions);
    }

    /**
     * Extracts the original WHERE clause text from a SQL Statement.
     * Returns the WHERE clause as it appears in the original query, preserving OR/AND structure.
     *
     * @param statement the parsed SQL statement
     * @return the WHERE clause text (without "WHERE" keyword), or empty string if none
     */
    public static String extractWhereClauseText(Statement statement) {
        Expression whereExpr = null;

        if (statement instanceof Select select) {
            whereExpr = getWhereFromSelect(select);
        } else if (statement instanceof Update update) {
            whereExpr = update.getWhere();
        } else if (statement instanceof Delete delete) {
            whereExpr = delete.getWhere();
        }

        if (whereExpr != null) {
            return whereExpr.toString();
        }
        return "";
    }

    private static Expression getWhereFromSelect(Select select) {
        Object selectBody = (select != null) ? select.getSelectBody() : null;
        
        if (selectBody instanceof PlainSelect plainSelect) {
            return plainSelect.getWhere();
        } else if (selectBody instanceof net.sf.jsqlparser.statement.select.ParenthesedSelect parenthesedSelect) {
            return getWhereFromSelect(parenthesedSelect.getSelect());
        } else {
            if (selectBody instanceof net.sf.jsqlparser.statement.select.SetOperationList setOpList && setOpList.getSelects() != null) {
                for (Select innerSelect : setOpList.getSelects()) {
                    Expression where = getWhereFromSelect(innerSelect);
                    if (where != null) {
                        return where;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Result object containing both WHERE and JOIN conditions.
     */
    public record ConditionExtractionResult(List<WhereCondition> whereConditions, List<JoinCondition> joinConditions) {
    }
}
