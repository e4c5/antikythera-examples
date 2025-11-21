package sa.com.cloudsolutions.antikythera.examples;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.generator.QueryType;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;
import sa.com.cloudsolutions.antikythera.generator.TypeWrapper;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;
import sa.com.cloudsolutions.antikythera.parser.Callable;
import sa.com.cloudsolutions.antikythera.parser.RepositoryParser;
import sa.com.cloudsolutions.antikythera.parser.converter.EntityMappingResolver;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for verifying that method calls in dependent classes are correctly
 * updated
 * when repository method signatures change.
 */
public class QueryOptimizerCallerUpdateTest {

    @TempDir
    Path tempDir;

    @Mock
    private GeminiAIService mockAiService;

    @Mock
    private QueryAnalysisEngine mockAnalysisEngine;

    private QueryOptimizer queryOptimizer;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);

        // 1. Setup configuration
        File configFile = new File("src/test/resources/test-config.yml");
        Settings.loadConfigMap(configFile);

        // 2. Setup temporary directory with source files
        Path sourceRoot = Path.of("../antikythera-test-helper/src/main/java");
        Path destRoot = tempDir.resolve("src/main/java");
        Files.createDirectories(destRoot);

        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            stream.forEach(source -> {
                Path dest = destRoot.resolve(sourceRoot.relativize(source));
                try {
                    Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception e) {
                    if (!Files.isDirectory(dest)) {
                        try {
                            Files.createDirectories(dest.getParent());
                            Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
                        } catch (Exception ex) {
                            throw new RuntimeException(ex);
                        }
                    }
                }
            });
        }

        // Override base_path in Settings to point to the temp directory
        Settings.setProperty("base_path", tempDir.toAbsolutePath().toString());

        // 3. Reset Compiler and EntityMappingResolver
        AbstractCompiler.reset();
        AbstractCompiler.setEnableLexicalPreservation(true);
        AbstractCompiler.preProcess();
        EntityMappingResolver.reset();

        // 4. Initialize QueryOptimizer with mocks
        File liquibaseFile = new File("src/test/resources/db.changelog-master.xml");
        queryOptimizer = new QueryOptimizer(liquibaseFile);

        setField(queryOptimizer, "aiService", mockAiService);
        setField(queryOptimizer, "analysisEngine", mockAnalysisEngine);

        RepositoryParser mockRepositoryParser = mock(RepositoryParser.class);
        setField(queryOptimizer, "repositoryParser", mockRepositoryParser);

        // Build field dependencies
        Fields.buildDependencies();
    }

    @AfterEach
    void tearDown() {
        AntikytheraRunTime.reset();
        EntityMappingResolver.reset();
    }

    @Test
    void testMethodCallsUpdated_WhenMethodNameChanges() throws Exception {
        // Arrange
        String repoFqn = "sa.com.cloudsolutions.antikythera.testhelper.repository.UserRepository";
        String serviceFqn = "sa.com.cloudsolutions.antikythera.testhelper.service.UserService";

        // Get the real CompilationUnit and MethodDeclaration
        var repoCu = AntikytheraRunTime.getCompilationUnit(repoFqn);
        var methodDecl = repoCu.getInterfaceByName("UserRepository").get()
                .getMethodsByName("findByUsername").get(0);

        // Mock AI Service response - recommending a method name change
        OptimizationIssue issue = mock(OptimizationIssue.class);
        RepositoryQuery query = mock(RepositoryQuery.class);
        when(query.getMethodName()).thenReturn("findByUsername");
        when(query.getQueryType()).thenReturn(QueryType.DERIVED);
        when(query.getOriginalQuery()).thenReturn("SELECT * FROM users WHERE username = ?1");
        when(query.getStatement()).thenReturn(mock(net.sf.jsqlparser.statement.Statement.class));

        Callable callable = new Callable(methodDecl, null);
        when(query.getMethodDeclaration()).thenReturn(callable);
        when(issue.query()).thenReturn(query);

        RepositoryQuery optimizedQuery = mock(RepositoryQuery.class);
        when(optimizedQuery.getMethodName()).thenReturn("findByUserName"); // Different name!
        when(optimizedQuery.getQueryType()).thenReturn(QueryType.DERIVED);
        when(optimizedQuery.getOriginalQuery())
                .thenReturn("SELECT * FROM users WHERE username = ?1 -- OPTIMIZED");

        net.sf.jsqlparser.statement.Statement mockStatement = mock(net.sf.jsqlparser.statement.Statement.class);
        when(mockStatement.toString()).thenReturn("SELECT * FROM users WHERE username = ?1 -- OPTIMIZED");
        when(optimizedQuery.getStatement()).thenReturn(mockStatement);

        when(issue.optimizedQuery()).thenReturn(optimizedQuery);
        when(issue.description()).thenReturn("Method name should be changed");
        when(issue.aiExplanation()).thenReturn("Use camelCase for method name");
        when(issue.currentColumnOrder()).thenReturn(List.of("username"));
        when(issue.recommendedColumnOrder()).thenReturn(List.of("username"));

        TokenUsage tokenUsage = new TokenUsage();
        when(mockAiService.getLastTokenUsage()).thenReturn(tokenUsage);
        when(mockAiService.analyzeQueryBatch(any())).thenReturn(Collections.singletonList(issue));

        QueryOptimizationResult result = mock(QueryOptimizationResult.class);
        when(result.getOptimizationIssue()).thenReturn(issue);
        when(result.getQuery()).thenReturn(query);
        when(result.getMethodName()).thenReturn("findByUsername");
        when(result.getWhereConditions()).thenReturn(Collections.emptyList());
        when(result.getIndexSuggestions()).thenReturn(Collections.emptyList());
        when(result.getFullWhereClause()).thenReturn("username = ?1");

        when(mockAnalysisEngine.analyzeQuery(any())).thenReturn(result);

        // Configure RepositoryParser mock
        RepositoryParser mockRepositoryParser = (RepositoryParser) getField(queryOptimizer, "repositoryParser");
        when(mockRepositoryParser.getCompilationUnit()).thenReturn(repoCu);
        when(mockRepositoryParser.getAllQueries()).thenReturn(List.of(query));
        when(mockRepositoryParser.getEntity())
                .thenReturn(new TypeWrapper(repoCu.getInterfaceByName("UserRepository").get()));

        // Act
        TypeWrapper typeWrapper = AntikytheraRunTime.getResolvedTypes().get(repoFqn);
        queryOptimizer.analyzeRepository(repoFqn, typeWrapper);

        // Assert - Check that the repository method name was changed
        Path repoFile = tempDir
                .resolve("src/main/java/sa/com/cloudsolutions/antikythera/testhelper/repository/UserRepository.java");
        String repoContent = Files.readString(repoFile);
        assertTrue(repoContent.contains("findByUserName"), "Repository method name should be changed");

        // Assert - Check that the service method calls were updated
        Path serviceFile = tempDir
                .resolve("src/main/java/sa/com/cloudsolutions/antikythera/testhelper/service/UserService.java");
        String serviceContent = Files.readString(serviceFile);

        System.out.println("=== Service File Content ===");
        System.out.println(serviceContent);
        System.out.println("=== End Service File Content ===");

        assertTrue(serviceContent.contains("userRepository.findByUserName(username)"),
                "Service method call should be updated to findByUserName");
        assertFalse(serviceContent.contains("userRepository.findByUsername(username)"),
                "Service should not contain old method name findByUsername");
    }

    @Test
    void testMethodCallsUpdated_WhenParametersReordered() throws Exception {
        // Arrange
        String repoFqn = "sa.com.cloudsolutions.antikythera.testhelper.repository.UserRepository";
        String serviceFqn = "sa.com.cloudsolutions.antikythera.testhelper.service.UserService";

        var repoCu = AntikytheraRunTime.getCompilationUnit(repoFqn);
        var methodDecl = repoCu.getInterfaceByName("UserRepository").get()
                .getMethodsByName("findByFirstNameAndLastName").get(0);

        // Mock AI Service response - recommending parameter reordering
        OptimizationIssue issue = mock(OptimizationIssue.class);
        RepositoryQuery query = mock(RepositoryQuery.class);
        when(query.getMethodName()).thenReturn("findByFirstNameAndLastName");
        when(query.getQueryType()).thenReturn(QueryType.DERIVED);
        when(query.getOriginalQuery()).thenReturn("SELECT * FROM users WHERE first_name = ?1 AND last_name = ?2");
        when(query.getStatement()).thenReturn(mock(net.sf.jsqlparser.statement.Statement.class));

        Callable callable = new Callable(methodDecl, null);
        when(query.getMethodDeclaration()).thenReturn(callable);
        when(issue.query()).thenReturn(query);

        RepositoryQuery optimizedQuery = mock(RepositoryQuery.class);
        when(optimizedQuery.getMethodName()).thenReturn("findByFirstNameAndLastName");
        when(optimizedQuery.getQueryType()).thenReturn(QueryType.DERIVED);
        when(optimizedQuery.getOriginalQuery())
                .thenReturn("SELECT * FROM users WHERE first_name = ?1 AND last_name = ?2");

        net.sf.jsqlparser.statement.Statement mockStatement = mock(net.sf.jsqlparser.statement.Statement.class);
        when(mockStatement.toString()).thenReturn("SELECT * FROM users WHERE first_name = ?1 AND last_name = ?2");
        when(optimizedQuery.getStatement()).thenReturn(mockStatement);

        when(issue.optimizedQuery()).thenReturn(optimizedQuery);
        when(issue.description()).thenReturn("Parameter reordering needed");
        when(issue.aiExplanation()).thenReturn("Reorder parameters");
        when(issue.currentColumnOrder()).thenReturn(List.of("firstName", "lastName"));
        when(issue.recommendedColumnOrder()).thenReturn(List.of("lastName", "firstName"));

        TokenUsage tokenUsage = new TokenUsage();
        when(mockAiService.getLastTokenUsage()).thenReturn(tokenUsage);
        when(mockAiService.analyzeQueryBatch(any())).thenReturn(Collections.singletonList(issue));

        QueryOptimizationResult result = mock(QueryOptimizationResult.class);
        when(result.getOptimizationIssue()).thenReturn(issue);
        when(result.getQuery()).thenReturn(query);
        when(result.getMethodName()).thenReturn("findByFirstNameAndLastName");
        when(result.getWhereConditions()).thenReturn(Collections.emptyList());
        when(result.getIndexSuggestions()).thenReturn(Collections.emptyList());
        when(result.getFullWhereClause()).thenReturn("first_name = ?1 AND last_name = ?2");

        when(mockAnalysisEngine.analyzeQuery(any())).thenReturn(result);

        RepositoryParser mockRepositoryParser = (RepositoryParser) getField(queryOptimizer, "repositoryParser");
        when(mockRepositoryParser.getCompilationUnit()).thenReturn(repoCu);
        when(mockRepositoryParser.getAllQueries()).thenReturn(List.of(query));
        when(mockRepositoryParser.getEntity())
                .thenReturn(new TypeWrapper(repoCu.getInterfaceByName("UserRepository").get()));

        // Act
        TypeWrapper typeWrapper = AntikytheraRunTime.getResolvedTypes().get(repoFqn);
        queryOptimizer.analyzeRepository(repoFqn, typeWrapper);

        // Assert - Check that service method calls have reordered arguments
        Path serviceFile = tempDir
                .resolve("src/main/java/sa/com/cloudsolutions/antikythera/testhelper/service/UserService.java");
        String serviceContent = Files.readString(serviceFile);

        System.out.println("=== Service File Content (Parameter Reordering) ===");
        System.out.println(serviceContent);
        System.out.println("=== End Service File Content ===");

        // Check that arguments are reordered in method calls
        assertTrue(serviceContent.contains("userRepository.findByFirstNameAndLastName(lastName, firstName)") ||
                serviceContent.contains("userRepository.findByFirstNameAndLastName(\"Doe\", \"John\")"),
                "Service method call arguments should be reordered");
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private Object getField(Object target, String fieldName) throws Exception {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }
}
