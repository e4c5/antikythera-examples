package sa.com.cloudsolutions.antikythera.examples.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Categorizes similar code patterns by functional area.
 * Implements requirement 1.2: Categorize patterns by functional area.
 */
public class PatternCategorizer {
    private static final Logger logger = LoggerFactory.getLogger(PatternCategorizer.class);
    
    // Keywords that indicate different functional areas
    private static final Map<FunctionalArea, Set<String>> AREA_KEYWORDS = Map.of(
        FunctionalArea.FILE_OPERATIONS, Set.of("file", "read", "write", "path", "files", "nio", "io", "bufferedwriter", "printwriter"),
        FunctionalArea.GIT_OPERATIONS, Set.of("git", "checkout", "pull", "reset", "branch", "repository", "processbuilder"),
        FunctionalArea.LIQUIBASE_GENERATION, Set.of("liquibase", "changeset", "index", "xml", "database", "createindex", "dropindex"),
        FunctionalArea.REPOSITORY_ANALYSIS, Set.of("repository", "jpa", "query", "annotation", "entity", "jparepo"),
        FunctionalArea.CONFIGURATION_LOADING, Set.of("config", "yaml", "properties", "settings", "load", "configuration"),
        FunctionalArea.QUERY_PROCESSING, Set.of("query", "sql", "hql", "select", "from", "where", "optimization"),
        FunctionalArea.ERROR_HANDLING, Set.of("exception", "error", "try", "catch", "throw", "handle")
    );
    
    /**
     * Categorizes similar method groups by functional area.
     */
    public Map<FunctionalArea, List<SimilarMethodGroup>> categorizeByFunctionalArea(List<SimilarMethodGroup> similarGroups) {
        logger.info("Categorizing {} similar method groups by functional area", similarGroups.size());
        
        Map<FunctionalArea, List<SimilarMethodGroup>> categorized = new EnumMap<>(FunctionalArea.class);
        
        for (SimilarMethodGroup group : similarGroups) {
            FunctionalArea area = determineFunctionalArea(group);
            if (area == null) {
                area = FunctionalArea.UNKNOWN;
            }
            group.setFunctionalArea(area);
            group.setRecommendedStrategy(determineConsolidationStrategy(area, group));
            
            categorized.computeIfAbsent(area, k -> new ArrayList<>()).add(group);
        }
        
        // Log categorization results
        for (Map.Entry<FunctionalArea, List<SimilarMethodGroup>> entry : categorized.entrySet()) {
            logger.info("Found {} groups in area: {}", entry.getValue().size(), entry.getKey());
        }
        
        return categorized;
    }
    
    /**
     * Determines the functional area for a group of similar methods.
     */
    private FunctionalArea determineFunctionalArea(SimilarMethodGroup group) {
        Map<FunctionalArea, Integer> areaScores = new EnumMap<>(FunctionalArea.class);
        
        for (MethodInfo method : group.getMethods()) {
            String methodContent = (method.getBody() + " " + method.getSignature() + " " + 
                                  String.join(" ", method.getMethodCalls())).toLowerCase();
            
            for (Map.Entry<FunctionalArea, Set<String>> entry : AREA_KEYWORDS.entrySet()) {
                FunctionalArea area = entry.getKey();
                Set<String> keywords = entry.getValue();
                
                int score = 0;
                for (String keyword : keywords) {
                    if (methodContent.contains(keyword)) {
                        score++;
                    }
                }
                
                if (score > 0) {
                    areaScores.merge(area, score, Integer::sum);
                }
            }
        }
        
        // Return the area with the highest score, or UNKNOWN if no matches
        return areaScores.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(FunctionalArea.UNKNOWN);
    }
    
    /**
     * Determines the recommended consolidation strategy based on functional area and group characteristics.
     */
    private ConsolidationStrategy determineConsolidationStrategy(FunctionalArea area, SimilarMethodGroup group) {
        // Strategy based on functional area
        switch (area) {
            case FILE_OPERATIONS:
            case GIT_OPERATIONS:
            case UTILITY_OPERATIONS:
                return ConsolidationStrategy.UTILITY_CLASS;
                
            case LIQUIBASE_GENERATION:
                return group.getMethodCount() > 3 ? 
                    ConsolidationStrategy.BUILDER_PATTERN : 
                    ConsolidationStrategy.UTILITY_CLASS;
                    
            case REPOSITORY_ANALYSIS:
            case QUERY_PROCESSING:
                return ConsolidationStrategy.DEPENDENCY_INJECTION;
                
            case CONFIGURATION_LOADING:
                return ConsolidationStrategy.CONFIGURATION_DRIVEN;
                
            case ERROR_HANDLING:
                return ConsolidationStrategy.TEMPLATE_METHOD;
                
            default:
                // For unknown areas, decide based on similarity score and method count
                if (group.getSimilarityScore() > 0.8 && group.getMethodCount() > 2) {
                    return ConsolidationStrategy.UTILITY_CLASS;
                } else if (group.getSimilarityScore() > 0.6) {
                    return ConsolidationStrategy.TEMPLATE_METHOD;
                } else {
                    return ConsolidationStrategy.NO_CONSOLIDATION;
                }
        }
    }
    
    /**
     * Gets consolidation priority based on functional area and potential impact.
     */
    public int getConsolidationPriority(SimilarMethodGroup group) {
        int basePriority = switch (group.getFunctionalArea()) {
            case FILE_OPERATIONS, GIT_OPERATIONS -> 10; // High priority - common operations
            case LIQUIBASE_GENERATION, REPOSITORY_ANALYSIS -> 8; // Medium-high priority
            case QUERY_PROCESSING, CONFIGURATION_LOADING -> 6; // Medium priority
            case ERROR_HANDLING -> 4; // Lower priority
            case UTILITY_OPERATIONS -> 3; // Low priority
            case UNKNOWN -> 1; // Lowest priority
        };
        
        // Adjust based on potential impact
        int impactBonus = Math.min(group.getEstimatedLinesSaved() / 10, 5);
        int similarityBonus = (int) (group.getSimilarityScore() * 3);
        
        return basePriority + impactBonus + similarityBonus;
    }
}