package sa.com.cloudsolutions.antikythera.examples;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test suite specifically for ExpressionConditionExtractor to validate the visitor pattern implementation.
 */
class ExpressionConditionExtractorTest {

    @BeforeAll
    static void setUp() {
        CardinalityAnalyzer.setIndexMap(new HashMap<>());
    }

    private RepositoryQuery createMockRepositoryQuery() {
        RepositoryQuery mockRepositoryQuery = mock(RepositoryQuery.class);
        when(mockRepositoryQuery.getMethodParameters()).thenReturn(new ArrayList<>());
        when(mockRepositoryQuery.getPrimaryTable()).thenReturn("test_table");
        return mockRepositoryQuery;
    }

    @Test
    void testSimpleWhereClause() throws JSQLParserException {
        RepositoryQuery mockQuery = createMockRepositoryQuery();
        String sql = "SELECT * FROM users WHERE user_id = ? AND status = ?";
        Statement statement = CCJSqlParserUtil.parse(sql);
        when(mockQuery.getQuery()).thenReturn(sql);

        // Get the WHERE expression directly
        net.sf.jsqlparser.statement.select.Select selectStmt = (net.sf.jsqlparser.statement.select.Select) statement;
        net.sf.jsqlparser.statement.select.PlainSelect plainSelect = selectStmt.getPlainSelect();
        Expression whereExpr = plainSelect.getWhere();

        // Test the extractor directly
        ExpressionConditionExtractor extractor = new ExpressionConditionExtractor();
        List<WhereCondition> conditions = extractor.extractConditions(whereExpr);

        assertNotNull(conditions);
        assertEquals(2, conditions.size(), "Should extract 2 conditions");
        assertEquals("user_id", conditions.get(0).columnName());
        assertEquals("status", conditions.get(1).columnName());
    }

    @Test
    void testDeleteWhereClause() throws JSQLParserException {
        RepositoryQuery mockQuery = createMockRepositoryQuery();
        String sql = "DELETE FROM orders WHERE order_id = ? AND status = 'cancelled'";
        Statement statement = CCJSqlParserUtil.parse(sql);
        when(mockQuery.getQuery()).thenReturn(sql);

        // Get the WHERE expression from DELETE statement
        Delete deleteStmt = (Delete) statement;
        Expression whereExpr = deleteStmt.getWhere();

        // Test the extractor directly
        ExpressionConditionExtractor extractor = new ExpressionConditionExtractor();
        List<WhereCondition> conditions = extractor.extractConditions(whereExpr);

        assertNotNull(conditions);
        assertEquals(2, conditions.size(), "Should extract 2 conditions from DELETE");
        assertEquals("order_id", conditions.get(0).columnName());
        assertEquals("status", conditions.get(1).columnName());
    }
}

