package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;

class QueryOptimizerTest {

    private QueryOptimizer queryOptimizer;

    @BeforeEach
    void setUp() {
        // Mock the class to bypass the constructor which has heavy side effects
        queryOptimizer = mock(QueryOptimizer.class);
        // Tell Mockito to call the real implementation of reorderMethodParameters
        doCallRealMethod().when(queryOptimizer).reorderMethodParameters(any(), any());
    }


    @Test
    void testReorderMethodArguments_Duplicates() {
        // Setup MethodCallExpr
        MethodCallExpr mce = new MethodCallExpr();
        mce.setName("findByAAndBAndA");
        mce.addArgument(new NameExpr("arg1")); // corresponds to first 'a'
        mce.addArgument(new NameExpr("arg2")); // corresponds to 'b'
        mce.addArgument(new NameExpr("arg3")); // corresponds to second 'a'

        // Setup NameChangeVisitor
        // We need a dummy field name and repo FQN, but they only matter for the visit
        // check
        String fieldName = "repo";
        String repoFqn = "com.example.Repo";

        // We need to mock the scope to match the field name
        mce.setScope(new NameExpr(fieldName));

        QueryOptimizer.NameChangeVisitor visitor = new QueryOptimizer.NameChangeVisitor(fieldName, repoFqn);

        // Mock QueryAnalysisResult and OptimizationIssue
        QueryAnalysisResult result = mock(QueryAnalysisResult.class);
        OptimizationIssue issue = mock(OptimizationIssue.class);
        RepositoryQuery optimizedQuery = mock(RepositoryQuery.class);

        Mockito.when(result.getMethodName()).thenReturn("findByAAndBAndA");
        Mockito.when(result.getOptimizationIssue()).thenReturn(issue);
        Mockito.when(issue.optimizedQuery()).thenReturn(optimizedQuery);
        Mockito.when(optimizedQuery.getMethodName()).thenReturn("findByAAndBAndA"); // Name doesn't change

        List<String> currentOrder = Arrays.asList("a", "b", "a");
        List<String> recommendedOrder = Arrays.asList("a", "a", "b");

        Mockito.when(issue.currentColumnOrder()).thenReturn(currentOrder);
        Mockito.when(issue.recommendedColumnOrder()).thenReturn(recommendedOrder);

        // Execute
        visitor.visit(mce, result);

        // Verify
        // Expected: arg1, arg3, arg2
        // Actual (with bug): arg1, arg1, arg2

        assertEquals(3, mce.getArguments().size());
        assertEquals("arg1", mce.getArgument(0).asNameExpr().getNameAsString());
        assertEquals("arg3", mce.getArgument(1).asNameExpr().getNameAsString());
        assertEquals("arg2", mce.getArgument(2).asNameExpr().getNameAsString());
    }
}
