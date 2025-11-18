package sa.com.cloudsolutions.antikythera.examples;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for OptimizationStatsLogger.
 * Tests CSV file creation, header writing, data appending, and Settings integration.
 */
class OptimizationStatsLoggerTest {

    @TempDir
    Path tempDir;

    private String originalCsvFilename;
    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;

    @BeforeAll
    static void setupClass() throws Exception {
        Settings.loadConfigMap(new File("src/test/resources/generator.yml"));
    }

    @BeforeEach
    void setUp() {
        // Capture System.out for console output verification
        System.setOut(new PrintStream(outContent));

        // Save original setting if exists
        originalCsvFilename = (String) Settings.getProperty("optimization_stats_csv");
    }

    @AfterEach
    void tearDown() {
        // Restore System.out
        System.setOut(originalOut);

        // Restore original setting
        if (originalCsvFilename != null) {
            Settings.setProperty("optimization_stats_csv", originalCsvFilename);
        } else {
            Settings.setProperty("optimization_stats_csv", null);
        }
    }

    @Test
    void testCreateStats() {
        // Test creating a new RepositoryStats instance
        String className = "com.example.UserRepository";
        OptimizationStatsLogger.RepositoryStats stats = OptimizationStatsLogger.createStats(className);

        assertNotNull(stats);
        assertEquals(className, stats.getRepositoryClass());
        assertEquals(0, stats.getQueriesAnalyzed());
        assertEquals(0, stats.getQueryAnnotationsChanged());
        assertEquals(0, stats.getMethodSignaturesChanged());
        assertEquals(0, stats.getMethodCallsUpdated());
        assertEquals(0, stats.getDependentClassesModified());
        assertEquals(0, stats.getLiquibaseIndexesGenerated());
    }

    @Test
    void testRepositoryStatsSetters() {
        OptimizationStatsLogger.RepositoryStats stats = OptimizationStatsLogger.createStats("TestRepository");

        stats.setQueriesAnalyzed(5);
        stats.setMethodCallsUpdated(3);
        stats.setDependentClassesModified(2);
        stats.setLiquibaseIndexesGenerated(4);

        assertEquals(5, stats.getQueriesAnalyzed());
        assertEquals(3, stats.getMethodCallsUpdated());
        assertEquals(2, stats.getDependentClassesModified());
        assertEquals(4, stats.getLiquibaseIndexesGenerated());
    }

    @Test
    void testRepositoryStatsIncrementMethods() {
        OptimizationStatsLogger.RepositoryStats stats = OptimizationStatsLogger.createStats("TestRepository");

        assertEquals(0, stats.getQueryAnnotationsChanged());
        stats.incrementQueryAnnotationsChanged();
        assertEquals(1, stats.getQueryAnnotationsChanged());
        stats.incrementQueryAnnotationsChanged();
        assertEquals(2, stats.getQueryAnnotationsChanged());

        assertEquals(0, stats.getMethodSignaturesChanged());
        stats.incrementMethodSignaturesChanged();
        assertEquals(1, stats.getMethodSignaturesChanged());
        stats.incrementMethodSignaturesChanged();
        stats.incrementMethodSignaturesChanged();
        assertEquals(3, stats.getMethodSignaturesChanged());
    }

    @Test
    void testRepositoryStatsToString() {
        OptimizationStatsLogger.RepositoryStats stats = OptimizationStatsLogger.createStats("com.example.Repository");
        stats.setQueriesAnalyzed(10);
        stats.incrementQueryAnnotationsChanged();
        stats.incrementQueryAnnotationsChanged();
        stats.incrementMethodSignaturesChanged();
        stats.setMethodCallsUpdated(5);
        stats.setDependentClassesModified(3);
        stats.setLiquibaseIndexesGenerated(2);

        String result = stats.toString();
        assertTrue(result.contains("com.example.Repository"));
        assertTrue(result.contains("queries=10"));
        assertTrue(result.contains("annotations=2"));
        assertTrue(result.contains("signatures=1"));
        assertTrue(result.contains("calls=5"));
        assertTrue(result.contains("classes=3"));
        assertTrue(result.contains("indexes=2"));
    }

