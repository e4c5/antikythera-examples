package sa.com.cloudsolutions.antikythera.examples.analysis;

import java.util.List;

/**
 * Represents a group of methods with similar functionality patterns.
 */
public class SimilarMethodGroup {
    private final List<MethodInfo> methods;
    private final double similarityScore;
    private FunctionalArea functionalArea;
    private ConsolidationStrategy recommendedStrategy;
    private int estimatedLinesSaved;
    
    public SimilarMethodGroup(List<MethodInfo> methods, double similarityScore) {
        this.methods = methods;
        this.similarityScore = similarityScore;
        this.estimatedLinesSaved = calculateEstimatedLinesSaved();
    }
    
    public List<MethodInfo> getMethods() {
        return methods;
    }
    
    public double getSimilarityScore() {
        return similarityScore;
    }
    
    public FunctionalArea getFunctionalArea() {
        return functionalArea;
    }
    
    public void setFunctionalArea(FunctionalArea functionalArea) {
        this.functionalArea = functionalArea;
    }
    
    public ConsolidationStrategy getRecommendedStrategy() {
        return recommendedStrategy;
    }
    
    public void setRecommendedStrategy(ConsolidationStrategy recommendedStrategy) {
        this.recommendedStrategy = recommendedStrategy;
    }
    
    public int getEstimatedLinesSaved() {
        return estimatedLinesSaved;
    }
    
    public int getMethodCount() {
        return methods.size();
    }
    
    /**
     * Calculates estimated lines of code that could be saved by consolidation.
     */
    private int calculateEstimatedLinesSaved() {
        if (methods.size() < 2) return 0;
        
        // Estimate based on average method body length
        int totalLines = methods.stream()
            .mapToInt(method -> method.getBody().split("\n").length)
            .sum();
        
        int averageLines = totalLines / methods.size();
        
        // Assume we can save (n-1) * average_lines by consolidating n methods
        return (methods.size() - 1) * averageLines;
    }
    
    /**
     * Gets a representative method from the group (typically the first one).
     */
    public MethodInfo getRepresentativeMethod() {
        return methods.isEmpty() ? null : methods.get(0);
    }
    
    @Override
    public String toString() {
        return String.format("SimilarMethodGroup{methods=%d, similarity=%.2f, area=%s, linesSaved=%d}", 
                           methods.size(), similarityScore, functionalArea, estimatedLinesSaved);
    }
}