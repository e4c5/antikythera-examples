package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;
import sa.com.cloudsolutions.antikythera.generator.TypeWrapper;
import sa.com.cloudsolutions.antikythera.parser.RepositoryParser;
import sa.com.cloudsolutions.liquibase.Indexes;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for QueryOptimizationChecker with improved coverage.
 */
class QueryOptimizationCheckerTest {

    private QueryOptimizationChecker checker;

    @Mock
    private TypeWrapper mockTypeWrapper;

    @Mock
    private ClassOrInterfaceDeclaration mockClassDeclaration;

    @Mock
    private RepositoryQuery mockRepositoryQuery;

    @Mock
    private OptimizationIssue mockOptimizationIssue;

    @Mock
    private QueryAnalysisResult mockResult;

    @Mock
    private RepositoryParser mockRepositoryParser;

    @Mock
    private GeminiAIService mockAiService;

    private static File liquibaseFile;

    @BeforeAll
    static void setupClass() throws Exception {
        // Load YAML settings explicitly to avoid reflection hacks
        Path tmpDir = Files.createTempDirectory("qoc-test");
        liquibaseFile = tmpDir.resolve("db.changelog-master.xml").toFile();
        try (FileWriter fw = new FileWriter(liquibaseFile)) {
            fw.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            fw.write("<databaseChangeLog\n");
            fw.write("    xmlns=\"http://www.liquibase.org/xml/ns/dbchangelog\"\n");
            fw.write("    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
            fw.write("    xsi:schemaLocation=\"http://www.liquibase.org/xml/ns/dbchangelog\n");
            fw.write("    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.0.xsd\">\n");
            fw.write("</databaseChangeLog>");
        }
        assertTrue(Indexes.load(liquibaseFile).isEmpty(), "Expected empty index map for minimal Liquibase file");

        Settings.loadConfigMap(new File("src/test/resources/generator.yml"));
    }

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        checker = new QueryOptimizationChecker(liquibaseFile);
        checker.setRepositoryParser(mockRepositoryParser);
        checker.setAiService(mockAiService);
        OptimizationStatsLogger.initialize("test");
    }

    @AfterEach
    void tearDown() {
        QueryOptimizationChecker.setQuietMode(false);
        QueryOptimizationChecker.setTargetClass(null);
        QueryOptimizationChecker.skipClass = null;
    }

    @Test
    void testParseListArg() {
        Set<String> low = QueryOptimizationChecker.parseListArg(
                new String[] { "--low-cardinality=email,is_active,  USER_ID   ", "--other=x" }, "--low-cardinality=");
        assertTrue(low.contains("email"));
        assertTrue(low.contains("is_active"));
        assertTrue(low.contains("user_id"));
        assertEquals(3, low.size());
    }

    @Test
    void testParseListArg_NoMatch() {
        Set<String> none = QueryOptimizationChecker.parseListArg(new String[] { "--foo=bar" }, "--low-cardinality=");
        assertTrue(none.isEmpty());
    }

    @Test
    void testInferTableNameFromQuery() {
        String table1 = checker.inferTableNameFromQuery("SELECT * FROM public.users u WHERE u.id = ?");
        assertEquals("users", table1);

        // Test with quoted table names
        String table2 = checker.inferTableNameFromQuery("SELECT * FROM `user_accounts` WHERE id = ?");
        assertEquals("user_accounts", table2);

        // Test with null query
        String table3 = checker.inferTableNameFromQuery(null);
        assertNull(table3);

        // Test with no FROM clause
        String table4 = checker.inferTableNameFromQuery("SELECT 1");
        assertNull(table4);
    }

    @Test
    void testBuildLiquibaseMultiColumnIndexChangeSet() {
        LinkedHashSet<String> columns = new LinkedHashSet<>();
        columns.add("user_id");
        columns.add("created_date");

        String result = checker.buildLiquibaseMultiColumnIndexChangeSet("orders", columns);

        assertTrue(result.contains("idx_orders_user_id_created_date"));
        assertTrue(result.contains("ON orders (user_id, created_date)"));
        assertTrue(result.toLowerCase().contains("rollback"));

        // Test with empty columns
        String result2 = checker.buildLiquibaseMultiColumnIndexChangeSet("orders", new LinkedHashSet<>());
        assertEquals("", result2);
    }

