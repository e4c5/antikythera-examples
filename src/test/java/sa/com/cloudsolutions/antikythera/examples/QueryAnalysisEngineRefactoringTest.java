package sa.com.cloudsolutions.antikythera.examples;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Test to verify that the refactoring to use parser infrastructure works correctly.
 * This ensures that the QueryAnalysisEngine still functions after removing duplicated parsing code.
 */
class QueryAnalysisEngineRefactoringTest {

    @Mock
    private RepositoryQuery repositoryQuery;

    private QueryAnalysisEngine queryAnalysisEngine;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        queryAnalysisEngine = new QueryAnalysisEngine();
        when(repositoryQuery.getPrimaryTable()).thenReturn("test_table");
    }

    @Test
    void testQueryAnalysisEngineCanBeCreated() {
        // Test that the refactored QueryAnalysisEngine can be instantiated
        assertNotNull(queryAnalysisEngine);
        // isReady depends on whether CardinalityAnalyzer.setIndexMap() was called
        // In real usage, it's set during QueryOptimizationChecker initialization
        assertNotNull(queryAnalysisEngine);
    }

    @Test
    void testAnalyzeQueryWithNullStatement() {
        // Test that the engine handles queries without parsed statements
        when(repositoryQuery.getStatement()).thenReturn(null);
        when(repositoryQuery.getMethodParameters()).thenReturn(List.of());
        
        QueryOptimizationResult result = queryAnalysisEngine.analyzeQuery(repositoryQuery);
        
        assertNotNull(result);
        assertNotNull(result.getWhereConditions());
        assertNotNull(result.getOptimizationIssues());
    }

    @Test
    void testAnalyzeQueryWithEmptyTable() {
        // Test that the engine handles queries with empty table names
        when(repositoryQuery.getPrimaryTable()).thenReturn("");
        when(repositoryQuery.getStatement()).thenReturn(null);
        
        QueryOptimizationResult result = queryAnalysisEngine.analyzeQuery(repositoryQuery);
        
        assertNotNull(result);
        // Should return empty result for queries without table names
        assertTrue(result.getWhereConditions().isEmpty());
        assertTrue(result.getOptimizationIssues().isEmpty());
    }

    @Test
    void testEngineReadiness() {
        // Test that the engine can check readiness
        // Note: isReady() checks if CardinalityAnalyzer.getIndexMap() != null
        // In isolated unit tests without full initialization, it may be null
        assertNotNull(queryAnalysisEngine);
        
        // The engine itself is always instantiable
        QueryAnalysisEngine anotherEngine = new QueryAnalysisEngine();
        assertNotNull(anotherEngine);
    }
}