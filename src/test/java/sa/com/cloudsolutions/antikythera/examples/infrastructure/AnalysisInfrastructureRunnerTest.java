package sa.com.cloudsolutions.antikythera.examples.infrastructure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sa.com.cloudsolutions.antikythera.examples.testing.TestDataFactory;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AnalysisInfrastructureRunner functionality.
 */
class AnalysisInfrastructureRunnerTest {
    
    @TempDir
    Path tempDir;
    
    private AnalysisInfrastructureRunner runner;
    
    @BeforeEach
    void setUp() {
        runner = new AnalysisInfrastructureRunner();
    }
    
    @Test
    void testRunAnalysisWithValidProject() throws Exception {
        // Create project structure
        Path sourceDir = tempDir.resolve("src/main/java");
        Path testDir = tempDir.resolve("src/test/java");
        Files.createDirectories(sourceDir);
        Files.createDirectories(testDir);
        
        // Create sample files
        Path sourceFile = sourceDir.resolve("TestClass.java");
        Files.write(sourceFile, TestDataFactory.createSampleJavaClassContent("TestClass").getBytes());
        
        Path testFile = testDir.resolve("TestClassTest.java");
        Files.write(testFile, TestDataFactory.createSampleTestClassContent("TestClassTest", "TestClass").getBytes());
        
        // Run analysis
        InfrastructureResults results = runner.runAnalysis(tempDir);
        
        // Verify results
        assertNotNull(results);
        assertNotNull(results.getCodeAnalysisReport());
        assertNotNull(results.getTestCoverageReport());
    }
    
    @Test
    void testGenerateReports() throws Exception {
        // Create sample results
        InfrastructureResults results = new InfrastructureResults(
            TestDataFactory.createSampleCodeAnalysisReport(),
            TestDataFactory.createSampleTestCoverageReport()
        );
        
        Path outputDir = tempDir.resolve("reports");
        
        // Generate reports
        runner.generateReports(results, outputDir);
        
        // Verify report files were created
        assertTrue(Files.exists(outputDir.resolve("code-analysis-report.txt")));
        assertTrue(Files.exists(outputDir.resolve("test-coverage-report.txt")));
        assertTrue(Files.exists(outputDir.resolve("test-improvement-plan.txt")));
        assertTrue(Files.exists(outputDir.resolve("analysis-summary.txt")));
        
        // Verify files have content
        assertTrue(Files.size(outputDir.resolve("code-analysis-report.txt")) > 0);
        assertTrue(Files.size(outputDir.resolve("test-coverage-report.txt")) > 0);
    }
    
    @Test
    void testRunAnalysisWithMissingSourceDirectory() {
        Path projectWithoutSource = tempDir.resolve("empty-project");
        
        assertThrows(Exception.class, () -> {
            runner.runAnalysis(projectWithoutSource);
        });
    }
}