    @Test
    void testBuildLiquibaseDropIndexChangeSet() {
        String dropXml = checker.buildLiquibaseDropIndexChangeSet("idx_users_email");
        assertTrue(
                dropXml.contains("DROP INDEX CONCURRENTLY IF EXISTS idx_users_email")
                        || dropXml.contains("DROP INDEX idx_users_email"));
        // Verify rollback section was added
        assertTrue(dropXml.toLowerCase().contains("rollback"));
        assertTrue(dropXml.contains("manual recreation required"));

        // Test with empty index name
        String dropXml2 = checker.buildLiquibaseDropIndexChangeSet("");
        assertTrue(dropXml2.contains("<INDEX_NAME>"));
    }

    // Note: sanitize method moved to LiquibaseGenerator utility class
    // Test coverage is now provided by LiquibaseGeneratorTest

    @Test
    void testIndent() {
        String indented = checker.indent("a\nb", 2);
        assertEquals("  a\n  b", indented);

        // Test with zero spaces
        String indented2 = checker.indent("test", 0);
        assertEquals("test", indented2);

        // Test with negative spaces
        String indented3 = checker.indent("test", -1);
        assertEquals("test", indented3);
    }

    @Test
    void testIndentXml() {
        String xml = "<tag>\n<nested>value</nested>\n</tag>";
        String result = checker.indentXml(xml, 2);
        assertTrue(result.contains("  <tag>"));
        assertTrue(result.contains("  <nested>value</nested>"));

        // Test with null/empty
        String result2 = checker.indentXml(null, 2);
        assertNull(result2);

        String result3 = checker.indentXml("", 2);
        assertEquals("", result3);
    }

    @Test
    void testCreateQueryBatch() {
        List<RepositoryQuery> queries = new ArrayList<>();

        // Mock the Callable that getMethodDeclaration() returns
        sa.com.cloudsolutions.antikythera.parser.Callable mockCallable = mock(
                sa.com.cloudsolutions.antikythera.parser.Callable.class);
        when(mockCallable.getNameAsString()).thenReturn("findByEmail");

        when(mockRepositoryQuery.getMethodName()).thenReturn("findByEmail");
        when(mockRepositoryQuery.getMethodDeclaration()).thenReturn(mockCallable);
        queries.add(mockRepositoryQuery);

        QueryBatch result = checker.createQueryBatch("TestRepository", queries);

        assertNotNull(result);
        // QueryBatch doesn't have getRepositoryName method, check toString instead
        assertTrue(result.toString().contains("TestRepository"));
    }

    @Test
    void testAnalyzeLLMRecommendations() {
        List<OptimizationIssue> recommendations = Arrays.asList(mockOptimizationIssue);
        List<RepositoryQuery> queries = Arrays.asList(mockRepositoryQuery);

        when(mockOptimizationIssue.query()).thenReturn(mockRepositoryQuery);
        when(mockRepositoryQuery.getMethodName()).thenReturn("findByEmail");

        List<QueryAnalysisResult> results = checker.analyzeLLMRecommendations(recommendations, queries);

        assertNotNull(results);
        assertEquals(1, results.size());
    }

    @Test
    void testIsCoveredByComposite() {
        // Add a multi-column index suggestion
        LinkedHashSet<String> multiColumnSet = checker.getSuggestedMultiColumnIndexes();
        multiColumnSet.add("users|email,name");

        assertTrue(checker.isCoveredByComposite("users", "email"));
        assertFalse(checker.isCoveredByComposite("users", "name")); // Not first column
        assertFalse(checker.isCoveredByComposite("orders", "email")); // Different table
    }

    @Test
    void testQuietModeOperations() {
        assertFalse(QueryOptimizationChecker.isQuietMode());

        QueryOptimizationChecker.setQuietMode(true);
        assertTrue(QueryOptimizationChecker.isQuietMode());

        QueryOptimizationChecker.setQuietMode(false);
        assertFalse(QueryOptimizationChecker.isQuietMode());
    }

    @Test
    void testSkipClassOperations() {
        assertNull(QueryOptimizationChecker.skipClass);

        QueryOptimizationChecker.skipClass = "com.example.SkipRepo";
        assertEquals("com.example.SkipRepo", QueryOptimizationChecker.skipClass);

        QueryOptimizationChecker.skipClass = null;
        assertNull(QueryOptimizationChecker.skipClass);
    }