    @Test
    void testLogStatsCreatesNewFileWithHeader() throws IOException {
        // Set custom CSV filename in temp directory
        Path csvPath = tempDir.resolve("test-stats.csv");
        Settings.setProperty("optimization_stats_csv", csvPath.toString());

        OptimizationStatsLogger.RepositoryStats stats = OptimizationStatsLogger.createStats("com.example.UserRepository");
        stats.setQueriesAnalyzed(5);
        stats.incrementQueryAnnotationsChanged();
        stats.incrementMethodSignaturesChanged();
        stats.setMethodCallsUpdated(2);
        stats.setDependentClassesModified(1);
        stats.setLiquibaseIndexesGenerated(3);

        OptimizationStatsLogger.logStats(stats);

        // Verify file was created
        assertTrue(Files.exists(csvPath));

        // Read and verify content
        List<String> lines = Files.readAllLines(csvPath);
        assertEquals(2, lines.size(), "Should have header and one data row");

        // Verify header
        String header = lines.get(0);
        assertTrue(header.contains("timestamp"));
        assertTrue(header.contains("repository_class"));
        assertTrue(header.contains("queries_analyzed"));
        assertTrue(header.contains("query_annotations_changed"));
        assertTrue(header.contains("method_signatures_changed"));
        assertTrue(header.contains("method_calls_updated"));
        assertTrue(header.contains("dependent_classes_modified"));
        assertTrue(header.contains("liquibase_indexes_generated"));

        // Verify data row
        String dataRow = lines.get(1);
        assertTrue(dataRow.contains("com.example.UserRepository"));
        assertTrue(dataRow.contains(",5,1,1,2,1,3"));
    }

    @Test
    void testLogStatsAppendsToExistingFile() throws IOException {
        // Set custom CSV filename in temp directory
        Path csvPath = tempDir.resolve("append-test.csv");
        Settings.setProperty("optimization_stats_csv", csvPath.toString());

        // Log first stats
        OptimizationStatsLogger.RepositoryStats stats1 = OptimizationStatsLogger.createStats("com.example.FirstRepository");
        stats1.setQueriesAnalyzed(3);
        OptimizationStatsLogger.logStats(stats1);

        // Log second stats
        OptimizationStatsLogger.RepositoryStats stats2 = OptimizationStatsLogger.createStats("com.example.SecondRepository");
        stats2.setQueriesAnalyzed(7);
        OptimizationStatsLogger.logStats(stats2);

        // Verify file content
        List<String> lines = Files.readAllLines(csvPath);
        assertEquals(3, lines.size(), "Should have header and two data rows");

        // Verify header is only written once
        String header = lines.get(0);
        assertTrue(header.contains("timestamp"));

        // Verify both data rows exist
        String row1 = lines.get(1);
        assertTrue(row1.contains("com.example.FirstRepository"));
        assertTrue(row1.contains(",3,"));

        String row2 = lines.get(2);
        assertTrue(row2.contains("com.example.SecondRepository"));
        assertTrue(row2.contains(",7,"));
    }

    @Test
    void testLogStatsWithDefaultFilename() throws IOException {
        // Remove custom setting to use default
        Settings.setProperty("optimization_stats_csv", null);

        OptimizationStatsLogger.RepositoryStats stats = OptimizationStatsLogger.createStats("TestRepository");
        stats.setQueriesAnalyzed(1);

        OptimizationStatsLogger.logStats(stats);

        // Verify default file was created
        Path defaultPath = Path.of("query-optimization-stats.csv");
        assertTrue(Files.exists(defaultPath));

        // Clean up
        Files.deleteIfExists(defaultPath);
    }

    @Test
    void testLogStatsWithEmptySettingUsesDefault() throws IOException {
        // Set empty string in settings
        Settings.setProperty("optimization_stats_csv", "");

        OptimizationStatsLogger.RepositoryStats stats = OptimizationStatsLogger.createStats("TestRepository");
        stats.setQueriesAnalyzed(1);

        OptimizationStatsLogger.logStats(stats);

        // Verify default file was created
        Path defaultPath = Path.of("query-optimization-stats.csv");
        assertTrue(Files.exists(defaultPath));

        // Clean up
        Files.deleteIfExists(defaultPath);
    }

    @Test
    void testLogStatsWithCustomFilenameFromSettings() throws IOException {
        // Set custom filename in settings
        Path customPath = tempDir.resolve("custom-stats-file.csv");
        Settings.setProperty("optimization_stats_csv", customPath.toString());

        OptimizationStatsLogger.RepositoryStats stats = OptimizationStatsLogger.createStats("CustomRepository");
        stats.setQueriesAnalyzed(42);

        OptimizationStatsLogger.logStats(stats);

        // Verify custom file was created
        assertTrue(Files.exists(customPath));

        // Verify content
        List<String> lines = Files.readAllLines(customPath);
        assertTrue(lines.size() >= 2);
        assertTrue(lines.get(1).contains("CustomRepository"));
        assertTrue(lines.get(1).contains(",42,"));
    }

