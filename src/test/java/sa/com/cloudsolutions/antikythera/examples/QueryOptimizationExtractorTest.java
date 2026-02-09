package sa.com.cloudsolutions.antikythera.examples;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test suite for QueryOptimizationExtractor demonstrating recursive processing
 * of SELECT, UPDATE, DELETE statements with subqueries using StatementVisitorAdapter.
 */
class QueryOptimizationExtractorTest {

    @BeforeAll
    static void setUp()  {
        CardinalityAnalyzer.setIndexMap(new HashMap<>());
    }

    private RepositoryQuery createMockRepositoryQuery() {
        RepositoryQuery mockRepositoryQuery = mock(RepositoryQuery.class);
        when(mockRepositoryQuery.getMethodParameters()).thenReturn(new ArrayList<>());
        when(mockRepositoryQuery.getPrimaryTable()).thenReturn("test_table");
        return mockRepositoryQuery;
    }

    @Test
    void testSimpleSelectWithWhereClause() throws JSQLParserException {
        // Test a simple SELECT statement with WHERE clause
        RepositoryQuery mockRepositoryQuery = createMockRepositoryQuery();
        String sql = "SELECT * FROM users WHERE user_id = ? AND status = ?";
        Statement statement = CCJSqlParserUtil.parse(sql);
        when(mockRepositoryQuery.getStatement()).thenReturn(statement);
        when(mockRepositoryQuery.getQuery()).thenReturn(sql);

        List<WhereCondition> conditions = QueryOptimizationExtractor.extractWhereConditions(mockRepositoryQuery.getStatement());

        assertNotNull(conditions);
        assertEquals(2, conditions.size(), "Should extract 2 WHERE conditions");
        
        // Verify first condition
        WhereCondition firstCondition = conditions.get(0);
        assertEquals("user_id", firstCondition.columnName());
        assertEquals("=", firstCondition.operator());
        
        // Verify second condition
        WhereCondition secondCondition = conditions.get(1);
        assertEquals("status", secondCondition.columnName());
        assertEquals("=", secondCondition.operator());
    }

    @Test
    void testSelectWithSubquery() throws JSQLParserException {
        // Test SELECT with subquery in WHERE clause
        RepositoryQuery mockRepositoryQuery = createMockRepositoryQuery();
        String sql = "SELECT * FROM orders WHERE customer_id IN (SELECT id FROM customers WHERE active = ?)";
        Statement statement = CCJSqlParserUtil.parse(sql);
        when(mockRepositoryQuery.getStatement()).thenReturn(statement);
        when(mockRepositoryQuery.getQuery()).thenReturn(sql);

        List<WhereCondition> conditions = QueryOptimizationExtractor.extractWhereConditions(mockRepositoryQuery.getStatement());

        assertNotNull(conditions);
        // Should extract the IN condition from outer query
        assertFalse(conditions.isEmpty(), "Should extract IN condition from outer query");

        // Verify we got the IN condition from outer query
        boolean hasInCondition = conditions.stream()
                .anyMatch(c -> "customer_id".equals(c.columnName()) && "IN".equals(c.operator()));
        assertTrue(hasInCondition, "Should have IN condition from outer query");
    }

    @Test
    void testSelectWithJoin() throws JSQLParserException {
        // Test SELECT with JOIN and ON conditions
        // Updated: This test now verifies that WHERE and JOIN conditions are SEPARATED
        RepositoryQuery mockRepositoryQuery = createMockRepositoryQuery();
        String sql = "SELECT * FROM orders o JOIN customers c ON o.customer_id = c.id WHERE o.status = ?";
        Statement statement = CCJSqlParserUtil.parse(sql);
        when(mockRepositoryQuery.getStatement()).thenReturn(statement);
        when(mockRepositoryQuery.getQuery()).thenReturn(sql);

        // Extract WHERE conditions only (should not include JOIN ON)
        List<WhereCondition> whereConditions = QueryOptimizationExtractor.extractWhereConditions(mockRepositoryQuery.getStatement());

        assertNotNull(whereConditions);
        assertEquals(1, whereConditions.size(), "Should extract only WHERE conditions, not JOIN ON");

        // Verify we got ONLY the WHERE condition (status), not the JOIN condition
        boolean hasWhereCondition = whereConditions.stream()
                .anyMatch(c -> "status".equals(c.columnName()));
        assertTrue(hasWhereCondition, "Should have status condition from WHERE clause");
        
        // Verify JOIN conditions are NOT included in WHERE conditions
        boolean hasJoinCondition = whereConditions.stream()
                .anyMatch(c -> "customer_id".equals(c.columnName()) || "id".equals(c.columnName()));
        assertFalse(hasJoinCondition, "Should NOT have JOIN ON conditions in WHERE conditions list");
        
        // Now verify JOIN conditions can be extracted separately
        List<JoinCondition> joinConditions = QueryOptimizationExtractor.extractJoinConditions(statement);
        assertEquals(1, joinConditions.size(), "Should have 1 JOIN ON condition");
    }

