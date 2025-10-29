package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;
import sa.com.cloudsolutions.antikythera.parser.Callable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Aggregates the results of query optimization analysis, including all identified
 * issues, WHERE clause conditions, and summary statistics.
 */
public class QueryOptimizationResult {
    private final RepositoryQuery query;
    private final List<WhereCondition> whereConditions;
    private final List<OptimizationIssue> optimizationIssues;
    private final boolean isAlreadyOptimized;
    
    /**
     * Creates a new QueryOptimizationResult instance.
     * 
     * @param query the full query that was analyzed
     * @param whereConditions the list of WHERE clause conditions found in the query
     * @param optimizationIssues the list of optimization issues identified
     */
    public QueryOptimizationResult(RepositoryQuery query,
                                   List<WhereCondition> whereConditions, List<OptimizationIssue> optimizationIssues) {
        this.query = query;
        this.whereConditions = new ArrayList<>(whereConditions != null ? whereConditions : Collections.emptyList());
        this.optimizationIssues = new ArrayList<>(optimizationIssues != null ? optimizationIssues : Collections.emptyList());
        this.isAlreadyOptimized = this.optimizationIssues.isEmpty();
    }
    
    /**
     * Gets the repository class name that was analyzed.
     * 
     * @return the fully qualified repository class name
     */
    public String getRepositoryClass() {
        // First try to get from stored repository class name (avoids AST traversal issues)
        if (query.getRepositoryClassName() != null) {
            return query.getRepositoryClassName();
        }
        
        // Fallback to AST traversal (original approach)
        return query.getMethodDeclaration().getCallableDeclaration()
                .findAncestor(ClassOrInterfaceDeclaration.class).orElseThrow().getFullyQualifiedName().orElseThrow();
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
     * Gets the full query text that was analyzed.
     * 
     * @return the query text
     */
    public RepositoryQuery getQuery() {
        return query;
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
    public boolean isAlreadyOptimized() {
        return isAlreadyOptimized;
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
                .toList();
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
     * Checks if the first WHERE condition uses a high-cardinality column.
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
        report.append(String.format("Query Analysis Results for %s.%s%n", getRepositoryClass(), getMethodName()));
        
        // Include full WHERE clause information
        String fullQuery = query.getOriginalQuery();
        report.append(String.format("Full Query: %s%n", fullQuery));
        report.append(String.format("WHERE Conditions: %d%n", getWhereConditionCount()));
        report.append(String.format("Optimization Issues: %d%n", getOptimizationIssueCount()));
        report.append(String.format("Is Optimized: %s%n", isAlreadyOptimized ? "Yes" : "No"));
        
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
                report.append(String.format("  %d. %s %s ? (Cardinality: %s)%n", 
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
        return String.format("repositoryClass='%s', methodName='%s', " +
                           "whereConditions=%d, optimizationIssues=%d, isOptimized=%s",
                           getRepositoryClass(), getMethodName(), whereConditions.size(),
                           optimizationIssues.size(), isAlreadyOptimized);
    }

    public Callable getMethod() {
        return getQuery().getMethodDeclaration();
    }
    
    /**
     * Clears all optimization issues from this result.
     * Used when replacing programmatic analysis with AI recommendations.
     */
    public void clearOptimizationIssues() {
        optimizationIssues.clear();
    }
    
    /**
     * Adds an optimization issue to this result.
     * Used when integrating AI recommendations.
     * 
     * @param issue the optimization issue to add
     */
    public void addOptimizationIssue(OptimizationIssue issue) {
        optimizationIssues.add(issue);
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
