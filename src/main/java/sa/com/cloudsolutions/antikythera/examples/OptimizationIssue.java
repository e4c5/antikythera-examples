package sa.com.cloudsolutions.antikythera.examples;

import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;
import java.util.List;
import java.util.Collections;

/**
 * Represents an identified query optimization issue with detailed information
 * about the problem and recommended improvements.
 */
public record OptimizationIssue(RepositoryQuery query, List<String> currentColumnOrder,
                                List<String> recommendedColumnOrder, String description,
                                Severity severity, String queryText, String aiExplanation, List<String> requiredIndexes) {

    /**
     * Convenience constructor for backward compatibility without AI fields.
     */
    public OptimizationIssue(RepositoryQuery query, List<String> currentColumnOrder,
                            List<String> recommendedColumnOrder, String description,
                            Severity severity, String queryText) {
        this(query, currentColumnOrder, recommendedColumnOrder, description, severity, queryText, "", Collections.emptyList());
    }

    /**
     * Legacy constructor for backward compatibility with single column approach.
     */
    public OptimizationIssue(RepositoryQuery query, String currentFirstColumn,
                            String recommendedFirstColumn, String description,
                            Severity severity, String queryText) {
        this(query, 
             currentFirstColumn != null && !currentFirstColumn.isEmpty() ? List.of(currentFirstColumn) : Collections.emptyList(),
             recommendedFirstColumn != null && !recommendedFirstColumn.isEmpty() ? List.of(recommendedFirstColumn) : Collections.emptyList(),
             description, severity, queryText, "", Collections.emptyList());
    }

    /**
     * Severity levels for optimization issues based on potential performance impact.
     */
    public enum Severity {
        /**
         * High severity: Low-cardinality column first with high-cardinality alternatives available.
         * This represents the most significant performance impact.
         */
        HIGH,

        /**
         * Medium severity: Suboptimal ordering of high cardinality columns.
         * Performance improvement is possible but less critical.
         */
        MEDIUM,

        /**
         * Low severity: Minor optimization opportunities.
         * Small potential performance gains.
         */
        LOW
    }

    /**
     * Checks if this issue has AI-generated recommendations.
     *
     * @return true if AI explanation is available, false otherwise
     */
    public boolean hasAIRecommendation() {
        return aiExplanation != null && !aiExplanation.trim().isEmpty();
    }

    /**
     * Gets the list of required indexes for this optimization.
     *
     * @return unmodifiable list of required indexes, empty if none
     */
    public List<String> getRequiredIndexes() {
        return requiredIndexes != null ? Collections.unmodifiableList(requiredIndexes) : Collections.emptyList();
    }

    /**
     * Backward compatibility method - gets the first column from current column order.
     *
     * @return first column in current order, or empty string if no columns
     */
    public String currentFirstColumn() {
        return (currentColumnOrder != null && !currentColumnOrder.isEmpty()) ? currentColumnOrder.get(0) : "";
    }

    /**
     * Backward compatibility method - gets the first column from recommended column order.
     *
     * @return first column in recommended order, or empty string if no columns
     */
    public String recommendedFirstColumn() {
        return (recommendedColumnOrder != null && !recommendedColumnOrder.isEmpty()) ? recommendedColumnOrder.get(0) : "";
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
        
        if (currentColumnOrder != null && !currentColumnOrder.isEmpty()) {
            report.append(String.format("  Current column order: %s%n", String.join(", ", currentColumnOrder)));
        }
        
        if (recommendedColumnOrder != null && !recommendedColumnOrder.isEmpty()) {
            report.append(String.format("  Recommended column order: %s%n", String.join(", ", recommendedColumnOrder)));
        }

        if (queryText != null && !queryText.isEmpty()) {
            report.append(String.format("  Query: %s%n", queryText));
        }

        if (hasAIRecommendation()) {
            report.append(String.format("  AI Explanation: %s%n", aiExplanation));
        }

        if (!getRequiredIndexes().isEmpty()) {
            report.append(String.format("  Required Indexes: %s%n", String.join(", ", getRequiredIndexes())));
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
        return String.format("%s.%s : currentColumnOrder=%s, recommendedColumnOrder=%s, severity=%s",
                query.getMethodDeclaration().getClassOrInterfaceDeclaration().getFullyQualifiedName(),
                query.getMethodDeclaration(), currentColumnOrder,
                recommendedColumnOrder, severity);
    }
}
