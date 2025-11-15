package sa.com.cloudsolutions.antikythera.examples;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple unit tests for query optimization functionality.
 * Tests core logic without complex dependencies.
 */
class SimpleQueryOptimizationTest {

    @Test
    void testCardinalityLevelEnum() {
        // Test CardinalityLevel enum values
        assertEquals("HIGH", CardinalityLevel.HIGH.toString());
        assertEquals("MEDIUM", CardinalityLevel.MEDIUM.toString());
        assertEquals("LOW", CardinalityLevel.LOW.toString());
        
        // Test enum ordering
        assertTrue(CardinalityLevel.HIGH.ordinal() < CardinalityLevel.MEDIUM.ordinal());
        assertTrue(CardinalityLevel.MEDIUM.ordinal() < CardinalityLevel.LOW.ordinal());
    }

    @Test
    void testTokenUsageBasicFunctionality() {
        // Test TokenUsage creation and basic methods
        TokenUsage usage = new TokenUsage(100, 50, 150, 0.15, 20);
        
        assertEquals(100, usage.getInputTokens());
        assertEquals(50, usage.getOutputTokens());
        assertEquals(150, usage.getTotalTokens());
        assertEquals(0.15, usage.getEstimatedCost(), 0.001);
        assertEquals(20, usage.getCachedContentTokenCount());
        
        // Test formatted report contains expected elements
        String report = usage.getFormattedReport();
        assertNotNull(report);
        assertTrue(report.contains("150"));
        assertTrue(report.contains("$0.15"));
    }

    @Test
    void testTokenUsageAddition() {
        // Test adding token usage
        TokenUsage usage1 = new TokenUsage(100, 50, 150, 0.15, 20);
        TokenUsage usage2 = new TokenUsage(50, 25, 75, 0.075, 10);
        
        usage1.add(usage2);
        
        assertEquals(150, usage1.getInputTokens());
        assertEquals(75, usage1.getOutputTokens());
        assertEquals(225, usage1.getTotalTokens());
        assertEquals(0.225, usage1.getEstimatedCost(), 0.001);
        assertEquals(30, usage1.getCachedContentTokenCount());
    }

    @Test
    void testQueryBatchBasicFunctionality() {
        // Test QueryBatch creation and basic operations
        QueryBatch batch = new QueryBatch("UserRepository");
        
        assertTrue(batch.isEmpty());
        assertEquals(0, batch.getQueries().size());
        assertEquals(0, batch.size());
        
        // Test adding column cardinality
        batch.addColumnCardinality("email", CardinalityLevel.HIGH);
        batch.addColumnCardinality("status", CardinalityLevel.LOW);
        
        assertEquals(2, batch.getColumnCardinalities().size());
        assertEquals(CardinalityLevel.HIGH, batch.getColumnCardinalities().get("email"));
        assertEquals(CardinalityLevel.LOW, batch.getColumnCardinalities().get("status"));
        assertEquals(CardinalityLevel.HIGH, batch.getColumnCardinality("email"));
        assertEquals(CardinalityLevel.LOW, batch.getColumnCardinality("status"));
        assertNull(batch.getColumnCardinality("nonexistent"));
    }
}
