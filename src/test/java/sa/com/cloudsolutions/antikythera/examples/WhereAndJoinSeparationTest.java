package sa.com.cloudsolutions.antikythera.examples;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for verifying that WHERE and JOIN conditions are properly separated.
 * This test demonstrates the core functionality requested in the problem statement.
 */
class WhereAndJoinSeparationTest {

    @BeforeAll
    static void setUp()  {
        CardinalityAnalyzer.setIndexMap(new HashMap<>());
    }

    @Test
    void testWhereConditionsOnlyExtractedFromWhereClause() throws JSQLParserException {
        // Test that WHERE conditions don't include JOIN ON conditions
        String sql = "SELECT * FROM orders o JOIN customers c ON o.customer_id = c.id WHERE o.status = ?";
        Statement statement = CCJSqlParserUtil.parse(sql);

        List<WhereCondition> whereConditions = QueryOptimizationExtractor.extractWhereConditions(statement);

        assertNotNull(whereConditions);
        assertEquals(1, whereConditions.size(), "Should only extract WHERE conditions, not JOIN ON");
        
        // Verify we only got the WHERE condition (status)
        WhereCondition condition = whereConditions.get(0);
        assertEquals("status", condition.columnName(), "Should have status from WHERE clause");
        assertEquals("=", condition.operator());
    }

    @Test
    void testJoinConditionsOnlyExtractedFromJoinClause() throws JSQLParserException {
        // Test that JOIN conditions don't include WHERE conditions
        String sql = "SELECT * FROM orders o JOIN customers c ON o.customer_id = c.id WHERE o.status = ?";
        Statement statement = CCJSqlParserUtil.parse(sql);

        List<JoinCondition> joinConditions = QueryOptimizationExtractor.extractJoinConditions(statement);

        assertNotNull(joinConditions);
        assertEquals(1, joinConditions.size(), "Should only extract JOIN ON conditions, not WHERE");
        
        // Verify we only got the JOIN condition (customer_id = id)
        JoinCondition condition = joinConditions.get(0);
        assertTrue("customer_id".equals(condition.leftColumn()) || "customer_id".equals(condition.rightColumn()),
                "Should have customer_id from JOIN ON");
        assertTrue("id".equals(condition.leftColumn()) || "id".equals(condition.rightColumn()),
                "Should have id from JOIN ON");
        assertEquals("=", condition.operator());
    }

    @Test
    void testExtractAllConditionsSeparately() throws JSQLParserException {
        // Test the convenience method that returns both
        String sql = "SELECT * FROM orders o " +
                     "JOIN customers c ON o.customer_id = c.id " +
                     "WHERE o.status = ? AND o.amount > ?";
        Statement statement = CCJSqlParserUtil.parse(sql);

        QueryOptimizationExtractor.ConditionExtractionResult result = 
            QueryOptimizationExtractor.extractAllConditions(statement);

        assertNotNull(result);
        
        // Verify WHERE conditions
        List<WhereCondition> whereConditions = result.whereConditions();
        assertEquals(2, whereConditions.size(), "Should have 2 WHERE conditions");
        assertTrue(whereConditions.stream().anyMatch(c -> "status".equals(c.columnName())));
        assertTrue(whereConditions.stream().anyMatch(c -> "amount".equals(c.columnName())));
        
        // Verify JOIN conditions
        List<JoinCondition> joinConditions = result.joinConditions();
        assertEquals(1, joinConditions.size(), "Should have 1 JOIN condition");
        JoinCondition joinCondition = joinConditions.get(0);
        assertTrue("customer_id".equals(joinCondition.leftColumn()) || "customer_id".equals(joinCondition.rightColumn()));
    }

    @Test
    void testMultipleJoins() throws JSQLParserException {
        // Test query with multiple joins
        String sql = "SELECT * FROM orders o " +
                     "JOIN customers c ON o.customer_id = c.id " +
                     "JOIN products p ON o.product_id = p.id " +
                     "WHERE o.status = ?";
        Statement statement = CCJSqlParserUtil.parse(sql);

        List<WhereCondition> whereConditions = QueryOptimizationExtractor.extractWhereConditions(statement);
        List<JoinCondition> joinConditions = QueryOptimizationExtractor.extractJoinConditions(statement);

        // Should have 1 WHERE condition
        assertEquals(1, whereConditions.size(), "Should have 1 WHERE condition");
        assertEquals("status", whereConditions.get(0).columnName());
        
        // Should have 2 JOIN conditions
        assertEquals(2, joinConditions.size(), "Should have 2 JOIN conditions");
    }

