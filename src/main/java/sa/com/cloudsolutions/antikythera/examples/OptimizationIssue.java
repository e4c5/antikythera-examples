package sa.com.cloudsolutions.antikythera.examples;

import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;
import java.util.Collections;
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
     * For each parameter name in the original method declaration, finds the
     * position it occupies in the refactored declaration. If the argument counts
     * differ or any original name cannot be found in the refactored output,
     * the AI has made a mistake and we return null to skip this refactoring.
     *
     * Extra arguments beyond the parameter count (e.g., Pageable) are
     * identity-mapped.
     *
     * @param argCount the number of arguments at the call site
     * @return the mapping, or an empty map if the refactored output is inconsistent
     */
    public Map<Integer, Integer> buildPositionMapping(int argCount) {
        if (query == null || optimizedQuery == null
                || query.getMethodDeclaration() == null
                || optimizedQuery.getMethodDeclaration() == null) {
            return Collections.emptyMap();
        }

        com.github.javaparser.ast.body.MethodDeclaration oldMd =
                query.getMethodDeclaration().asMethodDeclaration();
        com.github.javaparser.ast.body.MethodDeclaration newMd =
                optimizedQuery.getMethodDeclaration().asMethodDeclaration();

        if (oldMd == null || newMd == null) {
            return Collections.emptyMap();
        }

        int paramCount = oldMd.getParameters().size();
        if (paramCount != newMd.getParameters().size() || paramCount > argCount) {
            return Collections.emptyMap();
        }

        Map<Integer, Integer> map = new HashMap<>();
        for (int newIdx = 0; newIdx < paramCount; newIdx++) {
            String newParamName = newMd.getParameter(newIdx).getNameAsString();
            int oldIdx = -1;
            for (int j = 0; j < paramCount; j++) {
                if (oldMd.getParameter(j).getNameAsString().equals(newParamName)) {
                    oldIdx = j;
                    break;
                }
            }
            if (oldIdx < 0) {
                return Collections.emptyMap();
            }
            map.put(newIdx, oldIdx);
        }

        // Identity-map extra arguments (e.g., Pageable) beyond parameter count
        for (int i = paramCount; i < argCount; i++) {
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
