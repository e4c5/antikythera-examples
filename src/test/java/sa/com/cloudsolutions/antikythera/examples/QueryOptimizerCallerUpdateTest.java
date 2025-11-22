package sa.com.cloudsolutions.antikythera.examples;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
import java.io.IOException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;

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
class QueryOptimizerCallerUpdateTest {

        public static final String USER_SERVICE = "src/main/java/sa/com/cloudsolutions/antikythera/testhelper/service/UserService.java";
        public static final String USER_REPOSITORY = "src/main/java/sa/com/cloudsolutions/antikythera/testhelper/repository/UserRepository.java";
        @TempDir
        static Path tempDir;

        @Mock
        private GeminiAIService mockAiService;

        @Mock
        private QueryAnalysisEngine mockAnalysisEngine;

        private QueryOptimizer queryOptimizer;

        @BeforeAll
        static void init() throws IOException {
                File configFile = new File("src/test/resources/test-config.yml");
                Settings.loadConfigMap(configFile);

                // Override base_path in Settings to point to the temp directory BEFORE
                // preprocessing
                Settings.setProperty("base_path", tempDir.toString());

                // Mirror helper sources into the temp workspace
                mirrorHelperSource(
                                "../antikythera-test-helper/src/main/java/sa/com/cloudsolutions/antikythera/testhelper/repository/UserRepository.java",
                                USER_REPOSITORY);
                mirrorHelperSource(
                                "../antikythera-test-helper/src/main/java/sa/com/cloudsolutions/antikythera/testhelper/service/UserService.java",
                                USER_SERVICE);

                // Ensure the mirrored UserService contains a call used for parameter reordering
                // test
                ensureUserServiceHasFindByNamesMethod();

                // Initialize compiler/runtime with mirrored sources
                AbstractCompiler.reset();
                AbstractCompiler.setEnableLexicalPreservation(true);
                EntityMappingResolver.reset();
                AbstractCompiler.preProcess();
        }

        @BeforeEach
        void setUp() throws Exception {
                MockitoAnnotations.openMocks(this);

                // 4. Initialize QueryOptimizer with mocks
                File liquibaseFile = new File("src/test/resources/db.changelog-master.xml");
                queryOptimizer = new QueryOptimizer(liquibaseFile);

                queryOptimizer.setAiService(mockAiService);
                queryOptimizer.setAnalysisEngine(mockAnalysisEngine);

                RepositoryParser mockRepositoryParser = mock(RepositoryParser.class);
                queryOptimizer.setRepositoryParser(mockRepositoryParser);

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

                QueryAnalysisResult result = mock(QueryAnalysisResult.class);
                when(result.getOptimizationIssue()).thenReturn(issue);
                when(result.getQuery()).thenReturn(query);
                when(result.getMethodName()).thenReturn("findByUsername");
                when(result.getWhereConditions()).thenReturn(Collections.emptyList());
                when(result.getIndexSuggestions()).thenReturn(Collections.emptyList());
                when(result.getFullWhereClause()).thenReturn("username = ?1");

                when(mockAnalysisEngine.analyzeQuery(any())).thenReturn(result);

                // Configure RepositoryParser mock
                RepositoryParser mockRepositoryParser = queryOptimizer.getRepositoryParser();
                when(mockRepositoryParser.getCompilationUnit()).thenReturn(repoCu);
                when(mockRepositoryParser.getAllQueries()).thenReturn(List.of(query));
                when(mockRepositoryParser.getEntity())
                                .thenReturn(new TypeWrapper(repoCu.getInterfaceByName("UserRepository").get()));

                // Act
                TypeWrapper typeWrapper = AntikytheraRunTime.getResolvedTypes().get(repoFqn);

                queryOptimizer.analyzeRepository(repoFqn, typeWrapper);

                // Assert - Check that the repository method name was changed
                Path repoFile = tempDir.resolve(USER_REPOSITORY);
                String repoContent = Files.readString(repoFile);
                assertTrue(repoContent.contains("findByUserName"), "Repository method name should be changed");

                // Assert - Check that the service method calls were updated
                Path serviceFile = tempDir.resolve(USER_SERVICE);
                String serviceContent = Files.readString(serviceFile);

                assertTrue(serviceContent.contains("userRepository.findByUserName(username)"),
                                "Service method call should be updated to findByUserName");
                assertFalse(serviceContent.contains("repository.findByUsername(userName)"),
                                "Service should not contain old method name findByUsername");
        }

