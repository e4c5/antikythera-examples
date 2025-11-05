package sa.com.cloudsolutions.antikythera.examples.testing;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Represents a comprehensive test coverage analysis report.
 */
public class TestCoverageReport {
    private final Path sourceRoot;
    private final Path testRoot;
    private final LocalDateTime analysisTime;
    private final List<SourceFileInfo> sourceFiles;
    private final List<TestFileInfo> testFiles;
    private final List<CoverageGap> coverageGaps;
    private final CoverageMetrics metrics;
    
    public TestCoverageReport(Path sourceRoot, Path testRoot, List<SourceFileInfo> sourceFiles,
                             List<TestFileInfo> testFiles, List<CoverageGap> coverageGaps,
                             CoverageMetrics metrics) {
        this.sourceRoot = sourceRoot;
        this.testRoot = testRoot;
        this.analysisTime = LocalDateTime.now();
        this.sourceFiles = sourceFiles;
        this.testFiles = testFiles;
        this.coverageGaps = coverageGaps;
        this.metrics = metrics;
    }
    
    public Path getSourceRoot() {
        return sourceRoot;
    }
    
    public Path getTestRoot() {
        return testRoot;
    }
    
    public LocalDateTime getAnalysisTime() {
        return analysisTime;
    }
    
    public List<SourceFileInfo> getSourceFiles() {
        return sourceFiles;
    }
    
    public List<TestFileInfo> getTestFiles() {
        return testFiles;
    }
    
    public List<CoverageGap> getCoverageGaps() {
        return coverageGaps;
    }
    
    public CoverageMetrics getMetrics() {
        return metrics;
    }
    
    /**
     * Gets coverage gaps of a specific type.
     */
    public List<CoverageGap> getCoverageGapsByType(CoverageGapType type) {
        return coverageGaps.stream()
            .filter(gap -> gap.getGapType() == type)
            .collect(Collectors.toList());
    }
    
    /**
     * Gets high priority coverage gaps (priority >= 8).
     */
    public List<CoverageGap> getHighPriorityCoverageGaps() {
        return coverageGaps.stream()
            .filter(gap -> gap.getPriority() >= 8)
            .sorted((a, b) -> Integer.compare(b.getPriority(), a.getPriority()))
            .collect(Collectors.toList());
    }
    
    /**
     * Gets coverage gaps grouped by class.
     */
    public Map<String, List<CoverageGap>> getCoverageGapsByClass() {
        return coverageGaps.stream()
            .collect(Collectors.groupingBy(CoverageGap::getClassName));
    }
    
    /**
     * Generates a summary of the coverage analysis.
     */
    public String generateSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("Test Coverage Analysis Report\n");
        summary.append("============================\n");
        summary.append(String.format("Analysis Time: %s\n", analysisTime));
        summary.append(String.format("Source Root: %s\n", sourceRoot));
        summary.append(String.format("Test Root: %s\n", testRoot));
        summary.append("\nCoverage Metrics:\n");
        summary.append(String.format("  Line Coverage: %.1f%% (%d/%d lines)\n", 
                                    metrics.getLineCoverage(), metrics.getCoveredLines(), metrics.getTotalLines()));
        summary.append(String.format("  Branch Coverage: %.1f%% (%d/%d branches)\n", 
                                    metrics.getBranchCoverage(), metrics.getCoveredBranches(), metrics.getTotalBranches()));
        summary.append(String.format("  Method Coverage: %.1f%% (%d/%d methods)\n", 
                                    metrics.getMethodCoverage(), metrics.getCoveredMethods(), metrics.getTotalMethods()));
        
        summary.append("\nFile Statistics:\n");
        summary.append(String.format("  Source Files: %d\n", sourceFiles.size()));
        summary.append(String.format("  Test Files: %d\n", testFiles.size()));
        summary.append(String.format("  Coverage Gaps: %d\n", coverageGaps.size()));
        
