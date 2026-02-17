package sa.com.cloudsolutions.antikythera.examples;

import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents an identified query optimization issue with detailed information
 * about the problem and recommended improvements.
 */
public record OptimizationIssue(RepositoryQuery query, List<String> currentColumnOrder,
                                List<String> recommendedColumnOrder, String description, String aiExplanation,
                                RepositoryQuery optimizedQuery) {

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
     * Builds a position mapping (newIndex to oldIndex) for argument reordering.
     *
     * Uses the column order lists as the single source of truth. These come
     * directly from WHERE clause analysis and represent exactly which parameter
     * positions moved where.
     *
     * Extra parameters beyond the column count (e.g., Pageable) are
     * identity-mapped.
     *
     * @param argCount the number of arguments at the call site
     * @return the mapping, or null if column orders are unavailable
     */
    public Map<Integer, Integer> buildPositionMapping(int argCount) {
        if (currentColumnOrder == null || recommendedColumnOrder == null
                || currentColumnOrder.size() != recommendedColumnOrder.size()
                || currentColumnOrder.size() > argCount) {
            return null;
        }

        Map<Integer, Integer> map = new HashMap<>();
        for (int i = 0; i < currentColumnOrder.size(); i++) {
            int newIdx = recommendedColumnOrder.indexOf(currentColumnOrder.get(i));
            if (newIdx < 0) {
                return null;
            }
            map.put(newIdx, i);
        }

        // Identity-map extra parameters (e.g., Pageable) beyond column count
        for (int i = currentColumnOrder.size(); i < argCount; i++) {
            map.put(i, i);
        }
        return map;
    }

    @Override
    public String toString() {
        return String.format("%s.%s : currentColumnOrder=%s, recommendedColumnOrder=%s",
                query.getMethodDeclaration().getClassOrInterfaceDeclaration().getFullyQualifiedName(),
                query.getMethodDeclaration(), currentColumnOrder,
                recommendedColumnOrder);
    }
}
