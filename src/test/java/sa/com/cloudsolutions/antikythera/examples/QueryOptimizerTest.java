package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QueryOptimizerTest {

    private QueryOptimizer optimizer;
    private QueryAnalysisResult mockResult;
    private OptimizationIssue mockIssue;
    private RepositoryQuery mockOptimizedQuery;

    @BeforeEach
    void setUp() {
        optimizer = new QueryOptimizer();
        mockResult = mock(QueryAnalysisResult.class);
        mockIssue = mock(OptimizationIssue.class);
        mockOptimizedQuery = mock(RepositoryQuery.class);

        when(mockResult.getOptimizationIssue()).thenReturn(mockIssue);
        when(mockIssue.optimizedQuery()).thenReturn(mockOptimizedQuery);
        when(mockResult.getMethodName()).thenReturn("oldMethod");
        when(mockOptimizedQuery.getMethodName()).thenReturn("newMethod");
    }

    @Test
    void testNameChangeVisitor_WithThisExpression() {
        String code = "class Service { " +
                "  void test() { " +
                "    this.repo.oldMethod(arg1); " +
                "  } " +
                "}";
        CompilationUnit cu = StaticJavaParser.parse(code);

        QueryOptimizer.NameChangeVisitor visitor = optimizer.new NameChangeVisitor("repo");
        cu.accept(visitor, mockResult);

        String updatedCode = cu.toString();
        assertTrue(updatedCode.contains("this.repo.newMethod(arg1)"));
    }

    @Test
    void testNameChangeVisitor_WithMockitoVerifyAndThis() {
        String code = "class ServiceTest { " +
                "  void test() { " +
                "    verify(this.repo).oldMethod(arg1); " +
                "  } " +
                "}";
        CompilationUnit cu = StaticJavaParser.parse(code);

        QueryOptimizer.NameChangeVisitor visitor = optimizer.new NameChangeVisitor("repo");
        cu.accept(visitor, mockResult);

        String updatedCode = cu.toString();
        assertTrue(updatedCode.contains("verify(this.repo).newMethod(arg1)"));
    }

    @Test
    void testNameChangeVisitor_WithMethodReference() {
        String code = "class Service { " +
                "  void test() { " +
                "    list.stream().map(repo::oldMethod).collect(Collectors.toList()); " +
                "  } " +
                "}";
        CompilationUnit cu = StaticJavaParser.parse(code);

        QueryOptimizer.NameChangeVisitor visitor = optimizer.new NameChangeVisitor("repo");
        cu.accept(visitor, mockResult);

        String updatedCode = cu.toString();
        assertTrue(updatedCode.contains("repo::newMethod"));
    }

    @Test
    void testNameChangeVisitor_WithMethodReferenceAndThis() {
        String code = "class Service { " +
                "  void test() { " +
                "    list.stream().map(this.repo::oldMethod).collect(Collectors.toList()); " +
                "  } " +
                "}";
        CompilationUnit cu = StaticJavaParser.parse(code);

        QueryOptimizer.NameChangeVisitor visitor = optimizer.new NameChangeVisitor("repo");
        cu.accept(visitor, mockResult);

        String updatedCode = cu.toString();
        assertTrue(updatedCode.contains("this.repo::newMethod"));
    }
}
