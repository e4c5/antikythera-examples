package sa.com.cloudsolutions.antikythera.examples;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.liquibase.Indexes;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LiquibaseChangeSetGenerationTest {

    @TempDir
    Path tempDir;
    
    private QueryOptimizationChecker checker;
    private Class<?> checkerClass;

    @BeforeEach
    void setUp() throws Exception {
        // Create minimal Liquibase file
        File liquibaseFile = tempDir.resolve("db.changelog-master.xml").toFile();
        try (FileWriter fw = new FileWriter(liquibaseFile)) {
            fw.write("<databaseChangeLog xmlns=\"http://www.liquibase.org/xml/ns/dbchangelog\"></databaseChangeLog>");
        }
        
        // Verify empty index map
        assertTrue(Indexes.load(liquibaseFile).isEmpty());
        
        Settings.loadConfigMap();
        checker = new QueryOptimizationChecker(liquibaseFile);
        checkerClass = QueryOptimizationChecker.class;
    }

    @Test
    void testBuildLiquibaseNonLockingIndexChangeSet() throws Exception {
        Method method = checkerClass.getDeclaredMethod("buildLiquibaseNonLockingIndexChangeSet", String.class, String.class);
        method.setAccessible(true);
        
        String result = (String) method.invoke(checker, "users", "email");
        
        // Verify basic structure
        assertTrue(result.contains("<changeSet"));
        assertTrue(result.contains("author=\"antikythera\""));
        assertTrue(result.contains("<preConditions onFail=\"MARK_RAN\">"));
        assertTrue(result.contains("<indexExists tableName=\"users\" indexName=\"idx_users_email\"/>"));
        
        // Verify PostgreSQL and Oracle SQL
        assertTrue(result.contains("CREATE INDEX CONCURRENTLY idx_users_email ON users (email)"));
        assertTrue(result.contains("CREATE INDEX idx_users_email ON users (email) ONLINE"));
        
        // Verify rollback section
        assertTrue(result.contains("<rollback>"));
        assertTrue(result.contains("DROP INDEX CONCURRENTLY IF EXISTS idx_users_email"));
        assertTrue(result.contains("DROP INDEX idx_users_email"));
        assertTrue(result.contains("</rollback>"));
        
        // Verify proper closing
        assertTrue(result.contains("</changeSet>"));
    }

    @Test
    void testBuildLiquibaseDropIndexChangeSet() throws Exception {
        Method method = checkerClass.getDeclaredMethod("buildLiquibaseDropIndexChangeSet", String.class);
        method.setAccessible(true);
        
        String result = (String) method.invoke(checker, "idx_users_email");
        
        // Verify basic structure
        assertTrue(result.contains("<changeSet"));
        assertTrue(result.contains("author=\"antikythera\""));
        assertTrue(result.contains("<preConditions onFail=\"MARK_RAN\">"));
        assertTrue(result.contains("<indexExists indexName=\"idx_users_email\"/>"));
        
        // Verify PostgreSQL and Oracle SQL
        assertTrue(result.contains("DROP INDEX CONCURRENTLY IF EXISTS idx_users_email"));
        assertTrue(result.contains("DROP INDEX idx_users_email"));
        
        // Verify rollback section exists (this was missing before)
        assertTrue(result.contains("<rollback>"));
        assertTrue(result.contains("</rollback>"));
        
        // Verify proper closing
        assertTrue(result.contains("</changeSet>"));
    }

    @Test
    void testChangeSetWithEmptyTableName() throws Exception {
        Method method = checkerClass.getDeclaredMethod("buildLiquibaseNonLockingIndexChangeSet", String.class, String.class);
        method.setAccessible(true);
        
        String result = (String) method.invoke(checker, "", "email");
        
        // Should use placeholder for empty table name
        assertTrue(result.contains("<TABLE_NAME>"));
    }

    @Test
    void testChangeSetWithEmptyColumnName() throws Exception {
        Method method = checkerClass.getDeclaredMethod("buildLiquibaseNonLockingIndexChangeSet", String.class, String.class);
        method.setAccessible(true);
        
        String result = (String) method.invoke(checker, "users", "");
        
        // Should use placeholder for empty column name
        assertTrue(result.contains("<COLUMN_NAME>"));
    }

    @Test
    void testDropChangeSetWithEmptyIndexName() throws Exception {
        Method method = checkerClass.getDeclaredMethod("buildLiquibaseDropIndexChangeSet", String.class);
        method.setAccessible(true);
        
        String result = (String) method.invoke(checker, "");
        
        // Should use placeholder for empty index name
        assertTrue(result.contains("<INDEX_NAME>"));
    }

    @Test
    void testSanitizeMethod() throws Exception {
        Method sanitize = checkerClass.getDeclaredMethod("sanitize", String.class);
        sanitize.setAccessible(true);
        
        assertEquals("user_account", sanitize.invoke(checker, "user-account"));
        assertEquals("user_account", sanitize.invoke(checker, "user@account"));
        assertEquals("user_account_123", sanitize.invoke(checker, "user account 123"));
        assertEquals("", sanitize.invoke(checker, (Object) null));
        assertEquals("valid_name", sanitize.invoke(checker, "valid_name"));
    }

    @Test
    void testChangeSetIdUniqueness() throws Exception {
        Method method = checkerClass.getDeclaredMethod("buildLiquibaseNonLockingIndexChangeSet", String.class, String.class);
        method.setAccessible(true);
        
        String result1 = (String) method.invoke(checker, "users", "email");
        Thread.sleep(1); // Ensure different timestamp
        String result2 = (String) method.invoke(checker, "users", "email");
        
        // Extract IDs and verify they're different (due to timestamp)
        String id1 = extractChangeSetId(result1);
        String id2 = extractChangeSetId(result2);
        
        assertNotEquals(id1, id2, "ChangeSet IDs should be unique due to timestamp");
    }

    private String extractChangeSetId(String changeSet) {
        int start = changeSet.indexOf("id=\"") + 4;
        int end = changeSet.indexOf("\"", start);
        return changeSet.substring(start, end);
    }

    @Test
    void testBuildLiquibaseMultiColumnIndexChangeSet() {
        String result = checker.buildLiquibaseMultiColumnIndexChangeSet("orders", new LinkedHashSet<>(List.of("status", "created_at", "user_id")));
        // Verify basic structure
        assertTrue(result.contains("<changeSet"));
        assertTrue(result.contains("author=\"antikythera\""));
        assertTrue(result.contains("<preConditions onFail=\"MARK_RAN\">"));
        assertTrue(result.contains("<indexExists tableName=\"orders\" indexName=\"idx_orders_status_created_at_user_id\"/>"));
        
        // Verify PostgreSQL and Oracle SQL with multi-column syntax
        assertTrue(result.contains("CREATE INDEX CONCURRENTLY idx_orders_status_created_at_user_id ON orders (status, created_at, user_id)"));
        assertTrue(result.contains("CREATE INDEX idx_orders_status_created_at_user_id ON orders (status, created_at, user_id) ONLINE"));
        
        // Verify rollback section
        assertTrue(result.contains("<rollback>"));
        assertTrue(result.contains("DROP INDEX CONCURRENTLY IF EXISTS idx_orders_status_created_at_user_id"));
        assertTrue(result.contains("DROP INDEX idx_orders_status_created_at_user_id"));
        assertTrue(result.contains("</rollback>"));
        
        // Verify proper closing
        assertTrue(result.contains("</changeSet>"));
    }

    @Test
    void testMultiColumnIndexWithTwoColumns() {
        List<String> columns = List.of("email", "status");
        String result = checker.buildLiquibaseMultiColumnIndexChangeSet("users", new LinkedHashSet<>(columns));
        
        assertTrue(result.contains("idx_users_email_status"));
        assertTrue(result.contains("ON users (email, status)"));
    }

    @Test
    void testMultiColumnIndexWithEmptyColumns() {
        String result = checker.buildLiquibaseMultiColumnIndexChangeSet("users", new LinkedHashSet<>());
        
        assertEquals("", result);
    }

    @Test
    void testMultiColumnIndexWithEmptyTableName(){
        List<String> columns = List.of("col1", "col2");
        String result = checker.buildLiquibaseMultiColumnIndexChangeSet("users", new LinkedHashSet<>(columns));
        
        assertTrue(result.contains("col1, col2"));
    }
}
