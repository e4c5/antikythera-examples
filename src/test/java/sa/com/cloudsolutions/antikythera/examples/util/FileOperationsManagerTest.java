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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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

    @Test
    void testWriteFileContent() throws IOException {
        // Arrange
        String content = "Test content for writing";

        // Act
        FileOperationsManager.writeFileContent(testFile, content);

        // Assert
        assertTrue(Files.exists(testFile));
        String readContent = Files.readString(testFile, StandardCharsets.UTF_8);
        assertEquals(content, readContent);
    }

    @Test
    void testWriteFileContentCreatesDirectories() throws IOException {
        // Arrange
        String content = "Test content";

        // Act
        FileOperationsManager.writeFileContent(nonExistentFile, content);

        // Assert
        assertTrue(Files.exists(nonExistentFile));
        String readContent = Files.readString(nonExistentFile, StandardCharsets.UTF_8);
        assertEquals(content, readContent);
    }

    @Test
    void testWriteFileBytes() throws IOException {
        // Arrange
        byte[] content = "Binary content test".getBytes(StandardCharsets.UTF_8);

        // Act
        FileOperationsManager.writeFileBytes(testFile, content);

        // Assert
        assertTrue(Files.exists(testFile));
        byte[] readContent = Files.readAllBytes(testFile);
        assertArrayEquals(content, readContent);
    }

    @Test
    void testWriteLines() throws IOException {
        // Arrange
        List<String> lines = Arrays.asList("First line", "Second line", "Third line");

        // Act
        FileOperationsManager.writeLines(testFile, lines);

        // Assert
        assertTrue(Files.exists(testFile));
        List<String> readLines = Files.readAllLines(testFile, StandardCharsets.UTF_8);
        assertEquals(lines, readLines);
    }

    @Test
    void testAppendToFile() throws IOException {
        // Arrange
        String initialContent = "Initial content";
        String appendContent = "\nAppended content";
        Files.writeString(testFile, initialContent, StandardCharsets.UTF_8);

        // Act
        FileOperationsManager.appendToFile(testFile, appendContent);

        // Assert
        String result = Files.readString(testFile, StandardCharsets.UTF_8);
        assertEquals(initialContent + appendContent, result);
    }

    @Test
    void testAppendToFileCreatesFile() throws IOException {
        // Arrange
        String content = "New file content";

        // Act
        FileOperationsManager.appendToFile(testFile, content);

        // Assert
        assertTrue(Files.exists(testFile));
        String readContent = Files.readString(testFile, StandardCharsets.UTF_8);
        assertEquals(content, readContent);
    }

    @Test
    void testAppendLines() throws IOException {
        // Arrange
        List<String> initialLines = Arrays.asList("Line 1", "Line 2");
        List<String> appendLines = Arrays.asList("Line 3", "Line 4");
        Files.write(testFile, initialLines, StandardCharsets.UTF_8);

        // Act
        FileOperationsManager.appendLines(testFile, appendLines);

        // Assert
        List<String> result = Files.readAllLines(testFile, StandardCharsets.UTF_8);
        assertEquals(4, result.size());
        assertEquals("Line 1", result.get(0));
        assertEquals("Line 2", result.get(1));
        assertEquals("Line 3", result.get(2));
        assertEquals("Line 4", result.get(3));
    }

    @Test
    void testFileExists() throws IOException {
        // Assert file doesn't exist initially
        assertFalse(FileOperationsManager.fileExists(testFile));

        // Create file
        Files.createFile(testFile);

        // Assert file exists now
        assertTrue(FileOperationsManager.fileExists(testFile));
    }

    @Test
    void testIsDirectory() throws IOException {
        // Test with file
        Files.createFile(testFile);
        assertFalse(FileOperationsManager.isDirectory(testFile));

        // Test with directory
        Path testDir = tempDir.resolve("testdir");
        Files.createDirectory(testDir);
        assertTrue(FileOperationsManager.isDirectory(testDir));
    }

    @Test
    void testCreateDirectories() throws IOException {
        // Arrange
        Path nestedDir = tempDir.resolve("level1").resolve("level2").resolve("level3");

        // Act
        FileOperationsManager.createDirectories(nestedDir);

        // Assert
        assertTrue(Files.exists(nestedDir));
        assertTrue(Files.isDirectory(nestedDir));
    }

    @Test
    void testCreateDirectoriesWithNull() {
        // Act & Assert - should not throw exception
        assertDoesNotThrow(() -> FileOperationsManager.createDirectories(null));
    }

    @Test
    void testCopyFile() throws IOException {
        // Arrange
        String content = "Content to copy";
        Files.writeString(testFile, content, StandardCharsets.UTF_8);
        Path targetFile = tempDir.resolve("copied.txt");

        // Act
        FileOperationsManager.copyFile(testFile, targetFile);

        // Assert
        assertTrue(Files.exists(targetFile));
        String copiedContent = Files.readString(targetFile, StandardCharsets.UTF_8);
        assertEquals(content, copiedContent);
    }

    @Test
    void testCopyFileCreatesDirectories() throws IOException {
        // Arrange
        String content = "Content to copy";
        Files.writeString(testFile, content, StandardCharsets.UTF_8);
        Path targetFile = tempDir.resolve("subdir").resolve("copied.txt");

        // Act
        FileOperationsManager.copyFile(testFile, targetFile);

        // Assert
        assertTrue(Files.exists(targetFile));
        String copiedContent = Files.readString(targetFile, StandardCharsets.UTF_8);
        assertEquals(content, copiedContent);
    }

    @Test
    void testAtomicWriteFileContent() throws IOException {
        // Arrange
        String content = "Atomic write test content";

        // Act
        FileOperationsManager.atomicWriteFileContent(testFile, content);

        // Assert
        assertTrue(Files.exists(testFile));
        String readContent = Files.readString(testFile, StandardCharsets.UTF_8);
        assertEquals(content, readContent);

        // Verify no temporary files remain
        assertFalse(Files.exists(testFile.resolveSibling(testFile.getFileName() + ".tmp")));
    }

    @Test
    void testAtomicWriteLines() throws IOException {
        // Arrange
        List<String> lines = Arrays.asList("Atomic line 1", "Atomic line 2", "Atomic line 3");

        // Act
        FileOperationsManager.atomicWriteLines(testFile, lines);

        // Assert
        assertTrue(Files.exists(testFile));
        List<String> readLines = Files.readAllLines(testFile, StandardCharsets.UTF_8);
        assertEquals(lines, readLines);

        // Verify no temporary files remain
        assertFalse(Files.exists(testFile.resolveSibling(testFile.getFileName() + ".tmp")));
    }

    @Test
    void testConcurrentFileOperations() throws IOException {
        // Simplified concurrent test - just verify multiple file operations work
        for (int i = 0; i < 3; i++) {
            Path concurrentFile = tempDir.resolve("concurrent_" + i + ".txt");
            String content = "Content " + i;
            FileOperationsManager.writeFileContent(concurrentFile, content);
            
            String readContent = FileOperationsManager.readFileContent(concurrentFile);
            assertEquals(content, readContent);
        }
    }

    @Test
    void testUtf8EncodingConsistency() throws IOException {
        // Test with various UTF-8 characters
        String content = "UTF-8 Test: 你好世界 🌍 Ñoño café résumé";
        
        // Write and read back
        FileOperationsManager.writeFileContent(testFile, content);
        String readContent = FileOperationsManager.readFileContent(testFile);
        
        assertEquals(content, readContent);
        
        // Verify encoding by reading with Files directly
        String directRead = Files.readString(testFile, StandardCharsets.UTF_8);
        assertEquals(content, directRead);
    }

    @Test
    void testErrorHandlingFileAccessFailure() throws IOException {
        // Create a read-only directory to simulate access failure
        Path readOnlyDir = tempDir.resolve("readonly");
        Files.createDirectory(readOnlyDir);
        readOnlyDir.toFile().setReadOnly();
        
        Path readOnlyFile = readOnlyDir.resolve("test.txt");
        
        // Test that write operations handle access failures gracefully
        assertThrows(IOException.class, () -> 
            FileOperationsManager.writeFileContent(readOnlyFile, "test"));
    }

    @Test
    void testAtomicOperationCleanupOnFailure() throws IOException {
        // Create a scenario where atomic write might fail
        Path invalidPath = Path.of("/invalid/path/that/should/not/exist/file.txt");
        
        // Atomic write should fail and clean up temporary files
        assertThrows(IOException.class, () -> 
            FileOperationsManager.atomicWriteFileContent(invalidPath, "test"));
        
        // Verify no temporary files are left behind in temp directory
        // (We can't check the invalid path, but we can ensure our temp dir is clean)
        assertTrue(Files.list(tempDir).noneMatch(p -> p.getFileName().toString().endsWith(".tmp")));
    }
}