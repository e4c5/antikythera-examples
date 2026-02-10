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
public class QueryAnalysisResult {

    private final RepositoryQuery query;
    private final List<WhereCondition> whereConditions;
    private List<JoinCondition> joinConditions;
    private OptimizationIssue optimizationIssue;
    private List<String> indexSuggestions;
    private String originalWhereClauseText;

    /**
     * Creates a new QueryOptimizationResult instance.
     *
     * @param query             the full query that was analyzed
     * @param whereConditions   the list of WHERE clause conditions found in the query
     */
    public QueryAnalysisResult(RepositoryQuery query, List<WhereCondition> whereConditions) {
        this.query = query;
        this.whereConditions = new ArrayList<>(whereConditions != null ? whereConditions : Collections.emptyList());
        this.joinConditions = Collections.emptyList();
        this.indexSuggestions = List.of();
        this.originalWhereClauseText = "";
    }

    // Accessors to preserve record-like API compatibility
    public RepositoryQuery getQuery() { return query; }
    public List<WhereCondition> getWhereConditions() { return whereConditions; }
    public OptimizationIssue getOptimizationIssue() { return optimizationIssue; }
    public List<String> getIndexSuggestions() { return indexSuggestions; }

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
                        "whereConditions=%d",
                query.getClassname(), getMethodName(),
                whereConditions == null ? 0 : whereConditions.size());
    }

    public Callable getMethod() {
        return query.getMethodDeclaration();
    }

    /**
     * Gets the full WHERE clause text.
     * Returns the original WHERE clause text if available (preserving OR/AND structure),
     * otherwise falls back to reconstructing from parsed conditions.
     *
     * @return the full WHERE clause text, or empty string if not available
     */
    public String getFullWhereClause() {
        // Prefer original WHERE clause text if available (preserves OR/AND structure)
        if (originalWhereClauseText != null && !originalWhereClauseText.isEmpty()) {
            return "WHERE " + originalWhereClauseText;
        }

        // Fallback: reconstruct from parsed conditions (loses OR structure)
        if (whereConditions.isEmpty()) {
            return "";
        }

        StringBuilder whereClause = new StringBuilder("WHERE ");
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

    /**
     * Sets the original WHERE clause text extracted from the parsed query.
     * This preserves the original OR/AND structure for accurate display.
     *
     * @param whereClauseText the original WHERE clause text (without "WHERE" keyword)
     */
    public void setOriginalWhereClauseText(String whereClauseText) {
        this.originalWhereClauseText = whereClauseText;
    }

    public void setIndexSuggestions(List<String> indexSuggestions) {
        this.indexSuggestions = indexSuggestions;
    }

    public void setOptimizationIssue(OptimizationIssue optimizationIssue) {
        this.optimizationIssue = optimizationIssue;
    }

    public List<JoinCondition> getJoinConditions() {
        return joinConditions != null ? joinConditions : Collections.emptyList();
    }

    public void setJoinConditions(List<JoinCondition> joinConditions) {
        this.joinConditions = joinConditions != null ? new ArrayList<>(joinConditions) : Collections.emptyList();
    }
}
