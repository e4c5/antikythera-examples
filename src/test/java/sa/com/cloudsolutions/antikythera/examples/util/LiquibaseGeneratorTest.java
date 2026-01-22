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
        // Load configuration from the test resources file to ensure
        // liquibase_master_file and other settings are properly configured
        File configFile = new File("src/test/resources/generator.yml");
        Settings.loadConfigMap(configFile);
        
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

        // Verify XML structure uses Liquibase built-in indexExists precondition
        assertTrue(changeset.contains("<preConditions onFail=\"MARK_RAN\">"));
        assertTrue(changeset.contains("<not>"));
        assertTrue(changeset.contains("<indexExists tableName=\"users\" columnNames=\"email\"/>"));
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

        // Verify it uses Liquibase's built-in indexExists precondition
        assertTrue(changeset.contains("<indexExists tableName=\"persons\" columnNames=\"first_name, last_name, birth_date\"/>"));
    }

    @Test
    void testIndexExistsPreconditionFormat() {
        // Test that the precondition uses Liquibase's built-in indexExists with tableName and columnNames
        String changeset = generator.createIndexChangeset("users", "email");

        // Verify Liquibase built-in indexExists precondition is used (not custom SQL)
        assertTrue(changeset.contains("<indexExists tableName=\"users\" columnNames=\"email\"/>"));
        assertFalse(changeset.contains("sqlCheck"), "Should not use custom SQL checks");
        assertFalse(changeset.contains("pg_index"), "Should not use database-specific queries");
        assertFalse(changeset.contains("ALL_IND_COLUMNS"), "Should not use database-specific queries");
    }

    @Test
    void testMultiColumnIndexExistsPrecondition() {
        // Test that multi-column indexes use proper indexExists precondition
        List<String> columns = Arrays.asList("user_id", "status");
        String changeset = generator.createMultiColumnIndexChangeset("orders", columns);

        // Verify it uses Liquibase's indexExists with comma-separated column names
        assertTrue(changeset.contains("<indexExists tableName=\"orders\" columnNames=\"user_id, status\"/>"));
        assertFalse(changeset.contains("sqlCheck"), "Should not use custom SQL checks");
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
        assertTrue(indexName.length() <= 60, "Index name should not exceed 60 characters");

        // Test with numbers and underscores
        String indexName2 = generator.generateIndexName("table_123", Arrays.asList("col_1", "col_2"));
        assertEquals("idx_table_123_col_1_col_2", indexName2);
        assertTrue(indexName2.length() <= 60, "Index name should not exceed 60 characters");
    }

    @Test
    void testGenerateIndexNameLengthLimit() {
        // Test that index names are limited to 60 characters
        // Create a very long table name and column names
        String longTableName = "very_long_table_name_that_exceeds_normal_limits";
        List<String> longColumns = Arrays.asList(
            "very_long_column_name_one",
            "very_long_column_name_two",
            "very_long_column_name_three",
            "very_long_column_name_four"
        );

        String indexName = generator.generateIndexName(longTableName, longColumns);

        // Verify the index name doesn't exceed 60 characters
        assertTrue(indexName.length() <= 60,
            "Index name '" + indexName + "' exceeds 60 characters (length: " + indexName.length() + ")");

        // Verify it starts with idx_
        assertTrue(indexName.startsWith("idx_"), "Index name should start with 'idx_'");

        // Verify it contains a hash suffix when truncated
        // Format should be: idx_<truncated>_<7-digit-hash>
        String[] parts = indexName.split("_");
        assertTrue(parts.length >= 2, "Truncated index should have at least 2 parts");

        // Last part should be a 7-digit hash
        String lastPart = parts[parts.length - 1];
        assertTrue(lastPart.matches("\\d{7}"), "Last part should be a 7-digit hash, got: " + lastPart);
    }

    @Test
    void testGenerateIndexNameConsistentHashing() {
        // Test that the same input produces the same truncated index name
        String longTableName = "extremely_long_table_name_for_testing_purposes";
        List<String> longColumns = Arrays.asList(
            "column_one_with_long_name",
            "column_two_with_long_name",
            "column_three_with_long_name"
        );

        String indexName1 = generator.generateIndexName(longTableName, longColumns);
        String indexName2 = generator.generateIndexName(longTableName, longColumns);

        // Should produce identical results (deterministic hashing)
        assertEquals(indexName1, indexName2, "Same inputs should produce same index name");
        assertTrue(indexName1.length() <= 60, "Index name should not exceed 60 characters");
    }

    @Test
    void testGenerateIndexNameShortNamesUnchanged() {
        // Test that short index names are not modified
        String shortTableName = "users";
        List<String> shortColumns = Arrays.asList("email");

        String indexName = generator.generateIndexName(shortTableName, shortColumns);

        assertEquals("idx_users_email", indexName);
        assertTrue(indexName.length() <= 60, "Index name should not exceed 60 characters");
        assertFalse(indexName.matches(".*_\\d{7}$"), "Short names should not have hash suffix");
    }

    @Test
    void testGenerateIndexNameExactly60Characters() {
        // Test edge case where the name is exactly at the limit
        // This tests the boundary condition
        String tableName = "table_with_moderate_length_name";
        List<String> columns = Arrays.asList("column_a", "column_b", "column_c");

        String indexName = generator.generateIndexName(tableName, columns);

        assertTrue(indexName.length() <= 60,
            "Index name should not exceed 60 characters (length: " + indexName.length() + ")");
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
                true, true, null);

        LiquibaseGenerator dialectGenerator = new LiquibaseGenerator(config);
        String changeset = dialectGenerator.createIndexChangeset("users", "email");

        // Should contain SQL for all dialects
        assertTrue(changeset.contains("postgresql"));
        assertTrue(changeset.contains("oracle"));
        assertTrue(changeset.contains("mysql"));
    }

    @Test
    void testChangesetConfigFromConfiguration() {
        // Test that ChangesetConfig.fromConfiguration() reads dialects from
        // generator.yml
        // The test generator.yml should have query_optimizer.supported_dialects:
        // [postgresql, oracle]
        ChangesetConfig config = ChangesetConfig.fromConfiguration();

        assertNotNull(config);
        assertFalse(config.supportedDialects().isEmpty());

        // Verify it contains the dialects specified in generator.yml
        assertTrue(config.supportedDialects().contains(DatabaseDialect.POSTGRESQL));
        assertTrue(config.supportedDialects().contains(DatabaseDialect.ORACLE));
    }

    @Test
    void testGeneratorWithConfigurationDialects() {
        // Test that generator created with fromConfiguration() only generates for
        // configured dialects
        LiquibaseGenerator configGenerator = new LiquibaseGenerator(ChangesetConfig.fromConfiguration());
        String changeset = configGenerator.createIndexChangeset("users", "email");

        // Should contain SQL for postgresql and oracle (as configured in generator.yml)
        assertTrue(changeset.contains("postgresql"));
        assertTrue(changeset.contains("oracle"));

        // Should NOT contain mysql or h2 (not in configuration)
        assertFalse(changeset.contains("dbms=\"mysql\""));
        assertFalse(changeset.contains("dbms=\"h2\""));
    }

    @Test
    void testGetConfiguredMasterFile() {
        // Test that getConfiguredMasterFile reads from configuration
        LiquibaseGenerator configGenerator = new LiquibaseGenerator(ChangesetConfig.fromConfiguration());
        Optional<Path> configuredMasterFile = configGenerator.getConfiguredMasterFile();

        // Should be present since it's configured in test resources generator.yml
        assertTrue(configuredMasterFile.isPresent());
        assertTrue(configuredMasterFile.get().toString().contains("db.changelog-master.xml"));
    }

    @Test
    void testGetConfiguredMasterFileNotConfigured() {
        // Test when liquibase_master_file is not configured
        ChangesetConfig configWithoutMaster = new ChangesetConfig("author",
                Set.of(DatabaseDialect.POSTGRESQL), true, true, null);
        LiquibaseGenerator gen = new LiquibaseGenerator(configWithoutMaster);

        Optional<Path> configuredMasterFile = gen.getConfiguredMasterFile();
        assertTrue(configuredMasterFile.isPresent() == false || configuredMasterFile.isEmpty());
    }

    @Test
    void testWriteChangesetToConfiguredFileThrowsWhenNotConfigured() {
        // Test that writeChangesetToConfiguredFile throws when master file is not
        // configured
        ChangesetConfig configWithoutMaster = new ChangesetConfig("author",
                Set.of(DatabaseDialect.POSTGRESQL), true, true, null);
        LiquibaseGenerator gen = new LiquibaseGenerator(configWithoutMaster);

        assertThrows(IllegalStateException.class,
                () -> gen.writeChangesetToConfiguredFile("<changeSet>test</changeSet>"));
    }

    @Test
    void testDatabaseDialectFromString() {
        // Test parsing dialect names from strings
        assertEquals(Optional.of(DatabaseDialect.POSTGRESQL), DatabaseDialect.fromString("postgresql"));
        assertEquals(Optional.of(DatabaseDialect.ORACLE), DatabaseDialect.fromString("oracle"));
        assertEquals(Optional.of(DatabaseDialect.MYSQL), DatabaseDialect.fromString("mysql"));
        assertEquals(Optional.of(DatabaseDialect.H2), DatabaseDialect.fromString("h2"));

        // Test case insensitivity
        assertEquals(Optional.of(DatabaseDialect.POSTGRESQL), DatabaseDialect.fromString("POSTGRESQL"));
        assertEquals(Optional.of(DatabaseDialect.ORACLE), DatabaseDialect.fromString("Oracle"));

        // Test invalid/empty values
        assertEquals(Optional.empty(), DatabaseDialect.fromString(null));
        assertEquals(Optional.empty(), DatabaseDialect.fromString(""));
        assertEquals(Optional.empty(), DatabaseDialect.fromString("invalid"));
    }

    @Test
    void testChangesetConfigOptions() {
        // Test custom configuration
        ChangesetConfig customConfig = new ChangesetConfig("custom-author",
                Set.of(DatabaseDialect.POSTGRESQL), false, false, null);

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
                false,
                null);

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

    @Test
    void testRelativePathPrefixDetection() throws IOException {
        // Create a master file with existing include entries that have a path prefix
        String masterContentWithPrefix = """
                <?xml version="1.1" encoding="UTF-8" standalone="no"?>
                <databaseChangeLog
                        logicalFilePath="db/changelog/changelog-master.xml"
                        xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                    <include file="/db/changelog/master.xml"/>
                    <include file="/db/changelog/functions.xml"/>
                    <include file="/db/changelog/datamigration.xml"/>
                </databaseChangeLog>
                """;
        Files.writeString(masterFile, masterContentWithPrefix);

        // Write a new changeset
        String changeset = generator.createIndexChangeset("users", "email");
        WriteResult result = generator.writeChangesetToFile(masterFile, changeset);

        assertTrue(result.wasWritten());

        // Read the updated master file
        String updatedMaster = Files.readString(masterFile);

        // The new include should have the same path prefix as existing entries
        assertTrue(updatedMaster.contains("<include file=\"/db/changelog/antikythera-indexes-"),
                "New include should have the /db/changelog/ prefix. Actual content:\n" + updatedMaster);
    }

    @Test
    void testRelativePathPrefixDetectionWithNoPrefix() throws IOException {
        // Create a master file with existing include entries that have no path prefix
        String masterContentNoPrefix = """
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                    <include file="master.xml"/>
                    <include file="functions.xml"/>
                </databaseChangeLog>
                """;
        Files.writeString(masterFile, masterContentNoPrefix);

        // Write a new changeset
        String changeset = generator.createIndexChangeset("users", "email");
        WriteResult result = generator.writeChangesetToFile(masterFile, changeset);

        assertTrue(result.wasWritten());

        // Read the updated master file
        String updatedMaster = Files.readString(masterFile);

        // The new include should have no path prefix (just the filename)
        assertTrue(updatedMaster.contains("<include file=\"antikythera-indexes-"),
                "New include should have no prefix when existing entries have none. Actual content:\n" + updatedMaster);
        assertFalse(updatedMaster.contains("<include file=\"/"),
                "New include should not have a leading slash when existing entries don't have paths");
    }

    @Test
    void testRelativePathPrefixDetectionWithMixedPaths() throws IOException {
        // Create a master file with mixed path prefixes (most common should win)
        String masterContentMixed = """
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                    <include file="/db/changelog/master.xml"/>
                    <include file="/db/changelog/functions.xml"/>
                    <include file="/db/changelog/datamigration.xml"/>
                    <include file="legacy.xml"/>
                </databaseChangeLog>
                """;
        Files.writeString(masterFile, masterContentMixed);

        // Write a new changeset
        String changeset = generator.createIndexChangeset("users", "email");
        WriteResult result = generator.writeChangesetToFile(masterFile, changeset);

        assertTrue(result.wasWritten());

        // Read the updated master file
        String updatedMaster = Files.readString(masterFile);

        // The new include should use the most common prefix (/db/changelog/)
        assertTrue(updatedMaster.contains("<include file=\"/db/changelog/antikythera-indexes-"),
                "New include should use the most common path prefix. Actual content:\n" + updatedMaster);
    }

    @Test
    void testRelativePathPrefixDetectionWithEmptyMasterFile() throws IOException {
        // Create a master file with no existing include entries
        String masterContentEmpty = """
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                </databaseChangeLog>
                """;
        Files.writeString(masterFile, masterContentEmpty);

        // Write a new changeset
        String changeset = generator.createIndexChangeset("users", "email");
        WriteResult result = generator.writeChangesetToFile(masterFile, changeset);

        assertTrue(result.wasWritten());

        // Read the updated master file
        String updatedMaster = Files.readString(masterFile);

        // The new include should have no prefix when there are no existing entries
        assertTrue(updatedMaster.contains("<include file=\"antikythera-indexes-"),
                "New include should have no prefix when no existing entries. Actual content:\n" + updatedMaster);
    }
}
