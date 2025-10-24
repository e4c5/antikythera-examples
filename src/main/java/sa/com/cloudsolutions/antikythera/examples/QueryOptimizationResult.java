package sa.com.cloudsolutions.antikythera.examples;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Aggregates the results of query optimization analysis, including all identified
 * issues, WHERE clause conditions, and summary statistics.
 */
public class QueryOptimizationResult {
    
    private final String repositoryClass;
    private final String methodName;
    private final String queryText;
    private final List<WhereCondition> whereConditions;
    private final List<OptimizationIssue> optimizationIssues;
    private final boolean isOptimized;
    
    /**
     * Creates a new QueryOptimizationResult instance.
     * 
     * @param repositoryClass the fully qualified name of the repository class
     * @param methodName the name of the repository method analyzed
     * @param queryText the full query text that was analyzed
     * @param whereConditions the list of WHERE clause conditions found in the query
     * @param optimizationIssues the list of optimization issues identified
     */
    public QueryOptimizationResult(String repositoryClass, String methodName, String queryText,
                                 List<WhereCondition> whereConditions, List<OptimizationIssue> optimizationIssues) {
        this.repositoryClass = repositoryClass;
        this.methodName = methodName;
        this.queryText = queryText;
        this.whereConditions = new ArrayList<>(whereConditions != null ? whereConditions : Collections.emptyList());
        this.optimizationIssues = new ArrayList<>(optimizationIssues != null ? optimizationIssues : Collections.emptyList());
        this.isOptimized = this.optimizationIssues.isEmpty();
    }
    
    /**
     * Gets the repository class name that was analyzed.
     * 
     * @return the fully qualified repository class name
     */
    public String getRepositoryClass() {
        return repositoryClass;
    }
    
    /**
     * Gets the method name that was analyzed.
     * 
     * @return the method name
     */
    public String getMethodName() {
        return methodName;
    }
    
    /**
     * Gets the full query text that was analyzed.
     * 
     * @return the query text
     */
    public String getQueryText() {
        return queryText;
    }
    
    /**
     * Gets the list of WHERE clause conditions found in the query.
     * 
     * @return an unmodifiable list of WHERE conditions
     */
    public List<WhereCondition> getWhereConditions() {
        return Collections.unmodifiableList(whereConditions);
    }
    
    /**
     * Gets the list of optimization issues identified in the query.
     * 
     * @return an unmodifiable list of optimization issues
     */
    public List<OptimizationIssue> getOptimizationIssues() {
        return Collections.unmodifiableList(optimizationIssues);
    }
    
    /**
     * Checks if the query is already optimized (no issues found).
     * 
     * @return true if no optimization issues were found, false otherwise
     */
    public boolean isOptimized() {
        return isOptimized;
    }
    
    /**
     * Checks if any optimization issues were found.
     * 
     * @return true if optimization issues exist, false otherwise
     */
    public boolean hasOptimizationIssues() {
        return !optimizationIssues.isEmpty();
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
     * Gets the number of optimization issues found.
     * 
     * @return the count of optimization issues
     */
    public int getOptimizationIssueCount() {
        return optimizationIssues.size();
    }
    
    /**
     * Gets optimization issues filtered by severity level.
     * 
     * @param severity the severity level to filter by
     * @return a list of issues with the specified severity
     */
    public List<OptimizationIssue> getIssuesBySeverity(OptimizationIssue.Severity severity) {
        return optimizationIssues.stream()
                .filter(issue -> issue.getSeverity() == severity)
                .collect(Collectors.toList());
    }
    
    /**
     * Gets the highest severity level among all issues.
     * 
     * @return the highest severity, or null if no issues exist
     */
    public OptimizationIssue.Severity getHighestSeverity() {
        if (optimizationIssues.isEmpty()) {
            return null;
        }
        
        boolean hasHigh = optimizationIssues.stream().anyMatch(OptimizationIssue::isHighSeverity);
        if (hasHigh) {
            return OptimizationIssue.Severity.HIGH;
        }
        
        boolean hasMedium = optimizationIssues.stream().anyMatch(OptimizationIssue::isMediumSeverity);
        if (hasMedium) {
            return OptimizationIssue.Severity.MEDIUM;
        }
        
        return OptimizationIssue.Severity.LOW;
    }
    
    /**
     * Gets WHERE conditions filtered by cardinality level.
     * 
     * @param cardinality the cardinality level to filter by
     * @return a list of conditions with the specified cardinality
     */
    public List<WhereCondition> getConditionsByCardinality(CardinalityLevel cardinality) {
        return whereConditions.stream()
                .filter(condition -> condition.cardinality() == cardinality)
                .collect(Collectors.toList());
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
    
    /**
     * Checks if the first WHERE condition uses a high cardinality column.
     * 
     * @return true if the first condition is high cardinality, false otherwise
     */
    public boolean isFirstConditionHighCardinality() {
        WhereCondition firstCondition = getFirstCondition();
        return firstCondition != null && firstCondition.isHighCardinality();
    }
    
    /**
     * Gets a formatted summary report of the analysis results.
     * 
     * @return formatted summary report
     */
    public String getSummaryReport() {
        StringBuilder report = new StringBuilder();
        report.append(String.format("Query Analysis Results for %s.%s%n", repositoryClass, methodName));
        report.append(String.format("Query: %s%n", queryText));
        report.append(String.format("WHERE Conditions: %d%n", getWhereConditionCount()));
        report.append(String.format("Optimization Issues: %d%n", getOptimizationIssueCount()));
        report.append(String.format("Is Optimized: %s%n", isOptimized ? "Yes" : "No"));
        
        if (hasOptimizationIssues()) {
            report.append(String.format("Highest Severity: %s%n", getHighestSeverity()));
        }
        
        return report.toString();
    }
    
    /**
     * Gets a detailed report including all conditions and issues.
     * 
     * @return formatted detailed report
     */
    public String getDetailedReport() {
        StringBuilder report = new StringBuilder();
        report.append(getSummaryReport());
        
        if (!whereConditions.isEmpty()) {
            report.append("\nWHERE Conditions:\n");
            for (WhereCondition condition : whereConditions) {
                report.append(String.format("  %d. %s %s (Cardinality: %s)%n", 
                            condition.position() + 1, condition.columnName(),
                            condition.operator(), condition.cardinality()));
            }
        }
        
        if (!optimizationIssues.isEmpty()) {
            report.append("\nOptimization Issues:\n");
            for (OptimizationIssue issue : optimizationIssues) {
                report.append(issue.getFormattedReport());
                report.append("\n");
            }
        }
        
        return report.toString();
    }
    
    @Override
    public String toString() {
        return String.format("QueryOptimizationResult{repositoryClass='%s', methodName='%s', " +
                           "whereConditions=%d, optimizationIssues=%d, isOptimized=%s}",
                           repositoryClass, methodName, whereConditions.size(), 
                           optimizationIssues.size(), isOptimized);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        QueryOptimizationResult that = (QueryOptimizationResult) obj;
        return repositoryClass.equals(that.repositoryClass) &&
               methodName.equals(that.methodName) &&
               queryText.equals(that.queryText);
    }
    
    @Override
    public int hashCode() {
        int result = repositoryClass.hashCode();
        result = 31 * result + methodName.hashCode();
        result = 31 * result + queryText.hashCode();
        return result;
    }
}
