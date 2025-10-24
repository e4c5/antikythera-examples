package sa.com.cloudsolutions.antikythera.examples;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.liquibase.Indexes;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CardinalityAnalyzer with various index configurations.
 * Tests cardinality analysis based on database metadata from Liquibase IndexInfo data.
 */
class CardinalityAnalyzerTest {
    
    private CardinalityAnalyzer analyzer;
    private Map<String, List<Indexes.IndexInfo>> indexMap;
    
    @BeforeEach
    void setUp() {
        indexMap = new HashMap<>();
        setupTestIndexes();
        analyzer = new CardinalityAnalyzer(indexMap);
    }
    
    /**
     * Sets up test index configurations for various scenarios.
     */
    private void setupTestIndexes() {
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
        
        // Unique index on username
        Indexes.IndexInfo uniqueUsername = new Indexes.IndexInfo("UNIQUE_INDEX", "uidx_users_username", Arrays.asList("username"));
        userIndexes.add(uniqueUsername);
        
        indexMap.put("users", userIndexes);
        
        // Orders table with composite indexes
        List<Indexes.IndexInfo> orderIndexes = new ArrayList<>();
        
        // Primary key
        Indexes.IndexInfo orderPrimaryKey = new Indexes.IndexInfo("PRIMARY_KEY", "pk_orders", Arrays.asList("order_id"));
        orderIndexes.add(orderPrimaryKey);
        
        // Composite index
        Indexes.IndexInfo compositeIndex = new Indexes.IndexInfo("INDEX", "idx_orders_customer_date", Arrays.asList("customer_id", "order_date"));
        orderIndexes.add(compositeIndex);
        
        indexMap.put("orders", orderIndexes);
        
        // Products table with minimal indexes
        List<Indexes.IndexInfo> productIndexes = new ArrayList<>();
        
        Indexes.IndexInfo productPrimaryKey = new Indexes.IndexInfo("PRIMARY_KEY", "pk_products", Arrays.asList("product_id"));
        productIndexes.add(productPrimaryKey);
        
        indexMap.put("products", productIndexes);
    }
    
    @Test
    void testAnalyzeColumnCardinality_PrimaryKey_ReturnsHigh() {
        CardinalityLevel result = analyzer.analyzeColumnCardinality("users", "user_id");
        assertEquals(CardinalityLevel.HIGH, result);
    }
    
    @Test
    void testAnalyzeColumnCardinality_UniqueConstraint_ReturnsHigh() {
        CardinalityLevel result = analyzer.analyzeColumnCardinality("users", "email");
        assertEquals(CardinalityLevel.HIGH, result);
    }
    
    @Test
    void testAnalyzeColumnCardinality_UniqueIndex_ReturnsHigh() {
        CardinalityLevel result = analyzer.analyzeColumnCardinality("users", "username");
        assertEquals(CardinalityLevel.HIGH, result);
    }
    
    @Test
    void testAnalyzeColumnCardinality_RegularIndex_ReturnsMedium() {
        CardinalityLevel result = analyzer.analyzeColumnCardinality("users", "name");
        assertEquals(CardinalityLevel.MEDIUM, result);
    }
    
    @Test
    void testAnalyzeColumnCardinality_BooleanColumn_ReturnsLow() {
        CardinalityLevel result = analyzer.analyzeColumnCardinality("users", "is_active");
        assertEquals(CardinalityLevel.LOW, result);
    }
    
    @Test
    void testAnalyzeColumnCardinality_UnindexedColumn_ReturnsLow() {
        CardinalityLevel result = analyzer.analyzeColumnCardinality("users", "description");
        assertEquals(CardinalityLevel.MEDIUM, result);
    }
    
    @Test
    void testAnalyzeColumnCardinality_CompositeIndex_ReturnsMedium() {
        CardinalityLevel customerIdResult = analyzer.analyzeColumnCardinality("orders", "customer_id");
        CardinalityLevel orderDateResult = analyzer.analyzeColumnCardinality("orders", "order_date");
        
        assertEquals(CardinalityLevel.MEDIUM, customerIdResult);
        assertEquals(CardinalityLevel.MEDIUM, orderDateResult);
    }
    
