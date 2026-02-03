package sa.com.cloudsolutions.antikythera.examples;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CheckpointManagerTest {

    @TempDir
    Path tempDir;

    private File checkpointFile;
    private CheckpointManager manager;

    @BeforeEach
    void setUp() {
        checkpointFile = tempDir.resolve("test-checkpoint.json").toFile();
        manager = new CheckpointManager(checkpointFile);
    }

    @AfterEach
    void tearDown() {
        if (checkpointFile.exists()) {
            checkpointFile.delete();
        }
    }

    @Test
    void testNewCheckpointStartsFresh() {
        assertFalse(manager.hasCheckpoint());
        boolean loaded = manager.load();
        assertFalse(loaded, "Should return false for fresh start");
        assertEquals(0, manager.getProcessedCount());
    }

    @Test
    void testMarkProcessedAndSave() {
        manager.load();
        manager.markProcessed("com.example.UserRepository");
        manager.markProcessed("com.example.OrderRepository");
        manager.save();

        assertTrue(checkpointFile.exists(), "Checkpoint file should be created");
        assertEquals(2, manager.getProcessedCount());
        assertTrue(manager.isProcessed("com.example.UserRepository"));
        assertTrue(manager.isProcessed("com.example.OrderRepository"));
        assertFalse(manager.isProcessed("com.example.ProductRepository"));
    }

    @Test
    void testResumeFromCheckpoint() {
        // First session - process some repos
        manager.load();
        manager.markProcessed("com.example.UserRepository");
        manager.markProcessed("com.example.OrderRepository");
        manager.save();

        // Simulate new session by creating new manager
        CheckpointManager newManager = new CheckpointManager(checkpointFile);
        boolean loaded = newManager.load();

        assertTrue(loaded, "Should load existing checkpoint");
        assertEquals(2, newManager.getProcessedCount());
        assertTrue(newManager.isProcessed("com.example.UserRepository"));
        assertTrue(newManager.isProcessed("com.example.OrderRepository"));
    }

    @Test
    void testClearCheckpoint() {
        manager.load();
        manager.markProcessed("com.example.UserRepository");
        manager.save();
        assertTrue(checkpointFile.exists());

        manager.clear();

        assertFalse(checkpointFile.exists(), "Checkpoint file should be deleted");
        assertEquals(0, manager.getProcessedCount());
        assertFalse(manager.isProcessed("com.example.UserRepository"));
    }

    @Test
    void testIndexSuggestionsPersistence() {
        manager.load();

        LinkedHashSet<String> singleIndexes = new LinkedHashSet<>();
        singleIndexes.add("users|email");
        singleIndexes.add("orders|customer_id");

        LinkedHashSet<String> multiIndexes = new LinkedHashSet<>();
        multiIndexes.add("orders|status,created_at");

        manager.setIndexSuggestions(singleIndexes, multiIndexes);
        manager.save();

        // Load in new manager
        CheckpointManager newManager = new CheckpointManager(checkpointFile);
        newManager.load();

        LinkedHashSet<String> loadedSingle = newManager.getSuggestedNewIndexes();
        LinkedHashSet<String> loadedMulti = newManager.getSuggestedMultiColumnIndexes();

        assertEquals(2, loadedSingle.size());
        assertTrue(loadedSingle.contains("users|email"));
        assertTrue(loadedSingle.contains("orders|customer_id"));

        assertEquals(1, loadedMulti.size());
        assertTrue(loadedMulti.contains("orders|status,created_at"));
    }

    @Test
    void testModifiedFilesPersistence() {
        manager.load();

        Set<String> modifiedFiles = Set.of(
                "com.example.UserRepository",
                "com.example.UserService"
        );
        manager.setModifiedFiles(modifiedFiles);
        manager.save();

        // Load in new manager
        CheckpointManager newManager = new CheckpointManager(checkpointFile);
        newManager.load();

        Set<String> loaded = newManager.getModifiedFiles();
        assertEquals(2, loaded.size());
        assertTrue(loaded.contains("com.example.UserRepository"));
        assertTrue(loaded.contains("com.example.UserService"));
    }

    @Test
    void testLoadIsIdempotent() {
        manager.load();
        manager.markProcessed("com.example.UserRepository");
        manager.save();

        // Load multiple times
        boolean first = manager.load();
        boolean second = manager.load();
        boolean third = manager.load();

        // All calls should return the same result
        assertEquals(first, second);
        assertEquals(second, third);

        // State should be preserved
        assertEquals(1, manager.getProcessedCount());
        assertTrue(manager.isProcessed("com.example.UserRepository"));
    }

    @Test
    void testSessionIdIsGenerated() {
        manager.load();
        String sessionId = manager.getSessionId();

        assertNotNull(sessionId);
        assertFalse(sessionId.isEmpty());
        // Should be a valid UUID format
        assertTrue(sessionId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
    }

    @Test
    void testCorruptedCheckpointHandledGracefully() throws Exception {
        // Write garbage to checkpoint file
        java.nio.file.Files.writeString(checkpointFile.toPath(), "not valid json {{{");

        boolean loaded = manager.load();

        assertFalse(loaded, "Should return false for corrupted checkpoint");
        assertEquals(0, manager.getProcessedCount(), "Should start fresh");
    }
}
