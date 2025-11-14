package sa.com.cloudsolutions.antikythera.examples;

import net.sf.jsqlparser.statement.Statement;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursively process queries with subqueries, UPDATE statements, and DELETE statements
 * using StatementVisitorAdapter pattern to extract WHERE clause conditions for optimization analysis.
 */
public class QueryOptimizationExtractor {

    private QueryOptimizationExtractor() {
        /* use this as a utility class */
    }
    /**
     * Extracts WHERE conditions from a RepositoryQuery using the existing parser infrastructure.
     * This is the main method that replaces QueryAnalysisEngine.extractWhereConditions().
     *
     * Enhanced to handle SELECT, UPDATE, DELETE statements, and their subqueries recursively.
     */
    public static List<WhereCondition> extractWhereConditions(RepositoryQuery repositoryQuery) {
        Statement statement = repositoryQuery.getStatement();
        List<WhereCondition> allConditions = new ArrayList<>();

        // Use StatementVisitorAdapter to handle different statement types
        WhereClauseCollector collector = new WhereClauseCollector(repositoryQuery, allConditions);
        statement.accept(collector);

        return allConditions;
    }


}
