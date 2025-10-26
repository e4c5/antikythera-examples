package sa.com.cloudsolutions.antikythera.examples;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;
import sa.com.cloudsolutions.antikythera.parser.Callable;
import org.mockito.Answers;
import sa.com.cloudsolutions.liquibase.Indexes;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for condition ordering rules regarding LOW -> MEDIUM scenarios.
 */
class QueryOrderingTest {

    private QueryAnalysisEngine engine;

    @BeforeEach
    void setup() {
        // Minimal index map: only a primary key on users.user_id
        Map<String, List<Indexes.IndexInfo>> indexMap = new HashMap<>();
        List<Indexes.IndexInfo> userIndexes = new ArrayList<>();
        userIndexes.add(new Indexes.IndexInfo("PRIMARY_KEY", "pk_users", Arrays.asList("user_id")));
        indexMap.put("users", userIndexes);

        CardinalityAnalyzer analyzer = new CardinalityAnalyzer(indexMap);
        engine = new QueryAnalysisEngine(analyzer);
    }

    @Test
    void testLowThenMediumShouldBeFlagged() throws Exception {
        String sql = "SELECT * FROM users WHERE is_active = ? AND name = ?";
        Statement stmt = CCJSqlParserUtil.parse(sql);

        RepositoryQuery repoQuery = mock(RepositoryQuery.class);
        // Provide minimal method metadata to avoid NPEs in QueryAnalysisEngine
        Callable callable = mock(Callable.class, Answers.RETURNS_DEEP_STUBS);
        when(callable.getCallableDeclaration().getNameAsString()).thenReturn("UserRepository");
        when(callable.getNameAsString()).thenReturn("findByNameAndIsActive");
        when(repoQuery.getMethodDeclaration()).thenReturn(callable);
        when(repoQuery.getTable()).thenReturn("users");

        when(repoQuery.getStatement()).thenReturn(stmt);
        when(repoQuery.getOriginalQuery()).thenReturn(sql);
        when(repoQuery.getMethodParameters()).thenReturn(Collections.emptyList());
        when(repoQuery.isNative()).thenReturn(true);

        QueryOptimizationResult result = engine.analyzeQuery(repoQuery);

        assertNotNull(result);
        assertFalse(result.isAlreadyOptimized(), "Query should not be marked optimized when LOW precedes MEDIUM");
        assertTrue(result.hasOptimizationIssues(), "An optimization issue should be reported");
        assertEquals(1, result.getOptimizationIssueCount());

        OptimizationIssue issue = result.getOptimizationIssues().get(0);
        assertEquals(OptimizationIssue.Severity.MEDIUM, issue.severity(), "LOW→MEDIUM should be MEDIUM severity");
        assertEquals("is_active", issue.currentFirstColumn());
        assertEquals("name", issue.recommendedFirstColumn());
    }
}
