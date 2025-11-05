package sa.com.cloudsolutions.antikythera.examples;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test coverage for LiquibaseChangesWriter
 */
class LiquibaseChangesWriterCoverageTest {

    private LiquibaseChangesWriter writer;
    
    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        writer = new LiquibaseChangesWriter();
    }

    @Test
    void testWriteWithNullChangeSetsXml() throws IOException {
        // Given
        File masterXml = createMasterFile();
        
        // When
        LiquibaseChangesWriter.Written result = writer.write(masterXml, null);
        
        // Then
        assertNotNull(result);
        assertNull(result.changesFile());
    }

    @Test
    void testWriteWithBlankChangeSetsXml() throws IOException {
        // Given
        File masterXml = createMasterFile();
        
        // When
        LiquibaseChangesWriter.Written result = writer.write(masterXml, "   ");
        
        // Then
        assertNotNull(result);
        assertNull(result.changesFile());
    }

    @Test
    void testWriteWithEmptyChangeSetsXml() throws IOException {
        // Given
        File masterXml = createMasterFile();
        
        // When
        LiquibaseChangesWriter.Written result = writer.write(masterXml, "");
        
        // Then
        assertNotNull(result);
        assertNull(result.changesFile());
    }

    @Test
    void testWriteWithValidChangeSetsXml() throws IOException {
        // Given
        File masterXml = createMasterFile();
        String changeSetsXml = "<changeSet id=\"1\" author=\"test\"><createIndex/></changeSet>";
        
        // When
        LiquibaseChangesWriter.Written result = writer.write(masterXml, changeSetsXml);
        
        // Then
        assertNotNull(result);
        assertNotNull(result.changesFile());
        assertTrue(result.changesFile().exists());
        assertTrue(result.changesFile().getName().startsWith("antikythera-indexes-"));
        assertTrue(result.changesFile().getName().endsWith(".xml"));
        
        // Verify content of generated file
        String content = Files.readString(result.changesFile().toPath(), StandardCharsets.UTF_8);
        assertTrue(content.contains("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"));
        assertTrue(content.contains("<databaseChangeLog"));
        assertTrue(content.contains(changeSetsXml));
        assertTrue(content.contains("</databaseChangeLog>"));
    }

    @Test
    void testMasterFileUpdatedWithInclude() throws IOException {
        // Given
        File masterXml = createMasterFile();
        String changeSetsXml = "<changeSet id=\"1\" author=\"test\"><createIndex/></changeSet>";
        
        // When
        LiquibaseChangesWriter.Written result = writer.write(masterXml, changeSetsXml);
        
        // Then
        String masterContent = Files.readString(masterXml.toPath(), StandardCharsets.UTF_8);
        assertTrue(masterContent.contains("<include file=\"" + result.changesFile().getName() + "\"/>"));
    }

    @Test
    void testMasterFileWithoutClosingTag() throws IOException {
        // Given
        File masterXml = createMasterFileWithoutClosingTag();
        String changeSetsXml = "<changeSet id=\"1\" author=\"test\"><createIndex/></changeSet>";
        
        // When
        LiquibaseChangesWriter.Written result = writer.write(masterXml, changeSetsXml);
        
        // Then
        String masterContent = Files.readString(masterXml.toPath(), StandardCharsets.UTF_8);
        assertTrue(masterContent.contains("<include file=\"" + result.changesFile().getName() + "\"/>"));
        assertTrue(masterContent.endsWith("\n"));
    }

    @Test
    void testDuplicateIncludeNotAdded() throws IOException {
        // Given
        File masterXml = createMasterFile();
        String changeSetsXml = "<changeSet id=\"1\" author=\"test\"><createIndex/></changeSet>";
        
        // When - write first time
        LiquibaseChangesWriter.Written result1 = writer.write(masterXml, changeSetsXml);
        String masterContentAfterFirst = Files.readString(masterXml.toPath(), StandardCharsets.UTF_8);
        
        // Manually add the same include to simulate duplicate
        String includeTag = "<include file=\"" + result1.changesFile().getName() + "\"/>";
        int includeCount1 = countOccurrences(masterContentAfterFirst, includeTag);
        
        // When - write second time with same file (simulate duplicate scenario)
        // First, let's create the scenario where the file already exists
        Files.writeString(masterXml.toPath(), masterContentAfterFirst, StandardCharsets.UTF_8);
        LiquibaseChangesWriter.Written result2 = writer.write(masterXml, changeSetsXml);
        
        // Then - verify new file created but no duplicate include for first file
        String masterContentAfterSecond = Files.readString(masterXml.toPath(), StandardCharsets.UTF_8);
        int includeCount2 = countOccurrences(masterContentAfterSecond, includeTag);
        
        assertEquals(includeCount1, includeCount2, "Duplicate include should not be added");
    }

    @Test
    void testUniqueFileNamesGenerated() throws IOException {
        // Given
        File masterXml = createMasterFile();
        String changeSetsXml = "<changeSet id=\"1\" author=\"test\"><createIndex/></changeSet>";
        
        // When
        LiquibaseChangesWriter.Written result1 = writer.write(masterXml, changeSetsXml);
        LiquibaseChangesWriter.Written result2 = writer.write(masterXml, changeSetsXml);
        
        // Then
        assertNotEquals(result1.changesFile().getName(), result2.changesFile().getName());
    }

    @Test
    void testWrittenRecordFunctionality() throws IOException {
        // Given
        File masterXml = createMasterFile();
        String changeSetsXml = "<changeSet id=\"1\" author=\"test\"><createIndex/></changeSet>";
        
        // When
        LiquibaseChangesWriter.Written result = writer.write(masterXml, changeSetsXml);
        
        // Then - test record functionality
        assertNotNull(result);
        assertNotNull(result.changesFile());
        assertEquals(result.changesFile().getClass(), File.class);
        
        // Test record with null
        LiquibaseChangesWriter.Written nullResult = new LiquibaseChangesWriter.Written(null);
        assertNull(nullResult.changesFile());
    }

    private File createMasterFile() throws IOException {
        Path masterPath = tempDir.resolve("master-changelog.xml");
        String masterContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.0.xsd">
                    <include file="existing-changelog.xml"/>
                </databaseChangeLog>
                """;
        Files.writeString(masterPath, masterContent, StandardCharsets.UTF_8);
        return masterPath.toFile();
    }

    private File createMasterFileWithoutClosingTag() throws IOException {
        Path masterPath = tempDir.resolve("master-no-closing.xml");
        String masterContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.0.xsd">
                    <include file="existing-changelog.xml"/>
                """;
        Files.writeString(masterPath, masterContent, StandardCharsets.UTF_8);
        return masterPath.toFile();
    }

    private int countOccurrences(String text, String substring) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(substring, index)) != -1) {
            count++;
            index += substring.length();
        }
        return count;
    }
}