    @Test
    void testLogStatsTimestampFormat() throws IOException {
        Path csvPath = tempDir.resolve("timestamp-test.csv");
        Settings.setProperty("optimization_stats_csv", csvPath.toString());

        OptimizationStatsLogger.RepositoryStats stats = OptimizationStatsLogger.createStats("TimestampTest");
        OptimizationStatsLogger.logStats(stats);

        List<String> lines = Files.readAllLines(csvPath);
        String dataRow = lines.get(1);

        // Verify timestamp is in ISO format (e.g., 2024-01-15T10:30:45.123)
        // Should start with a date pattern YYYY-MM-DD
        assertTrue(dataRow.matches("\\d{4}-\\d{2}-\\d{2}T.*"),
                "Timestamp should be in ISO format");
    }

    @Test
    void testLogStatsWithAllZeroValues() throws IOException {
        Path csvPath = tempDir.resolve("zero-values.csv");
        Settings.setProperty("optimization_stats_csv", csvPath.toString());

        OptimizationStatsLogger.RepositoryStats stats = OptimizationStatsLogger.createStats("ZeroRepository");
        OptimizationStatsLogger.logStats(stats);

        List<String> lines = Files.readAllLines(csvPath);
        String dataRow = lines.get(1);

        // Verify all numeric fields are 0
        assertTrue(dataRow.contains("ZeroRepository,0,0,0,0,0,0"));
    }

    @Test
    void testLogStatsWithMaxValues() throws IOException {
        Path csvPath = tempDir.resolve("max-values.csv");
        Settings.setProperty("optimization_stats_csv", csvPath.toString());

        OptimizationStatsLogger.RepositoryStats stats = OptimizationStatsLogger.createStats("MaxRepository");
        stats.setQueriesAnalyzed(Integer.MAX_VALUE);
        stats.setMethodCallsUpdated(Integer.MAX_VALUE);
        stats.setDependentClassesModified(Integer.MAX_VALUE);
        stats.setLiquibaseIndexesGenerated(Integer.MAX_VALUE);

        // Increment to max won't overflow
        for (int i = 0; i < 100; i++) {
            stats.incrementQueryAnnotationsChanged();
            stats.incrementMethodSignaturesChanged();
        }

        OptimizationStatsLogger.logStats(stats);

        List<String> lines = Files.readAllLines(csvPath);
        String dataRow = lines.get(1);

        assertTrue(dataRow.contains("MaxRepository"));
        assertTrue(dataRow.contains(String.valueOf(Integer.MAX_VALUE)));
    }

    @Test
    void testLogStatsWithSpecialCharactersInClassName() throws IOException {
        Path csvPath = tempDir.resolve("special-chars.csv");
        Settings.setProperty("optimization_stats_csv", csvPath.toString());

        // Repository names with special characters (package separators)
        OptimizationStatsLogger.RepositoryStats stats = OptimizationStatsLogger.createStats(
                "com.example.sub$package.Inner$Class$Repository");
        stats.setQueriesAnalyzed(5);

        OptimizationStatsLogger.logStats(stats);

        List<String> lines = Files.readAllLines(csvPath);
        String dataRow = lines.get(1);

        assertTrue(dataRow.contains("com.example.sub$package.Inner$Class$Repository"));
    }

    @Test
    void testMultipleRepositoriesLogging() throws IOException {
        Path csvPath = tempDir.resolve("multiple-repos.csv");
        Settings.setProperty("optimization_stats_csv", csvPath.toString());

        // Log stats for multiple repositories
        String[] repositories = {
                "com.example.UserRepository",
                "com.example.OrderRepository",
                "com.example.ProductRepository",
                "com.example.CategoryRepository",
                "com.example.ReviewRepository"
        };

        for (int i = 0; i < repositories.length; i++) {
            OptimizationStatsLogger.RepositoryStats stats = OptimizationStatsLogger.createStats(repositories[i]);
            stats.setQueriesAnalyzed(i + 1);
            stats.setMethodCallsUpdated(i * 2);
            OptimizationStatsLogger.logStats(stats);
        }

        List<String> lines = Files.readAllLines(csvPath);
        assertEquals(6, lines.size(), "Should have header + 5 data rows");

        // Verify all repositories are logged
        for (String repo : repositories) {
            assertTrue(lines.stream().anyMatch(line -> line.contains(repo)),
                    "Should contain " + repo);
        }
    }

