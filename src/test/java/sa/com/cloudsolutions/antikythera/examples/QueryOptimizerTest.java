package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;
import sa.com.cloudsolutions.antikythera.parser.converter.EntityMappingResolver;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QueryOptimizerTest {
    private QueryAnalysisResult mockResult;
    private RepositoryQuery mockOriginalQuery;
    private RepositoryQuery mockOptimizedQuery;
    private static File tempLiquibaseFile;

    @BeforeAll
    static void setUpAll() throws Exception {
        // Load settings to avoid NPE in QueryOptimizationChecker
        File settingsFile = new File("src/test/resources/generator.yml");
        if (settingsFile.exists()) {
            Settings.loadConfigMap(settingsFile);
        }

        tempLiquibaseFile = Files.createTempFile("db-changelog", ".xml").toFile();
        try (FileWriter fw = new FileWriter(tempLiquibaseFile)) {
            fw.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            fw.write("<databaseChangeLog\n");
            fw.write("    xmlns=\"http://www.liquibase.org/xml/ns/dbchangelog\"\n");
            fw.write("    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
            fw.write("    xsi:schemaLocation=\"http://www.liquibase.org/xml/ns/dbchangelog\n");
            fw.write("    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.0.xsd\">\n");
            fw.write("</databaseChangeLog>");
        }
        tempLiquibaseFile.deleteOnExit();
    }

    @BeforeEach
    void setUp() throws Exception {
        mockResult = mock(QueryAnalysisResult.class);
        mockOriginalQuery = mock(RepositoryQuery.class);
        mockOptimizedQuery = mock(RepositoryQuery.class);

        when(mockResult.getMethodName()).thenReturn("oldMethod");
        when(mockOptimizedQuery.getMethodName()).thenReturn("newMethod");

        // Mock original method (with one parameter to match test call sites)
        sa.com.cloudsolutions.antikythera.parser.Callable mockOldCallable = mock(
                sa.com.cloudsolutions.antikythera.parser.Callable.class);
        MethodDeclaration oldMd = new MethodDeclaration();
        oldMd.setName("oldMethod");
        oldMd.addParameter("String", "arg1");
        when(mockOldCallable.asMethodDeclaration()).thenReturn(oldMd);
        when(mockOriginalQuery.getMethodDeclaration()).thenReturn(mockOldCallable);

        // Mock optimized method (same single parameter — no reordering needed)
        sa.com.cloudsolutions.antikythera.parser.Callable mockNewCallable = mock(
                sa.com.cloudsolutions.antikythera.parser.Callable.class);
        MethodDeclaration newMd = new MethodDeclaration();
        newMd.setName("newMethod");
        newMd.addParameter("String", "arg1");
        when(mockNewCallable.asMethodDeclaration()).thenReturn(newMd);
        when(mockOptimizedQuery.getMethodDeclaration()).thenReturn(mockNewCallable);

        // Use a real OptimizationIssue so buildPositionMapping() works
        OptimizationIssue issue = new OptimizationIssue(mockOriginalQuery, List.of("arg1"),
                List.of("arg1"), "test", "test", mockOptimizedQuery);
        when(mockResult.getOptimizationIssue()).thenReturn(issue);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "WithThisExpression|class Service { void test() { this.repo.oldMethod(arg1); } }|this.repo.newMethod(arg1)",
        "WithMockitoVerifyAndThis|class ServiceTest { void test() { verify(this.repo).oldMethod(arg1); } }|verify(this.repo).newMethod(arg1)",
        "WithMethodReference|class Service { void test() { list.stream().map(repo::oldMethod).collect(Collectors.toList()); } }|repo::newMethod",
        "WithMethodReferenceAndThis|class Service { void test() { list.stream().map(this.repo::oldMethod).collect(Collectors.toList()); } }|this.repo::newMethod"
    })
    void testNameChangeVisitor(String testCase) {
        String[] parts = testCase.split("\\|");
        String code = parts[1];
        String expectedOutput = parts[2];

        CompilationUnit cu = StaticJavaParser.parse(code);
        QueryOptimizer.NameChangeVisitor visitor = new QueryOptimizer.NameChangeVisitor("repo", "com.example.Repo");
        cu.accept(visitor, mockResult);

        String updatedCode = cu.toString();
        assertTrue(updatedCode.contains(expectedOutput), 
            "Test " + parts[0] + " failed: expected '" + expectedOutput + "' in output");
    }
}
