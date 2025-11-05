package sa.com.cloudsolutions.antikythera.examples.testing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TestCoverageAnalyzer functionality.
 */
class TestCoverageAnalyzerTest {
    
    @TempDir
    Path tempDir;
    
    private TestCoverageAnalyzer coverageAnalyzer;
    
    @BeforeEach
    void setUp() {
        coverageAnalyzer = new TestCoverageAnalyzer();
    }
    
    @Test
    void testAnalyzeCoverageWithSampleFiles() throws Exception {
        // Create source directory
        Path sourceDir = tempDir.resolve("src/main/java");
        Files.createDirectories(sourceDir);
        
        // Create test directory
        Path testDir = tempDir.resolve("src/test/java");
        Files.createDirectories(testDir);
        
        // Create sample source file
        Path sourceFile = sourceDir.resolve("TestClass.java");
        Files.write(sourceFile, TestDataFactory.createSampleJavaClassContent("TestClass").getBytes());
        
        // Create sample test file
        Path testFile = testDir.resolve("TestClassTest.java");
        Files.write(testFile, TestDataFactory.createSampleTestClassContent("TestClassTest", "TestClass").getBytes());
        
        // Run analysis
        TestCoverageReport report = coverageAnalyzer.analyzeCoverage(sourceDir, testDir);
        
        // Verify results
        assertNotNull(report);
        assertFalse(report.getSourceFiles().isEmpty());
        assertFalse(report.getTestFiles().isEmpty());
        assertNotNull(report.getMetrics());
        assertTrue(report.getMetrics().getMethodCoverage() > 0);
    }
    
    @Test
    void testAnalyzeCoverageWithNoTestFiles() throws Exception {
        // Create source directory with files
        Path sourceDir = tempDir.resolve("src/main/java");
        Files.createDirectories(sourceDir);
        
        Path sourceFile = sourceDir.resolve("TestClass.java");
        Files.write(sourceFile, TestDataFactory.createSampleJavaClassContent("TestClass").getBytes());
        
        // Create empty test directory
        Path testDir = tempDir.resolve("src/test/java");
        Files.createDirectories(testDir);
        
        // Run analysis
        TestCoverageReport report = coverageAnalyzer.analyzeCoverage(sourceDir, testDir);
        
        // Verify results show coverage gaps
        assertNotNull(report);
        assertFalse(report.getSourceFiles().isEmpty());
        assertTrue(report.getTestFiles().isEmpty());
        assertFalse(report.getCoverageGaps().isEmpty());
        
        // Should have gaps for missing test files
        assertTrue(report.getCoverageGaps().stream()
                  .anyMatch(gap -> gap.getGapType() == CoverageGapType.NO_TEST_FILE));
    }
    
    @Test
    void testCoverageMetricsCalculation() throws Exception {
        CoverageMetrics metrics = TestDataFactory.createSampleCoverageMetrics();
        
        assertTrue(metrics.getLineCoverage() > 0);
        assertTrue(metrics.getBranchCoverage() > 0);
        assertTrue(metrics.getMethodCoverage() > 0);
        assertTrue(metrics.getCoveredLines() <= metrics.getTotalLines());
        assertTrue(metrics.getCoveredBranches() <= metrics.getTotalBranches());
        assertTrue(metrics.getCoveredMethods() <= metrics.getTotalMethods());
    }
}