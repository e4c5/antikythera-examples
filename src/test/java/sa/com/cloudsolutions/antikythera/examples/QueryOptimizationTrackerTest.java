package sa.com.cloudsolutions.antikythera.examples;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class QueryOptimizationTrackerTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        Settings.loadConfigMap(new File("src/test/resources/generator.yml"));
        QueryOptimizationTracker.reset();
    }

    @AfterEach
    void tearDown() {
        QueryOptimizationTracker.reset();
    }

    @Test
    void testShouldSkipWhenDisabled() {
        // Default config has skip_processed: false
        QueryOptimizationTracker tracker = QueryOptimizationTracker.getInstance();
        assertFalse(tracker.isSkipProcessedEnabled());
        assertFalse(tracker.shouldSkip("com.example.SomeRepository"));
    }

    @Test
    void testShouldSkipWhenEnabledAndRepositoryNotProcessed() throws IOException {
        // Create a CSV file with some repositories
        File csvFile = tempDir.resolve("test-stats.csv").toFile();
        try (FileWriter writer = new FileWriter(csvFile)) {
            writer.write("timestamp,repository,query_count\n");
            writer.write("2024-01-01,com.example.UserRepository,5\n");
            writer.write("2024-01-02,com.example.OrderRepository,3\n");
        }

        // Configure settings to enable skip_processed
        Map<String, Object> dbConfig = new HashMap<>();
        Map<String, Object> qcConfig = new HashMap<>();
        qcConfig.put("enabled", true);
        qcConfig.put("skip_processed", true);
        dbConfig.put("query_conversion", qcConfig);
        dbConfig.put("log_file", csvFile.getAbsolutePath());
        Settings.setProperty("database", dbConfig);

        QueryOptimizationTracker.reset();
        QueryOptimizationTracker tracker = QueryOptimizationTracker.getInstance();

        assertTrue(tracker.isSkipProcessedEnabled());
        // Repository not in the CSV should not be skipped
        assertFalse(tracker.shouldSkip("com.example.NewRepository"));
    }

    @Test
    void testShouldSkipWhenEnabledAndRepositoryProcessed() throws IOException {
        // Create a CSV file with some repositories
        File csvFile = tempDir.resolve("test-stats.csv").toFile();
        try (FileWriter writer = new FileWriter(csvFile)) {
            writer.write("timestamp,repository,query_count\n");
            writer.write("2024-01-01,com.example.UserRepository,5\n");
            writer.write("2024-01-02,com.example.OrderRepository,3\n");
        }

        // Configure settings to enable skip_processed
        Map<String, Object> dbConfig = new HashMap<>();
        Map<String, Object> qcConfig = new HashMap<>();
        qcConfig.put("enabled", true);
        qcConfig.put("skip_processed", true);
        dbConfig.put("query_conversion", qcConfig);
        dbConfig.put("log_file", csvFile.getAbsolutePath());
        Settings.setProperty("database", dbConfig);

        QueryOptimizationTracker.reset();
        QueryOptimizationTracker tracker = QueryOptimizationTracker.getInstance();

        assertTrue(tracker.isSkipProcessedEnabled());
        // Repository in the CSV should be skipped
        assertTrue(tracker.shouldSkip("com.example.UserRepository"));
        assertTrue(tracker.shouldSkip("com.example.OrderRepository"));
    }

    @Test
    void testGetProcessedRepositories() throws IOException {
        // Create a CSV file with some repositories
        File csvFile = tempDir.resolve("test-stats.csv").toFile();
        try (FileWriter writer = new FileWriter(csvFile)) {
            writer.write("timestamp,repository,query_count,errors\n");
            writer.write("2024-01-01,com.example.UserRepository,5,0\n");
            writer.write("2024-01-02,com.example.OrderRepository,3,1\n");
            writer.write("2024-01-03,com.example.ProductRepository,10,0\n");
        }

        // Configure settings to enable skip_processed
        Map<String, Object> dbConfig = new HashMap<>();
        Map<String, Object> qcConfig = new HashMap<>();
        qcConfig.put("enabled", true);
        qcConfig.put("skip_processed", true);
        dbConfig.put("query_conversion", qcConfig);
        dbConfig.put("log_file", csvFile.getAbsolutePath());
        Settings.setProperty("database", dbConfig);

        QueryOptimizationTracker.reset();
        QueryOptimizationTracker tracker = QueryOptimizationTracker.getInstance();

        Set<String> processed = tracker.getProcessedRepositories();
        assertEquals(3, processed.size());
        assertTrue(processed.contains("com.example.UserRepository"));
        assertTrue(processed.contains("com.example.OrderRepository"));
        assertTrue(processed.contains("com.example.ProductRepository"));
    }

    @Test
    void testHandlesMissingCsvFile() {
        // Configure settings with a non-existent file
        Map<String, Object> dbConfig = new HashMap<>();
        Map<String, Object> qcConfig = new HashMap<>();
        qcConfig.put("enabled", true);
        qcConfig.put("skip_processed", true);
        dbConfig.put("query_conversion", qcConfig);
        dbConfig.put("log_file", "/non/existent/file.csv");
        Settings.setProperty("database", dbConfig);

        QueryOptimizationTracker.reset();
        QueryOptimizationTracker tracker = QueryOptimizationTracker.getInstance();

        assertTrue(tracker.isSkipProcessedEnabled());
        // Should not throw, should just return empty set
        Set<String> processed = tracker.getProcessedRepositories();
        assertTrue(processed.isEmpty());
        assertFalse(tracker.shouldSkip("com.example.AnyRepository"));
    }

    @Test
    void testSkipsHeaderRow() throws IOException {
        // Create a CSV file with header containing "repository"
        File csvFile = tempDir.resolve("test-stats.csv").toFile();
        try (FileWriter writer = new FileWriter(csvFile)) {
            writer.write("Timestamp,Repository Name,Count\n");
            writer.write("2024-01-01,com.example.UserRepository,5\n");
        }

        Map<String, Object> dbConfig = new HashMap<>();
        Map<String, Object> qcConfig = new HashMap<>();
        qcConfig.put("enabled", true);
        qcConfig.put("skip_processed", true);
        dbConfig.put("query_conversion", qcConfig);
        dbConfig.put("log_file", csvFile.getAbsolutePath());
        Settings.setProperty("database", dbConfig);

        QueryOptimizationTracker.reset();
        QueryOptimizationTracker tracker = QueryOptimizationTracker.getInstance();

        Set<String> processed = tracker.getProcessedRepositories();
        assertEquals(1, processed.size());
        assertTrue(processed.contains("com.example.UserRepository"));
        // Should not contain the header value
        assertFalse(processed.contains("Repository Name"));
    }

    @Test
    void testHandlesQuotedValues() throws IOException {
        // Create a CSV file with quoted values
        File csvFile = tempDir.resolve("test-stats.csv").toFile();
        try (FileWriter writer = new FileWriter(csvFile)) {
            writer.write("timestamp,repository,query_count\n");
            writer.write("\"2024-01-01\",\"com.example.UserRepository\",\"5\"\n");
        }

        Map<String, Object> dbConfig = new HashMap<>();
        Map<String, Object> qcConfig = new HashMap<>();
        qcConfig.put("enabled", true);
        qcConfig.put("skip_processed", true);
        dbConfig.put("query_conversion", qcConfig);
        dbConfig.put("log_file", csvFile.getAbsolutePath());
        Settings.setProperty("database", dbConfig);

        QueryOptimizationTracker.reset();
        QueryOptimizationTracker tracker = QueryOptimizationTracker.getInstance();

        assertTrue(tracker.shouldSkip("com.example.UserRepository"));
    }

    @Test
    void testSingletonBehavior() {
        QueryOptimizationTracker tracker1 = QueryOptimizationTracker.getInstance();
        QueryOptimizationTracker tracker2 = QueryOptimizationTracker.getInstance();
        assertSame(tracker1, tracker2);
    }

    @Test
    void testDefaultLogFilePath() throws IOException {
        // Without explicit log_file configuration
        Map<String, Object> dbConfig = new HashMap<>();
        Map<String, Object> qcConfig = new HashMap<>();
        qcConfig.put("enabled", true);
        qcConfig.put("skip_processed", false);
        dbConfig.put("query_conversion", qcConfig);
        Settings.setProperty("database", dbConfig);

        QueryOptimizationTracker.reset();
        QueryOptimizationTracker tracker = QueryOptimizationTracker.getInstance();

        assertEquals("query-optimization-stats.csv", tracker.getLogFilePath());
    }
}