        @Test
        void testMethodCallsUpdated_WhenParametersReordered() throws Exception {
                // Arrange
                String repoFqn = "sa.com.cloudsolutions.antikythera.testhelper.repository.UserRepository";

                var repoCu = AntikytheraRunTime.getCompilationUnit(repoFqn);
                var methodDecl = repoCu.getInterfaceByName("UserRepository").get()
                                .getMethodsByName("findByFirstNameAndLastName").get(0);

                // Mock AI Service response - recommending parameter reordering
                OptimizationIssue issue = mock(OptimizationIssue.class);
                RepositoryQuery query = mock(RepositoryQuery.class);
                when(query.getMethodName()).thenReturn("findByFirstNameAndLastName");
                when(query.getQueryType()).thenReturn(QueryType.DERIVED);
                when(query.getOriginalQuery())
                                .thenReturn("SELECT * FROM users WHERE first_name = ?1 AND last_name = ?2");
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
                when(mockStatement.toString())
                                .thenReturn("SELECT * FROM users WHERE first_name = ?1 AND last_name = ?2");
                when(optimizedQuery.getStatement()).thenReturn(mockStatement);

                when(issue.optimizedQuery()).thenReturn(optimizedQuery);
                when(issue.description()).thenReturn("Parameter reordering needed");
                when(issue.aiExplanation()).thenReturn("Reorder parameters");
                when(issue.currentColumnOrder()).thenReturn(List.of("firstName", "lastName"));
                when(issue.recommendedColumnOrder()).thenReturn(List.of("lastName", "firstName"));

                TokenUsage tokenUsage = new TokenUsage();
                when(mockAiService.getLastTokenUsage()).thenReturn(tokenUsage);
                when(mockAiService.analyzeQueryBatch(any())).thenReturn(Collections.singletonList(issue));

                QueryAnalysisResult result = mock(QueryAnalysisResult.class);
                when(result.getOptimizationIssue()).thenReturn(issue);
                when(result.getQuery()).thenReturn(query);
                when(result.getMethodName()).thenReturn("findByFirstNameAndLastName");
                when(result.getWhereConditions()).thenReturn(Collections.emptyList());
                when(result.getIndexSuggestions()).thenReturn(Collections.emptyList());
                when(result.getFullWhereClause()).thenReturn("first_name = ?1 AND last_name = ?2");

                when(mockAnalysisEngine.analyzeQuery(any())).thenReturn(result);

                RepositoryParser mockRepositoryParser = queryOptimizer.getRepositoryParser();
                when(mockRepositoryParser.getCompilationUnit()).thenReturn(repoCu);
                when(mockRepositoryParser.getAllQueries()).thenReturn(List.of(query));
                when(mockRepositoryParser.getEntity())
                                .thenReturn(new TypeWrapper(repoCu.getInterfaceByName("UserRepository").get()));

                // Act
                TypeWrapper typeWrapper = AntikytheraRunTime.getResolvedTypes().get(repoFqn);
                queryOptimizer.analyzeRepository(repoFqn, typeWrapper);

                // Assert - Check that service method calls have reordered arguments
                Path serviceFile = tempDir
                                .resolve(USER_SERVICE);
                String serviceContent = Files.readString(serviceFile);

                // Check that arguments are reordered in method calls
                assertTrue(serviceContent.contains("userRepository.findByFirstNameAndLastName(lastName, firstName)") ||
                                serviceContent.contains("userRepository.findByFirstNameAndLastName(\"Doe\", \"John\")"),
                                "Service method call arguments should be reordered");
        }

        // Mirrors a helper source file into the temporary workspace, preserving package
        // path
        private static void mirrorHelperSource(String srcRelativePath, String destRelativePath) throws IOException {
                Path srcPath = new File(srcRelativePath).toPath();
                Path destPath = tempDir.resolve(destRelativePath);
                Files.createDirectories(destPath.getParent());
                Files.copy(srcPath, destPath, StandardCopyOption.REPLACE_EXISTING);
        }

        // Ensures the mirrored UserService contains a call suitable for the parameter
        // reordering test
        private static void ensureUserServiceHasFindByNamesMethod() throws IOException {
                Path servicePath = tempDir.resolve(USER_SERVICE);
                String content = Files.readString(servicePath);
                if (!content.contains("findByFirstNameAndLastName(")) {
                        String method = "\n    public void findByNames(String firstName, String lastName) {\n" +
                                        "        repository.findByFirstNameAndLastName(firstName, lastName);\n" +
                                        "    }\n";
                        int idx = content.lastIndexOf('}');
                        if (idx >= 0) {
                                content = content.substring(0, idx) + method + content.substring(idx);
                        } else {
                                content = content + method;
                        }
                        Files.createDirectories(servicePath.getParent());
                        Files.writeString(servicePath, content);
                }
        }
}
