package sa.com.cloudsolutions.antikythera.examples;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.liquibase.Indexes;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for QueryAnalysisEngine to verify core functionality
 * including initialization and basic validation.
 */
class QueryAnalysisEngineTest {
    
    private QueryAnalysisEngine engine;
    private CardinalityAnalyzer cardinalityAnalyzer;
    
    @BeforeEach
    void setUp() {
        // Create a real CardinalityAnalyzer with test data
        Map<String, List<Indexes.IndexInfo>> indexMap = new HashMap<>();
        setupTestIndexes(indexMap);
        cardinalityAnalyzer = new CardinalityAnalyzer(indexMap);
        engine = new QueryAnalysisEngine(cardinalityAnalyzer);
    }
    
    /**
     * Sets up test index configurations for various scenarios.
     */
    private void setupTestIndexes(Map<String, List<Indexes.IndexInfo>> indexMap) {
        // Users table with various index types
        List<Indexes.IndexInfo> userIndexes = new ArrayList<>();
        
        // Primary key index
        Indexes.IndexInfo primaryKey = new Indexes.IndexInfo("PRIMARY_KEY", "pk_users", Arrays.asList("user_id"));
        userIndexes.add(primaryKey);
        
        // Unique constraint on email
        Indexes.IndexInfo uniqueEmail = new Indexes.IndexInfo("UNIQUE_CONSTRAINT", "uk_users_email", Arrays.asList("email"));
        userIndexes.add(uniqueEmail);
        
        // Regular index on name
        Indexes.IndexInfo nameIndex = new Indexes.IndexInfo("INDEX", "idx_users_name", Arrays.asList("name"));
        userIndexes.add(nameIndex);
        
        indexMap.put("users", userIndexes);
    }
    
    @Test
    void testEngineInitialization() {
        assertNotNull(engine);
        assertTrue(engine.isReady());
        assertEquals(cardinalityAnalyzer, engine.getCardinalityAnalyzer());
    }
    
    @Test
    void testEngineWithNullCardinalityAnalyzer() {
        QueryAnalysisEngine nullEngine = new QueryAnalysisEngine(null);
        assertFalse(nullEngine.isReady());
        assertNull(nullEngine.getCardinalityAnalyzer());
    }
    
    @Test
    void testAnalyzeQueryWithNullInput() {
        assertThrows(IllegalArgumentException.class, () -> {
            engine.analyzeQueryWithCallable(null);
        });
    }
    
    @Test
    void testCardinalityAnalyzerIntegration() {
        // Test that the engine properly uses the CardinalityAnalyzer
        CardinalityLevel userIdCardinality = cardinalityAnalyzer.analyzeColumnCardinality("users", "user_id");
        assertEquals(CardinalityLevel.HIGH, userIdCardinality);
        
        CardinalityLevel emailCardinality = cardinalityAnalyzer.analyzeColumnCardinality("users", "email");
        assertEquals(CardinalityLevel.HIGH, emailCardinality);
        
        CardinalityLevel nameCardinality = cardinalityAnalyzer.analyzeColumnCardinality("users", "name");
        assertEquals(CardinalityLevel.MEDIUM, nameCardinality);
    }
    
    @Test
    void testEngineReadiness() {
        // Test that engine reports readiness correctly
        assertTrue(engine.isReady());
        
        // Test with null analyzer
        QueryAnalysisEngine emptyEngine = new QueryAnalysisEngine(null);
        assertFalse(emptyEngine.isReady());
    }
}