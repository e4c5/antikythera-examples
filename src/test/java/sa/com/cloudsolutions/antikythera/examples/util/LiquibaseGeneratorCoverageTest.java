package sa.com.cloudsolutions.antikythera.examples.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test coverage for LiquibaseGenerator
 */
class LiquibaseGeneratorCoverageTest {

    private LiquibaseGenerator generator;
    
    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        generator = new LiquibaseGenerator();
    }

    @Test
    void testDefaultConstructor() {
        // When
        LiquibaseGenerator defaultGenerator = new LiquibaseGenerator();
        
        // Then
        assertNotNull(defaultGenerator);
        assertTrue(defaultGenerator.getGeneratedChangesetIds().isEmpty());
    }

    @Test
    void testConstructorWithConfig() {
        // Given
        LiquibaseGenerator.ChangesetConfig config = new LiquibaseGenerator.ChangesetConfig(
            "test-author",
            Set.of(LiquibaseGenerator.DatabaseDialect.MYSQL),
            false,
            false
        );
        
        // When
        LiquibaseGenerator customGenerator = new LiquibaseGenerator(config);
        
        // Then
        assertNotNull(customGenerator);
        assertTrue(customGenerator.getGeneratedChangesetIds().isEmpty());
    }

    @Test
    void testCreateIndexChangesetWithValidInput() {
        // When
        String changeset = generator.createIndexChangeset("users", "email");
        
        // Then
        assertNotNull(changeset);
        assertTrue(changeset.contains("users"));
        assertTrue(changeset.contains("email"));
        assertTrue(changeset.contains("idx_users_email"));
        assertTrue(changeset.contains("CREATE INDEX"));
        assertTrue(changeset.contains("antikythera"));
    }

    @Test
    void testCreateIndexChangesetWithNullColumn() {
        // When
        String changeset = generator.createIndexChangeset("users", null);
        
        // Then
        assertNotNull(changeset);
        assertTrue(changeset.contains("users"));
        assertTrue(changeset.contains("<COLUMN_NAME>"));
    }

    @Test
    void testCreateIndexChangesetWithEmptyColumn() {
        // When
        String changeset = generator.createIndexChangeset("users", "");
        
        // Then
        assertNotNull(changeset);
        assertTrue(changeset.contains("users"));
        assertTrue(changeset.contains("<COLUMN_NAME>"));
    }

    @Test
    void testCreateIndexChangesetWithNullTable() {
        // When
        String changeset = generator.createIndexChangeset(null, "email");
        
        // Then
        assertNotNull(changeset);
        assertTrue(changeset.contains("<TABLE_NAME>"));
        assertTrue(changeset.contains("email"));
    }

    @Test
    void testCreateMultiColumnIndexChangesetWithList() {
        // Given
        List<String> columns = Arrays.asList("first_name", "last_name");
        
        // When
        String changeset = generator.createMultiColumnIndexChangeset("users", columns);
        
        // Then
        assertNotNull(changeset);
        assertTrue(changeset.contains("users"));
        assertTrue(changeset.contains("first_name"));
        assertTrue(changeset.contains("last_name"));
        assertTrue(changeset.contains("idx_users_first_name_last_name"));
    }

    @Test
    void testCreateMultiColumnIndexChangesetWithLinkedHashSet() {
        // Given
        LinkedHashSet<String> columns = new LinkedHashSet<>();
        columns.add("category_id");
        columns.add("status");
        
        // When
        String changeset = generator.createMultiColumnIndexChangeset("products", columns);
        
        // Then
        assertNotNull(changeset);
        assertTrue(changeset.contains("products"));
        assertTrue(changeset.contains("category_id"));
        assertTrue(changeset.contains("status"));
        assertTrue(changeset.contains("idx_products_category_id_status"));
    }

    @Test
    void testCreateMultiColumnIndexChangesetWithEmptyList() {
        // When
        String changeset = generator.createMultiColumnIndexChangeset("users", Collections.emptyList());
        
        // Then
        assertEquals("", changeset);
    }

    @Test
    void testCreateMultiColumnIndexChangesetWithNullList() {
        // When
        String changeset = generator.createMultiColumnIndexChangeset("users", (List<String>) null);
        
        // Then
        assertEquals("", changeset);
    }

    @Test
    void testCreateDropIndexChangeset() {
        // When
        String changeset = generator.createDropIndexChangeset("idx_users_email");
        
        // Then
        assertNotNull(changeset);
        assertTrue(changeset.contains("idx_users_email"));
        assertTrue(changeset.contains("DROP INDEX"));
        assertTrue(changeset.contains("drop_idx_users_email"));
        assertTrue(changeset.contains("antikythera"));
    }

    @Test
    void testCreateDropIndexChangesetWithNullIndex() {
        // When
        String changeset = generator.createDropIndexChangeset(null);
        
        // Then
        assertNotNull(changeset);
        assertTrue(changeset.contains("<INDEX_NAME>"));
    }

    @Test
    void testCreateDropIndexChangesetWithEmptyIndex() {
        // When
        String changeset = generator.createDropIndexChangeset("");
        
        // Then
        assertNotNull(changeset);
        assertTrue(changeset.contains("<INDEX_NAME>"));
    }

    @Test
    void testCreateCompositeChangeset() {
        // Given
        List<String> changesets = Arrays.asList(
            "<changeSet id=\"1\">content1</changeSet>",
            "<changeSet id=\"2\">content2</changeSet>"
        );
        
        // When
        String composite = generator.createCompositeChangeset(changesets);
        
        // Then
        assertNotNull(composite);
        assertTrue(composite.contains("content1"));
        assertTrue(composite.contains("content2"));
        assertTrue(composite.contains("\n\n"));
    }

    @Test
    void testCreateCompositeChangesetWithEmptyList() {
        // When
        String composite = generator.createCompositeChangeset(Collections.emptyList());
        
        // Then
        assertEquals("", composite);
    }

    @Test
    void testCreateCompositeChangesetWithNullList() {
        // When
        String composite = generator.createCompositeChangeset(null);
        
        // Then
        assertEquals("", composite);
    }

    @Test
    void testCreateCompositeChangesetFiltersNullAndEmpty() {
        // Given
        List<String> changesets = Arrays.asList(
            "<changeSet id=\"1\">content1</changeSet>",
            null,
            "",
            "   ",
            "<changeSet id=\"2\">content2</changeSet>"
        );
        
        // When
        String composite = generator.createCompositeChangeset(changesets);
        
        // Then
        assertNotNull(composite);
        assertTrue(composite.contains("content1"));
        assertTrue(composite.contains("content2"));
        assertFalse(composite.contains("null"));
    }

    @Test
    void testGenerateIndexName() {
        // When
        String indexName = generator.generateIndexName("users", Arrays.asList("email", "status"));
        
        // Then
        assertEquals("idx_users_email_status", indexName);
    }

    @Test
    void testGenerateIndexNameWithEmptyColumns() {
        // When
        String indexName = generator.generateIndexName("users", Collections.emptyList());
        
        // Then
        assertEquals("idx_users", indexName);
    }

    @Test
    void testGenerateIndexNameWithSpecialCharacters() {
        // When
        String indexName = generator.generateIndexName("user-table", Arrays.asList("email@domain", "status#field"));
        
        // Then
        assertEquals("idx_user_table_email_domain_status_field", indexName);
    }

    @Test
    void testIsChangesetGenerated() {
        // Given
        generator.createIndexChangeset("users", "email");
        
        // When & Then
        assertFalse(generator.getGeneratedChangesetIds().isEmpty());
        
        String generatedId = generator.getGeneratedChangesetIds().iterator().next();
        assertTrue(generator.isChangesetGenerated(generatedId));
        assertFalse(generator.isChangesetGenerated("non-existent-id"));
    }

    @Test
    void testGetGeneratedChangesetIds() {
        // Given
        generator.createIndexChangeset("users", "email");
        generator.createIndexChangeset("products", "name");
        
        // When
        Set<String> ids = generator.getGeneratedChangesetIds();
        
        // Then
        assertEquals(2, ids.size());
        // Verify it's a copy, not the original set
        ids.clear();
        assertEquals(2, generator.getGeneratedChangesetIds().size());
    }

    @Test
    void testClearGeneratedChangesets() {
        // Given
        generator.createIndexChangeset("users", "email");
        assertFalse(generator.getGeneratedChangesetIds().isEmpty());
        
        // When
        generator.clearGeneratedChangesets();
        
        // Then
        assertTrue(generator.getGeneratedChangesetIds().isEmpty());
    }

    @Test
    void testWriteChangesetToFileWithPath() throws IOException {
        // Given
        Path masterFile = createMasterFile();
        String changesets = "<changeSet id=\"test\">content</changeSet>";
        
        // When
        LiquibaseGenerator.WriteResult result = generator.writeChangesetToFile(masterFile, changesets);
        
        // Then
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
        // Given
        File masterFile = createMasterFile().toFile();
        String changesets = "<changeSet id=\"test\">content</changeSet>";
        
        // When
        LiquibaseGenerator.WriteResult result = generator.writeChangesetToFile(masterFile, changesets);
        
        // Then
        assertNotNull(result);
        assertTrue(result.wasWritten());
        assertNotNull(result.getChangesFile());
        assertTrue(result.getChangesFile().exists());
    }

    @Test
    void testWriteChangesetToFileWithEmptyChangesets() throws IOException {
        // Given
        Path masterFile = createMasterFile();
        
        // When
        LiquibaseGenerator.WriteResult result = generator.writeChangesetToFile(masterFile, "");
        
        // Then
        assertNotNull(result);
        assertFalse(result.wasWritten());
        assertNull(result.getChangesFile());
    }

    @Test
    void testWriteChangesetToFileWithNullChangesets() throws IOException {
        // Given
        Path masterFile = createMasterFile();
        
        // When
        LiquibaseGenerator.WriteResult result = generator.writeChangesetToFile(masterFile, null);
        
        // Then
        assertNotNull(result);
        assertFalse(result.wasWritten());
        assertNull(result.getChangesFile());
    }

    @Test
    void testDatabaseDialectEnum() {
        // Test all enum values and their properties
        assertEquals("postgresql", LiquibaseGenerator.DatabaseDialect.POSTGRESQL.getLiquibaseDbms());
        assertEquals("oracle", LiquibaseGenerator.DatabaseDialect.ORACLE.getLiquibaseDbms());
        assertEquals("mysql", LiquibaseGenerator.DatabaseDialect.MYSQL.getLiquibaseDbms());
        assertEquals("h2", LiquibaseGenerator.DatabaseDialect.H2.getLiquibaseDbms());
    }

    @Test
    void testChangesetConfigDefaultConfig() {
        // When
        LiquibaseGenerator.ChangesetConfig config = LiquibaseGenerator.ChangesetConfig.defaultConfig();
        
        // Then
        assertEquals("antikythera", config.getAuthor());
        assertTrue(config.getSupportedDialects().contains(LiquibaseGenerator.DatabaseDialect.POSTGRESQL));
        assertTrue(config.getSupportedDialects().contains(LiquibaseGenerator.DatabaseDialect.ORACLE));
        assertTrue(config.isIncludePreconditions());
        assertTrue(config.isIncludeRollback());
    }

    @Test
    void testChangesetConfigCustomConfig() {
        // Given
        Set<LiquibaseGenerator.DatabaseDialect> dialects = Set.of(LiquibaseGenerator.DatabaseDialect.MYSQL);
        
        // When
        LiquibaseGenerator.ChangesetConfig config = new LiquibaseGenerator.ChangesetConfig(
            "custom-author", dialects, false, true
        );
        
        // Then
        assertEquals("custom-author", config.getAuthor());
        assertEquals(dialects, config.getSupportedDialects());
        assertFalse(config.isIncludePreconditions());
        assertTrue(config.isIncludeRollback());
    }

    @Test
    void testWriteResultClass() {
        // Given
        File testFile = new File("test.xml");
        
        // When
        LiquibaseGenerator.WriteResult result1 = new LiquibaseGenerator.WriteResult(testFile, true);
        LiquibaseGenerator.WriteResult result2 = new LiquibaseGenerator.WriteResult(null, false);
        
        // Then
        assertEquals(testFile, result1.getChangesFile());
        assertTrue(result1.wasWritten());
        
        assertNull(result2.getChangesFile());
        assertFalse(result2.wasWritten());
    }

    @Test
    void testUniqueChangesetIdGeneration() {
        // When - Generate multiple changesets quickly
        String changeset1 = generator.createIndexChangeset("users", "email");
        String changeset2 = generator.createIndexChangeset("users", "email");
        
        // Then - Should have different IDs
        Set<String> ids = generator.getGeneratedChangesetIds();
        assertEquals(2, ids.size());
        
        // Extract changeset IDs from the XML
        assertTrue(changeset1.contains("id=\""));
        assertTrue(changeset2.contains("id=\""));
        assertNotEquals(changeset1, changeset2);
    }

    private Path createMasterFile() throws IOException {
        Path masterPath = tempDir.resolve("master-changelog.xml");
        String masterContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.0.xsd">
                    <include file="existing-changelog.xml"/>
                </databaseChangeLog>
                """;
        Files.writeString(masterPath, masterContent);
        return masterPath;
    }
}