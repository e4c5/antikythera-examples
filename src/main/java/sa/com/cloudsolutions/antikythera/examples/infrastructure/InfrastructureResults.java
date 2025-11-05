package sa.com.cloudsolutions.antikythera.examples.infrastructure;

import sa.com.cloudsolutions.antikythera.examples.analysis.CodeAnalysisReport;
import sa.com.cloudsolutions.antikythera.examples.testing.TestCoverageReport;

/**
 * Container for the results of the analysis infrastructure setup.
 */
public class InfrastructureResults {
    private final CodeAnalysisReport codeAnalysisReport;
    private final TestCoverageReport testCoverageReport;
    
    public InfrastructureResults(CodeAnalysisReport codeAnalysisReport, TestCoverageReport testCoverageReport) {
        this.codeAnalysisReport = codeAnalysisReport;
        this.testCoverageReport = testCoverageReport;
    }
    
    public CodeAnalysisReport getCodeAnalysisReport() {
        return codeAnalysisReport;
    }
    
    public TestCoverageReport getTestCoverageReport() {
        return testCoverageReport;
    }
    
    /**
     * Checks if the analysis indicates that refactoring should proceed.
     */
    public boolean shouldProceedWithRefactoring() {
        // Proceed if we have significant duplication and reasonable test coverage
        boolean hasSignificantDuplication = codeAnalysisReport.getTotalSimilarGroups() > 0 &&
                                          codeAnalysisReport.getTotalEstimatedLinesSaved() > 50;
        
        boolean hasReasonableTestCoverage = testCoverageReport.getMetrics().getLineCoverage() > 60.0;
        
        return hasSignificantDuplication && hasReasonableTestCoverage;
    }
    
    /**
     * Gets the recommended next steps based on analysis results.
     */
    public String getRecommendedNextSteps() {
        StringBuilder steps = new StringBuilder();
        steps.append("Recommended Next Steps:\n");
        steps.append("======================\n");
        
        if (testCoverageReport.getMetrics().getLineCoverage() < 80.0) {
            steps.append("1. PRIORITY: Improve test coverage before refactoring\n");
            steps.append("   - Current coverage: ").append(String.format("%.1f%%", testCoverageReport.getMetrics().getLineCoverage())).append("\n");
            steps.append("   - Target coverage: 80%+\n");
            steps.append("   - Focus on high-priority gaps first\n\n");
        }
        
        if (codeAnalysisReport.getTotalSimilarGroups() > 0) {
            steps.append("2. Begin code consolidation in priority order:\n");
            steps.append("   - Start with file operations (highest impact)\n");
            steps.append("   - Then Git operations\n");
            steps.append("   - Finally repository analysis operations\n\n");
        }
        
        steps.append("3. Run baseline test suite to establish working state\n");
        steps.append("4. Create utility components for consolidated functionality\n");
        steps.append("5. Migrate existing classes incrementally\n");
        steps.append("6. Validate backward compatibility throughout\n");
        
        return steps.toString();
    }
}