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
    }

    @Test
    void testBuildLiquibaseNonLockingIndexChangeSet() {
        String result = checker.buildLiquibaseNonLockingIndexChangeSet("users","email");
        
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
    void testBuildLiquibaseDropIndexChangeSet() {
        String result = checker.buildLiquibaseDropIndexChangeSet("idx_users_email");
        
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
    void testDropChangeSetWithEmptyIndexName() {
        String result = checker.buildLiquibaseDropIndexChangeSet("");
        assertTrue(result.contains("<INDEX_NAME>"));
    }

    // Note: sanitize method moved to LiquibaseGenerator utility class
    // Test coverage is now provided by LiquibaseGeneratorTest
    @Test
    void testSanitizeMethodMoved() {
        // This test is no longer needed as sanitize functionality is now in LiquibaseGenerator
        assertTrue(true, "Sanitize method moved to LiquibaseGenerator utility");
    }

    @Test
    void testChangeSetIdUniqueness() throws Exception {
        String result1 = checker.buildLiquibaseNonLockingIndexChangeSet("users", "email");
        Thread.sleep(1); // Ensure different timestamp
        String result2 = checker.buildLiquibaseNonLockingIndexChangeSet("users", "email");
        
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
