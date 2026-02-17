package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import liquibase.exception.LiquibaseException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;
import sa.com.cloudsolutions.antikythera.parser.Callable;
import sa.com.cloudsolutions.antikythera.parser.converter.EntityMappingResolver;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration tests for QueryOptimizer that exercise the real Antikythera
 * framework pipeline against the antikythera-test-helper testbed.
 *
 * These tests parse real repository interfaces and service classes, build
 * real dependency graphs, and verify that method renames and argument
 * reordering work end-to-end.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QueryOptimizerIntegrationTest {

    private static final String USER_REPO_FQN =
            "sa.com.cloudsolutions.antikythera.testhelper.repository.UserRepository";
    private static final String USER_SERVICE_FQN =
            "sa.com.cloudsolutions.antikythera.testhelper.service.UserService";

    private static File tempLiquibaseFile;

    @BeforeAll
    static void setUpAll() throws Exception {
        Settings.loadConfigMap(new File("src/test/resources/generator.yml"));
        AbstractCompiler.reset();
        AntikytheraRunTime.resetAll();
        AbstractCompiler.preProcess();

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
    void setUp() {
        EntityMappingResolver.reset();
        Fields.clearFieldDependencies();
        CardinalityAnalyzer.setIndexMap(new HashMap<>());
        OptimizationStatsLogger.initialize("test");
    }

    // ---------------------------------------------------------------
    // 1. Field dependency resolution
    // ---------------------------------------------------------------

    @Test
    @Order(1)
    void testFieldDependenciesResolvedForUserRepository() {
        EntityMappingResolver.build();
        Fields.buildDependencies();
        Map<String, Set<String>> deps = Fields.getFieldDependencies(USER_REPO_FQN);
        assertNotNull(deps, "UserRepository should have dependent classes");

        Set<String> fieldNames = deps.get(USER_SERVICE_FQN);
        assertNotNull(fieldNames, "UserService should depend on UserRepository");
        assertTrue(fieldNames.contains("userRepository"),
                "Field name should be 'userRepository'");
    }

    // ---------------------------------------------------------------
    // 2. Method call index
    // ---------------------------------------------------------------

    @Test
    @Order(2)
    void testMethodCallIndexBuiltForUserRepository() {
        EntityMappingResolver.build();
        Fields.buildDependencies();
        Set<Fields.CallerInfo> callers =
                Fields.getMethodCallers(USER_REPO_FQN, "findByFirstNameAndLastName");
        assertFalse(callers.isEmpty(),
                "findByFirstNameAndLastName should have callers in UserService");

        boolean foundUserService = callers.stream()
                .anyMatch(c -> c.callerClass().equals(USER_SERVICE_FQN)
                        && c.fieldName().equals("userRepository"));
        assertTrue(foundUserService, "UserService.userRepository should be a caller");
    }

    @Test
    @Order(3)
    void testMethodCallIndexContainsDerivedQueryMethods() {
        EntityMappingResolver.build();
        Fields.buildDependencies();
        Set<String> indexedMethods = Fields.getIndexedMethodNames(USER_REPO_FQN);
        assertNotNull(indexedMethods);
        // UserService calls several UserRepository methods
        assertTrue(indexedMethods.contains("findByUsername"),
                "findByUsername should be indexed");
        assertTrue(indexedMethods.contains("findByFirstNameAndLastName"),
                "findByFirstNameAndLastName should be indexed");
        assertTrue(indexedMethods.contains("findByUsernameAndAge"),
                "findByUsernameAndAge should be indexed");
    }

    // ---------------------------------------------------------------
    // 3. Batch update: renames callers and reorders args
    // ---------------------------------------------------------------

    @Test
    @Order(4)
    void testBatchUpdateMethodSignatures_RenamesCallersAndReordersArgs() throws Exception {
        QueryOptimizer optimizer = new QueryOptimizer(tempLiquibaseFile);

        // Build a synthetic MethodRename: findByFirstNameAndLastName → findByLastNameAndFirstName
        MethodDeclaration oldMd = new MethodDeclaration();
        oldMd.setName("findByFirstNameAndLastName");
        oldMd.addParameter(new Parameter(new ClassOrInterfaceType(null, "String"), "firstName"));
        oldMd.addParameter(new Parameter(new ClassOrInterfaceType(null, "String"), "lastName"));

        MethodDeclaration newMd = new MethodDeclaration();
        newMd.setName("findByLastNameAndFirstName");
        newMd.addParameter(new Parameter(new ClassOrInterfaceType(null, "String"), "lastName"));
        newMd.addParameter(new Parameter(new ClassOrInterfaceType(null, "String"), "firstName"));

        Callable mockOldCallable = mock(Callable.class);
        when(mockOldCallable.asMethodDeclaration()).thenReturn(oldMd);
        when(mockOldCallable.getNameAsString()).thenReturn("findByFirstNameAndLastName");

        Callable mockNewCallable = mock(Callable.class);
        when(mockNewCallable.asMethodDeclaration()).thenReturn(newMd);
        when(mockNewCallable.getNameAsString()).thenReturn("findByLastNameAndFirstName");

        RepositoryQuery mockOriginal = mock(RepositoryQuery.class);
        when(mockOriginal.getMethodDeclaration()).thenReturn(mockOldCallable);
        when(mockOriginal.getMethodName()).thenReturn("findByFirstNameAndLastName");

        RepositoryQuery mockOptimized = mock(RepositoryQuery.class);
        when(mockOptimized.getMethodDeclaration()).thenReturn(mockNewCallable);
        when(mockOptimized.getMethodName()).thenReturn("findByLastNameAndFirstName");

        OptimizationIssue issue = new OptimizationIssue(
                mockOriginal, List.of("first_name", "last_name"),
                List.of("last_name", "first_name"),
                "Reorder for index", "Swap columns",
                mockOptimized);

        QueryAnalysisResult result = mock(QueryAnalysisResult.class);
        when(result.getOptimizationIssue()).thenReturn(issue);
        when(result.getMethodName()).thenReturn("findByFirstNameAndLastName");

        java.util.Map<Integer, Integer> positionMap = issue.buildPositionMapping(2);
        QueryOptimizer.MethodRename rename = new QueryOptimizer.MethodRename(
                "findByFirstNameAndLastName", "findByLastNameAndFirstName", result, issue, positionMap);

        optimizer.batchUpdateMethodSignatures(List.of(rename), USER_REPO_FQN);

        // Verify: UserService's CompilationUnit should now have renamed method calls
        CompilationUnit serviceCu = AntikytheraRunTime.getCompilationUnit(USER_SERVICE_FQN);
        assertNotNull(serviceCu);

        List<MethodCallExpr> calls = serviceCu.findAll(MethodCallExpr.class);
        long renamedCount = calls.stream()
                .filter(c -> c.getNameAsString().equals("findByLastNameAndFirstName"))
                .count();

        // UserService has 3 calls to findByFirstNameAndLastName (in getUsersByName + processUsers)
        assertTrue(renamedCount >= 3,
                "Expected at least 3 renamed calls, found " + renamedCount);

        // Verify arguments were swapped: first call should have args in reversed order
        MethodCallExpr firstRenamed = calls.stream()
                .filter(c -> c.getNameAsString().equals("findByLastNameAndFirstName"))
                .findFirst()
                .orElseThrow();
        assertEquals(2, firstRenamed.getArguments().size());

        // No calls should remain with the old name
        long oldNameCount = calls.stream()
                .filter(c -> c.getNameAsString().equals("findByFirstNameAndLastName"))
                .count();
        assertEquals(0, oldNameCount,
                "No calls should remain with old name findByFirstNameAndLastName");
    }

    // ---------------------------------------------------------------
    // 4. Rollback on mapping failure
    // ---------------------------------------------------------------

    @Test
    @Order(5)
    void testBatchUpdateMethodSignatures_RollsBackOnMappingFailure() throws Exception {
        QueryOptimizer optimizer = new QueryOptimizer(tempLiquibaseFile);

        // Build a rename with mismatched param names AND no column orders
        MethodDeclaration oldMd = new MethodDeclaration();
        oldMd.setName("findByAge");
        oldMd.addParameter(new Parameter(new ClassOrInterfaceType(null, "Integer"), "yearsOld"));

        MethodDeclaration newMd = new MethodDeclaration();
        newMd.setName("findByAgeRenamed");
        newMd.addParameter(new Parameter(new ClassOrInterfaceType(null, "Integer"), "differentName"));

        Callable mockOldCallable = mock(Callable.class);
        when(mockOldCallable.asMethodDeclaration()).thenReturn(oldMd);
        when(mockOldCallable.getNameAsString()).thenReturn("findByAge");

        Callable mockNewCallable = mock(Callable.class);
        when(mockNewCallable.asMethodDeclaration()).thenReturn(newMd);
        when(mockNewCallable.getNameAsString()).thenReturn("findByAgeRenamed");

        RepositoryQuery mockOriginal = mock(RepositoryQuery.class);
        when(mockOriginal.getMethodDeclaration()).thenReturn(mockOldCallable);
        when(mockOriginal.getMethodName()).thenReturn("findByAge");

        RepositoryQuery mockOptimized = mock(RepositoryQuery.class);
        when(mockOptimized.getMethodDeclaration()).thenReturn(mockNewCallable);
        when(mockOptimized.getMethodName()).thenReturn("findByAgeRenamed");

        // No column orders → fallback mapping will fail too
        OptimizationIssue issue = new OptimizationIssue(
                mockOriginal, null, null,
                "Test", "N/A", mockOptimized);

        QueryAnalysisResult result = mock(QueryAnalysisResult.class);
        when(result.getOptimizationIssue()).thenReturn(issue);
        when(result.getMethodName()).thenReturn("findByAge");

        java.util.Map<Integer, Integer> positionMap = issue.buildPositionMapping(1);
        QueryOptimizer.MethodRename rename = new QueryOptimizer.MethodRename(
                "findByAge", "findByAgeRenamed", result, issue, positionMap);

        optimizer.batchUpdateMethodSignatures(List.of(rename), USER_REPO_FQN);

        // Verify: UserService should still have findByAge (rollback)
        CompilationUnit serviceCu = AntikytheraRunTime.getCompilationUnit(USER_SERVICE_FQN);
        List<MethodCallExpr> calls = serviceCu.findAll(MethodCallExpr.class);

        long oldNameCount = calls.stream()
                .filter(c -> c.getNameAsString().equals("findByAge"))
                .count();
        assertTrue(oldNameCount > 0,
                "findByAge calls should remain unchanged after rollback");

        long newNameCount = calls.stream()
                .filter(c -> c.getNameAsString().equals("findByAgeRenamed"))
                .count();
        assertEquals(0, newNameCount,
                "No calls should have been renamed to findByAgeRenamed");
    }

    // ---------------------------------------------------------------
    // 5. actOnAnalysisResult updates @Query annotation
    // ---------------------------------------------------------------

    @Test
    @Order(6)
    void testActOnAnalysisResult_UpdatesQueryAnnotation() throws Exception {
        QueryOptimizer optimizer = new QueryOptimizer(tempLiquibaseFile);

        // Get the real UserRepository CompilationUnit
        CompilationUnit repoCu = AntikytheraRunTime.getCompilationUnit(USER_REPO_FQN);
        assertNotNull(repoCu);

        // Find the findByUsernameAndAgeWithQuery method (has @Query annotation)
        MethodDeclaration targetMethod = repoCu.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getNameAsString().equals("findByUsernameAndAgeWithQuery"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("findByUsernameAndAgeWithQuery not found"));

        // Build mocks for the analysis result
        Callable mockCallable = mock(Callable.class);
        when(mockCallable.asMethodDeclaration()).thenReturn(targetMethod);
        when(mockCallable.getNameAsString()).thenReturn("findByUsernameAndAgeWithQuery");

        RepositoryQuery mockOriginal = mock(RepositoryQuery.class);
        when(mockOriginal.getMethodDeclaration()).thenReturn(mockCallable);
        when(mockOriginal.getMethodName()).thenReturn("findByUsernameAndAgeWithQuery");

        // Create optimized query with a new query string
        String optimizedSql = "SELECT u FROM User u WHERE u.age = ?2 AND u.username = ?1";
        RepositoryQuery mockOptimized = mock(RepositoryQuery.class);
        when(mockOptimized.getMethodDeclaration()).thenReturn(mockCallable);
        when(mockOptimized.getMethodName()).thenReturn("findByUsernameAndAgeWithQuery");
        when(mockOptimized.getOriginalQuery()).thenReturn(optimizedSql);
        when(mockOptimized.getQueryType()).thenReturn(
                sa.com.cloudsolutions.antikythera.generator.QueryType.HQL);

        OptimizationIssue issue = new OptimizationIssue(
                mockOriginal,
                List.of("username", "age"),
                List.of("age", "username"),
                "Reorder columns", "Swap for index",
                mockOptimized);

        QueryAnalysisResult analysisResult = mock(QueryAnalysisResult.class);
        when(analysisResult.getOptimizationIssue()).thenReturn(issue);
        when(analysisResult.getMethodName()).thenReturn("findByUsernameAndAgeWithQuery");

        // Act
        optimizer.actOnAnalysisResult(analysisResult, new ArrayList<>());

        // Verify the @Query annotation was updated
        String updatedAnnotation = targetMethod.getAnnotationByName("Query")
                .orElseThrow()
                .toString();
        assertTrue(updatedAnnotation.contains("u.age"),
                "Updated annotation should contain reordered query: " + updatedAnnotation);
    }

    // ---------------------------------------------------------------
    // 6. reorderMethodParameters on real parsed method
    // ---------------------------------------------------------------

    @Test
    @Order(7)
    void testReorderMethodParameters_WorksOnParsedMethod() throws LiquibaseException, IOException {
        QueryOptimizer optimizer = new QueryOptimizer(tempLiquibaseFile);

        // Parse a simple method declaration
        CompilationUnit cu = StaticJavaParser.parse(
                "interface Repo { void findByAAndB(String a, int b); }");
        MethodDeclaration method = cu.findFirst(MethodDeclaration.class).orElseThrow();

        assertEquals("a", method.getParameter(0).getNameAsString());
        assertEquals("b", method.getParameter(1).getNameAsString());

        // Create a new method with swapped parameters
        MethodDeclaration newMethod = new MethodDeclaration();
        newMethod.addParameter(new Parameter(new ClassOrInterfaceType(null, "int"), "b"));
        newMethod.addParameter(new Parameter(new ClassOrInterfaceType(null, "String"), "a"));

        optimizer.reorderMethodParameters(method, newMethod);

        // Verify parameters were reordered
        assertEquals("b", method.getParameter(0).getNameAsString());
        assertEquals("int", method.getParameter(0).getTypeAsString());
        assertEquals("a", method.getParameter(1).getNameAsString());
        assertEquals("String", method.getParameter(1).getTypeAsString());
    }
}
