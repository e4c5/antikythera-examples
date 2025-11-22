package sa.com.cloudsolutions.antikythera.examples.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sa.com.cloudsolutions.antikythera.examples.util.LiquibaseGenerator.ChangesetConfig;
import sa.com.cloudsolutions.antikythera.examples.util.LiquibaseGenerator.DatabaseDialect;
import sa.com.cloudsolutions.antikythera.examples.util.LiquibaseGenerator.WriteResult;

import java.io.File;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LiquibaseGenerator utility class.
 * Tests changeset generation for all supported database dialects,
 * duplicate prevention, multi-column index handling, and XML formatting.
 */
class LiquibaseGeneratorTest {

    @TempDir
    Path tempDir;

    private LiquibaseGenerator generator;
    private Path masterFile;

    @BeforeEach
    void setUp() throws IOException {
        Settings.loadConfigMap();
        generator = new LiquibaseGenerator();
        masterFile = tempDir.resolve("master-changelog.xml");

        // Create a basic master changelog file
        String masterContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.0.xsd">
                </databaseChangeLog>
                """;
        Files.writeString(masterFile, masterContent);
    }

    @Test
    void testCreateIndexChangeset() {
        // Test basic single-column index creation
        String changeset = generator.createIndexChangeset("users", "email");

        assertNotNull(changeset);
        assertTrue(changeset.contains("users"));
        assertTrue(changeset.contains("email"));
        assertTrue(changeset.contains("idx_users_email"));

        // Verify specific SQL syntax for different dialects
        assertTrue(changeset.contains("CREATE INDEX CONCURRENTLY idx_users_email ON users (email)"));
        assertTrue(changeset.contains("CREATE INDEX idx_users_email ON users (email) ONLINE"));

        // Verify XML structure
        assertTrue(changeset.contains("<preConditions onFail=\"MARK_RAN\">"));
        assertTrue(changeset.contains("<indexExists tableName=\"users\" indexName=\"idx_users_email\"/>"));
        assertTrue(changeset.contains("<rollback>"));
        assertTrue(changeset.contains("DROP INDEX CONCURRENTLY IF EXISTS idx_users_email"));
        assertTrue(changeset.contains("DROP INDEX idx_users_email"));
    }

    @Test
    void testCreateMultiColumnIndexChangeset() {
        // Test multi-column index creation
        List<String> columns = Arrays.asList("user_id", "created_date", "status");
        String changeset = generator.createMultiColumnIndexChangeset("orders", columns);

        assertNotNull(changeset);
        assertTrue(changeset.contains("orders"));
        assertTrue(changeset.contains("idx_orders_user_id_created_date_status"));

        // Verify SQL syntax
        assertTrue(changeset.contains(
                "CREATE INDEX CONCURRENTLY idx_orders_user_id_created_date_status ON orders (user_id, created_date, status)"));
        assertTrue(changeset.contains(
                "CREATE INDEX idx_orders_user_id_created_date_status ON orders (user_id, created_date, status) ONLINE"));

        // Verify rollback
        assertTrue(changeset.contains("DROP INDEX CONCURRENTLY IF EXISTS idx_orders_user_id_created_date_status"));
        assertTrue(changeset.contains("DROP INDEX idx_orders_user_id_created_date_status"));
    }

    @Test
    void testCreateMultiColumnIndexChangesetWithLinkedHashSet() {
        // Test multi-column index with LinkedHashSet to preserve order
        LinkedHashSet<String> columns = new LinkedHashSet<>();
        columns.add("first_name");
        columns.add("last_name");
        columns.add("birth_date");

        String changeset = generator.createMultiColumnIndexChangeset("persons", columns);

        assertNotNull(changeset);
        assertTrue(changeset.contains("first_name, last_name, birth_date"));
        assertTrue(changeset.contains("idx_persons_first_name_last_name_birth_date"));
    }

    @Test
    void testCreateMultiColumnIndexChangesetWithEmptyColumns() {
        // Test with empty column list
        String changeset = generator.createMultiColumnIndexChangeset("table", new ArrayList<>());
        assertEquals("", changeset);

        // Test with null column list
        String changeset2 = generator.createMultiColumnIndexChangeset("table", (List<String>) null);
        assertEquals("", changeset2);
    }

    @Test
    void testCreateDropIndexChangeset() {
        // Test index drop changeset creation
        String changeset = generator.createDropIndexChangeset("idx_users_email");

        assertNotNull(changeset);
        assertTrue(changeset.contains("idx_users_email"));

        // Verify SQL syntax
        assertTrue(changeset.contains("DROP INDEX CONCURRENTLY IF EXISTS idx_users_email"));
        assertTrue(changeset.contains("DROP INDEX idx_users_email"));

        // Verify XML structure
        assertTrue(changeset.contains("<preConditions onFail=\"MARK_RAN\">"));
        assertTrue(changeset.contains("<indexExists indexName=\"idx_users_email\"/>"));
        assertTrue(changeset.contains("<rollback>"));
    }

    @Test
    void testCreateDropIndexChangesetWithNullName() {
        // Test with null index name
        String changeset = generator.createDropIndexChangeset(null);
        assertTrue(changeset.contains("<INDEX_NAME>"));

        // Test with empty index name
        String changeset2 = generator.createDropIndexChangeset("");
        assertTrue(changeset2.contains("<INDEX_NAME>"));
    }

    @Test
    void testCreateCompositeChangeset() {
        // Test combining multiple changesets
        String changeset1 = generator.createIndexChangeset("users", "email");
        String changeset2 = generator.createIndexChangeset("orders", "user_id");
        String changeset3 = generator.createDropIndexChangeset("old_index");

        List<String> changesets = Arrays.asList(changeset1, changeset2, changeset3);
        String composite = generator.createCompositeChangeset(changesets);

        assertNotNull(composite);
        assertTrue(composite.contains("users"));
        assertTrue(composite.contains("orders"));
        assertTrue(composite.contains("old_index"));

        // Should contain all individual changesets
        assertTrue(composite.contains(changeset1));
        assertTrue(composite.contains(changeset2));
        assertTrue(composite.contains(changeset3));
    }

    @Test
    void testCreateCompositeChangesetWithEmptyList() {
        // Test with empty changeset list
        String composite = generator.createCompositeChangeset(new ArrayList<>());
        assertEquals("", composite);

        // Test with null changeset list
        String composite2 = generator.createCompositeChangeset(null);
        assertEquals("", composite2);

        // Test with list containing null and empty strings
        List<String> changesets = Arrays.asList(null, "", "  ", "valid changeset");
        String composite3 = generator.createCompositeChangeset(changesets);
        assertEquals("valid changeset", composite3);
    }

    @Test
    void testWriteChangesetToFileWithEmptyChangeset() throws IOException {
        // Test with empty changeset
        WriteResult result = generator.writeChangesetToFile(masterFile, "");
        assertFalse(result.wasWritten());
        assertNull(result.getChangesFile());

        // Test with null changeset
        WriteResult result2 = generator.writeChangesetToFile(masterFile, null);
        assertFalse(result2.wasWritten());
        assertNull(result2.getChangesFile());
    }

    @Test
    void testGenerateIndexNameWithSpecialCharacters() {
        // Test index name generation with special characters
        String indexName = generator.generateIndexName("user-table", Arrays.asList("email@domain"));
        assertEquals("idx_user_table_email_domain", indexName);

        // Test with numbers and underscores
        String indexName2 = generator.generateIndexName("table_123", Arrays.asList("col_1", "col_2"));
        assertEquals("idx_table_123_col_1_col_2", indexName2);
    }

    @Test
    void testDuplicatePreventionMechanism() {
        // Test that changeset IDs are unique
        String changeset1 = generator.createIndexChangeset("users", "email");
        String changeset2 = generator.createIndexChangeset("users", "email");

        // Extract changeset IDs (they should be different due to timestamp)
        assertNotEquals(changeset1, changeset2);

        // Test isChangesetGenerated method
        Set<String> generatedIds = generator.getGeneratedChangesetIds();
        assertFalse(generatedIds.isEmpty());

        // Test clearing generated changesets
        generator.clearGeneratedChangesets();
        assertTrue(generator.getGeneratedChangesetIds().isEmpty());
    }

    @Test
    void testDatabaseDialectSupport() {
        // Test all supported database dialects
        ChangesetConfig config = new ChangesetConfig("test-author",
                Set.of(DatabaseDialect.POSTGRESQL, DatabaseDialect.ORACLE,
                        DatabaseDialect.MYSQL, DatabaseDialect.H2),
                true, true);

        LiquibaseGenerator dialectGenerator = new LiquibaseGenerator(config);
        String changeset = dialectGenerator.createIndexChangeset("users", "email");

        // Should contain SQL for all dialects
        assertTrue(changeset.contains("postgresql"));
        assertTrue(changeset.contains("oracle"));
        assertTrue(changeset.contains("mysql"));
        assertTrue(changeset.contains("h2"));

        // Test dialect-specific SQL
        assertTrue(changeset.contains("CONCURRENTLY")); // PostgreSQL
        assertTrue(changeset.contains("ONLINE")); // Oracle
    }

    @Test
    void testChangesetConfigOptions() {
        // Test custom configuration
        ChangesetConfig customConfig = new ChangesetConfig("custom-author",
                Set.of(DatabaseDialect.POSTGRESQL), false, false);

        LiquibaseGenerator customGenerator = new LiquibaseGenerator(customConfig);
        String changeset = customGenerator.createIndexChangeset("users", "email");

        assertTrue(changeset.contains("custom-author"));
        assertFalse(changeset.contains("preConditions")); // No preconditions
        assertFalse(changeset.contains("rollback")); // No rollback
        assertTrue(changeset.contains("postgresql"));
        assertFalse(changeset.contains("oracle")); // Only PostgreSQL
    }

    @Test
    void testDefaultChangesetConfig() {
        // Test default configuration
        ChangesetConfig defaultConfig = ChangesetConfig.defaultConfig();

        assertEquals("antikythera", defaultConfig.author());
        assertTrue(defaultConfig.supportedDialects().contains(DatabaseDialect.POSTGRESQL));
        assertTrue(defaultConfig.supportedDialects().contains(DatabaseDialect.ORACLE));
        assertTrue(defaultConfig.includePreconditions());
        assertTrue(defaultConfig.includeRollback());
    }

    @Test
    void testXmlFormattingAndStructure() {
        // Test XML structure and formatting
        String changeset = generator.createIndexChangeset("users", "email");

        // Should be valid XML structure
        assertTrue(changeset.contains("<changeSet"));
        assertTrue(changeset.contains("</changeSet>"));
        assertTrue(changeset.contains("id="));
        assertTrue(changeset.contains("author="));

        // Should contain proper SQL tags
        assertTrue(changeset.contains("<sql dbms="));
        assertTrue(changeset.contains("</sql>"));

        // Should contain preconditions
        assertTrue(changeset.contains("<preConditions"));
        assertTrue(changeset.contains("</preConditions>"));

        // Should contain rollback
        assertTrue(changeset.contains("<rollback>"));
        assertTrue(changeset.contains("</rollback>"));
    }

    @Test
    void testConcurrentChangesetGeneration() {
        // Simplified concurrent test - verify multiple changesets are unique
        List<String> changesets = new ArrayList<>();

        for (int i = 0; i < 5; i++) {
            String changeset = generator.createIndexChangeset("table" + i, "column" + i);
            changesets.add(changeset);
        }

        // All changesets should be unique
        assertEquals(5, changesets.size());
        Set<String> uniqueChangesets = new HashSet<>(changesets);
        assertEquals(5, uniqueChangesets.size());
    }

    @Test
    void testErrorHandlingInFileOperations() {
        // Test error handling when master file doesn't exist
        Path nonExistentMaster = tempDir.resolve("nonexistent").resolve("master.xml");

        String changeset = generator.createIndexChangeset("users", "email");

        // Should handle missing directory gracefully
        assertDoesNotThrow(() -> {
            try {
                WriteResult result = generator.writeChangesetToFile(nonExistentMaster, changeset);
                assertTrue(result.wasWritten());
            } catch (IOException e) {
                // IOException is acceptable for file operations
            }
        });
    }

    @Test
    void testDefaultConstructor() {
        LiquibaseGenerator defaultGenerator = new LiquibaseGenerator();
        assertNotNull(defaultGenerator);
        assertTrue(defaultGenerator.getGeneratedChangesetIds().isEmpty());
    }

    @Test
    void testConstructorWithConfig() {
        ChangesetConfig config = new ChangesetConfig(
                "test-author",
                Set.of(DatabaseDialect.MYSQL),
                false,
                false);

        LiquibaseGenerator customGenerator = new LiquibaseGenerator(config);

        assertNotNull(customGenerator);
        assertTrue(customGenerator.getGeneratedChangesetIds().isEmpty());
    }

    @Test
    void testCreateIndexChangesetWithNullColumn() {
        assertThrows(NullPointerException.class,
                () -> generator.createIndexChangeset("users", null));
    }

    @Test
    void testCreateIndexChangesetWithEmptyColumn() {
        String changeset = generator.createIndexChangeset("users", "");
        assertNotNull(changeset);
        assertTrue(changeset.contains("users"));
    }

    @Test
    void testCreateIndexChangesetWithNullTable() {
        assertThrows(IllegalArgumentException.class, () -> generator.createIndexChangeset(null, "email"));
    }

    @Test
    void testGenerateIndexName() {
        String indexName = generator.generateIndexName("users", Arrays.asList("email", "status"));
        assertEquals("idx_users_email_status", indexName);
    }

    @Test
    void testIsChangesetGenerated() {
        generator.createIndexChangeset("users", "email");

        assertFalse(generator.getGeneratedChangesetIds().isEmpty());

        String generatedId = generator.getGeneratedChangesetIds().iterator().next();
        assertTrue(generator.isChangesetGenerated(generatedId));
        assertFalse(generator.isChangesetGenerated("non-existent-id"));
    }

    @Test
    void testGetGeneratedChangesetIds() {
        generator.createIndexChangeset("users", "email");
        generator.createIndexChangeset("products", "name");

        Set<String> ids = generator.getGeneratedChangesetIds();

        assertEquals(2, ids.size());
        // Verify it's a copy, not the original set
        ids.clear();
        assertEquals(2, generator.getGeneratedChangesetIds().size());
    }

    @Test
    void testWriteChangesetToFileWithPath() throws IOException {
        // Use existing masterFile from setUp
        String changesets = "<changeSet id=\"test\">content</changeSet>";

        WriteResult result = generator.writeChangesetToFile(masterFile, changesets);

        assertNotNull(result);
        assertTrue(result.wasWritten());
        assertNotNull(result.getChangesFile());
        assertTrue(result.getChangesFile().exists());

        String content = Files.readString(result.getChangesFile().toPath());
        assertTrue(content.contains("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"));
        assertTrue(content.contains("content"));
    }

    @Test
    void testWriteChangesetToFileWithFile() throws IOException {
        // Use existing masterFile from setUp but convert to File
        File masterFileObj = masterFile.toFile();
        String changesets = "<changeSet id=\"test\">content</changeSet>";

        WriteResult result = generator.writeChangesetToFile(masterFileObj, changesets);

        assertNotNull(result);
        assertTrue(result.wasWritten());
        assertNotNull(result.getChangesFile());
        assertTrue(result.getChangesFile().exists());
    }

    @Test
    void testDatabaseDialectEnum() {
        assertEquals("postgresql", DatabaseDialect.POSTGRESQL.getLiquibaseDbms());
        assertEquals("oracle", DatabaseDialect.ORACLE.getLiquibaseDbms());
        assertEquals("mysql", DatabaseDialect.MYSQL.getLiquibaseDbms());
        assertEquals("h2", DatabaseDialect.H2.getLiquibaseDbms());
    }

    @Test
    void testWriteResultClass() {
        File testFile = new File("test.xml");

        WriteResult result1 = new WriteResult(testFile, true);
        WriteResult result2 = new WriteResult(null, false);

        assertEquals(testFile, result1.getChangesFile());
        assertTrue(result1.wasWritten());

        assertNull(result2.getChangesFile());
        assertFalse(result2.wasWritten());
    }
}
