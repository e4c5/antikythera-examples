package sa.com.cloudsolutions.antikythera.examples;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test coverage for WhereCondition record
 */
class WhereConditionCoverageTest {

    @Test
    void testWhereConditionCreation() {
        // Given & When
        WhereCondition condition = new WhereCondition(
            "users", 
            "id", 
            "=", 
            CardinalityLevel.HIGH, 
            1, 
            null
        );
        
        // Then
        assertEquals("users", condition.tableName());
        assertEquals("id", condition.columnName());
        assertEquals("=", condition.operator());
        assertEquals(CardinalityLevel.HIGH, condition.cardinality());
        assertEquals(1, condition.position());
        assertNull(condition.parameter());
    }

    @Test
    void testIsHighCardinalityWithHighCardinality() {
        // Given
        WhereCondition condition = new WhereCondition(
            "users", 
            "id", 
            "=", 
            CardinalityLevel.HIGH, 
            1, 
            null
        );
        
        // When & Then
        assertTrue(condition.isHighCardinality());
        assertFalse(condition.isLowCardinality());
    }

    @Test
    void testIsHighCardinalityWithMediumCardinality() {
        // Given
        WhereCondition condition = new WhereCondition(
            "products", 
            "category_id", 
            "=", 
            CardinalityLevel.MEDIUM, 
            1, 
            null
        );
        
        // When & Then
        assertFalse(condition.isHighCardinality());
        assertFalse(condition.isLowCardinality());
    }

    @Test
    void testIsLowCardinalityWithLowCardinality() {
        // Given
        WhereCondition condition = new WhereCondition(
            "users", 
            "active", 
            "=", 
            CardinalityLevel.LOW, 
            1, 
            null
        );
        
        // When & Then
        assertTrue(condition.isLowCardinality());
        assertFalse(condition.isHighCardinality());
    }

    @Test
    void testIsLowCardinalityWithHighCardinality() {
        // Given
        WhereCondition condition = new WhereCondition(
            "users", 
            "id", 
            "=", 
            CardinalityLevel.HIGH, 
            1, 
            null
        );
        
        // When & Then
        assertFalse(condition.isLowCardinality());
        assertTrue(condition.isHighCardinality());
    }

    @Test
    void testWhereConditionWithDifferentOperators() {
        // When & Then - Test different operators
        WhereCondition equalsCondition = new WhereCondition("users", "name", "=", CardinalityLevel.MEDIUM, 1, null);
        assertEquals("=", equalsCondition.operator());
        
        WhereCondition likeCondition = new WhereCondition("users", "name", "LIKE", CardinalityLevel.MEDIUM, 2, null);
        assertEquals("LIKE", likeCondition.operator());
        
        WhereCondition inCondition = new WhereCondition("users", "status", "IN", CardinalityLevel.LOW, 3, null);
        assertEquals("IN", inCondition.operator());
        
        WhereCondition gtCondition = new WhereCondition("orders", "amount", ">", CardinalityLevel.HIGH, 4, null);
        assertEquals(">", gtCondition.operator());
    }

    @Test
    void testWhereConditionWithDifferentPositions() {
        // When
        WhereCondition firstCondition = new WhereCondition("users", "id", "=", CardinalityLevel.HIGH, 1, null);
        WhereCondition secondCondition = new WhereCondition("users", "name", "=", CardinalityLevel.MEDIUM, 2, null);
        WhereCondition thirdCondition = new WhereCondition("users", "email", "=", CardinalityLevel.HIGH, 10, null);
        
        // Then
        assertEquals(1, firstCondition.position());
        assertEquals(2, secondCondition.position());
        assertEquals(10, thirdCondition.position());
        assertTrue(firstCondition.position() < secondCondition.position());
        assertTrue(secondCondition.position() < thirdCondition.position());
    }

