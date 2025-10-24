package sa.com.cloudsolutions.antikythera.examples;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.liquibase.Indexes;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proper JUnit 5 unit tests for QueryOptimizationChecker using reflection to access private helpers.
 */
class QueryOptimizationCheckerTest {

    private File liquibaseFile;
    private QueryOptimizationChecker checker;
    private Class<?> cls;

    @BeforeEach
    void setUp() throws Exception {
        Path tmpDir = Files.createTempDirectory("qoc-test");
        liquibaseFile = tmpDir.resolve("db.changelog-master.xml").toFile();
        try (FileWriter fw = new FileWriter(liquibaseFile)) {
            fw.write("<databaseChangeLog xmlns=\"http://www.liquibase.org/xml/ns/dbchangelog\"></databaseChangeLog>");
        }
        assertTrue(Indexes.load(liquibaseFile).isEmpty(), "Expected empty index map for minimal Liquibase file");
        checker = new QueryOptimizationChecker(liquibaseFile.getAbsolutePath());
        cls = QueryOptimizationChecker.class;
    }

    @Test
    void testParseListArg() throws Exception {
        Method parseListArg = cls.getDeclaredMethod("parseListArg", String[].class, String.class);
        parseListArg.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> low = (Set<String>) parseListArg.invoke(null, new Object[]{new String[]{"--low-cardinality=email,is_active,  USER_ID   ", "--other=x"}, "--low-cardinality="});
        assertTrue(low.contains("email"));
        assertTrue(low.contains("is_active"));
        assertTrue(low.contains("user_id"));
        assertEquals(3, low.size());
    }

    @Test
    void testParseListArg_NoMatch() throws Exception {
        Method parseListArg = cls.getDeclaredMethod("parseListArg", String[].class, String.class);
        parseListArg.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> none = (Set<String>) parseListArg.invoke(null, new Object[]{new String[]{"--foo=bar"}, "--low-cardinality="});
        assertTrue(none.isEmpty());
    }

    @Test
    void testSeverityPriorityOrdering() throws Exception {
        Method getSeverityPriority = cls.getDeclaredMethod("getSeverityPriority", OptimizationIssue.Severity.class);
        getSeverityPriority.setAccessible(true);
        int pHigh = (int) getSeverityPriority.invoke(checker, OptimizationIssue.Severity.HIGH);
        int pMed = (int) getSeverityPriority.invoke(checker, OptimizationIssue.Severity.MEDIUM);
        int pLow = (int) getSeverityPriority.invoke(checker, OptimizationIssue.Severity.LOW);
        assertTrue(pHigh < pMed && pMed < pLow);
    }

    @Test
    void testSeverityIconMapping() throws Exception {
        Method getSeverityIcon = cls.getDeclaredMethod("getSeverityIcon", OptimizationIssue.Severity.class);
        getSeverityIcon.setAccessible(true);
        String iHigh = (String) getSeverityIcon.invoke(checker, OptimizationIssue.Severity.HIGH);
        String iMed = (String) getSeverityIcon.invoke(checker, OptimizationIssue.Severity.MEDIUM);
        String iLow = (String) getSeverityIcon.invoke(checker, OptimizationIssue.Severity.LOW);
        assertEquals("🔴", iHigh);
        assertEquals("🟡", iMed);
        assertEquals("🟢", iLow);
    }

    @Test
    void testInferTableNameFromQuery() throws Exception {
        Method inferFromQuery = cls.getDeclaredMethod("inferTableNameFromQuery", String.class);
        inferFromQuery.setAccessible(true);
        String table1 = (String) inferFromQuery.invoke(checker, "SELECT * FROM public.users u WHERE u.id = ?");
        assertEquals("users", table1);
    }

    @Test
    void testInferTableNameFromQuerySafeFallback() throws Exception {
        Method inferSafe = cls.getDeclaredMethod("inferTableNameFromQuerySafe", String.class, String.class);
        inferSafe.setAccessible(true);
        String table2 = (String) inferSafe.invoke(checker, null, "UserAccountRepository");
        assertEquals("user_account", table2);
    }

    @Test
    void testBuildLiquibaseNonLockingIndexChangeSet() throws Exception {
        Method buildCreate = cls.getDeclaredMethod("buildLiquibaseNonLockingIndexChangeSet", String.class, String.class);
        buildCreate.setAccessible(true);
        String createXml = (String) buildCreate.invoke(checker, "users", "email");
        assertTrue(
                createXml.contains("CREATE INDEX CONCURRENTLY idx_users_email ON users (email)")
                        || createXml.contains("CREATE INDEX idx_users_email ON users (email)")
        );
        assertTrue(createXml.toLowerCase().contains("rollback"));
    }

    @Test
    void testBuildLiquibaseDropIndexChangeSet() throws Exception {
        Method buildDrop = cls.getDeclaredMethod("buildLiquibaseDropIndexChangeSet", String.class);
        buildDrop.setAccessible(true);
        String dropXml = (String) buildDrop.invoke(checker, "idx_users_email");
        assertTrue(
                dropXml.contains("DROP INDEX CONCURRENTLY IF EXISTS idx_users_email")
                        || dropXml.contains("DROP INDEX idx_users_email")
        );
    }

    @Test
    void testSanitize() throws Exception {
        Method sanitize = cls.getDeclaredMethod("sanitize", String.class);
        sanitize.setAccessible(true);
        assertEquals("abc_123", sanitize.invoke(checker, "Abc-123"));
        assertEquals("", sanitize.invoke(checker, (Object) null));
    }

    @Test
    void testIndent() throws Exception {
        Method indent = cls.getDeclaredMethod("indent", String.class, int.class);
        indent.setAccessible(true);
        String indented = (String) indent.invoke(checker, "a\nb", 2);
        assertEquals("  a\n  b", indented);
    }

    @Test
    void testPrintConsolidatedIndexActions() throws Exception {
        Field suggested = cls.getDeclaredField("suggestedNewIndexes");
        suggested.setAccessible(true);
        @SuppressWarnings("unchecked")
        LinkedHashSet<String> suggestedSet = (LinkedHashSet<String>) suggested.get(checker);
        suggestedSet.add("users|email");
        suggestedSet.add("orders|user_id");

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.io.PrintStream originalOut = System.out;
        System.setOut(new java.io.PrintStream(baos));
        try {
            checker.printConsolidatedIndexActions();
        } finally {
            System.setOut(originalOut);
        }
        String out = baos.toString();
        assertTrue(out.contains("SUGGESTED NEW INDEXES"));
        assertTrue(checker.getTotalIndexCreateRecommendations() >= 2);
        assertTrue(checker.getTotalIndexDropRecommendations() >= 0);
    }
}
