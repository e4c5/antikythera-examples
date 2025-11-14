package sa.com.cloudsolutions.antikythera.examples;

import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;
import sa.com.cloudsolutions.antikythera.parser.Callable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Aggregates the results of query optimization analysis, including all identified
 * issues, WHERE clause conditions, and summary statistics.
 */
public record QueryOptimizationResult(RepositoryQuery query, List<WhereCondition> whereConditions,
                                      OptimizationIssue optimizationIssue, List<String> indexSuggestions) {
    /**
     * Creates a new QueryOptimizationResult instance.
     *
     * @param query             the full query that was analyzed
     * @param whereConditions   the list of WHERE clause conditions found in the query
     * @param optimizationIssue the issue identified
     */
    public QueryOptimizationResult(RepositoryQuery query, List<WhereCondition> whereConditions,
                                   OptimizationIssue optimizationIssue, List<String> indexSuggestions) {
        this.query = query;
        this.whereConditions = new ArrayList<>(whereConditions != null ? whereConditions : Collections.emptyList());
        this.optimizationIssue = optimizationIssue;
        this.indexSuggestions = indexSuggestions;
    }

    /**
     * Gets the method name that was analyzed.
     *
     * @return the method name
     */
    public String getMethodName() {
        return query.getMethodName();
    }

    /**
     * Gets the number of WHERE clause conditions in the query.
     *
     * @return the count of WHERE conditions
     */
    public int getWhereConditionCount() {
        return whereConditions.size();
    }

    /**
     * Gets the first WHERE condition in the query.
     *
     * @return the first WHERE condition, or null if no conditions exist
     */
    public WhereCondition getFirstCondition() {
        return whereConditions.stream()
                .filter(condition -> condition.position() == 0)
                .findFirst()
                .orElse(null);
    }

    @Override
    public String toString() {
        return String.format("repositoryClass='%s', methodName='%s', " +
                        "whereConditions=%d, optimizationIssues=%d, isOptimized=%s",
                query.getClassname(), getMethodName(), whereConditions.size());
    }

    public Callable getMethod() {
        return query().getMethodDeclaration();
    }

    /**
     * Gets the full WHERE clause text from the parsed WHERE conditions.
     * Uses the existing parsed WHERE condition data instead of re-parsing the query text.
     *
     * @return the full WHERE clause text reconstructed from parsed conditions, or empty string if not available
     */
    public String getFullWhereClause() {
        if (whereConditions.isEmpty()) {
            return "";
        }

        // Build WHERE clause from parsed conditions
        StringBuilder whereClause = new StringBuilder("WHERE ");

        // Sort conditions by position to maintain proper order
        List<WhereCondition> sortedConditions = whereConditions.stream()
                .sorted((c1, c2) -> Integer.compare(c1.position(), c2.position()))
                .toList();

        for (int i = 0; i < sortedConditions.size(); i++) {
            WhereCondition condition = sortedConditions.get(i);

            if (i > 0) {
                whereClause.append(" AND ");
            }

            whereClause.append(condition.columnName())
                    .append(" ")
                    .append(condition.operator())
                    .append(" ?");
        }

        return whereClause.toString();
    }
}
