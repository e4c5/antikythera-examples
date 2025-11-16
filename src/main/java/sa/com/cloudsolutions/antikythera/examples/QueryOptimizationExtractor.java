package sa.com.cloudsolutions.antikythera.examples;

import net.sf.jsqlparser.statement.Statement;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;

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
     * Result object containing both WHERE and JOIN conditions.
     */
    public static class ConditionExtractionResult {
        private final List<WhereCondition> whereConditions;
        private final List<JoinCondition> joinConditions;

        public ConditionExtractionResult(List<WhereCondition> whereConditions, List<JoinCondition> joinConditions) {
            this.whereConditions = whereConditions;
            this.joinConditions = joinConditions;
        }

        public List<WhereCondition> getWhereConditions() {
            return whereConditions;
        }

        public List<JoinCondition> getJoinConditions() {
            return joinConditions;
        }
    }
}
