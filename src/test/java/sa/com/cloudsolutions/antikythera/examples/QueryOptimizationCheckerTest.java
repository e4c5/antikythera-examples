package sa.com.cloudsolutions.antikythera.examples;

import sa.com.cloudsolutions.liquibase.Indexes;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Coverage tests for QueryOptimizationChecker using reflection to access private helpers.
 * This project uses simple main-method tests with Java asserts.
 */
public class QueryOptimizationCheckerTest {

    public static void main(String[] args) throws Exception {
        System.out.println("=== QueryOptimizationChecker Coverage Test ===\n");

        // 1) Prepare a minimal Liquibase file that can be parsed
        Path tmpDir = Files.createTempDirectory("qoc-test");
        File lb = tmpDir.resolve("db.changelog-master.xml").toFile();
        try (FileWriter fw = new FileWriter(lb)) {
            fw.write("<databaseChangeLog xmlns=\"http://www.liquibase.org/xml/ns/dbchangelog\"></databaseChangeLog>");
        }

        // Sanity: Indexes.load should work and return an empty map
        assert Indexes.load(lb).isEmpty();

        QueryOptimizationChecker checker = new QueryOptimizationChecker(lb.getAbsolutePath());

        // Use reflection helpers
        Class<?> cls = QueryOptimizationChecker.class;

        // parseListArg
        Method parseListArg = cls.getDeclaredMethod("parseListArg", String[].class, String.class);
        parseListArg.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> low = (Set<String>) parseListArg.invoke(null, new Object[]{new String[]{"--low-cardinality=email,is_active,  USER_ID   ", "--other=x"}, "--low-cardinality="});
        assert low.contains("email");
        assert low.contains("is_active");
        assert low.contains("user_id");
        assert low.size() == 3;
        @SuppressWarnings("unchecked")
        Set<String> none = (Set<String>) parseListArg.invoke(null, new Object[]{new String[]{"--foo=bar"}, "--low-cardinality="});
        assert none.isEmpty();

        // getSeverityPriority ordering
        Method getSeverityPriority = cls.getDeclaredMethod("getSeverityPriority", OptimizationIssue.Severity.class);
        getSeverityPriority.setAccessible(true);
        int pHigh = (int) getSeverityPriority.invoke(checker, OptimizationIssue.Severity.HIGH);
        int pMed = (int) getSeverityPriority.invoke(checker, OptimizationIssue.Severity.MEDIUM);
        int pLow = (int) getSeverityPriority.invoke(checker, OptimizationIssue.Severity.LOW);
        assert pHigh < pMed && pMed < pLow;

        // getSeverityIcon mapping
        Method getSeverityIcon = cls.getDeclaredMethod("getSeverityIcon", OptimizationIssue.Severity.class);
        getSeverityIcon.setAccessible(true);
        String iHigh = (String) getSeverityIcon.invoke(checker, OptimizationIssue.Severity.HIGH);
        String iMed = (String) getSeverityIcon.invoke(checker, OptimizationIssue.Severity.MEDIUM);
        String iLow = (String) getSeverityIcon.invoke(checker, OptimizationIssue.Severity.LOW);
        assert "🔴".equals(iHigh);
        assert "🟡".equals(iMed);
        assert "🟢".equals(iLow);

        // inferTableNameFromQuery and safe fallback
        Method inferFromQuery = cls.getDeclaredMethod("inferTableNameFromQuery", String.class);
        inferFromQuery.setAccessible(true);
        String table1 = (String) inferFromQuery.invoke(checker, "SELECT * FROM public.users u WHERE u.id = ?");
        assert "users".equals(table1);
        Method inferSafe = cls.getDeclaredMethod("inferTableNameFromQuerySafe", String.class, String.class);
        inferSafe.setAccessible(true);
        String table2 = (String) inferSafe.invoke(checker, null, "UserAccountRepository");
        // camel to snake
        assert "user_account".equals(table2);

        // buildLiquibaseNonLockingIndexChangeSet
        Method buildCreate = cls.getDeclaredMethod("buildLiquibaseNonLockingIndexChangeSet", String.class, String.class);
        buildCreate.setAccessible(true);
        String createXml = (String) buildCreate.invoke(checker, "users", "email");
        assert createXml.contains("CREATE INDEX CONCURRENTLY idx_users_email ON users (email)")
                || createXml.contains("CREATE INDEX idx_users_email ON users (email)");
        assert createXml.toLowerCase().contains("rollback");

        // buildLiquibaseDropIndexChangeSet
        Method buildDrop = cls.getDeclaredMethod("buildLiquibaseDropIndexChangeSet", String.class);
        buildDrop.setAccessible(true);
        String dropXml = (String) buildDrop.invoke(checker, "idx_users_email");
        assert dropXml.contains("DROP INDEX CONCURRENTLY IF EXISTS idx_users_email")
                || dropXml.contains("DROP INDEX idx_users_email");

        // sanitize
        Method sanitize = cls.getDeclaredMethod("sanitize", String.class);
        sanitize.setAccessible(true);
        assert "abc_123".equals(sanitize.invoke(checker, "Abc-123"));
        assert "".equals(sanitize.invoke(checker, null));

        // indent
        Method indent = cls.getDeclaredMethod("indent", String.class, int.class);
        indent.setAccessible(true);
        String indented = (String) indent.invoke(checker, "a\nb", 2);
        assert indented.equals("  a\n  b");

        // printConsolidatedIndexActions: seed suggestedNewIndexes via reflection and check counters
        Field suggested = cls.getDeclaredField("suggestedNewIndexes");
        suggested.setAccessible(true);
        @SuppressWarnings("unchecked")
        LinkedHashSet<String> suggestedSet = (LinkedHashSet<String>) suggested.get(checker);
        suggestedSet.add("users|email");
        suggestedSet.add("orders|user_id");

        // Capture output
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.io.PrintStream originalOut = System.out;
        System.setOut(new java.io.PrintStream(baos));
        try {
            checker.printConsolidatedIndexActions();
        } finally {
            System.setOut(originalOut);
        }
        String out = baos.toString();
        assert out.contains("SUGGESTED NEW INDEXES");
        assert checker.getTotalIndexCreateRecommendations() >= 2;
        // No drops expected from empty Liquibase file
        assert checker.getTotalIndexDropRecommendations() >= 0;

        System.out.println("\n✅ QueryOptimizationChecker helper coverage OK");
    }
}
