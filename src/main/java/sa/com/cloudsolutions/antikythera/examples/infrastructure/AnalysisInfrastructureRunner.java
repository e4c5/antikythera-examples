package sa.com.cloudsolutions.antikythera.examples.infrastructure;

import sa.com.cloudsolutions.antikythera.examples.analysis.CodeAnalysisEngine;
import sa.com.cloudsolutions.antikythera.examples.analysis.CodeAnalysisReport;
import sa.com.cloudsolutions.antikythera.examples.testing.TestCoverageAnalyzer;
import sa.com.cloudsolutions.antikythera.examples.testing.TestCoverageReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Main runner for the analysis and testing infrastructure.
 * Implements task 1: Set up analysis and testing infrastructure.
 */
public class AnalysisInfrastructureRunner {
    private static final Logger logger = LoggerFactory.getLogger(AnalysisInfrastructureRunner.class);
    
    private final CodeAnalysisEngine codeAnalysisEngine;
    private final TestCoverageAnalyzer testCoverageAnalyzer;
    
    public AnalysisInfrastructureRunner() {
        this.codeAnalysisEngine = new CodeAnalysisEngine();
        this.testCoverageAnalyzer = new TestCoverageAnalyzer();
    }
    
    /**
     * Runs the complete analysis and testing infrastructure setup.
     */
    public InfrastructureResults runAnalysis(Path projectRoot) throws IOException {
        logger.info("Starting analysis infrastructure for project: {}", projectRoot);
        
        // Define source and test paths
        Path sourceRoot = projectRoot.resolve("src/main/java");
        Path testRoot = projectRoot.resolve("src/test/java");
        
        // Validate paths exist
        validatePaths(sourceRoot, testRoot);
        
        // Run code analysis
        logger.info("Running code duplication analysis...");
        CodeAnalysisReport codeAnalysisReport = codeAnalysisEngine.analyzeCodebase(sourceRoot);
        
        // Run test coverage analysis
        logger.info("Running test coverage analysis...");
        TestCoverageReport testCoverageReport = testCoverageAnalyzer.analyzeCoverage(sourceRoot, testRoot);
        
        // Create combined results
        InfrastructureResults results = new InfrastructureResults(codeAnalysisReport, testCoverageReport);
        
        logger.info("Analysis infrastructure setup complete");
        return results;
    }
    
    /**
     * Validates that the required paths exist.
     */
    private void validatePaths(Path sourceRoot, Path testRoot) throws IOException {
        if (!Files.exists(sourceRoot)) {
            throw new IOException("Source root does not exist: " + sourceRoot);
        }
        
        if (!Files.exists(testRoot)) {
            logger.warn("Test root does not exist, creating: {}", testRoot);
            Files.createDirectories(testRoot);
        }
    }
    
    /**
     * Generates and saves analysis reports to files.
     */
    public void generateReports(InfrastructureResults results, Path outputDir) throws IOException {
        logger.info("Generating analysis reports to: {}", outputDir);
        
        // Create output directory if it doesn't exist
        Files.createDirectories(outputDir);
        
        // Generate code analysis report
        Path codeAnalysisReportPath = outputDir.resolve("code-analysis-report.txt");
        String codeAnalysisContent = results.getCodeAnalysisReport().generateDetailedReport();
        Files.write(codeAnalysisReportPath, codeAnalysisContent.getBytes());
        logger.info("Code analysis report saved to: {}", codeAnalysisReportPath);
        
        // Generate test coverage report
        Path testCoverageReportPath = outputDir.resolve("test-coverage-report.txt");
        String testCoverageContent = results.getTestCoverageReport().generateDetailedReport();
        Files.write(testCoverageReportPath, testCoverageContent.getBytes());
        logger.info("Test coverage report saved to: {}", testCoverageReportPath);
        
        // Generate test improvement plan
        Path testImprovementPlanPath = outputDir.resolve("test-improvement-plan.txt");
        String testImprovementContent = results.getTestCoverageReport().generateTestImprovementPlan();
        Files.write(testImprovementPlanPath, testImprovementContent.getBytes());
        logger.info("Test improvement plan saved to: {}", testImprovementPlanPath);
        
        // Generate summary report
        Path summaryReportPath = outputDir.resolve("analysis-summary.txt");
        String summaryContent = generateSummaryReport(results);
        Files.write(summaryReportPath, summaryContent.getBytes());
        logger.info("Summary report saved to: {}", summaryReportPath);
    }
    
    /**
     * Generates a combined summary report.
     */
    private String generateSummaryReport(InfrastructureResults results) {
        StringBuilder summary = new StringBuilder();
        summary.append("Antikythera Code Refactoring Analysis Summary\n");
        summary.append("===========================================\n\n");
        
        // Code analysis summary
        summary.append("CODE DUPLICATION ANALYSIS:\n");
        summary.append(results.getCodeAnalysisReport().generateSummary());
        summary.append("\n\n");
        
        // Test coverage summary
        summary.append("TEST COVERAGE ANALYSIS:\n");
        summary.append(results.getTestCoverageReport().generateSummary());
        summary.append("\n\n");
        
        // Combined recommendations
        summary.append("COMBINED RECOMMENDATIONS:\n");
        summary.append("========================\n");
        
        CodeAnalysisReport codeReport = results.getCodeAnalysisReport();
        TestCoverageReport testReport = results.getTestCoverageReport();
        
        summary.append(String.format("1. Address %d similar method groups to reduce code duplication\n", 
                                    codeReport.getTotalSimilarGroups()));
        summary.append(String.format("2. Potential lines of code reduction: %d lines\n", 
                                    codeReport.getTotalEstimatedLinesSaved()));
        summary.append(String.format("3. Improve test coverage from %.1f%% to target 90%% line coverage\n", 
                                    testReport.getMetrics().getLineCoverage()));
        summary.append(String.format("4. Address %d high-priority coverage gaps\n", 
                                    testReport.getHighPriorityCoverageGaps().size()));
        summary.append("5. Focus on file operations and Git operations for highest impact consolidation\n");
        summary.append("6. Create comprehensive test suite before starting refactoring\n");
        
        return summary.toString();
    }
    
    /**
     * Main method for running the analysis infrastructure.
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Usage: java AnalysisInfrastructureRunner <project_root>");
            System.exit(1);
        }
        
        Path projectRoot = Paths.get(args[0]);
        AnalysisInfrastructureRunner runner = new AnalysisInfrastructureRunner();
        
        try {
            // Run analysis
            InfrastructureResults results = runner.runAnalysis(projectRoot);
            
            // Generate reports
            Path outputDir = projectRoot.resolve("analysis-reports");
            runner.generateReports(results, outputDir);
            
            // Print summary to console
            System.out.println("\n" + runner.generateSummaryReport(results));
            
            logger.info("Analysis infrastructure setup completed successfully");
            
        } catch (Exception e) {
            logger.error("Analysis infrastructure setup failed", e);
            System.err.println("Analysis failed: " + e.getMessage());
            System.exit(1);
        }
    }
}