    @Test
    void testQueryWithoutJoins() throws JSQLParserException {
        // Test query without joins - should have no join conditions
        String sql = "SELECT * FROM orders WHERE status = ? AND amount > ?";
        Statement statement = CCJSqlParserUtil.parse(sql);

        List<WhereCondition> whereConditions = QueryOptimizationExtractor.extractWhereConditions(statement);
        List<JoinCondition> joinConditions = QueryOptimizationExtractor.extractJoinConditions(statement);

        assertEquals(2, whereConditions.size(), "Should have 2 WHERE conditions");
        assertEquals(0, joinConditions.size(), "Should have 0 JOIN conditions");
    }

    @Test
    void testQueryWithoutWhereClause() throws JSQLParserException {
        // Test query without WHERE clause - should have no where conditions
        String sql = "SELECT * FROM orders o JOIN customers c ON o.customer_id = c.id";
        Statement statement = CCJSqlParserUtil.parse(sql);

        List<WhereCondition> whereConditions = QueryOptimizationExtractor.extractWhereConditions(statement);
        List<JoinCondition> joinConditions = QueryOptimizationExtractor.extractJoinConditions(statement);

        assertEquals(0, whereConditions.size(), "Should have 0 WHERE conditions");
        assertEquals(1, joinConditions.size(), "Should have 1 JOIN condition");
    }

    @Test
    void testUpdateStatementWithJoin() throws JSQLParserException {
        // Test UPDATE with JOIN
        String sql = "UPDATE orders o JOIN customers c ON o.customer_id = c.id " +
                     "SET o.status = 'shipped' WHERE c.country = ?";
        Statement statement = CCJSqlParserUtil.parse(sql);

        List<WhereCondition> whereConditions = QueryOptimizationExtractor.extractWhereConditions(statement);
        List<JoinCondition> joinConditions = QueryOptimizationExtractor.extractJoinConditions(statement);

        assertEquals(1, whereConditions.size(), "Should have 1 WHERE condition");
        assertEquals("country", whereConditions.get(0).columnName());
        
        assertEquals(1, joinConditions.size(), "Should have 1 JOIN condition");
    }

    @Test
    void testDeleteStatementWithJoin() throws JSQLParserException {
        // Test DELETE with JOIN
        String sql = "DELETE o FROM orders o JOIN customers c ON o.customer_id = c.id WHERE c.status = ?";
        Statement statement = CCJSqlParserUtil.parse(sql);

        List<WhereCondition> whereConditions = QueryOptimizationExtractor.extractWhereConditions(statement);
        List<JoinCondition> joinConditions = QueryOptimizationExtractor.extractJoinConditions(statement);

        assertEquals(1, whereConditions.size(), "Should have 1 WHERE condition");
        assertEquals("status", whereConditions.get(0).columnName());
        
        assertEquals(1, joinConditions.size(), "Should have 1 JOIN condition");
    }

    @Test
    void testJoinConditionStructure() throws JSQLParserException {
        // Verify the structure of JoinCondition
        String sql = "SELECT * FROM orders o JOIN customers c ON o.customer_id = c.id";
        Statement statement = CCJSqlParserUtil.parse(sql);

        List<JoinCondition> joinConditions = QueryOptimizationExtractor.extractJoinConditions(statement);

        assertEquals(1, joinConditions.size());
        JoinCondition condition = joinConditions.get(0);
        
        // Verify all fields are populated
        assertNotNull(condition.leftTable(), "Left table should be populated");
        assertNotNull(condition.leftColumn(), "Left column should be populated");
        assertNotNull(condition.rightTable(), "Right table should be populated");
        assertNotNull(condition.rightColumn(), "Right column should be populated");
        assertNotNull(condition.operator(), "Operator should be populated");
        assertEquals("=", condition.operator(), "JOIN typically uses = operator");
    }
}
