package sa.com.cloudsolutions.antikythera.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LiquibaseValidatorTest {

    @Test
    void testValidateSampleChangelog() {
        LiquibaseValidator validator = new LiquibaseValidator();
        String changelogPath = "src/test/resources/liquibase/sample-changelog.xml";

        LiquibaseValidator.ValidationResult result = validator.validate(changelogPath);

        assertTrue(result.valid(), "Expected valid changelog");
        assertEquals(0, result.errors().size(), "Expected no errors");
        assertFalse(result.warnings().isEmpty(), "Expected some warnings");
    }

    @Test
    void testValidateNonExistentFile() {
        LiquibaseValidator validator = new LiquibaseValidator();
        String changelogPath = "/non/existent/file.xml";

        LiquibaseValidator.ValidationResult result = validator.validate(changelogPath);

        assertFalse(result.valid(), "Expected invalid result for non-existent file");
        assertFalse(result.errors().isEmpty(), "Expected errors");
        assertTrue(result.errors().get(0).contains("not found"), "Expected 'not found' error");
    }

    @Test
    void testValidateInvalidXml(@TempDir Path tempDir) throws IOException {
        File invalidFile = tempDir.resolve("invalid.xml").toFile();
        try (FileWriter fw = new FileWriter(invalidFile)) {
            fw.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            fw.write("<databaseChangeLog>\n");
            fw.write("  <changeSet id=\"1\" author=\"test\">\n");
            fw.write("    <!-- Missing closing tags -->\n");
        }

        LiquibaseValidator validator = new LiquibaseValidator();
        LiquibaseValidator.ValidationResult result = validator.validate(invalidFile.getAbsolutePath());

        assertFalse(result.valid(), "Expected invalid result for malformed XML");
        assertFalse(result.errors().isEmpty(), "Expected errors");
    }

    @Test
    void testValidationResultJson() {
        LiquibaseValidator.ValidationResult result = new LiquibaseValidator.ValidationResult(
                true,
                java.util.List.of("Error 1", "Error 2"),
                java.util.List.of("Warning 1"));

        String json = result.toJson();

        assertNotNull(json);
        assertTrue(json.contains("\"valid\":true"));
        assertTrue(json.contains("Error 1"));
        assertTrue(json.contains("Error 2"));
        assertTrue(json.contains("Warning 1"));
    }

    @Test
    void testEmptyChangelog(@TempDir Path tempDir) throws IOException {
        File emptyChangelog = tempDir.resolve("empty.xml").toFile();
        try (FileWriter fw = new FileWriter(emptyChangelog)) {
            fw.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            fw.write("<databaseChangeLog\n");
            fw.write("    xmlns=\"http://www.liquibase.org/xml/ns/dbchangelog\"\n");
            fw.write("    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
            fw.write("    xsi:schemaLocation=\"http://www.liquibase.org/xml/ns/dbchangelog\n");
            fw.write("    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.0.xsd\">\n");
            fw.write("</databaseChangeLog>\n");
        }

        LiquibaseValidator validator = new LiquibaseValidator();
        LiquibaseValidator.ValidationResult result = validator.validate(emptyChangelog.getAbsolutePath());

        assertTrue(result.valid(), "Empty changelog should be valid");
        assertTrue(result.warnings().stream()
                .anyMatch(w -> w.contains("no change sets")), "Expected warning about no change sets");
    }

    @Test
    void testValidateInvalidSql(@TempDir Path tempDir) throws IOException {
        File invalidSqlFile = tempDir.resolve("invalid-sql.xml").toFile();
        try (FileWriter fw = new FileWriter(invalidSqlFile)) {
            fw.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            fw.write("<databaseChangeLog\n");
            fw.write("    xmlns=\"http://www.liquibase.org/xml/ns/dbchangelog\"\n");
            fw.write("    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
            fw.write("    xsi:schemaLocation=\"http://www.liquibase.org/xml/ns/dbchangelog\n");
            fw.write("    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.0.xsd\">\n");
            fw.write("  <changeSet id=\"1\" author=\"test\">\n");
            fw.write("    <sql>INSERT INTO users (username, email) VALUES ('test' --- MISSING STUFF\n");
            fw.write("    </sql>\n");
            fw.write("  </changeSet>\n");
            fw.write("</databaseChangeLog>\n");
        }

        LiquibaseValidator validator = new LiquibaseValidator();
        LiquibaseValidator.ValidationResult result = validator.validate(invalidSqlFile.getAbsolutePath());

        assertFalse(result.valid(), "Should be invalid due to SQL syntax error");
        assertTrue(result.errors().stream()
                .anyMatch(e -> e.contains("SQL Syntax error")), "Expected SQL syntax error message");
    }

}
