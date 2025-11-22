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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryOptimizerTest {

    @TempDir
    Path tempDir;

    private QueryOptimizer queryOptimizer;

    @Mock
    private GeminiAIService mockAiService;

    @Mock
    private QueryAnalysisEngine mockAnalysisEngine;

    private AutoCloseable closeable;

    @BeforeEach
    void setUp() throws Exception {
        closeable = MockitoAnnotations.openMocks(this);

        // 1. Setup configuration
        File configFile = new File("src/test/resources/test-config.yml");
        Settings.loadConfigMap(configFile);

        // 2. Setup temporary directory with source files
        // Copy all files from antikythera-test-helper to tempDir to ensure entities are
        // resolved
        Path sourceRoot = Path.of("../antikythera-test-helper/src/main/java");
        Path destRoot = tempDir.resolve("src/main/java");
        Files.createDirectories(destRoot);

        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            stream.forEach(source -> {
                Path dest = destRoot.resolve(sourceRoot.relativize(source));
                try {
                    Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception e) {
                    // Ignore directory creation errors if they already exist or if it's a directory
                    if (!Files.isDirectory(dest)) {
                        // if destination directory doesn't exist, create it
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
        // Enable lexical preservation for AST modification tracking
        AbstractCompiler.setEnableLexicalPreservation(true);
        AbstractCompiler.preProcess();
        EntityMappingResolver.reset();

        // 4. Initialize QueryOptimizer with mocks
        File liquibaseFile = new File("src/test/resources/db.changelog-master.xml");
        queryOptimizer = new QueryOptimizer(liquibaseFile);

        // Inject mocks using setters
        queryOptimizer.setAiService(mockAiService);
        queryOptimizer.setAnalysisEngine(mockAnalysisEngine);

        // Mock RepositoryParser
        RepositoryParser mockRepositoryParser = mock(RepositoryParser.class);
        queryOptimizer.setRepositoryParser(mockRepositoryParser);
    }

    @AfterEach
    void tearDown() throws Exception {
        closeable.close();
    }

    @Test
    void testAnalyzeRepository_WithOptimization() throws Exception {
        // Arrange
        String fullyQualifiedName = "sa.com.cloudsolutions.antikythera.testhelper.repository.UserRepository";

        // Get the real CompilationUnit and MethodDeclaration
        var cu = AntikytheraRunTime.getCompilationUnit(fullyQualifiedName);
        var methodDecl = cu.getInterfaceByName("UserRepository").get().getMethodsByName("findByUsernameWithQuery")
                .get(0);

        // Mock AI Service response
        OptimizationIssue issue = mock(OptimizationIssue.class);
        RepositoryQuery query = mock(RepositoryQuery.class);
        when(query.getMethodName()).thenReturn("findByUsernameWithQuery");
        when(query.getQueryType()).thenReturn(QueryType.HQL);
        when(query.getOriginalQuery()).thenReturn("SELECT u FROM User u WHERE u.username = ?1");
        when(query.getStatement()).thenReturn(mock(net.sf.jsqlparser.statement.Statement.class));

        Callable callable = new Callable(methodDecl, null);
        when(query.getMethodDeclaration()).thenReturn(callable);

        when(issue.query()).thenReturn(query);

        // Create a separate mock for optimized query to simulate a change
        RepositoryQuery optimizedQuery = mock(RepositoryQuery.class);
        when(optimizedQuery.getMethodName()).thenReturn("findByUsernameWithQuery");
        when(optimizedQuery.getQueryType()).thenReturn(QueryType.HQL);
        when(optimizedQuery.getOriginalQuery()).thenReturn("SELECT u FROM User u WHERE u.username = ?1 -- OPTIMIZED");
        when(optimizedQuery.getStatement()).thenReturn(mock(net.sf.jsqlparser.statement.Statement.class));

        when(issue.optimizedQuery()).thenReturn(optimizedQuery);
        when(issue.description()).thenReturn("Optimization needed");
        when(issue.aiExplanation()).thenReturn("Use index");

        // Mock TokenUsage
        TokenUsage tokenUsage = new TokenUsage();
        when(mockAiService.getLastTokenUsage()).thenReturn(tokenUsage);

        when(mockAiService.analyzeQueryBatch(any())).thenReturn(Collections.singletonList(issue));

        // Mock Analysis Engine
        QueryAnalysisResult result = mock(QueryAnalysisResult.class);
        when(result.getOptimizationIssue()).thenReturn(issue);
        when(result.getQuery()).thenReturn(query);
        when(result.getMethodName()).thenReturn("findByUsernameWithQuery");
        when(result.getWhereConditions()).thenReturn(Collections.emptyList());
        when(result.getIndexSuggestions()).thenReturn(Collections.emptyList());
        when(result.getFullWhereClause()).thenReturn("u.username = ?1");

        when(mockAnalysisEngine.analyzeQuery(any())).thenReturn(result);

        // Configure RepositoryParser mock
        RepositoryParser mockRepositoryParser = queryOptimizer.getRepositoryParser();
        when(mockRepositoryParser.getCompilationUnit()).thenReturn(cu);
        when(mockRepositoryParser.getAllQueries()).thenReturn(List.of(query));
        // We need to return something for getEntity, otherwise it might throw NPE if
        // used
        // But analyzeRepository calls repositoryParser.getEntity() only for logging?
        // Let's verify. It calls repositoryParser.getEntity() in line 118 of
        // QueryOptimizationChecker.
        // So we should mock it.
        when(mockRepositoryParser.getEntity())
                .thenReturn(new TypeWrapper(cu.getInterfaceByName("UserRepository").get()));

        // Act
        TypeWrapper typeWrapper = AntikytheraRunTime.getResolvedTypes().get(fullyQualifiedName);

        queryOptimizer.analyzeRepository(fullyQualifiedName, typeWrapper);

        Path repoFile = tempDir
                .resolve("src/main/java/sa/com/cloudsolutions/antikythera/testhelper/repository/UserRepository.java");

        // Verify that writeFile was called (it calls getCompilationUnit)
        verify(mockRepositoryParser).getCompilationUnit();

        // Assert
        String content = Files.readString(repoFile);

        assertTrue(content.contains("-- OPTIMIZED"), "File should contain optimized query");
    }

    @Test
    void testAnalyzeRepository_WithParameterReordering() throws Exception {
        // Arrange
        String fullyQualifiedName = "sa.com.cloudsolutions.antikythera.testhelper.repository.UserRepository";

        // Get the real CompilationUnit and MethodDeclaration
        var cu = AntikytheraRunTime.getCompilationUnit(fullyQualifiedName);
        var methodDecl = cu.getInterfaceByName("UserRepository").get().getMethodsByName("findByFirstNameAndLastName")
                .get(0);

        // Mock AI Service response
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

        // Mock statement for derived query
        net.sf.jsqlparser.statement.Statement mockStatement = mock(net.sf.jsqlparser.statement.Statement.class);
        when(mockStatement.toString()).thenReturn("SELECT * FROM users WHERE first_name = ?1 AND last_name = ?2");
        when(optimizedQuery.getStatement()).thenReturn(mockStatement);

        when(issue.optimizedQuery()).thenReturn(optimizedQuery);
        when(issue.description()).thenReturn("Parameter reordering needed");
        when(issue.aiExplanation()).thenReturn("Reorder parameters");
        when(issue.currentColumnOrder()).thenReturn(List.of("firstName", "lastName"));
        when(issue.recommendedColumnOrder()).thenReturn(List.of("lastName", "firstName"));

        // Mock TokenUsage
        TokenUsage tokenUsage = new TokenUsage();
        when(mockAiService.getLastTokenUsage()).thenReturn(tokenUsage);

        when(mockAiService.analyzeQueryBatch(any())).thenReturn(Collections.singletonList(issue));

        // Mock Analysis Engine
        QueryAnalysisResult result = mock(QueryAnalysisResult.class);
        when(result.getOptimizationIssue()).thenReturn(issue);
        when(result.getQuery()).thenReturn(query);
        when(result.getMethodName()).thenReturn("findByFirstNameAndLastName");
        when(result.getWhereConditions()).thenReturn(Collections.emptyList());
        when(result.getIndexSuggestions()).thenReturn(Collections.emptyList());
        when(result.getFullWhereClause()).thenReturn("first_name = ?1 AND last_name = ?2");

        when(mockAnalysisEngine.analyzeQuery(any())).thenReturn(result);

        // Configure RepositoryParser mock
        RepositoryParser mockRepositoryParser = queryOptimizer.getRepositoryParser();
        when(mockRepositoryParser.getCompilationUnit()).thenReturn(cu);
        when(mockRepositoryParser.getAllQueries()).thenReturn(List.of(query));
        when(mockRepositoryParser.getEntity())
                .thenReturn(new TypeWrapper(cu.getInterfaceByName("UserRepository").get()));

        // Act
        TypeWrapper typeWrapper = AntikytheraRunTime.getResolvedTypes().get(fullyQualifiedName);
        queryOptimizer.analyzeRepository(fullyQualifiedName, typeWrapper);

        // Assert
        Path repoFile = tempDir
                .resolve("src/main/java/sa/com/cloudsolutions/antikythera/testhelper/repository/UserRepository.java");
        String content = Files.readString(repoFile);
        // We expect parameters to be reordered in the file content if the optimizer
        // writes it back
        // Note: The optimizer writes back if 'repositoryFileModified' is true.
        // Parameter reordering sets 'repositoryFileModified = true'.
        // But we need to verify if it actually reordered them in the printed file.
        // Since we are using LexicalPreservingPrinter, it should work.
        // However, verifying exact string might be tricky with formatting.
        // Let's check if "String lastName, String firstName" appears.
        assertTrue(content.contains("String lastName, String firstName"), "Parameters should be reordered");
    }

    @Test
    void testAnalyzeRepository_WithMethodNameChange() throws Exception {
        // Arrange
        String fullyQualifiedName = "sa.com.cloudsolutions.antikythera.testhelper.repository.UserRepository";

        // Get the real CompilationUnit and MethodDeclaration
        var cu = AntikytheraRunTime.getCompilationUnit(fullyQualifiedName);
        var methodDecl = cu.getInterfaceByName("UserRepository").get().getMethodsByName("findByUsername")
                .get(0);

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

        // Mock statement for derived query
        net.sf.jsqlparser.statement.Statement mockStatement = mock(net.sf.jsqlparser.statement.Statement.class);
        when(mockStatement.toString()).thenReturn("SELECT * FROM users WHERE username = ?1 -- OPTIMIZED");
        when(optimizedQuery.getStatement()).thenReturn(mockStatement);

        when(issue.optimizedQuery()).thenReturn(optimizedQuery);
        when(issue.description()).thenReturn("Method name should be changed");
        when(issue.aiExplanation()).thenReturn("Use camelCase for method name");
        when(issue.currentColumnOrder()).thenReturn(List.of("username"));
        when(issue.recommendedColumnOrder()).thenReturn(List.of("username"));

        // Mock TokenUsage
        TokenUsage tokenUsage = new TokenUsage();
        when(mockAiService.getLastTokenUsage()).thenReturn(tokenUsage);

        when(mockAiService.analyzeQueryBatch(any())).thenReturn(Collections.singletonList(issue));

        // Mock Analysis Engine
        QueryAnalysisResult result = mock(QueryAnalysisResult.class);
        when(result.getOptimizationIssue()).thenReturn(issue);
        when(result.getQuery()).thenReturn(query);
        when(result.getMethodName()).thenReturn("findByUserName");
        when(result.getWhereConditions()).thenReturn(Collections.emptyList());
        when(result.getIndexSuggestions()).thenReturn(Collections.emptyList());
        when(result.getFullWhereClause()).thenReturn("username = ?1");

        when(mockAnalysisEngine.analyzeQuery(any())).thenReturn(result);

        // Configure RepositoryParser mock
        RepositoryParser mockRepositoryParser = queryOptimizer.getRepositoryParser();
        when(mockRepositoryParser.getCompilationUnit()).thenReturn(cu);
        when(mockRepositoryParser.getAllQueries()).thenReturn(List.of(query));
        when(mockRepositoryParser.getEntity())
                .thenReturn(new TypeWrapper(cu.getInterfaceByName("UserRepository").get()));

        // Act
        TypeWrapper typeWrapper = AntikytheraRunTime.getResolvedTypes().get(fullyQualifiedName);
        queryOptimizer.analyzeRepository(fullyQualifiedName, typeWrapper);

        // Assert
        Path repoFile = tempDir
                .resolve("src/main/java/sa/com/cloudsolutions/antikythera/testhelper/repository/UserRepository.java");
        String content = Files.readString(repoFile);

        // Verify the method name was changed in the file
        assertTrue(content.contains("findByUserName"), "Method name should be changed to findByUserName");
        assertFalse(content.contains("User findByUsername(String username)"),
                "Old method signature should not be present");
    }

}
