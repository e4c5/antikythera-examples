package sa.com.cloudsolutions.antikythera.examples.analysis;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Represents the results of code analysis for duplication patterns.
 * Implements requirement 1.3: Generate report showing similar code locations.
 */
public class CodeAnalysisReport {
    private final Path sourceRoot;
    private final LocalDateTime analysisTime;
    private final int totalMethods;
    private final List<SimilarMethodGroup> similarGroups;
    private final Map<FunctionalArea, List<SimilarMethodGroup>> categorizedPatterns;
    
    public CodeAnalysisReport(Path sourceRoot, int totalMethods, 
                             List<SimilarMethodGroup> similarGroups,
                             Map<FunctionalArea, List<SimilarMethodGroup>> categorizedPatterns) {
        this.sourceRoot = sourceRoot;
        this.analysisTime = LocalDateTime.now();
        this.totalMethods = totalMethods;
        this.similarGroups = similarGroups;
        this.categorizedPatterns = categorizedPatterns;
    }
    
    public Path getSourceRoot() {
        return sourceRoot;
    }
    
    public LocalDateTime getAnalysisTime() {
        return analysisTime;
    }
    
    public int getTotalMethods() {
        return totalMethods;
    }
    
    public List<SimilarMethodGroup> getSimilarGroups() {
        return similarGroups;
    }
    
    public Map<FunctionalArea, List<SimilarMethodGroup>> getCategorizedPatterns() {
        return categorizedPatterns;
    }
    
    /**
     * Gets the total number of similar method groups found.
     */
    public int getTotalSimilarGroups() {
        return similarGroups.size();
    }
    
    /**
     * Gets the total estimated lines of code that could be saved.
     */
    public int getTotalEstimatedLinesSaved() {
        return similarGroups.stream()
            .mapToInt(SimilarMethodGroup::getEstimatedLinesSaved)
            .sum();
    }
    
    /**
     * Gets the total number of methods involved in similar groups.
     */
    public int getTotalMethodsInSimilarGroups() {
        return similarGroups.stream()
            .mapToInt(SimilarMethodGroup::getMethodCount)
            .sum();
    }
    
    /**
     * Gets the percentage of methods that are part of similar groups.
     */
    public double getSimilarMethodsPercentage() {
        return totalMethods > 0 ? (double) getTotalMethodsInSimilarGroups() / totalMethods * 100 : 0.0;
    }
    
    /**
     * Generates a summary string of the analysis results.
     */
    public String generateSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("Code Analysis Report\n");
        summary.append("===================\n");
        summary.append(String.format("Analysis Time: %s\n", analysisTime));
        summary.append(String.format("Source Root: %s\n", sourceRoot));
        summary.append(String.format("Total Methods Analyzed: %d\n", totalMethods));
        summary.append(String.format("Similar Method Groups Found: %d\n", getTotalSimilarGroups()));
        summary.append(String.format("Methods in Similar Groups: %d (%.1f%%)\n", 
                                    getTotalMethodsInSimilarGroups(), getSimilarMethodsPercentage()));
        summary.append(String.format("Estimated Lines Saved: %d\n", getTotalEstimatedLinesSaved()));
        summary.append("\nBreakdown by Functional Area:\n");
        
        for (Map.Entry<FunctionalArea, List<SimilarMethodGroup>> entry : categorizedPatterns.entrySet()) {
            FunctionalArea area = entry.getKey();
            List<SimilarMethodGroup> groups = entry.getValue();
            int totalMethods = groups.stream().mapToInt(SimilarMethodGroup::getMethodCount).sum();
            int totalLinesSaved = groups.stream().mapToInt(SimilarMethodGroup::getEstimatedLinesSaved).sum();
            
            summary.append(String.format("  %s: %d groups, %d methods, %d lines saved\n",
                                        area.getDisplayName(), groups.size(), totalMethods, totalLinesSaved));
        }
        
        return summary.toString();
    }
    
    /**
     * Generates a detailed report with all similar method groups.
     */
    public String generateDetailedReport() {
        StringBuilder report = new StringBuilder();
        report.append(generateSummary());
        report.append("\nDetailed Analysis:\n");
        report.append("==================\n");
        
        for (Map.Entry<FunctionalArea, List<SimilarMethodGroup>> entry : categorizedPatterns.entrySet()) {
            FunctionalArea area = entry.getKey();
            List<SimilarMethodGroup> groups = entry.getValue();
            
            if (groups.isEmpty()) continue;
            
            report.append(String.format("\n%s (%s):\n", area.getDisplayName(), area.getDescription()));
            report.append("-".repeat(area.getDisplayName().length() + area.getDescription().length() + 4));
            report.append("\n");
            
            for (int i = 0; i < groups.size(); i++) {
                SimilarMethodGroup group = groups.get(i);
                report.append(String.format("\nGroup %d: %d methods, %.2f similarity, %d lines saved\n",
                                          i + 1, group.getMethodCount(), group.getSimilarityScore(), 
                                          group.getEstimatedLinesSaved()));
                report.append(String.format("Recommended Strategy: %s\n", group.getRecommendedStrategy()));
                report.append("Methods:\n");
                
                for (MethodInfo method : group.getMethods()) {
                    report.append(String.format("  - %s.%s\n", method.getClassName(), method.getMethodName()));
                }
            }
        }
        
        return report.toString();
    }
}