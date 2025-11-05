package sa.com.cloudsolutions.antikythera.examples.analysis;

import sa.com.cloudsolutions.antikythera.examples.analysis.CodeAnalysisEngine.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates detailed duplication analysis reports showing similar code patterns,
 * consolidation opportunities, and impact metrics.
 */
public class DuplicationAnalysisReporter {
    
    private final CodeAnalysisEngine analysisEngine;
    
    public DuplicationAnalysisReporter() {
        this.analysisEngine = new CodeAnalysisEngine();
    }
    
    /**
     * Generates a comprehensive duplication analysis report.
     * 
     * @param sourceRoot the root directory containing Java source files
     * @return the generated report as a string
     */
    public String generateReport(Path sourceRoot) throws IOException {
        CodeAnalysisReport analysisReport = analysisEngine.analyzeCodebase(sourceRoot);
        List<SimilarMethodGroup> similarGroups = analysisReport.getSimilarGroups();
        Map<FunctionalArea, List<SimilarMethodGroup>> categorizedPatterns = analysisReport.getCategorizedPatterns();
        
        StringBuilder report = new StringBuilder();
        
        // Report header
        report.append("# Code Duplication Analysis Report\n\n");
        report.append("Generated: ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n");
        report.append("Source Root: ").append(sourceRoot.toAbsolutePath()).append("\n\n");
        
        // Executive summary
        generateExecutiveSummary(report, similarGroups);
        
        // Detailed analysis by functional area
        generateDetailedAnalysis(report, categorizedPatterns);
        
        // Consolidation recommendations
        generateConsolidationRecommendations(report, similarGroups);
        
        // Impact analysis
        generateImpactAnalysis(report, similarGroups);
        
        return report.toString();
    }
    
    /**
     * Generates executive summary section of the report.
     */
    private void generateExecutiveSummary(StringBuilder report, List<SimilarMethodGroup> groups) {
        report.append("## Executive Summary\n\n");
        
        int totalGroups = groups.size();
        int totalMethods = groups.stream().mapToInt(SimilarMethodGroup::getMethodCount).sum();
        int totalLinesSaved = groups.stream().mapToInt(SimilarMethodGroup::getEstimatedLinesSaved).sum();
        
        report.append("- **Total Similar Groups Found**: ").append(totalGroups).append("\n");
        report.append("- **Total Methods in Groups**: ").append(totalMethods).append("\n");
        report.append("- **Estimated Lines Saved**: ").append(totalLinesSaved).append("\n");
        report.append("- **Functional Areas Analyzed**: File Operations, Git Operations, Liquibase Generation, Repository Analysis\n");
        report.append("- **Analysis Approach**: Intelligent filtering excludes legitimate design patterns (Visitor, Builder, Factory)\n\n");
        
        // Functional area breakdown
        Map<FunctionalArea, Long> areaBreakdown = groups.stream()
            .collect(Collectors.groupingBy(SimilarMethodGroup::getFunctionalArea, Collectors.counting()));
        
        report.append("### Functional Area Breakdown\n");
        for (Map.Entry<FunctionalArea, Long> entry : areaBreakdown.entrySet()) {
            report.append("- **").append(entry.getKey().getDisplayName()).append("**: ")
                  .append(entry.getValue()).append(" groups\n");
        }
        report.append("\n");
    }
    
