package sa.com.cloudsolutions.antikythera.examples;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

class QueryOptimizationCheckerFixTest {

    private QueryOptimizationChecker checker;

    @Mock
    private QueryAnalysisEngine mockAnalysisEngine;

    @Mock
    private OptimizationIssue mockOptimizationIssue;

    @Mock
    private RepositoryQuery mockRawQuery;

    @Mock
    private RepositoryQuery mockOptimizedQuery;

    @Mock
    private QueryAnalysisResult mockOptimizedResult;

    @Mock
    private QueryAnalysisResult mockRawResult;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);

        Path tmpDir = Files.createTempDirectory("qoc-fix-test");
        // Ensure the temp directory and created files are removed when the JVM exits (avoids accumulating temp files on CI)
        tmpDir.toFile().deleteOnExit();

        // Setup minimal valid Liquibase file with proper schema declaration
        File liquibaseFile = tmpDir.resolve("db.changelog-master.xml").toFile();
        liquibaseFile.createNewFile();
        // schedule files for deletion on JVM exit
        liquibaseFile.deleteOnExit();
        try (FileWriter fw = new FileWriter(liquibaseFile)) {
            fw.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            fw.write("<databaseChangeLog\n");
            fw.write("    xmlns=\"http://www.liquibase.org/xml/ns/dbchangelog\"\n");
            fw.write("    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
            fw.write("    xsi:schemaLocation=\"http://www.liquibase.org/xml/ns/dbchangelog\n");
            fw.write("    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.0.xsd\">\n");
            fw.write("</databaseChangeLog>");
        }
        // Setup dummy generator.yml
        File configFile = tmpDir.resolve("generator.yml").toFile();
        try (FileWriter fw = new FileWriter(configFile)) {
            fw.write("base_path: " + tmpDir.toAbsolutePath().toString() + "\n");
            fw.write("ai_service:\n");
            fw.write("  api_key: test\n");
        }
        configFile.deleteOnExit();

        sa.com.cloudsolutions.antikythera.configuration.Settings.loadConfigMap(configFile);

        checker = new QueryOptimizationChecker(liquibaseFile);
        checker.setAnalysisEngine(mockAnalysisEngine);

        // Mock common behavior
        when(mockOptimizedResult.getJoinConditions()).thenReturn(new ArrayList<>());
        when(mockRawResult.getJoinConditions()).thenReturn(new ArrayList<>());
    }

    @Test
    void testCreateResultWithIndexAnalysis_UsesOptimizedQuery_WhenAvailable() {
        // Arrange
        WhereCondition optimizedCondition = new WhereCondition("table", "optimizedCol", "=", 0);
        List<WhereCondition> optimizedConditions = Collections.singletonList(optimizedCondition);

        when(mockOptimizedResult.getWhereConditions()).thenReturn(optimizedConditions);
        when(mockOptimizationIssue.optimizedQuery()).thenReturn(mockOptimizedQuery);
        when(mockOptimizationIssue.query()).thenReturn(mockRawQuery);
        when(mockAnalysisEngine.analyzeQuery(mockOptimizedQuery)).thenReturn(mockOptimizedResult);

        // Act
        QueryAnalysisResult result = checker.createResultWithIndexAnalysis(mockOptimizationIssue, mockRawQuery);

        // Assert
        // Verify that the result contains conditions from the OPTIMIZED analysis
        assertNotNull(result);
        assertEquals(1, result.getWhereConditions().size());
        assertEquals("optimizedCol", result.getWhereConditions().get(0).getColumnName());

        // Verify interaction
        verify(mockAnalysisEngine).analyzeQuery(mockOptimizedQuery);
        verify(mockAnalysisEngine, never()).analyzeQuery(mockRawQuery);
    }

    @Test
    void testCreateResultWithIndexAnalysis_UsesRawQuery_WhenOptimizedQueryNotAvailable() {
        // Arrange
        WhereCondition rawCondition = new WhereCondition("table", "rawCol", "=", 0);
        List<WhereCondition> rawConditions = Collections.singletonList(rawCondition);

        when(mockRawResult.getWhereConditions()).thenReturn(rawConditions);
        when(mockOptimizationIssue.optimizedQuery()).thenReturn(null);
        when(mockOptimizationIssue.query()).thenReturn(mockRawQuery);
        when(mockAnalysisEngine.analyzeQuery(mockRawQuery)).thenReturn(mockRawResult);

        // Act
        QueryAnalysisResult result = checker.createResultWithIndexAnalysis(mockOptimizationIssue, mockRawQuery);

        // Assert
        // Verify that the result contains conditions from the RAW analysis
        assertNotNull(result);
        assertEquals(1, result.getWhereConditions().size());
        assertEquals("rawCol", result.getWhereConditions().get(0).getColumnName());

        // Verify interaction
        verify(mockAnalysisEngine).analyzeQuery(mockRawQuery);
    }
}
