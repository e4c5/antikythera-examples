package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueryOptimizerArgumentReorderTest {

    private QueryAnalysisResult mockResult;
    private OptimizationIssue mockIssue;
    private RepositoryQuery mockOriginalQuery;
    private RepositoryQuery mockOptimizedQuery;
    private sa.com.cloudsolutions.antikythera.parser.Callable mockOldCallable;
    private sa.com.cloudsolutions.antikythera.parser.Callable mockNewCallable;

    @BeforeEach
    void setUp() {
        mockResult = mock(QueryAnalysisResult.class);
        mockIssue = mock(OptimizationIssue.class);
        mockOriginalQuery = mock(RepositoryQuery.class);
        mockOptimizedQuery = mock(RepositoryQuery.class);
        mockOldCallable = mock(sa.com.cloudsolutions.antikythera.parser.Callable.class);
        mockNewCallable = mock(sa.com.cloudsolutions.antikythera.parser.Callable.class);

        when(mockResult.getOptimizationIssue()).thenReturn(mockIssue);
        when(mockIssue.query()).thenReturn(mockOriginalQuery);
        when(mockIssue.optimizedQuery()).thenReturn(mockOptimizedQuery);
        when(mockOriginalQuery.getMethodDeclaration()).thenReturn(mockOldCallable);
        when(mockOptimizedQuery.getMethodDeclaration()).thenReturn(mockNewCallable);
    }

    @Test
    void testNameChangeVisitor_ReordersArguments() {
        // Setup original method: findByAAndB(String a, int b)
        MethodDeclaration oldMethod = new MethodDeclaration();
        oldMethod.addParameter(new Parameter(new ClassOrInterfaceType(null, "String"), "a"));
        oldMethod.addParameter(new Parameter(new ClassOrInterfaceType(null, "int"), "b"));
        when(mockOldCallable.asMethodDeclaration()).thenReturn(oldMethod);

        // Setup optimized method: findByBAndA(int b, String a)
        MethodDeclaration newMethod = new MethodDeclaration();
        newMethod.addParameter(new Parameter(new ClassOrInterfaceType(null, "int"), "b"));
        newMethod.addParameter(new Parameter(new ClassOrInterfaceType(null, "String"), "a"));
        when(mockNewCallable.asMethodDeclaration()).thenReturn(newMethod);

        when(mockResult.getMethodName()).thenReturn("findByAAndB");
        when(mockOptimizedQuery.getMethodName()).thenReturn("findByBAndA");
        
        // Setup column orders: a and b -> b and a (reordered)
        when(mockIssue.currentColumnOrder()).thenReturn(List.of("a", "b"));
        when(mockIssue.recommendedColumnOrder()).thenReturn(List.of("b", "a"));

        // Setup method call: repo.findByAAndB("valA", 123)
        MethodCallExpr call = new MethodCallExpr(new NameExpr("repo"), "findByAAndB");
        call.addArgument(new NameExpr("\"valA\""));
        call.addArgument(new NameExpr("123"));

        QueryOptimizer.NameChangeVisitor visitor = new QueryOptimizer.NameChangeVisitor("repo", "com.example.Repo");
        visitor.visit(call, mockResult);

        // Verify: repo.findByBAndA(123, "valA")
        assertEquals("findByBAndA", call.getNameAsString());
        assertEquals(2, call.getArguments().size());
        assertEquals("123", call.getArgument(0).toString());
        assertEquals("\"valA\"", call.getArgument(1).toString());
    }

    @Test
    void testBatchedNameChangeVisitor_ReordersArguments() {
        // Setup original method: findByXAndY(String x, String y)
        MethodDeclaration oldMethod = new MethodDeclaration();
        oldMethod.setName("findByXAndY");
        oldMethod.addParameter(new Parameter(new ClassOrInterfaceType(null, "String"), "x"));
        oldMethod.addParameter(new Parameter(new ClassOrInterfaceType(null, "String"), "y"));
        when(mockOldCallable.asMethodDeclaration()).thenReturn(oldMethod);

        // Setup optimized method: findByYAndX(String y, String x)
        MethodDeclaration newMethod = new MethodDeclaration();
        newMethod.setName("findByYAndX");
        newMethod.addParameter(new Parameter(new ClassOrInterfaceType(null, "String"), "y"));
        newMethod.addParameter(new Parameter(new ClassOrInterfaceType(null, "String"), "x"));
        when(mockNewCallable.asMethodDeclaration()).thenReturn(newMethod);
        
        // Setup column orders: x and y -> y and x (reordered)
        when(mockIssue.currentColumnOrder()).thenReturn(List.of("x", "y"));
        when(mockIssue.recommendedColumnOrder()).thenReturn(List.of("y", "x"));
        
        // Setup MethodRename
        QueryOptimizer.MethodRename rename = new QueryOptimizer.MethodRename(
            "findByXAndY", 
            "findByYAndX", 
            mockResult, 
            mockIssue
        );
        List<QueryOptimizer.MethodRename> renames = Collections.singletonList(rename);
        Set<String> fields = new HashSet<>();
        fields.add("repo");

        // Setup method call: repo.findByXAndY("valX", "valY")
        MethodCallExpr call = new MethodCallExpr(new NameExpr("repo"), "findByXAndY");
        call.addArgument(new NameExpr("\"valX\""));
        call.addArgument(new NameExpr("\"valY\""));

        QueryOptimizer.BatchedNameChangeProcessor processor = new QueryOptimizer.BatchedNameChangeProcessor(fields, renames);
        processor.processMethodCall(call);

        // Verify: repo.findByYAndX("valY", "valX")
        assertEquals("findByYAndX", call.getNameAsString());
        assertEquals(2, call.getArguments().size());
        assertEquals("\"valY\"", call.getArgument(0).toString());
        assertEquals("\"valX\"", call.getArgument(1).toString());
    }
}
