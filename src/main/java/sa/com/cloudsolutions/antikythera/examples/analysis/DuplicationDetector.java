package sa.com.cloudsolutions.antikythera.examples.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Detects similar functionality patterns across different methods.
 * Implements requirement 1.1: Identify code blocks performing similar functionality patterns.
 */
public class DuplicationDetector {
    private static final Logger logger = LoggerFactory.getLogger(DuplicationDetector.class);
    
    // Thresholds for similarity detection
    private static final double METHOD_CALL_SIMILARITY_THRESHOLD = 0.6;
    private static final double BODY_SIMILARITY_THRESHOLD = 0.4;
    private static final int MIN_GROUP_SIZE = 2;
    
    /**
     * Detects methods with similar functionality patterns.
     */
    public List<SimilarMethodGroup> detectSimilarMethods(List<MethodInfo> methods) {
        logger.info("Analyzing {} methods for similarity patterns", methods.size());
        
        Map<String, List<MethodInfo>> groupedMethods = new HashMap<>();
        
        // Group methods by similarity
        for (MethodInfo method : methods) {
            boolean foundGroup = false;
            
            for (Map.Entry<String, List<MethodInfo>> entry : groupedMethods.entrySet()) {
                List<MethodInfo> group = entry.getValue();
                if (isSimilarToGroup(method, group)) {
                    group.add(method);
                    foundGroup = true;
                    break;
                }
            }
            
            if (!foundGroup) {
                String groupKey = generateGroupKey(method);
                groupedMethods.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(method);
            }
        }
        
        // Convert to SimilarMethodGroup objects, filtering out single-method groups
        List<SimilarMethodGroup> similarGroups = groupedMethods.entrySet().stream()
            .filter(entry -> entry.getValue().size() >= MIN_GROUP_SIZE)
            .map(entry -> createSimilarMethodGroup(entry.getValue()))
            .collect(Collectors.toList());
        
        logger.info("Found {} groups of similar methods", similarGroups.size());
        return similarGroups;
    }
    
    /**
     * Checks if a method is similar to methods in an existing group.
     */
    private boolean isSimilarToGroup(MethodInfo method, List<MethodInfo> group) {
        if (group.isEmpty()) return false;
        
        // Check similarity with the first method in the group (representative)
        MethodInfo representative = group.get(0);
        
        double methodCallSimilarity = calculateMethodCallSimilarity(method, representative);
        double bodySimilarity = calculateBodySimilarity(method, representative);
        
        return methodCallSimilarity >= METHOD_CALL_SIMILARITY_THRESHOLD ||
               bodySimilarity >= BODY_SIMILARITY_THRESHOLD;
    }
    
    /**
     * Calculates similarity based on method calls made within the methods.
     */
    private double calculateMethodCallSimilarity(MethodInfo method1, MethodInfo method2) {
        Set<String> calls1 = new HashSet<>(method1.getMethodCalls());
        Set<String> calls2 = new HashSet<>(method2.getMethodCalls());
        
        if (calls1.isEmpty() && calls2.isEmpty()) return 0.0;
        
        Set<String> intersection = new HashSet<>(calls1);
        intersection.retainAll(calls2);
        
        Set<String> union = new HashSet<>(calls1);
        union.addAll(calls2);
        
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }
    
    /**
     * Calculates similarity based on method body content.
     */
    private double calculateBodySimilarity(MethodInfo method1, MethodInfo method2) {
        String body1 = normalizeMethodBody(method1.getBody());
        String body2 = normalizeMethodBody(method2.getBody());
        
        if (body1.isEmpty() && body2.isEmpty()) return 0.0;
        
        // Simple similarity based on common keywords and patterns
        Set<String> tokens1 = extractTokens(body1);
        Set<String> tokens2 = extractTokens(body2);
        
        Set<String> intersection = new HashSet<>(tokens1);
        intersection.retainAll(tokens2);
        
        Set<String> union = new HashSet<>(tokens1);
        union.addAll(tokens2);
        
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }
    
    /**
     * Normalizes method body by removing whitespace and comments.
     */
    private String normalizeMethodBody(String body) {
        return body.replaceAll("\\s+", " ")
                  .replaceAll("//.*", "")
                  .replaceAll("/\\*.*?\\*/", "")
                  .trim();
    }
    
    /**
     * Extracts meaningful tokens from method body.
     */
    private Set<String> extractTokens(String body) {
        Set<String> tokens = new HashSet<>();
        
        // Extract method calls, keywords, and identifiers
        String[] words = body.split("[\\s\\(\\)\\{\\}\\[\\];,\\.]+");
        for (String word : words) {
            if (word.length() > 2 && !word.matches("\\d+")) {
                tokens.add(word.toLowerCase());
            }
        }
        
        return tokens;
    }
    
    /**
     * Generates a group key based on method characteristics.
     */
    private String generateGroupKey(MethodInfo method) {
        // Use method calls as primary grouping criteria
        List<String> sortedCalls = method.getMethodCalls().stream()
            .sorted()
            .collect(Collectors.toList());
        
        return String.join(",", sortedCalls);
    }
    
    /**
     * Creates a SimilarMethodGroup from a list of similar methods.
     */
    private SimilarMethodGroup createSimilarMethodGroup(List<MethodInfo> methods) {
        // Calculate average similarity score for the group
        double totalSimilarity = 0.0;
        int comparisons = 0;
        
        for (int i = 0; i < methods.size(); i++) {
            for (int j = i + 1; j < methods.size(); j++) {
                double similarity = Math.max(
                    calculateMethodCallSimilarity(methods.get(i), methods.get(j)),
                    calculateBodySimilarity(methods.get(i), methods.get(j))
                );
                totalSimilarity += similarity;
                comparisons++;
            }
        }
        
        double averageSimilarity = comparisons > 0 ? totalSimilarity / comparisons : 0.0;
        
        return new SimilarMethodGroup(methods, averageSimilarity);
    }
}