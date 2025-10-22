package sa.com.cloudsolutions.antikythera.examples;

/**
 * Represents an identified query optimization issue with detailed information
 * about the problem and recommended improvements.
 */
public class OptimizationIssue {
    
    /**
     * Severity levels for optimization issues based on potential performance impact.
     */
    public enum Severity {
        /**
         * High severity: Low cardinality column first with high cardinality alternatives available.
         * This represents the most significant performance impact.
         */
        HIGH,
        
        /**
         * Medium severity: Suboptimal ordering of high cardinality columns.
         * Performance improvement possible but less critical.
         */
        MEDIUM,
        
        /**
         * Low severity: Minor optimization opportunities.
         * Small potential performance gains.
         */
        LOW
    }
    
    private final String repositoryClass;
    private final String methodName;
    private final String currentFirstColumn;
    private final String recommendedFirstColumn;
    private final String description;
    private final Severity severity;
    private final String queryText;
    
    /**
     * Creates a new OptimizationIssue instance.
     * 
     * @param repositoryClass the fully qualified name of the repository class
     * @param methodName the name of the repository method with the issue
     * @param currentFirstColumn the column currently appearing first in the WHERE clause
     * @param recommendedFirstColumn the column that should appear first for optimization
     * @param description detailed description of the optimization issue
     * @param severity the severity level of the issue
     * @param queryText the full query text (optional, may be null)
     */
    public OptimizationIssue(String repositoryClass, String methodName, String currentFirstColumn,
                           String recommendedFirstColumn, String description, Severity severity,
                           String queryText) {
        this.repositoryClass = repositoryClass;
        this.methodName = methodName;
        this.currentFirstColumn = currentFirstColumn;
        this.recommendedFirstColumn = recommendedFirstColumn;
        this.description = description;
        this.severity = severity;
        this.queryText = queryText;
    }
    
    /**
     * Gets the repository class name where the issue was found.
     * 
     * @return the fully qualified repository class name
     */
    public String getRepositoryClass() {
        return repositoryClass;
    }
    
    /**
     * Gets the method name where the issue was found.
     * 
     * @return the method name
     */
    public String getMethodName() {
        return methodName;
    }
    
    /**
     * Gets the column currently appearing first in the WHERE clause.
     * 
     * @return the current first column name
     */
    public String getCurrentFirstColumn() {
        return currentFirstColumn;
    }
    
    /**
     * Gets the recommended column that should appear first for optimization.
     * 
     * @return the recommended first column name
     */
    public String getRecommendedFirstColumn() {
        return recommendedFirstColumn;
    }
    
    /**
     * Gets the detailed description of the optimization issue.
     * 
     * @return the issue description
     */
    public String getDescription() {
        return description;
    }
    
    /**
     * Gets the severity level of the optimization issue.
     * 
     * @return the severity level
     */
    public Severity getSeverity() {
        return severity;
    }
    
    /**
     * Gets the full query text where the issue was found.
     * 
     * @return the query text, or null if not available
     */
    public String getQueryText() {
        return queryText;
    }
    
    /**
     * Gets a formatted string representation suitable for reporting.
     * 
     * @return formatted issue report
     */
    public String getFormattedReport() {
        StringBuilder report = new StringBuilder();
        report.append(String.format("[%s] %s.%s%n", severity, repositoryClass, methodName));
        report.append(String.format("  Issue: %s%n", description));
        report.append(String.format("  Current first condition: %s%n", currentFirstColumn));
        report.append(String.format("  Recommended first condition: %s%n", recommendedFirstColumn));
        
        if (queryText != null && !queryText.isEmpty()) {
            report.append(String.format("  Query: %s%n", queryText));
        }
        
        return report.toString();
    }
    
    /**
     * Checks if this is a high severity issue.
     * 
     * @return true if severity is HIGH, false otherwise
     */
    public boolean isHighSeverity() {
        return severity == Severity.HIGH;
    }
    
    /**
     * Checks if this is a medium severity issue.
     * 
     * @return true if severity is MEDIUM, false otherwise
     */
    public boolean isMediumSeverity() {
        return severity == Severity.MEDIUM;
    }
    
    /**
     * Checks if this is a low severity issue.
     * 
     * @return true if severity is LOW, false otherwise
     */
    public boolean isLowSeverity() {
        return severity == Severity.LOW;
    }
    
    @Override
    public String toString() {
        return String.format("OptimizationIssue{repositoryClass='%s', methodName='%s', " +
                           "currentFirstColumn='%s', recommendedFirstColumn='%s', severity=%s}",
                           repositoryClass, methodName, currentFirstColumn, 
                           recommendedFirstColumn, severity);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        OptimizationIssue that = (OptimizationIssue) obj;
        return repositoryClass.equals(that.repositoryClass) &&
               methodName.equals(that.methodName) &&
               currentFirstColumn.equals(that.currentFirstColumn) &&
               recommendedFirstColumn.equals(that.recommendedFirstColumn) &&
               severity == that.severity;
    }
    
    @Override
    public int hashCode() {
        int result = repositoryClass.hashCode();
        result = 31 * result + methodName.hashCode();
        result = 31 * result + currentFirstColumn.hashCode();
        result = 31 * result + recommendedFirstColumn.hashCode();
        result = 31 * result + severity.hashCode();
        return result;
    }
}