    @Test
    void testUpdateStatement() throws JSQLParserException {
        // Test UPDATE statement with WHERE clause
        RepositoryQuery mockRepositoryQuery = createMockRepositoryQuery();
        String sql = "UPDATE users SET status = 'active' WHERE user_id = ? AND email = ?";
        Statement statement = CCJSqlParserUtil.parse(sql);
        when(mockRepositoryQuery.getStatement()).thenReturn(statement);
        when(mockRepositoryQuery.getQuery()).thenReturn(sql);

        List<WhereCondition> conditions = QueryOptimizationExtractor.extractWhereConditions(mockRepositoryQuery.getStatement());

        assertNotNull(conditions);
        assertEquals(2, conditions.size(), "Should extract 2 WHERE conditions from UPDATE");
        
        // Verify conditions
        boolean hasUserId = conditions.stream()
                .anyMatch(c -> "user_id".equals(c.columnName()));
        boolean hasEmail = conditions.stream()
                .anyMatch(c -> "email".equals(c.columnName()));
        
        assertTrue(hasUserId, "Should have user_id condition");
        assertTrue(hasEmail, "Should have email condition");
    }

    @Test
    void testDeleteStatement() throws JSQLParserException {
        // Test DELETE statement with WHERE clause
        RepositoryQuery mockRepositoryQuery = createMockRepositoryQuery();
        String sql = "DELETE FROM orders WHERE order_id = ? AND status = 'cancelled'";
        Statement statement = CCJSqlParserUtil.parse(sql);
        when(mockRepositoryQuery.getStatement()).thenReturn(statement);
        when(mockRepositoryQuery.getQuery()).thenReturn(sql);

        List<WhereCondition> conditions = QueryOptimizationExtractor.extractWhereConditions(mockRepositoryQuery.getStatement());

        assertNotNull(conditions);
        assertEquals(2, conditions.size(), "Should extract 2 WHERE conditions from DELETE");
        
        // Verify conditions
        boolean hasOrderId = conditions.stream()
                .anyMatch(c -> "order_id".equals(c.columnName()));
        boolean hasStatus = conditions.stream()
                .anyMatch(c -> "status".equals(c.columnName()));
        
        assertTrue(hasOrderId, "Should have order_id condition");
        assertTrue(hasStatus, "Should have status condition");
    }

    @Disabled("This falling foul of a bug in JSQL parser")
    @Test
    void testComplexQueryWithMultipleSubqueries() throws JSQLParserException {
        // Test complex query with nested subqueries
        RepositoryQuery mockRepositoryQuery = createMockRepositoryQuery();
        String sql = "SELECT * FROM orders o " +
                     "WHERE o.customer_id IN (SELECT c.id FROM customers c WHERE c.country = ?) " +
                     "AND o.product_id IN (SELECT p.id FROM products p WHERE p.category = ?)";
        Statement statement = CCJSqlParserUtil.parse(sql);
        when(mockRepositoryQuery.getStatement()).thenReturn(statement);
        when(mockRepositoryQuery.getQuery()).thenReturn(sql);

        List<WhereCondition> conditions = QueryOptimizationExtractor.extractWhereConditions(mockRepositoryQuery.getStatement());

        assertNotNull(conditions);
        // Should extract the IN conditions from outer query
        assertFalse(conditions.isEmpty(), "Should extract IN conditions from outer query");

        // Verify we have IN conditions from outer query
        long inConditionCount = conditions.stream()
                .filter(c -> "IN".equals(c.operator()))
                .count();
        assertEquals(2, inConditionCount, "Should have 2 IN conditions from outer query");
    }

    @Test
    void testBetweenOperator() throws JSQLParserException {
        // Test BETWEEN operator
        RepositoryQuery mockRepositoryQuery = createMockRepositoryQuery();
        String sql = "SELECT * FROM orders WHERE order_date BETWEEN ? AND ?";
        Statement statement = CCJSqlParserUtil.parse(sql);
        when(mockRepositoryQuery.getStatement()).thenReturn(statement);
        when(mockRepositoryQuery.getQuery()).thenReturn(sql);

        List<WhereCondition> conditions = QueryOptimizationExtractor.extractWhereConditions(mockRepositoryQuery.getStatement());

        assertNotNull(conditions);
        assertEquals(1, conditions.size(), "Should extract 1 BETWEEN condition");
        
        WhereCondition condition = conditions.get(0);
        assertEquals("order_date", condition.columnName());
        assertEquals("BETWEEN", condition.operator());
    }