    @Test
    void testWhereConditionWithNullValues() {
        // When
        WhereCondition condition = new WhereCondition(
            null, 
            null, 
            null, 
            CardinalityLevel.HIGH, 
            0, 
            null
        );
        
        // Then
        assertNull(condition.tableName());
        assertNull(condition.columnName());
        assertNull(condition.operator());
        assertEquals(CardinalityLevel.HIGH, condition.cardinality());
        assertEquals(0, condition.position());
        assertNull(condition.parameter());
    }

    @Test
    void testWhereConditionEquality() {
        // Given
        WhereCondition condition1 = new WhereCondition("users", "id", "=", CardinalityLevel.HIGH, 1, null);
        WhereCondition condition2 = new WhereCondition("users", "id", "=", CardinalityLevel.HIGH, 1, null);
        WhereCondition condition3 = new WhereCondition("users", "name", "=", CardinalityLevel.MEDIUM, 1, null);
        WhereCondition condition4 = new WhereCondition("products", "id", "=", CardinalityLevel.HIGH, 1, null);
        
        // When & Then
        assertEquals(condition1, condition2);
        assertNotEquals(condition1, condition3);
        assertNotEquals(condition1, condition4);
        assertEquals(condition1.hashCode(), condition2.hashCode());
    }

    @Test
    void testWhereConditionToString() {
        // Given
        WhereCondition condition = new WhereCondition(
            "users", 
            "id", 
            "=", 
            CardinalityLevel.HIGH, 
            1, 
            null
        );
        
        // When
        String toString = condition.toString();
        
        // Then
        assertNotNull(toString);
        assertTrue(toString.contains("users"));
        assertTrue(toString.contains("id"));
        assertTrue(toString.contains("="));
        assertTrue(toString.contains("HIGH"));
        assertTrue(toString.contains("1"));
    }

    @Test
    void testAllCardinalityLevels() {
        // When & Then - Test all cardinality levels
        WhereCondition highCondition = new WhereCondition("table", "col", "=", CardinalityLevel.HIGH, 1, null);
        assertTrue(highCondition.isHighCardinality());
        assertFalse(highCondition.isLowCardinality());
        
        WhereCondition mediumCondition = new WhereCondition("table", "col", "=", CardinalityLevel.MEDIUM, 1, null);
        assertFalse(mediumCondition.isHighCardinality());
        assertFalse(mediumCondition.isLowCardinality());
        
        WhereCondition lowCondition = new WhereCondition("table", "col", "=", CardinalityLevel.LOW, 1, null);
        assertFalse(lowCondition.isHighCardinality());
        assertTrue(lowCondition.isLowCardinality());
    }

    @Test
    void testCardinalityLevelCombinations() {
        // Test edge cases and combinations
        WhereCondition[] conditions = {
            new WhereCondition("users", "id", "=", CardinalityLevel.HIGH, 1, null),
            new WhereCondition("users", "status", "=", CardinalityLevel.LOW, 2, null),
            new WhereCondition("users", "category", "=", CardinalityLevel.MEDIUM, 3, null)
        };
        
        // Verify high cardinality
        assertTrue(conditions[0].isHighCardinality());
        assertFalse(conditions[0].isLowCardinality());
        
        // Verify low cardinality
        assertTrue(conditions[1].isLowCardinality());
        assertFalse(conditions[1].isHighCardinality());
        
        // Verify medium cardinality (neither high nor low)
        assertFalse(conditions[2].isHighCardinality());
        assertFalse(conditions[2].isLowCardinality());
    }

    @Test
    void testRecordComponentAccess() {
        // Given
        WhereCondition condition = new WhereCondition(
            "orders", 
            "customer_id", 
            "IN", 
            CardinalityLevel.MEDIUM, 
            5, 
            null
        );
        
        // When & Then - Test all record components
        assertEquals("orders", condition.tableName());
        assertEquals("customer_id", condition.columnName());
        assertEquals("IN", condition.operator());
        assertEquals(CardinalityLevel.MEDIUM, condition.cardinality());
        assertEquals(5, condition.position());
        assertNull(condition.parameter());
    }
}