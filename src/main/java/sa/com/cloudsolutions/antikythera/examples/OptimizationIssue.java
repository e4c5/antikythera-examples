package sa.com.cloudsolutions.antikythera.examples;

import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;
import java.util.List;

/**
 * Represents an identified query optimization issue with detailed information
 * about the problem and recommended improvements.
 */
public record OptimizationIssue(RepositoryQuery query, List<String> currentColumnOrder,
                                List<String> recommendedColumnOrder, String description, String aiExplanation,
                                RepositoryQuery optimizedQuery) {

    public OptimizationIssue() {
        this(null, null, null , null , null , null);
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
        report.append(String.format("%s.%s%n",
                query.getMethodDeclaration().getClassOrInterfaceDeclaration().getFullyQualifiedName(),
                query.getMethodDeclaration().getNameAsString()));
        report.append(String.format("  Issue: %s%n", description));
        
        if (currentColumnOrder != null && !currentColumnOrder.isEmpty()) {
            report.append(String.format("  Current column order: %s%n", String.join(", ", currentColumnOrder)));
        }
        
        if (recommendedColumnOrder != null && !recommendedColumnOrder.isEmpty()) {
            report.append(String.format("  Recommended column order: %s%n", String.join(", ", recommendedColumnOrder)));
        }

        if (hasAIRecommendation()) {
            report.append(String.format("  AI Explanation: %s%n", aiExplanation));
        }

        return report.toString();
    }
    @Override
    public String toString() {
        return String.format("%s.%s : currentColumnOrder=%s, recommendedColumnOrder=%s",
                query.getMethodDeclaration().getClassOrInterfaceDeclaration().getFullyQualifiedName(),
                query.getMethodDeclaration(), currentColumnOrder,
                recommendedColumnOrder);
    }
}
