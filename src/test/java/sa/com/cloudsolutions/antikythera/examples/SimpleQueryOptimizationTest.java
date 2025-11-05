package sa.com.cloudsolutions.antikythera.examples;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple unit tests for query optimization functionality.
 * Tests core logic without complex dependencies.
 */
class SimpleQueryOptimizationTest {

    @Test
    void testOptimizationIssueCreation() {
        // Test creating an OptimizationIssue with basic data
        OptimizationIssue issue = new OptimizationIssue(
            null, // query
            java.util.List.of("email", "status"),
            java.util.List.of("status", "email"),
            "Reorder WHERE clause for better performance",
            OptimizationIssue.Severity.HIGH,
            "SELECT * FROM users WHERE email = ? AND status = ?",
            "High cardinality column should come first",
            null // optimizedQuery
        );
        
        assertNotNull(issue);
        assertEquals(OptimizationIssue.Severity.HIGH, issue.severity());
        assertEquals("Reorder WHERE clause for better performance", issue.description());
        assertTrue(issue.isHighSeverity());
        assertFalse(issue.isMediumSeverity());
        assertFalse(issue.isLowSeverity());
    }

    @Test
    void testOptimizationIssueSeverityLevels() {
        // Test HIGH severity
        OptimizationIssue highIssue = new OptimizationIssue(
            null, null, null, "High priority issue", 
            OptimizationIssue.Severity.HIGH, null, null, null
        );
        assertTrue(highIssue.isHighSeverity());
        assertFalse(highIssue.isMediumSeverity());
        assertFalse(highIssue.isLowSeverity());
        
        // Test MEDIUM severity
        OptimizationIssue mediumIssue = new OptimizationIssue(
            null, null, null, "Medium priority issue", 
            OptimizationIssue.Severity.MEDIUM, null, null, null
        );
        assertFalse(mediumIssue.isHighSeverity());
        assertTrue(mediumIssue.isMediumSeverity());
        assertFalse(mediumIssue.isLowSeverity());
        
        // Test LOW severity
        OptimizationIssue lowIssue = new OptimizationIssue(
            null, null, null, "Low priority issue", 
            OptimizationIssue.Severity.LOW, null, null, null
        );
        assertFalse(lowIssue.isHighSeverity());
        assertFalse(lowIssue.isMediumSeverity());
        assertTrue(lowIssue.isLowSeverity());
    }

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
    void testWhereConditionCreation() {
        // Test WhereCondition record creation
        WhereCondition condition = new WhereCondition(
            "users", "email", "=", CardinalityLevel.HIGH, 0, null
        );
        
        assertEquals("users", condition.tableName());
        assertEquals("email", condition.columnName());
        assertEquals("=", condition.operator());
        assertEquals(CardinalityLevel.HIGH, condition.cardinality());
        assertEquals(0, condition.position());
        assertTrue(condition.isHighCardinality());
        assertFalse(condition.isLowCardinality());
    }

    @Test
    void testWhereConditionCardinalityChecks() {
        // Test high cardinality condition
        WhereCondition highCondition = new WhereCondition(
            "users", "id", "=", CardinalityLevel.HIGH, 0, null
        );
        assertTrue(highCondition.isHighCardinality());
        assertFalse(highCondition.isLowCardinality());
        
        // Test low cardinality condition
        WhereCondition lowCondition = new WhereCondition(
            "users", "status", "=", CardinalityLevel.LOW, 1, null
        );
        assertFalse(lowCondition.isHighCardinality());
        assertTrue(lowCondition.isLowCardinality());
        
        // Test medium cardinality condition
        WhereCondition mediumCondition = new WhereCondition(
            "users", "department", "=", CardinalityLevel.MEDIUM, 2, null
        );
        assertFalse(mediumCondition.isHighCardinality());
        assertFalse(mediumCondition.isLowCardinality());
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