        // Gap breakdown by type
        Map<CoverageGapType, Long> gapsByType = coverageGaps.stream()
            .collect(Collectors.groupingBy(CoverageGap::getGapType, Collectors.counting()));
        
        summary.append("\nCoverage Gaps by Type:\n");
        for (Map.Entry<CoverageGapType, Long> entry : gapsByType.entrySet()) {
            summary.append(String.format("  %s: %d\n", entry.getKey().getDisplayName(), entry.getValue()));
        }
        
        return summary.toString();
    }
    
    /**
     * Generates a detailed report with all coverage gaps.
     */
    public String generateDetailedReport() {
        StringBuilder report = new StringBuilder();
        report.append(generateSummary());
        report.append("\nDetailed Coverage Gaps:\n");
        report.append("======================\n");
        
        Map<String, List<CoverageGap>> gapsByClass = getCoverageGapsByClass();
        
        for (Map.Entry<String, List<CoverageGap>> entry : gapsByClass.entrySet()) {
            String className = entry.getKey();
            List<CoverageGap> classGaps = entry.getValue();
            
            report.append(String.format("\n%s (%d gaps):\n", className, classGaps.size()));
            report.append("-".repeat(className.length() + 10));
            report.append("\n");
            
            // Sort gaps by priority (highest first)
            classGaps.sort((a, b) -> Integer.compare(b.getPriority(), a.getPriority()));
            
            for (CoverageGap gap : classGaps) {
                report.append(String.format("  [Priority %d] %s: %s\n", 
                                          gap.getPriority(), gap.getGapType().getDisplayName(), gap.getDescription()));
            }
        }
        
        return report.toString();
    }
    
    /**
     * Generates a test improvement plan based on coverage gaps.
     */
    public String generateTestImprovementPlan() {
        StringBuilder plan = new StringBuilder();
        plan.append("Test Coverage Improvement Plan\n");
        plan.append("==============================\n");
        
        List<CoverageGap> highPriorityGaps = getHighPriorityCoverageGaps();
        
        plan.append(String.format("High Priority Items (%d):\n", highPriorityGaps.size()));
        for (int i = 0; i < Math.min(10, highPriorityGaps.size()); i++) {
            CoverageGap gap = highPriorityGaps.get(i);
            plan.append(String.format("%d. %s - %s\n", i + 1, gap.getFullMethodName(), gap.getDescription()));
        }
        
        // Recommendations by gap type
        Map<CoverageGapType, Long> gapCounts = coverageGaps.stream()
            .collect(Collectors.groupingBy(CoverageGap::getGapType, Collectors.counting()));
        
        plan.append("\nRecommended Actions:\n");
        
        if (gapCounts.getOrDefault(CoverageGapType.NO_TEST_FILE, 0L) > 0) {
            plan.append(String.format("- Create test files for %d classes without tests\n", 
                                    gapCounts.get(CoverageGapType.NO_TEST_FILE)));
        }
        
        if (gapCounts.getOrDefault(CoverageGapType.UNTESTED_METHOD, 0L) > 0) {
            plan.append(String.format("- Add test methods for %d untested methods\n", 
                                    gapCounts.get(CoverageGapType.UNTESTED_METHOD)));
        }
        
        if (gapCounts.getOrDefault(CoverageGapType.UNTESTED_ERROR_HANDLING, 0L) > 0) {
            plan.append(String.format("- Add error handling tests for %d methods\n", 
                                    gapCounts.get(CoverageGapType.UNTESTED_ERROR_HANDLING)));
        }
        
        if (gapCounts.getOrDefault(CoverageGapType.UNTESTED_BRANCHES, 0L) > 0) {
            plan.append(String.format("- Add branch coverage tests for %d methods\n", 
                                    gapCounts.get(CoverageGapType.UNTESTED_BRANCHES)));
        }
        
        return plan.toString();
    }
}