package sa.com.cloudsolutions.antikythera.examples;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import sa.com.cloudsolutions.liquibase.Indexes;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CardinalityAnalyzer with various index configurations.
 * Tests cardinality analysis based on database metadata from Liquibase IndexInfo data.
 */
class CardinalityAnalyzerTest {
    private Map<String, Set<Indexes.IndexInfo>> indexMap;

    @BeforeEach
    void setUp() {
        indexMap = new HashMap<>();
        setupTestIndexes();
        CardinalityAnalyzer.setIndexMap(indexMap);
    }

    /**
     * Sets up test index configurations for various scenarios.
     */
    private void setupTestIndexes() {
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

        // Unique index on username
        Indexes.IndexInfo uniqueUsername = new Indexes.IndexInfo("UNIQUE_INDEX", "uidx_users_username", Arrays.asList("username"));
        userIndexes.add(uniqueUsername);

        indexMap.put("users", userIndexes);

        // Orders table with composite indexes
        Set<Indexes.IndexInfo> orderIndexes = new HashSet<>();

        // Primary key
        Indexes.IndexInfo orderPrimaryKey = new Indexes.IndexInfo("PRIMARY_KEY", "pk_orders", Arrays.asList("order_id"));
        orderIndexes.add(orderPrimaryKey);

        // Composite index
        Indexes.IndexInfo compositeIndex = new Indexes.IndexInfo("INDEX", "idx_orders_customer_date", Arrays.asList("customer_id", "order_date"));
        orderIndexes.add(compositeIndex);

        indexMap.put("orders", orderIndexes);

        // Products table with minimal indexes
        Set<Indexes.IndexInfo> productIndexes = new HashSet<>();

        Indexes.IndexInfo productPrimaryKey = new Indexes.IndexInfo("PRIMARY_KEY", "pk_products", Arrays.asList("product_id"));
        productIndexes.add(productPrimaryKey);

        indexMap.put("products", productIndexes);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "PrimaryKey_ReturnsHigh, users, user_id, HIGH",
        "UniqueConstraint_ReturnsHigh, users, email, HIGH",
        "UniqueIndex_ReturnsHigh, users, username, HIGH",
        "RegularIndex_ReturnsMedium, users, name, MEDIUM",
        "BooleanColumn_ReturnsLow, users, is_active, LOW",
        "UnindexedColumn_ReturnsMedium, users, description, MEDIUM"
    })
    void testAnalyzeColumnCardinality(String testName, String table, String column, CardinalityLevel expected) {
        CardinalityLevel result = CardinalityAnalyzer.analyzeColumnCardinality(table, column);
        assertEquals(expected, result);
    }

    @Test
    void testAnalyzeColumnCardinality_CompositeIndex_ReturnsMedium() {
        CardinalityLevel customerIdResult = CardinalityAnalyzer.analyzeColumnCardinality("orders", "customer_id");
        CardinalityLevel orderDateResult = CardinalityAnalyzer.analyzeColumnCardinality("orders", "order_date");

        assertEquals(CardinalityLevel.MEDIUM, customerIdResult);
        assertEquals(CardinalityLevel.MEDIUM, orderDateResult);
    }

    @Test
    void testAnalyzeColumnCardinality_CaseInsensitive() {
        CardinalityLevel lowerCase = CardinalityAnalyzer.analyzeColumnCardinality("users", "user_id");
        CardinalityLevel upperCase = CardinalityAnalyzer.analyzeColumnCardinality("USERS", "USER_ID");
        CardinalityLevel mixedCase = CardinalityAnalyzer.analyzeColumnCardinality("Users", "User_Id");

        assertEquals(CardinalityLevel.HIGH, lowerCase);
        assertEquals(CardinalityLevel.HIGH, upperCase);
        assertEquals(CardinalityLevel.HIGH, mixedCase);
    }

    @Test
    void testAnalyzeColumnCardinality_NullInputs_ReturnsLow() {
        CardinalityLevel nullTable = CardinalityAnalyzer.analyzeColumnCardinality(null, "column");
        CardinalityLevel nullColumn = CardinalityAnalyzer.analyzeColumnCardinality("table", null);
        CardinalityLevel bothNull = CardinalityAnalyzer.analyzeColumnCardinality(null, null);

        assertEquals(CardinalityLevel.MEDIUM, nullTable);
        assertEquals(CardinalityLevel.MEDIUM, nullColumn);
        assertEquals(CardinalityLevel.MEDIUM, bothNull);
    }

    @Test
    void testAnalyzeColumnCardinality_UnknownTable_ReturnsLow() {
        CardinalityLevel result = CardinalityAnalyzer.analyzeColumnCardinality("unknown_table", "some_column");
        assertEquals(CardinalityLevel.MEDIUM, result);
    }

    @Test
    void testIsPrimaryKey_ValidPrimaryKey_ReturnsTrue() {
        assertTrue(CardinalityAnalyzer.isPrimaryKey("users", "user_id"));
        assertTrue(CardinalityAnalyzer.isPrimaryKey("orders", "order_id"));
        assertTrue(CardinalityAnalyzer.isPrimaryKey("products", "product_id"));
    }

    @Test
    void testIsPrimaryKey_NonPrimaryKey_ReturnsFalse() {
        assertFalse(CardinalityAnalyzer.isPrimaryKey("users", "email"));
        assertFalse(CardinalityAnalyzer.isPrimaryKey("users", "name"));
        assertFalse(CardinalityAnalyzer.isPrimaryKey("orders", "customer_id"));
    }

    @Test
    void testIsPrimaryKey_UnknownTable_ReturnsFalse() {
        assertFalse(CardinalityAnalyzer.isPrimaryKey("unknown_table", "id"));
    }

    @Test
    void testIsBooleanColumn_BooleanNamingPatterns_ReturnsTrue() {
        assertTrue(CardinalityAnalyzer.isBooleanColumn("is_active"));
        assertTrue(CardinalityAnalyzer.isBooleanColumn("has_permission"));
        assertTrue(CardinalityAnalyzer.isBooleanColumn("can_edit"));
        assertTrue(CardinalityAnalyzer.isBooleanColumn("should_notify"));
        assertTrue(CardinalityAnalyzer.isBooleanColumn("admin_flag"));
        assertTrue(CardinalityAnalyzer.isBooleanColumn("email_enabled"));
        assertTrue(CardinalityAnalyzer.isBooleanColumn("is_active"));
        assertTrue(CardinalityAnalyzer.isBooleanColumn("active"));
        assertTrue(CardinalityAnalyzer.isBooleanColumn("enabled"));
        assertTrue(CardinalityAnalyzer.isBooleanColumn("deleted"));
        assertTrue(CardinalityAnalyzer.isBooleanColumn("visible"));
    }

    @Test
    void testIsBooleanColumn_NonBooleanColumns_ReturnsFalse() {
        assertFalse(CardinalityAnalyzer.isBooleanColumn("user_id"));
        assertFalse(CardinalityAnalyzer.isBooleanColumn("email"));
        assertFalse(CardinalityAnalyzer.isBooleanColumn("name"));
        assertFalse(CardinalityAnalyzer.isBooleanColumn("created_date"));
        assertFalse(CardinalityAnalyzer.isBooleanColumn("description"));
    }

    @Test
    void testHasUniqueConstraint_UniqueConstraint_ReturnsTrue() {
        assertTrue(CardinalityAnalyzer.hasUniqueConstraint("users", "email"));
    }

    @Test
    void testHasUniqueConstraint_UniqueIndex_ReturnsTrue() {
        assertTrue(CardinalityAnalyzer.hasUniqueConstraint("users", "username"));
    }

    @Test
    void testHasUniqueConstraint_NonUniqueColumn_ReturnsFalse() {
        assertFalse(CardinalityAnalyzer.hasUniqueConstraint("users", "name"));
        assertFalse(CardinalityAnalyzer.hasUniqueConstraint("users", "user_id")); // Primary key, not unique constraint
    }

    @Test
    void testHasUniqueConstraint_UnknownTable_ReturnsFalse() {
        assertFalse(CardinalityAnalyzer.hasUniqueConstraint("unknown_table", "email"));
    }

    @Test
    void testCardinalityPriority_PrimaryKeyOverUnique() {
        // Add a unique constraint on user_id to test priority
        Set<Indexes.IndexInfo> userIndexes = indexMap.get("users");
        Indexes.IndexInfo uniqueUserId = new Indexes.IndexInfo("UNIQUE_CONSTRAINT", "uk_users_user_id", Arrays.asList("user_id"));
        userIndexes.add(uniqueUserId);

        // Primary key should take priority over unique constraint
        CardinalityLevel result = CardinalityAnalyzer.analyzeColumnCardinality("users", "user_id");
        assertEquals(CardinalityLevel.HIGH, result);
        assertTrue(CardinalityAnalyzer.isPrimaryKey("users", "user_id"));
    }

    @Test
    void testCardinalityPriority_UniqueOverRegularIndex() {
        // Add a regular index on email to test priority
        Set<Indexes.IndexInfo> userIndexes = indexMap.get("users");
        Indexes.IndexInfo emailIndex = new Indexes.IndexInfo("INDEX", "idx_users_email", Arrays.asList("email"));
        userIndexes.add(emailIndex);

        // Unique constraint should take priority over regular index
        CardinalityLevel result = CardinalityAnalyzer.analyzeColumnCardinality("users", "email");
        assertEquals(CardinalityLevel.HIGH, result);
        assertTrue(CardinalityAnalyzer.hasUniqueConstraint("users", "email"));
    }

    @Test
    void testCardinalityPriority_BooleanOverIndex() {
        // Add a regular index on a boolean-named column
        Set<Indexes.IndexInfo> userIndexes = indexMap.get("users");
        Indexes.IndexInfo activeIndex = new Indexes.IndexInfo("INDEX", "idx_users_is_active", Arrays.asList("is_active"));
        userIndexes.add(activeIndex);

        // Boolean naming should take priority over index (low cardinality)
        CardinalityLevel result = CardinalityAnalyzer.analyzeColumnCardinality("users", "is_active");
        assertEquals(CardinalityLevel.LOW, result);
        assertTrue(CardinalityAnalyzer.isBooleanColumn("is_active"));
    }

    @Test
    void testEmptyIndexMap() {
        // Test with a completely unknown table (not in index map)
        CardinalityLevel result = CardinalityAnalyzer.analyzeColumnCardinality("unknown_table", "some_column");
        assertEquals(CardinalityLevel.MEDIUM, result);

        assertFalse(CardinalityAnalyzer.isPrimaryKey("unknown_table", "some_column"));
        assertFalse(CardinalityAnalyzer.hasUniqueConstraint("unknown_table", "some_column"));

        // Boolean detection should still work (doesn't depend on indexes)
        assertTrue(CardinalityAnalyzer.isBooleanColumn("is_active"));
    }

    @Test
    void testMultipleTablesIndependence() {
        // Test that analysis of one table doesn't affect another
        CardinalityLevel usersUserId = CardinalityAnalyzer.analyzeColumnCardinality("users", "user_id");
        CardinalityLevel ordersUserId = CardinalityAnalyzer.analyzeColumnCardinality("orders", "user_id");
        
        assertEquals(CardinalityLevel.HIGH, usersUserId); // Primary key in users table
        assertEquals(CardinalityLevel.MEDIUM, ordersUserId);  // No identifiable LOW/HIGH characteristics
    }
}
