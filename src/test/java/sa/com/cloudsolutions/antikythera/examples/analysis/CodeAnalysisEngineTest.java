package sa.com.cloudsolutions.antikythera.examples.analysis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sa.com.cloudsolutions.antikythera.examples.testing.TestDataFactory;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CodeAnalysisEngine functionality.
 */
class CodeAnalysisEngineTest {
    
    @TempDir
    Path tempDir;
    
    private CodeAnalysisEngine analysisEngine;
    
    @BeforeEach
    void setUp() {
        analysisEngine = new CodeAnalysisEngine();
    }
    
    @Test
    void testAnalyzeCodebaseWithSampleFiles() throws Exception {
        // Create sample Java files
        Path sourceDir = tempDir.resolve("src/main/java");
        Files.createDirectories(sourceDir);
        
        // Create files with similar functionality
        Path file1 = sourceDir.resolve("FileClass1.java");
        Files.write(file1, TestDataFactory.createSampleJavaClassContent("FileClass1").getBytes());
        
        Path file2 = sourceDir.resolve("FileClass2.java");
        Files.write(file2, TestDataFactory.createSampleJavaClassContent("FileClass2").getBytes());
        
        // Run analysis
        CodeAnalysisReport report = analysisEngine.analyzeCodebase(sourceDir);
        
        // Verify results
        assertNotNull(report);
        assertTrue(report.getTotalMethods() > 0);
        assertNotNull(report.getSimilarGroups());
        assertNotNull(report.getCategorizedPatterns());
    }
    
    @Test
    void testAnalyzeEmptyDirectory() throws Exception {
        Path emptyDir = tempDir.resolve("empty");
        Files.createDirectories(emptyDir);
        
        CodeAnalysisReport report = analysisEngine.analyzeCodebase(emptyDir);
        
        assertNotNull(report);
        assertEquals(0, report.getTotalMethods());
        assertTrue(report.getSimilarGroups().isEmpty());
    }
    
    @Test
    void testAnalyzeNonExistentDirectory() {
        Path nonExistent = tempDir.resolve("nonexistent");
        
        assertThrows(Exception.class, () -> {
            analysisEngine.analyzeCodebase(nonExistent);
        });
    }
}