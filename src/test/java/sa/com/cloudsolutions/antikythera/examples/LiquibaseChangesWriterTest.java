package sa.com.cloudsolutions.antikythera.examples;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LiquibaseChangesWriterTest {

    @TempDir
    Path tempDir;
    
    private File masterFile;
    private LiquibaseChangesWriter writer;

    @BeforeEach
    void setUp() throws IOException {
        masterFile = tempDir.resolve("db.changelog-master.xml").toFile();
        writer = new LiquibaseChangesWriter();
        
        // Create a minimal master file
        Files.writeString(masterFile.toPath(), """
            <?xml version="1.0" encoding="UTF-8"?>
            <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                               xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                               xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.0.xsd">
            
                <!-- Existing includes would go here -->
                
            </databaseChangeLog>
            """);
    }

    @Test
    void testWriteValidChangeSet() throws IOException {
        String changeSet = """
            <changeSet id="idx_users_email_123" author="antikythera">
                <preConditions onFail="MARK_RAN">
                    <not>
                        <indexExists tableName="users" indexName="idx_users_email"/>
                    </not>
                </preConditions>
                <sql dbms="postgresql">CREATE INDEX CONCURRENTLY idx_users_email ON users (email);</sql>
                <rollback>
                    <sql dbms="postgresql">DROP INDEX CONCURRENTLY IF EXISTS idx_users_email;</sql>
                </rollback>
            </changeSet>
            """;

        LiquibaseChangesWriter.Written result = writer.write(masterFile, changeSet);

        assertNotNull(result.changesFile());
        assertTrue(result.changesFile().exists());
        
        // Verify generated file has proper XML structure
        String generatedContent = Files.readString(result.changesFile().toPath());
        assertTrue(generatedContent.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"));
        assertTrue(generatedContent.contains("<databaseChangeLog"));
        assertTrue(generatedContent.contains("xmlns=\"http://www.liquibase.org/xml/ns/dbchangelog\""));
        assertTrue(generatedContent.contains("dbchangelog-4.0.xsd"));
        assertTrue(generatedContent.contains(changeSet.trim()));
        assertTrue(generatedContent.endsWith("</databaseChangeLog>\n"));
        
        // Verify master file was updated
        String masterContent = Files.readString(masterFile.toPath());
        assertTrue(masterContent.contains("<include file=\"" + result.changesFile().getName() + "\"/>"));
    }

    @Test
    void testWriteEmptyChangeSet() throws IOException {
        LiquibaseChangesWriter.Written result = writer.write(masterFile, "");
        assertNull(result.changesFile());
        
        result = writer.write(masterFile, null);
        assertNull(result.changesFile());
        
        result = writer.write(masterFile, "   ");
        assertNull(result.changesFile());
    }

    @Test
    void testNoDuplicateIncludes() throws IOException {
        String changeSet = "<changeSet id=\"test\" author=\"test\"></changeSet>";
        
        // Write first time
        LiquibaseChangesWriter.Written result1 = writer.write(masterFile, changeSet);
        assertNotNull(result1.changesFile());
        
        String masterAfterFirst = Files.readString(masterFile.toPath());
        int includeCount1 = countOccurrences(masterAfterFirst, result1.changesFile().getName());
        assertEquals(1, includeCount1);
        
        // Write same file again - should not duplicate
        writer.write(masterFile, changeSet);
        String masterAfterSecond = Files.readString(masterFile.toPath());
        int includeCount2 = countOccurrences(masterAfterSecond, result1.changesFile().getName());
        assertEquals(1, includeCount2, "Include should not be duplicated");
    }

    @Test
    void testIncludeProperIndentation() throws IOException {
        String changeSet = "<changeSet id=\"test\" author=\"test\"></changeSet>";
        
        LiquibaseChangesWriter.Written result = writer.write(masterFile, changeSet);
        
        String masterContent = Files.readString(masterFile.toPath());
        String expectedInclude = "    <include file=\"" + result.changesFile().getName() + "\"/>";
        assertTrue(masterContent.contains(expectedInclude), 
                  "Include should have proper 4-space indentation");
    }

    @Test
    void testMasterFileWithoutClosingTag() throws IOException {
        // Create master file without proper closing tag
        Files.writeString(masterFile.toPath(), """
            <?xml version="1.0" encoding="UTF-8"?>
            <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
            <!-- No closing tag -->
            """);
        
        String changeSet = "<changeSet id=\"test\" author=\"test\"></changeSet>";
        
        LiquibaseChangesWriter.Written result = writer.write(masterFile, changeSet);
        assertNotNull(result.changesFile());
        
        // Should still add include, even without proper closing tag
        String masterContent = Files.readString(masterFile.toPath());
        assertTrue(masterContent.contains("<include file=\"" + result.changesFile().getName() + "\"/>"));
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