    /**
     * Generates detailed analysis section by functional area.
     */
    private void generateDetailedAnalysis(StringBuilder report, Map<FunctionalArea, List<SimilarMethodGroup>> categorizedPatterns) {
        report.append("## Detailed Analysis by Functional Area\n\n");
        
        for (Map.Entry<FunctionalArea, List<SimilarMethodGroup>> entry : categorizedPatterns.entrySet()) {
            FunctionalArea functionalArea = entry.getKey();
            List<SimilarMethodGroup> groups = entry.getValue();
            
            if (groups.isEmpty()) continue;
            
            report.append("### ").append(functionalArea.getDisplayName()).append("\n\n");
            report.append("**Description**: ").append(functionalArea.getDescription()).append("\n");
            report.append("**Groups Found**: ").append(groups.size()).append("\n\n");
            
            for (int i = 0; i < groups.size(); i++) {
                SimilarMethodGroup group = groups.get(i);
                report.append("#### Group ").append(i + 1).append("\n");
                report.append("**Similarity Score**: ").append(String.format("%.2f", group.getSimilarityScore())).append("\n");
                report.append("**Recommended Strategy**: ").append(group.getRecommendedStrategy()).append("\n");
                report.append("**Estimated Lines Saved**: ").append(group.getEstimatedLinesSaved()).append("\n");
                report.append("**Method Count**: ").append(group.getMethodCount()).append("\n\n");
                
                // Group methods by class
                Map<String, List<MethodInfo>> methodsByClass = group.getMethods().stream()
                    .collect(Collectors.groupingBy(MethodInfo::getClassName));
                
                report.append("**Similar Methods**:\n\n");
                for (Map.Entry<String, List<MethodInfo>> classEntry : methodsByClass.entrySet()) {
                    String className = classEntry.getKey();
                    List<MethodInfo> classMethods = classEntry.getValue();
                    
                    report.append("- **").append(className).append("** (").append(classMethods.size()).append(" methods)\n");
                    
                    for (MethodInfo method : classMethods) {
                        report.append("  - `").append(method.getMethodName()).append("`\n");
                    }
                    report.append("\n");
                }
            }
        }
    }
    
    /**
     * Generates consolidation recommendations section.
     */
    private void generateConsolidationRecommendations(StringBuilder report, List<SimilarMethodGroup> groups) {
        report.append("## Consolidation Recommendations\n\n");
        
        // Sort groups by estimated lines saved (high to low)
        List<SimilarMethodGroup> sortedGroups = groups.stream()
            .sorted((g1, g2) -> Integer.compare(g2.getEstimatedLinesSaved(), g1.getEstimatedLinesSaved()))
            .collect(Collectors.toList());
        
        int priority = 1;
        for (SimilarMethodGroup group : sortedGroups) {
            report.append("### Priority ").append(priority++).append(": ").append(group.getFunctionalArea().getDisplayName()).append("\n\n");
            
            report.append("**Strategy**: ").append(getStrategyDescription(group.getRecommendedStrategy())).append("\n\n");
            
            report.append("**Implementation Steps**:\n");
            generateImplementationSteps(report, group);
            
            report.append("**Benefits**:\n");
            report.append("- Reduce code duplication by ").append(group.getEstimatedLinesSaved()).append(" lines\n");
            report.append("- Centralize ").append(group.getFunctionalArea().getDisplayName().toLowerCase()).append(" logic\n");
            report.append("- Improve maintainability and consistency\n");
            report.append("- Reduce error-prone repetition\n\n");
        }
    }
    
    /**
     * Generates implementation steps for a consolidation strategy.
     */
    private void generateImplementationSteps(StringBuilder report, SimilarMethodGroup group) {
        switch (group.getRecommendedStrategy()) {
            case UTILITY_CLASS:
                report.append("1. Create a new utility class (e.g., `").append(group.getFunctionalArea().getDisplayName().replace(" ", "")).append("Manager`)\n");
                report.append("2. Extract common methods from existing classes\n");
                report.append("3. Add consistent error handling and logging\n");
                report.append("4. Update existing classes to use the utility\n");
                report.append("5. Add comprehensive unit tests\n\n");
                break;
                
            case ENHANCED_COMPONENT:
                report.append("1. Enhance existing component with consolidated functionality\n");
                report.append("2. Merge similar methods and add parameterization\n");
                report.append("3. Implement consistent API and error handling\n");
                report.append("4. Update dependent classes to use enhanced component\n");
                report.append("5. Add integration tests\n\n");
                break;
                
            default:
                report.append("1. Analyze specific consolidation approach\n");
                report.append("2. Design unified interface\n");
                report.append("3. Implement consolidated component\n");
                report.append("4. Migrate existing usage\n");
                report.append("5. Test and validate\n\n");
        }
    }
    