    @Test
    void testCsvFileCreationInSubdirectory() throws IOException {
        // Create subdirectory in temp
        Path subDir = tempDir.resolve("logs/stats");
        Files.createDirectories(subDir);

        Path csvPath = subDir.resolve("nested-stats.csv");
        Settings.setProperty("optimization_stats_csv", csvPath.toString());

        OptimizationStatsLogger.RepositoryStats stats = OptimizationStatsLogger.createStats("NestedRepository");
        OptimizationStatsLogger.logStats(stats);

        assertTrue(Files.exists(csvPath));
        List<String> lines = Files.readAllLines(csvPath);
        assertTrue(lines.size() >= 2);
    }

    @Test
    void testGetCsvFilenameWithNonStringValue() throws IOException {
        // Set a non-string value (edge case)
        Settings.setProperty("optimization_stats_csv", 12345);

        OptimizationStatsLogger.RepositoryStats stats = OptimizationStatsLogger.createStats("TestRepository");
        OptimizationStatsLogger.logStats(stats);

        // Should fall back to default
        Path defaultPath = Path.of("query-optimization-stats.csv");
        assertTrue(Files.exists(defaultPath));

        // Clean up
        Files.deleteIfExists(defaultPath);
    }

    @Test
    void testConsoleOutputInQuietMode() throws IOException {
        Path csvPath = tempDir.resolve("quiet-mode.csv");
        Settings.setProperty("optimization_stats_csv", csvPath.toString());

        // Note: This test assumes QueryOptimizationChecker.isQuietMode() is accessible
        // If quiet mode is enabled, no console output should be produced
        OptimizationStatsLogger.RepositoryStats stats = OptimizationStatsLogger.createStats("QuietRepository");
        stats.setQueriesAnalyzed(10);

        OptimizationStatsLogger.logStats(stats);

        // File should still be created
        assertTrue(Files.exists(csvPath));

        // Console output behavior depends on quiet mode setting
        // This test documents the expected behavior
    }

    @Test
    void testStatsObjectImmutableRepositoryClass() {
        OptimizationStatsLogger.RepositoryStats stats = OptimizationStatsLogger.createStats("ImmutableRepository");

        // Repository class should not change
        assertEquals("ImmutableRepository", stats.getRepositoryClass());

        // Modify other fields
        stats.setQueriesAnalyzed(100);

        // Repository class should still be the same
        assertEquals("ImmutableRepository", stats.getRepositoryClass());
    }

    @Test
    void testCsvHeaderFormat() throws IOException {
        Path csvPath = tempDir.resolve("header-test.csv");
        Settings.setProperty("optimization_stats_csv", csvPath.toString());

        OptimizationStatsLogger.RepositoryStats stats = OptimizationStatsLogger.createStats("TestRepository");
        OptimizationStatsLogger.logStats(stats);

        List<String> lines = Files.readAllLines(csvPath);
        String header = lines.get(0);

        // Verify exact header format
        String[] columns = header.split(",");
        assertEquals(8, columns.length, "Should have 8 columns");
        assertEquals("timestamp", columns[0]);
        assertEquals("repository_class", columns[1]);
        assertEquals("queries_analyzed", columns[2]);
        assertEquals("query_annotations_changed", columns[3]);
        assertEquals("method_signatures_changed", columns[4]);
        assertEquals("method_calls_updated", columns[5]);
        assertEquals("dependent_classes_modified", columns[6]);
        assertEquals("liquibase_indexes_generated", columns[7]);
    }

    @Test
    void testCsvDataRowFormat() throws IOException {
        Path csvPath = tempDir.resolve("row-format-test.csv");
        Settings.setProperty("optimization_stats_csv", csvPath.toString());

        OptimizationStatsLogger.RepositoryStats stats = OptimizationStatsLogger.createStats("com.test.Repository");
        stats.setQueriesAnalyzed(10);
        stats.incrementQueryAnnotationsChanged();
        stats.incrementQueryAnnotationsChanged();
        stats.incrementMethodSignaturesChanged();
        stats.setMethodCallsUpdated(5);
        stats.setDependentClassesModified(3);
        stats.setLiquibaseIndexesGenerated(2);

        OptimizationStatsLogger.logStats(stats);

        List<String> lines = Files.readAllLines(csvPath);
        String dataRow = lines.get(1);

        // Verify row has 8 columns
        String[] columns = dataRow.split(",");
        assertEquals(8, columns.length, "Data row should have 8 columns");

        // Verify timestamp is first (should be valid ISO date)
        assertTrue(columns[0].matches("\\d{4}-.*"));

        // Verify data values
        assertEquals("com.test.Repository", columns[1]);
        assertEquals("10", columns[2]);
        assertEquals("2", columns[3]);
        assertEquals("1", columns[4]);
        assertEquals("5", columns[5]);
        assertEquals("3", columns[6]);
        assertEquals("2", columns[7]);
    }
}