    @Test
    void testAnalyzeColumnCardinality_CaseInsensitive() {
        CardinalityLevel lowerCase = analyzer.analyzeColumnCardinality("users", "user_id");
        CardinalityLevel upperCase = analyzer.analyzeColumnCardinality("USERS", "USER_ID");
        CardinalityLevel mixedCase = analyzer.analyzeColumnCardinality("Users", "User_Id");
        
        assertEquals(CardinalityLevel.HIGH, lowerCase);
        assertEquals(CardinalityLevel.HIGH, upperCase);
        assertEquals(CardinalityLevel.HIGH, mixedCase);
    }
    
    @Test
    void testAnalyzeColumnCardinality_NullInputs_ReturnsLow() {
        CardinalityLevel nullTable = analyzer.analyzeColumnCardinality(null, "column");
        CardinalityLevel nullColumn = analyzer.analyzeColumnCardinality("table", null);
        CardinalityLevel bothNull = analyzer.analyzeColumnCardinality(null, null);
        
        assertEquals(CardinalityLevel.MEDIUM, nullTable);
        assertEquals(CardinalityLevel.MEDIUM, nullColumn);
        assertEquals(CardinalityLevel.MEDIUM, bothNull);
    }
    
    @Test
    void testAnalyzeColumnCardinality_UnknownTable_ReturnsLow() {
        CardinalityLevel result = analyzer.analyzeColumnCardinality("unknown_table", "some_column");
        assertEquals(CardinalityLevel.MEDIUM, result);
    }
    
    @Test
    void testIsPrimaryKey_ValidPrimaryKey_ReturnsTrue() {
        assertTrue(analyzer.isPrimaryKey("users", "user_id"));
        assertTrue(analyzer.isPrimaryKey("orders", "order_id"));
        assertTrue(analyzer.isPrimaryKey("products", "product_id"));
    }
    
    @Test
    void testIsPrimaryKey_NonPrimaryKey_ReturnsFalse() {
        assertFalse(analyzer.isPrimaryKey("users", "email"));
        assertFalse(analyzer.isPrimaryKey("users", "name"));
        assertFalse(analyzer.isPrimaryKey("orders", "customer_id"));
    }
    
    @Test
    void testIsPrimaryKey_UnknownTable_ReturnsFalse() {
        assertFalse(analyzer.isPrimaryKey("unknown_table", "id"));
    }
    
    @Test
    void testIsBooleanColumn_BooleanNamingPatterns_ReturnsTrue() {
        assertTrue(analyzer.isBooleanColumn("users", "is_active"));
        assertTrue(analyzer.isBooleanColumn("users", "has_permission"));
        assertTrue(analyzer.isBooleanColumn("users", "can_edit"));
        assertTrue(analyzer.isBooleanColumn("users", "should_notify"));
        assertTrue(analyzer.isBooleanColumn("users", "admin_flag"));
        assertTrue(analyzer.isBooleanColumn("users", "email_enabled"));
        assertTrue(analyzer.isBooleanColumn("users", "is_active"));
        assertTrue(analyzer.isBooleanColumn("users", "active"));
        assertTrue(analyzer.isBooleanColumn("users", "enabled"));
        assertTrue(analyzer.isBooleanColumn("users", "deleted"));
        assertTrue(analyzer.isBooleanColumn("users", "visible"));
    }
    
    @Test
    void testIsBooleanColumn_NonBooleanColumns_ReturnsFalse() {
        assertFalse(analyzer.isBooleanColumn("users", "user_id"));
        assertFalse(analyzer.isBooleanColumn("users", "email"));
        assertFalse(analyzer.isBooleanColumn("users", "name"));
        assertFalse(analyzer.isBooleanColumn("users", "created_date"));
        assertFalse(analyzer.isBooleanColumn("users", "description"));
    }
    
    @Test
    void testHasUniqueConstraint_UniqueConstraint_ReturnsTrue() {
        assertTrue(analyzer.hasUniqueConstraint("users", "email"));
    }
    
