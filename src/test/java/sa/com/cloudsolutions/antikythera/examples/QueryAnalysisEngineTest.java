package sa.com.cloudsolutions.antikythera.examples;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.liquibase.Indexes;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for QueryAnalysisEngine to verify core functionality,
 * including initialization and basic validation.
 */
class QueryAnalysisEngineTest {
    
    private QueryAnalysisEngine engine;

    @BeforeEach
    void setUp() {
        // Create a real CardinalityAnalyzer with test data
        Map<String, Set<Indexes.IndexInfo>> indexMap = new HashMap<>();
        setupTestIndexes(indexMap);
        CardinalityAnalyzer.setIndexMap(indexMap);
        engine = new QueryAnalysisEngine();
    }
    
    /**
     * Sets up test index configurations for various scenarios.
     */
    private void setupTestIndexes(Map<String, Set<Indexes.IndexInfo>> indexMap) {
        // Users table with various index types
        Set<Indexes.IndexInfo> userIndexes = new HashSet<>();
        
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
    void testAnalyzeQueryWithNullInput() {
        // The analyzeQuery method should throw NullPointerException with null input
        assertThrows(NullPointerException.class, () -> {
            engine.analyzeQuery(null);
        });
    }
    
    @Test
    void testCardinalityAnalyzerIntegration() {
        // Test that the engine properly uses the CardinalityAnalyzer
        CardinalityLevel userIdCardinality = CardinalityAnalyzer.analyzeColumnCardinality("users", "user_id");
        assertEquals(CardinalityLevel.HIGH, userIdCardinality);
        
        CardinalityLevel emailCardinality = CardinalityAnalyzer.analyzeColumnCardinality("users", "email");
        assertEquals(CardinalityLevel.HIGH, emailCardinality);
        
        CardinalityLevel nameCardinality = CardinalityAnalyzer.analyzeColumnCardinality("users", "name");
        assertEquals(CardinalityLevel.MEDIUM, nameCardinality);
    }
}