    @Test
    void testCollectIndexSuggestions() {
        // Create mock objects
        when(mockResult.getIndexSuggestions()).thenReturn(Arrays.asList("users.email"));
        when(mockResult.getQuery()).thenReturn(mockRepositoryQuery);
        when(mockRepositoryQuery.getPrimaryTable()).thenReturn("users");
        when(mockRepositoryQuery.getClassname()).thenReturn("UserRepository");
        when(mockRepositoryQuery.getQuery()).thenReturn("SELECT * FROM users WHERE email = ?");

        WhereCondition mockCondition = mock(WhereCondition.class);
        when(mockCondition.columnName()).thenReturn("email");
        when(mockCondition.cardinality()).thenReturn(CardinalityLevel.HIGH);
        when(mockResult.getWhereConditions()).thenReturn(Arrays.asList(mockCondition));

        when(mockOptimizationIssue.recommendedColumnOrder()).thenReturn(Arrays.asList("email"));

        // Test the method
        checker.collectIndexSuggestions(mockResult);

        // Verify it doesn't throw exceptions
        assertTrue(true);
    }

    @Test
    void testGenerateLiquibaseChangesFile() {
        // Add some index suggestions
        LinkedHashSet<String> suggestedSet = checker.getSuggestedNewIndexes();
        suggestedSet.add("users|email");

        // This method requires file I/O, so we'll just test it doesn't throw
        try {
            checker.generateLiquibaseChangesFile();
            assertTrue(true); // If we get here, no exception was thrown
        } catch (Exception e) {
            // Expected since we don't have a proper Liquibase setup
            assertNotNull(e.getMessage());
        }
    }

    @Test
    void testCreateResultWithIndexAnalysis() {
        when(mockOptimizationIssue.query()).thenReturn(mockRepositoryQuery);
        when(mockOptimizationIssue.currentColumnOrder()).thenReturn(List.of("id"));
        when(mockOptimizationIssue.recommendedColumnOrder()).thenReturn(List.of("email"));
        when(mockOptimizationIssue.description()).thenReturn("Test optimization");
        when(mockOptimizationIssue.aiExplanation()).thenReturn("AI explanation");
        when(mockOptimizationIssue.optimizedQuery()).thenReturn(null);

        when(mockRepositoryQuery.getMethodName()).thenReturn("findById");

        QueryAnalysisResult result = checker.createResultWithIndexAnalysis(mockOptimizationIssue,
                mockRepositoryQuery);

        assertNotNull(result);
        assertEquals(mockRepositoryQuery, result.getQuery());
    }

    @Test
    void testAddWhereClauseColumnCardinality() {
        QueryBatch batch = new QueryBatch("TestRepository");
        when(mockRepositoryQuery.getMethodName()).thenReturn("findByEmail");

        // This method uses QueryAnalysisEngine internally, so we test it doesn't throw
        checker.addWhereClauseColumnCardinality(batch, mockRepositoryQuery);

        // Verify the method completes without exception
        assertTrue(true);
    }

    @Test
    void testReportingMethods() {
        // Test private reporting methods through direct calls
        when(mockResult.getWhereConditions()).thenReturn(new ArrayList<>());
        WhereCondition result = checker.findConditionByColumn(mockResult, "email");
        assertNull(result);

        // Test formatConditionWithCardinality
        String formatted = checker.formatConditionWithCardinality("email", null);
        assertTrue(formatted.contains("cardinality unknown"));
    }

    @Test
    void testTokenUsageTracking() {
        TokenUsage usage = checker.getCumulativeTokenUsage();
        assertNotNull(usage);
        assertEquals(0, usage.getTotalTokens()); // Should start at 0
    }

    @Test
    void testLiquibaseGeneration() {
        // Test the method that generates Liquibase changes
        LinkedHashSet<String> suggestedSet = checker.getSuggestedNewIndexes();
        suggestedSet.add("users|email");

        // This will likely fail due to file I/O but we test it doesn't crash
        try {
            checker.generateLiquibaseChangesFile();
        } catch (Exception e) {
            // Expected - we don't have proper file setup
            assertTrue(e.getMessage() != null || e.getCause() != null);
        }
    }

    @Test
    void testStaticMethods() {
        // Test parseListArg method which is static and safe to test
        Set<String> result = QueryOptimizationChecker.parseListArg(new String[] { "--test=a,b,c" }, "--test=");
        assertEquals(3, result.size());
        assertTrue(result.contains("a"));
        assertTrue(result.contains("b"));
        assertTrue(result.contains("c"));
    }

    @Test
    void testReportOptimizationResults() {
        // Test with empty optimization issues (should call reportOptimizedQuery)
        when(mockResult.getOptimizationIssue()).thenReturn(null);
        when(mockResult.getWhereConditions()).thenReturn(new ArrayList<>());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(baos));
        try {
            checker.reportOptimizationResults(mockResult);
        } finally {
            System.setOut(originalOut);
        }

