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
    private CardinalityAnalyzer cardinalityAnalyzer;

    @Mock
    private RepositoryQuery repositoryQuery;

    private QueryAnalysisEngine queryAnalysisEngine;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        queryAnalysisEngine = new QueryAnalysisEngine(cardinalityAnalyzer);
        
        // Setup basic mocks
        when(cardinalityAnalyzer.analyzeColumnCardinality(anyString(), anyString()))
            .thenReturn(CardinalityLevel.MEDIUM);
        when(repositoryQuery.getPrimaryTable()).thenReturn("test_table");
    }

    @Test
    void testQueryAnalysisEngineCanBeCreated() {
        // Test that the refactored QueryAnalysisEngine can be instantiated
        assertNotNull(queryAnalysisEngine);
        assertNotNull(queryAnalysisEngine.getCardinalityAnalyzer());
        assertTrue(queryAnalysisEngine.isReady());
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
    void testCardinalityAnalyzerIntegration() {
        // Test that the cardinality analyzer is properly integrated
        CardinalityAnalyzer analyzer = queryAnalysisEngine.getCardinalityAnalyzer();
        
        assertNotNull(analyzer);
        assertSame(cardinalityAnalyzer, analyzer);
    }

    @Test
    void testEngineReadiness() {
        // Test that the engine reports readiness correctly
        assertTrue(queryAnalysisEngine.isReady());
        
        // Test with null analyzer
        QueryAnalysisEngine engineWithNullAnalyzer = new QueryAnalysisEngine(null);
        assertFalse(engineWithNullAnalyzer.isReady());
    }
}