    @Test
    void testOrConditions() throws JSQLParserException {
        // Test OR conditions
        RepositoryQuery mockRepositoryQuery = createMockRepositoryQuery();
        String sql = "SELECT * FROM users WHERE email = ? OR username = ?";
        Statement statement = CCJSqlParserUtil.parse(sql);
        when(mockRepositoryQuery.getStatement()).thenReturn(statement);
        when(mockRepositoryQuery.getQuery()).thenReturn(sql);

        List<WhereCondition> conditions = QueryOptimizationExtractor.extractWhereConditions(mockRepositoryQuery.getStatement());

        assertNotNull(conditions);
        assertEquals(2, conditions.size(), "Should extract both conditions from OR expression");
        
        boolean hasEmail = conditions.stream()
                .anyMatch(c -> "email".equals(c.columnName()));
        boolean hasUsername = conditions.stream()
                .anyMatch(c -> "username".equals(c.columnName()));
        
        assertTrue(hasEmail, "Should have email condition");
        assertTrue(hasUsername, "Should have username condition");
    }

    @Test
    void testIsNullOperator() throws JSQLParserException {
        // Test IS NULL operator
        RepositoryQuery mockRepositoryQuery = createMockRepositoryQuery();
        String sql = "SELECT * FROM users WHERE deleted_at IS NULL";
        Statement statement = CCJSqlParserUtil.parse(sql);
        when(mockRepositoryQuery.getStatement()).thenReturn(statement);
        when(mockRepositoryQuery.getQuery()).thenReturn(sql);

        List<WhereCondition> conditions = QueryOptimizationExtractor.extractWhereConditions(mockRepositoryQuery.getStatement());

        assertNotNull(conditions);
        assertEquals(1, conditions.size(), "Should extract 1 IS NULL condition");
        
        WhereCondition condition = conditions.get(0);
        assertEquals("deleted_at", condition.columnName());
        assertEquals("IS NULL", condition.operator());
    }

    @Test
    void testEmptyWhereClause() throws JSQLParserException {
        // Test query without WHERE clause
        RepositoryQuery mockRepositoryQuery = createMockRepositoryQuery();
        String sql = "SELECT * FROM users";
        Statement statement = CCJSqlParserUtil.parse(sql);
        when(mockRepositoryQuery.getStatement()).thenReturn(statement);
        when(mockRepositoryQuery.getQuery()).thenReturn(sql);

        List<WhereCondition> conditions = QueryOptimizationExtractor.extractWhereConditions(mockRepositoryQuery.getStatement());

        assertNotNull(conditions);
        assertEquals(0, conditions.size(), "Should extract no conditions from query without WHERE");
    }

    @Test
    void testUpdateWithoutFrom_usesTargetTableForWhereConditions() throws JSQLParserException {
        // Anonymized fish-themed query per requirement
        String sql = "UPDATE fish_ledger SET gill_count = :gillCount WHERE fish_id = :fishId";
        Statement statement = CCJSqlParserUtil.parse(sql);

        List<WhereCondition> whereConditions = QueryOptimizationExtractor.extractWhereConditions(statement);

        assertNotNull(whereConditions, "Where conditions should not be null");
        assertEquals(1, whereConditions.size(), "Should extract one WHERE condition");

        WhereCondition c = whereConditions.get(0);
        assertEquals("fish_ledger", c.getTableName(), "Table name should be resolved to the UPDATE target table");
        assertEquals("fish_id", c.getColumnName());
        assertEquals("=", c.getOperator());
    }

    @Test
    void testExtractWhereClauseText_Union() throws JSQLParserException {
        String sql = "SELECT * FROM users WHERE id = 1 UNION SELECT * FROM users WHERE id = 2";
        Statement statement = CCJSqlParserUtil.parse(sql);
        String whereClause = QueryOptimizationExtractor.extractWhereClauseText(statement);
        assertEquals("id = 1", whereClause);
    }

    @Test
    void testExtractWhereClauseText_ParenthesedSelect() throws JSQLParserException {
        String sql = "(SELECT * FROM users WHERE user_id = ?)";
        Statement statement = CCJSqlParserUtil.parse(sql);
        String whereClause = QueryOptimizationExtractor.extractWhereClauseText(statement);
        assertEquals("user_id = ?", whereClause);
    }

    @Test
    void testExtractWhereClauseText_ComplexNested() throws JSQLParserException {
        String sql = "(((SELECT * FROM users WHERE status = 'ACTIVE') UNION (SELECT * FROM users WHERE status = 'PENDING')))";
        Statement statement = CCJSqlParserUtil.parse(sql);
        String whereClause = QueryOptimizationExtractor.extractWhereClauseText(statement);
        assertEquals("status = 'ACTIVE'", whereClause);
    }

    @Test
    void testUnionWhereConditions() throws JSQLParserException {
        String sql = "SELECT * FROM table1 WHERE id = 1 UNION SELECT * FROM table2 WHERE id = 2";
        Statement statement = CCJSqlParserUtil.parse(sql);
        List<WhereCondition> conditions = QueryOptimizationExtractor.extractWhereConditions(statement);
        
        assertNotNull(conditions);
        assertEquals(2, conditions.size(), "Should extract WHERE conditions from both sides of UNION");
        
        assertTrue(conditions.stream().anyMatch(c -> "table1".equals(c.getTableName()) && "id".equals(c.columnName())));
        assertTrue(conditions.stream().anyMatch(c -> "table2".equals(c.getTableName()) && "id".equals(c.columnName())));
    }
}