    @Test
    void testHasUniqueConstraint_UniqueIndex_ReturnsTrue() {
        assertTrue(analyzer.hasUniqueConstraint("users", "username"));
    }
    
    @Test
    void testHasUniqueConstraint_NonUniqueColumn_ReturnsFalse() {
        assertFalse(analyzer.hasUniqueConstraint("users", "name"));
        assertFalse(analyzer.hasUniqueConstraint("users", "user_id")); // Primary key, not unique constraint
    }
    
    @Test
    void testHasUniqueConstraint_UnknownTable_ReturnsFalse() {
        assertFalse(analyzer.hasUniqueConstraint("unknown_table", "email"));
    }
    
    @Test
    void testCardinalityPriority_PrimaryKeyOverUnique() {
        // Add a unique constraint on user_id to test priority
        List<Indexes.IndexInfo> userIndexes = indexMap.get("users");
        Indexes.IndexInfo uniqueUserId = new Indexes.IndexInfo("UNIQUE_CONSTRAINT", "uk_users_user_id", Arrays.asList("user_id"));
        userIndexes.add(uniqueUserId);
        
        // Primary key should take priority over unique constraint
        CardinalityLevel result = analyzer.analyzeColumnCardinality("users", "user_id");
        assertEquals(CardinalityLevel.HIGH, result);
        assertTrue(analyzer.isPrimaryKey("users", "user_id"));
    }
    
    @Test
    void testCardinalityPriority_UniqueOverRegularIndex() {
        // Add a regular index on email to test priority
        List<Indexes.IndexInfo> userIndexes = indexMap.get("users");
        Indexes.IndexInfo emailIndex = new Indexes.IndexInfo("INDEX", "idx_users_email", Arrays.asList("email"));
        userIndexes.add(emailIndex);
        
        // Unique constraint should take priority over regular index
        CardinalityLevel result = analyzer.analyzeColumnCardinality("users", "email");
        assertEquals(CardinalityLevel.HIGH, result);
        assertTrue(analyzer.hasUniqueConstraint("users", "email"));
    }
    
    @Test
    void testCardinalityPriority_BooleanOverIndex() {
        // Add a regular index on a boolean-named column
        List<Indexes.IndexInfo> userIndexes = indexMap.get("users");
        Indexes.IndexInfo activeIndex = new Indexes.IndexInfo("INDEX", "idx_users_is_active", Arrays.asList("is_active"));
        userIndexes.add(activeIndex);
        
        // Boolean naming should take priority over index (low cardinality)
        CardinalityLevel result = analyzer.analyzeColumnCardinality("users", "is_active");
        assertEquals(CardinalityLevel.LOW, result);
        assertTrue(analyzer.isBooleanColumn("users", "is_active"));
    }
    
    @Test
    void testEmptyIndexMap() {
        CardinalityAnalyzer emptyAnalyzer = new CardinalityAnalyzer(new HashMap<>());
        
        CardinalityLevel result = emptyAnalyzer.analyzeColumnCardinality("users", "user_id");
        assertEquals(CardinalityLevel.MEDIUM, result);
        
        assertFalse(emptyAnalyzer.isPrimaryKey("users", "user_id"));
        assertFalse(emptyAnalyzer.hasUniqueConstraint("users", "email"));
        
        // Boolean detection should still work (doesn't depend on indexes)
        assertTrue(emptyAnalyzer.isBooleanColumn("users", "is_active"));
    }
    
    @Test
    void testMultipleTablesIndependence() {
        // Test that analysis of one table doesn't affect another
        CardinalityLevel usersUserId = analyzer.analyzeColumnCardinality("users", "user_id");
        CardinalityLevel ordersUserId = analyzer.analyzeColumnCardinality("orders", "user_id");
        
        assertEquals(CardinalityLevel.HIGH, usersUserId); // Primary key in users table
        assertEquals(CardinalityLevel.MEDIUM, ordersUserId);  // No identifiable LOW/HIGH characteristics
    }
}
