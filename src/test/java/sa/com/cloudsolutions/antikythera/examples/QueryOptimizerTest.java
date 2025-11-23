package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
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
        doCallRealMethod().when(queryOptimizer).reorderMethodParameters(any(), any(), any());
    }

    @Test
    void testReorderMethodParameters_BasicReordering() {
        // Setup
        MethodDeclaration method = new MethodDeclaration();
        method.setName("findByFirstNameAndLastName");
        Parameter p1 = new Parameter(new ClassOrInterfaceType(null, "String"), "firstName");
        Parameter p2 = new Parameter(new ClassOrInterfaceType(null, "String"), "lastName");
        method.addParameter(p1);
        method.addParameter(p2);

        List<String> currentOrder = Arrays.asList("firstName", "lastName");
        List<String> recommendedOrder = Arrays.asList("lastName", "firstName");

        // Execute
        queryOptimizer.reorderMethodParameters(method, currentOrder, recommendedOrder);

        // Verify
        assertEquals(2, method.getParameters().size());
        assertEquals("lastName", method.getParameter(0).getNameAsString());
        assertEquals("firstName", method.getParameter(1).getNameAsString());
    }

    @Test
    void testReorderMethodParameters_NoChange() {
        MethodDeclaration method = new MethodDeclaration();
        Parameter p1 = new Parameter(new ClassOrInterfaceType(null, "String"), "a");
        Parameter p2 = new Parameter(new ClassOrInterfaceType(null, "String"), "b");
        method.addParameter(p1);
        method.addParameter(p2);

        List<String> currentOrder = Arrays.asList("a", "b");
        List<String> recommendedOrder = Arrays.asList("a", "b");

        queryOptimizer.reorderMethodParameters(method, currentOrder, recommendedOrder);

        assertEquals("a", method.getParameter(0).getNameAsString());
        assertEquals("b", method.getParameter(1).getNameAsString());
    }

    @Test
    void testReorderMethodParameters_SizeMismatch() {
        MethodDeclaration method = new MethodDeclaration();
        method.addParameter(new Parameter(new ClassOrInterfaceType(null, "String"), "a"));

        List<String> currentOrder = Arrays.asList("a", "b");
        List<String> recommendedOrder = Arrays.asList("b", "a");

        queryOptimizer.reorderMethodParameters(method, currentOrder, recommendedOrder);

        // Should not change because method params size (1) != current order size (2)
        assertEquals("a", method.getParameter(0).getNameAsString());
    }

    @Test
    void testReorderMethodParameters_PartialMatch() {
        // Case where recommended order has a column not in current order (should not
        // happen in valid flow, but good to test safety)
        MethodDeclaration method = new MethodDeclaration();
        Parameter p1 = new Parameter(new ClassOrInterfaceType(null, "String"), "a");
        Parameter p2 = new Parameter(new ClassOrInterfaceType(null, "String"), "b");
        method.addParameter(p1);
        method.addParameter(p2);

        List<String> currentOrder = Arrays.asList("a", "b");
        List<String> recommendedOrder = Arrays.asList("b", "c"); // 'c' is missing

        queryOptimizer.reorderMethodParameters(method, currentOrder, recommendedOrder);

        // Should not change because reorderedParams size won't match currentParams size
        assertEquals("a", method.getParameter(0).getNameAsString());
        assertEquals("b", method.getParameter(1).getNameAsString());
    }

    @Test
    void testReorderMethodParameters_Duplicates() {
        // Case with duplicate column names where order changes
        MethodDeclaration method = new MethodDeclaration();
        Parameter p1 = new Parameter(new ClassOrInterfaceType(null, "String"), "a1");
        Parameter p2 = new Parameter(new ClassOrInterfaceType(null, "String"), "b1");
        Parameter p3 = new Parameter(new ClassOrInterfaceType(null, "String"), "a2");
        method.addParameter(p1);
        method.addParameter(p2);
        method.addParameter(p3);

        List<String> currentOrder = Arrays.asList("a", "b", "a");
        List<String> recommendedOrder = Arrays.asList("a", "a", "b");

        queryOptimizer.reorderMethodParameters(method, currentOrder, recommendedOrder);

        // With current implementation:
        // 1. 'a' -> index 0 -> p1
        // 2. 'a' -> index 0 -> p1
        // 3. 'b' -> index 1 -> p2
        // Result: p1, p1, p2

        // Expected: p1, p3, p2 (or p3, p1, p2 - depending on which 'a' is which, but
        // definitely not p1 twice)

        // We assert that we have 3 unique parameters.
        assertEquals("a1", method.getParameter(0).getNameAsString());
        assertEquals("a2", method.getParameter(1).getNameAsString()); // This will fail if it's "a1"
        assertEquals("b1", method.getParameter(2).getNameAsString());
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
