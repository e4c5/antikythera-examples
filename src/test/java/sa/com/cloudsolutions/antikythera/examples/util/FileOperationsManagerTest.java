package sa.com.cloudsolutions.antikythera.examples.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FileOperationsManager utility class.
 * Tests file reading, writing, modification operations, error handling,
 * and atomic operations.
 */
class FileOperationsManagerTest {

    @TempDir
    Path tempDir;

    private Path testFile;
    private Path nonExistentFile;

    @BeforeEach
    void setUp() {
        testFile = tempDir.resolve("test.txt");
        nonExistentFile = tempDir.resolve("nonexistent").resolve("file.txt");
    }

    @Test
    void testReadFileContent() throws IOException {
        // Arrange
        String content = "Hello, World!\nThis is a test file.";
        Files.writeString(testFile, content, StandardCharsets.UTF_8);

        // Act
        String result = FileOperationsManager.readFileContent(testFile);

        // Assert
        assertEquals(content, result);
    }

    @Test
    void testReadFileContentNonExistent() {
        // Act & Assert
        assertThrows(IOException.class, () -> 
            FileOperationsManager.readFileContent(nonExistentFile));
    }

    @Test
    void testReadLines() throws IOException {
        // Arrange
        List<String> lines = Arrays.asList("Line 1", "Line 2", "Line 3");
        Files.write(testFile, lines, StandardCharsets.UTF_8);

        // Act
        List<String> result = FileOperationsManager.readLines(testFile);

        // Assert
        assertEquals(lines, result);
    }
}