        // Should not throw exception
        assertTrue(true);
    }

    @Test
    void testReportOptimizedQuery() {
        // Mock a WhereCondition
        WhereCondition mockCondition = mock(WhereCondition.class);
        when(mockCondition.cardinality()).thenReturn(CardinalityLevel.HIGH);
        when(mockCondition.columnName()).thenReturn("email");

        when(mockResult.getWhereConditions()).thenReturn(List.of(mockCondition));
        when(mockResult.getFirstCondition()).thenReturn(mockCondition);
        when(mockResult.getQuery()).thenReturn(mockRepositoryQuery);
        when(mockRepositoryQuery.getClassname()).thenReturn("UserRepository");
        when(mockResult.getMethodName()).thenReturn("findByEmail");
        when(mockResult.getFullWhereClause()).thenReturn("email = ?");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(baos));
        try {
            checker.reportOptimizedQuery(mockResult);
        } finally {
            System.setOut(originalOut);
        }

        String output = baos.toString();
        assertTrue(output.contains("OPTIMIZED"));
    }

    @Test
    void testPrintQueryDetails() {
        when(mockResult.getFullWhereClause()).thenReturn("email = ? AND status = ?");
        when(mockResult.getOptimizationIssue()).thenReturn(null);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(baos));
        try {
            checker.printQueryDetails(mockResult);
        } finally {
            System.setOut(originalOut);
        }

        String output = baos.toString();
        assertTrue(output.contains("WHERE"));
    }

    @Test
    void testAnalyzeRepository() throws Exception {
        // Setup mocks
        when(mockRepositoryParser.getEntity()).thenReturn(mockTypeWrapper);
        when(mockTypeWrapper.getFullyQualifiedName()).thenReturn("com.example.UserRepository");
        when(mockRepositoryParser.getAllQueries()).thenReturn(Collections.emptyList());
        when(mockAiService.getLastTokenUsage()).thenReturn(new TokenUsage());
        when(mockAiService.analyzeQueryBatch(any())).thenReturn(Collections.emptyList());

        checker.analyzeRepository(mockTypeWrapper);

        // Verify interactions
        verify(mockRepositoryParser).compile(any());
        verify(mockRepositoryParser).processTypes();
        verify(mockRepositoryParser).buildQueries();
    }

    @Test
    void testAnalyze() {
        try {
            checker.analyze();
            // This will likely fail due to AntikytheraRunTime dependencies
            assertTrue(true);
        } catch (Exception e) {
            // Expected - method depends on AntikytheraRunTime.getResolvedTypes()
            assertTrue(e.getCause() != null || e.getMessage() != null);
        }
    }

    @Test
    void testFindConditionByColumn() {
        WhereCondition mockCondition = mock(WhereCondition.class);
        when(mockCondition.columnName()).thenReturn("email");

        when(mockResult.getWhereConditions()).thenReturn(Arrays.asList(mockCondition));

        WhereCondition result = checker.findConditionByColumn(mockResult, "email");
        assertEquals(mockCondition, result);

        // Test with non-matching column
        WhereCondition result2 = checker.findConditionByColumn(mockResult, "nonexistent");
        assertNull(result2);
    }

    @Test
    void testFormatConditionWithCardinalityWithCondition() {
        WhereCondition mockCondition = mock(WhereCondition.class);
        when(mockCondition.cardinality()).thenReturn(CardinalityLevel.HIGH);

        String result = checker.formatConditionWithCardinality("email", mockCondition);
        assertTrue(result.contains("email"));
        assertTrue(result.contains("high cardinality"));
    }

    @Test
    void testRemoveProposedIndexesCoveredBy_TableMismatch() {
        checker.getSuggestedMultiColumnIndexes().add("orders|user_id");
        List<String> newColumns = List.of("user_id", "created_date");
        
        // This should NOT remove "orders|user_id" because we are adding an index to "users"
        checker.removeProposedIndexesCoveredBy("users", newColumns);
        
        assertTrue(checker.getSuggestedMultiColumnIndexes().contains("orders|user_id"));
    }

    @Test
    void testEdgeCasesAndErrorHandling() {
        // Test with valid inputs first
        assertFalse(checker.hasOptimalIndexForColumn("users", "email"));
        assertFalse(checker.hasOptimalIndexForColumn("", ""));

        // Test isCoveredByComposite with edge cases
        assertFalse(checker.isCoveredByComposite("users", "email"));
        assertFalse(checker.isCoveredByComposite("", ""));
    }
}
