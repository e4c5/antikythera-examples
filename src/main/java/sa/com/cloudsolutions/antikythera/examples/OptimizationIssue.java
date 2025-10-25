package sa.com.cloudsolutions.antikythera.examples;

import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;

/**
 * Represents an identified query optimization issue with detailed information
 * about the problem and recommended improvements.
 */
public record OptimizationIssue(RepositoryQuery query, String currentFirstColumn,
                                String recommendedFirstColumn, String description,
                                sa.com.cloudsolutions.antikythera.examples.OptimizationIssue.Severity severity,
                                String queryText) {

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

    /**
     * Gets a formatted string representation suitable for reporting.
     *
     * @return formatted issue report
     */
    public String getFormattedReport() {
        StringBuilder report = new StringBuilder();
        report.append(String.format("[%s] %s.%s%n", severity,
                query.getMethodDeclaration().getClassOrInterfaceDeclaration().getFullyQualifiedName(),
                query.getMethodDeclaration().getNameAsString()));
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
                query.getMethodDeclaration().getClassOrInterfaceDeclaration().getFullyQualifiedName(),
                query.getMethodDeclaration(), currentFirstColumn,
                recommendedFirstColumn, severity);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;

        OptimizationIssue that = (OptimizationIssue) obj;
        return query.equals(that.query) &&
                currentFirstColumn.equals(that.currentFirstColumn) &&
                recommendedFirstColumn.equals(that.recommendedFirstColumn) &&
                severity == that.severity;
    }

    @Override
    public int hashCode() {
        int result = query.hashCode();
        result = 31 * result + currentFirstColumn.hashCode();
        result = 31 * result + recommendedFirstColumn.hashCode();
        result = 31 * result + severity.hashCode();
        return result;
    }
}