    /**
     * Gets a human-readable description of the consolidation strategy.
     */
    private String getStrategyDescription(ConsolidationStrategy strategy) {
        return switch (strategy) {
            case UTILITY_CLASS -> "Create a dedicated utility class to centralize common operations";
            case ENHANCED_COMPONENT -> "Enhance existing component with consolidated functionality";
            case BUILDER_PATTERN -> "Use builder pattern for complex object construction";
            case STRATEGY_PATTERN -> "Implement strategy pattern for varying implementations";
            case TEMPLATE_METHOD -> "Use template method pattern for similar workflows";
            case DEPENDENCY_INJECTION -> "Extract as injectable service component";
            case CONFIGURATION_DRIVEN -> "Make behavior configurable through properties";
            case NO_CONSOLIDATION -> "Methods are too different to consolidate effectively";
        };
    }
    
    /**
     * Generates impact analysis section.
     */
    private void generateImpactAnalysis(StringBuilder report, List<SimilarMethodGroup> groups) {
        report.append("## Impact Analysis\n\n");
        
        // Calculate metrics
        int totalLinesSaved = groups.stream().mapToInt(SimilarMethodGroup::getEstimatedLinesSaved).sum();
        int totalMethods = groups.stream().mapToInt(SimilarMethodGroup::getMethodCount).sum();
        
        report.append("### Quantitative Impact\n\n");
        report.append("- **Total Lines of Code Reduction**: ").append(totalLinesSaved).append(" lines\n");
        report.append("- **Total Methods Involved**: ").append(totalMethods).append("\n");
        report.append("- **Similar Method Groups**: ").append(groups.size()).append("\n");
        report.append("- **Estimated Maintenance Effort Reduction**: ").append(calculateMaintenanceReduction(groups)).append("%\n\n");
        
        report.append("### Qualitative Benefits\n\n");
        report.append("- **Consistency**: Centralized logic ensures consistent behavior across components\n");
        report.append("- **Maintainability**: Single point of change for common operations\n");
        report.append("- **Testability**: Consolidated components are easier to test comprehensively\n");
        report.append("- **Error Reduction**: Eliminates copy-paste errors and inconsistencies\n");
        report.append("- **Code Quality**: Improves overall code organization and readability\n\n");
        
        report.append("### Risk Assessment\n\n");
        report.append("- **Low Risk**: Utility class consolidations (isolated changes)\n");
        report.append("- **Medium Risk**: Component enhancements (requires careful testing)\n");
        report.append("- **Mitigation**: Comprehensive test coverage and gradual migration\n\n");
    }
    
    /**
     * Calculates estimated maintenance effort reduction percentage.
     */
    private int calculateMaintenanceReduction(List<SimilarMethodGroup> groups) {
        // Estimate based on lines saved and method count
        double totalImpact = groups.stream()
            .mapToDouble(g -> g.getEstimatedLinesSaved() * 0.2)
            .sum();
        
        // Cap at reasonable percentage
        return Math.min(40, (int) Math.round(totalImpact / 10));
    }
    
    /**
     * Writes the report to a file.
     */
    public void writeReportToFile(String report, Path outputPath) throws IOException {
        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, report);
    }
    
    /**
     * Main method for running the analysis and generating the report.
     */
    public static void main(String[] args) throws IOException {
        Path sourceRoot = Paths.get("src/main/java");
        Path outputPath = Paths.get("analysis-reports/code-duplication-analysis.md");
        
        DuplicationAnalysisReporter reporter = new DuplicationAnalysisReporter();
        String report = reporter.generateReport(sourceRoot);
        
        reporter.writeReportToFile(report, outputPath);
        
        System.out.println("Code duplication analysis completed.");
        System.out.println("Report generated: " + outputPath.toAbsolutePath());
        System.out.println("\nReport preview:");
        System.out.println("=".repeat(80));
        System.out.println(report.substring(0, Math.min(1000, report.length())));
        if (report.length() > 1000) {
            System.out.println("\n... (truncated, see full report in file)");
        }
    }
}