package sa.com.cloudsolutions.antikythera.examples;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Tracks which repositories have been processed by the QueryOptimizer.
 * Supports skipping already-processed repositories when configured to do so.
 *
 * Configuration in generator.yml:
 * <pre>
 * database:
 *   query_conversion:
 *     enabled: true
 *     skip_processed: false  # Set to true to skip already-processed repositories
 *   log_file: query-optimization-stats.csv
 * </pre>
 */
public class QueryOptimizationTracker {
    private static final Logger logger = LoggerFactory.getLogger(QueryOptimizationTracker.class);

    private static final String DATABASE_KEY = "database";
    private static final String QUERY_CONVERSION_KEY = "query_conversion";
    private static final String SKIP_PROCESSED_KEY = "skip_processed";
    private static final String LOG_FILE_KEY = "log_file";
    private static final String DEFAULT_LOG_FILE = "query-optimization-stats.csv";

    private final Set<String> processedRepositories;
    private final boolean skipProcessedEnabled;
    private final String logFilePath;
    private boolean initialized;

    private static QueryOptimizationTracker instance;

    private QueryOptimizationTracker() {
        this.processedRepositories = new HashSet<>();
        this.skipProcessedEnabled = isSkipProcessedConfigured();
        this.logFilePath = readLogFilePathFromConfig();
        this.initialized = false;
    }

    /**
     * Gets the singleton instance of the tracker.
     */
    public static synchronized QueryOptimizationTracker getInstance() {
        if (instance == null) {
            instance = new QueryOptimizationTracker();
        }
        return instance;
    }

    /**
     * Resets the singleton instance. Useful for testing.
     */
    public static synchronized void reset() {
        instance = null;
    }

    /**
     * Checks if a repository should be skipped.
     *
     * @param repositoryName the fully qualified name of the repository
     * @return true if the repository should be skipped, false otherwise
     */
    public boolean shouldSkip(String repositoryName) {
        if (!skipProcessedEnabled) {
            return false;
        }
        ensureInitialized();
        boolean skip = processedRepositories.contains(repositoryName);
        if (skip) {
            logger.debug("Skipping already processed repository: {}", repositoryName);
        }
        return skip;
    }

    /**
     * Gets the set of processed repository names.
     * Initializes from CSV if not already done.
     *
     * @return set of processed repository names
     */
    public Set<String> getProcessedRepositories() {
        ensureInitialized();
        return new HashSet<>(processedRepositories);
    }

    /**
     * Returns whether skip-processed feature is enabled.
     */
    public boolean isSkipProcessedEnabled() {
        return skipProcessedEnabled;
    }

    /**
     * Returns the path to the log file.
     */
    public String getLogFilePath() {
        return logFilePath;
    }

    /**
     * Ensures the tracker is initialized by reading the CSV file.
     */
    private synchronized void ensureInitialized() {
        if (initialized) {
            return;
        }
        initialized = true;
        loadProcessedRepositoriesFromCsv();
    }

    /**
     * Loads the list of processed repositories from the CSV file.
     * The CSV format is expected to have the repository name in the second column.
     */
    private void loadProcessedRepositoriesFromCsv() {
        File csvFile = new File(logFilePath);
        if (!csvFile.exists()) {
            logger.debug("Query optimization stats file not found: {}", logFilePath);
            return;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(csvFile))) {
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                // Skip header row if present
                if (firstLine) {
                    firstLine = false;
                    if (isHeaderRow(line)) {
                        continue;
                    }
                }

                String repositoryName = extractRepositoryName(line);
                if (repositoryName != null && !repositoryName.isEmpty()) {
                    processedRepositories.add(repositoryName);
                }
            }
            logger.info("Loaded {} processed repositories from {}", processedRepositories.size(), logFilePath);
        } catch (IOException e) {
            logger.warn("Failed to read query optimization stats file: {}", e.getMessage());
        }
    }

    /**
     * Checks if a line appears to be a header row.
     */
    private boolean isHeaderRow(String line) {
        String lower = line.toLowerCase();
        return lower.contains("repository") || lower.contains("timestamp") || lower.startsWith("#");
    }

    /**
     * Extracts the repository name from a CSV line.
     * The repository name is expected to be in the second column (index 1).
     *
     * @param line the CSV line
     * @return the repository name, or null if not found
     */
    private String extractRepositoryName(String line) {
        if (line == null || line.trim().isEmpty()) {
            return null;
        }

        String[] columns = line.split(",");
        if (columns.length >= 2) {
            // Repository name is in the second column
            return columns[1].trim().replace("\"", "");
        }
        return null;
    }

    /**
     * Checks if skip_processed is enabled in configuration.
     */
    private static boolean isSkipProcessedConfigured() {
        Object databaseConfig = Settings.getProperty(DATABASE_KEY);
        if (databaseConfig instanceof Map<?, ?> dbMap) {
            Object queryConversionConfig = dbMap.get(QUERY_CONVERSION_KEY);
            if (queryConversionConfig instanceof Map<?, ?> qcMap) {
                Object skipProcessed = qcMap.get(SKIP_PROCESSED_KEY);
                if (skipProcessed instanceof Boolean b) {
                    return b;
                } else if (skipProcessed != null) {
                    return Boolean.parseBoolean(skipProcessed.toString());
                }
            }
        }
        return false; // Default is off
    }

    /**
     * Reads the log file path from configuration.
     */
    private static String readLogFilePathFromConfig() {
        Object databaseConfig = Settings.getProperty(DATABASE_KEY);
        if (databaseConfig instanceof Map<?, ?> dbMap) {
            Object logFile = dbMap.get(LOG_FILE_KEY);
            if (logFile instanceof String s) {
                return s;
            }
        }
        return DEFAULT_LOG_FILE;
    }
}
