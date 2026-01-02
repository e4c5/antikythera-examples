package sa.com.cloudsolutions.antikythera.examples;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Logs optimization statistics to a CSV file for tracking QueryOptimizer
 * changes.
 * The CSV file is appended to on each run and contains detailed metrics about
 * the changes made to repository classes.
 */
public class OptimizationStatsLogger {
    private static final Logger logger = LoggerFactory.getLogger(OptimizationStatsLogger.class);
    private static final String CSV_FILENAME = "query-optimization-stats.csv";
    private static final String CSV_HEADER = "Date,Repository Name,Queries,Annotations Changed,Signatures Changed,Calls Updated,Dependency Classes,Indexes Created,Indexes Dropped";
    private static Stats current = null;
    private static Stats total;

    public static class Stats {
        private final String repo;
        /**
         * The number of queries across all repository methods that were analyzed.
         */
        private int queriesAnalyzed = 0;
        /**
         * Total Query annotations changes across all repositories
         */
        private int queryAnnotationsChanged = 0;
        /**
         * Total number number of method signatures changed across all repositories
         */
        private int methodSignaturesChanged = 0;
        /**
         * Method calls changes across all classes.
         * When a method signature change occurs, all calls to that method must be
         * updated.
         */
        private int methodCallsChanged = 0;
        /**
         * The number of repository files that were actually modified.
         */
        private int repositoriesModified = 0;
        /**
         * The number of dependent classes changed (not including repositories).
         */
        private int dependentClassesModified = 0;
        /**
         * The number of Liquibase indexes generated across the board.
         */
        private int liquibaseIndexesGenerated = 0;

        /**
         * The number of redundant indexes dropped from Liquibase changelogs.
         */
        private int liquibaseIndexesDropped = 0;

        public Stats(String repo) {
            this.repo = repo;
        }
    }

    private static int totalRepositoriesProcessed = 0;

    private OptimizationStatsLogger() {
        /* not to be instantiated */
    }

    public static Stats getCurrent() {
        return current;
    }

    public static void initialize(String repo) {
        if (current == null) {
            current = new Stats(repo);
            total = new Stats("");
        } else {
            flush();
            current = new Stats(repo);
        }
        totalRepositoriesProcessed++;
    }

    public static void flush() {
        if (current == null) {
            return;
        }
        logStats(current);
        // Reset current stats to zero for any post-analysis global updates,
        // but preserve the repo name to avoid NullPointerException if used.
        current.queriesAnalyzed = 0;
        current.queryAnnotationsChanged = 0;
        current.methodSignaturesChanged = 0;
        current.methodCallsChanged = 0;
        current.repositoriesModified = 0;
        current.dependentClassesModified = 0;
        current.liquibaseIndexesGenerated = 0;
        current.liquibaseIndexesDropped = 0;
    }


    private static void logStats(Stats stats) {
        Path csvPath = Paths.get(CSV_FILENAME);
        boolean exists = csvPath.toFile().exists();

        try (FileWriter fw = new FileWriter(CSV_FILENAME, true);
             PrintWriter out = new PrintWriter(fw)) {

            if (!exists) {
                out.println(CSV_HEADER);
            }

            out.println(String.format("%s,%s,%d,%d,%d,%d,%d,%d,%d",
                    java.time.LocalDate.now(),
                    stats.repo,
                    stats.queriesAnalyzed,
                    stats.queryAnnotationsChanged,
                    stats.methodSignaturesChanged,
                    stats.methodCallsChanged,
                    stats.dependentClassesModified,
                    stats.liquibaseIndexesGenerated,
                    stats.liquibaseIndexesDropped));
        } catch (IOException e) {
            logger.error("Failed to write to CSV: {}", e.getMessage());
        }
    }

    public static void updateQueriesAnalyzed(int queriesAnalyzed) {
        current.queriesAnalyzed += queriesAnalyzed;
        total.queriesAnalyzed += queriesAnalyzed;
    }

    public static void updateQueryAnnotationsChanged(int i) {
        current.queryAnnotationsChanged += i;
        total.queryAnnotationsChanged += i;
    }

    public static void updateMethodSignaturesChanged(int i) {
        current.methodSignaturesChanged += i;
        total.methodSignaturesChanged += i;
    }

    public static void updateMethodCallsChanged(int i) {
        current.methodCallsChanged += i;
        total.methodCallsChanged += i;
    }

    public static void updateRepositoriesModified(int i) {
        current.repositoriesModified += i;
        total.repositoriesModified += i;
    }

    public static void updateDependentClassesChanged(int i) {
        current.dependentClassesModified += i;
        total.dependentClassesModified += i;
    }

    public static int updateIndexesGenerated(int i) {
        current.liquibaseIndexesGenerated += i;
        total.liquibaseIndexesGenerated += i;
        return current.liquibaseIndexesGenerated;
    }

    public static void updateIndexesDropped(int i) {
        if (current != null) {
            current.liquibaseIndexesDropped += i;
        }
        total.liquibaseIndexesDropped += i;
    }

    public static int getTotalIndexesGenerated() {
        return total.liquibaseIndexesGenerated + current.liquibaseIndexesGenerated;
    }

    public static int getTotalIndexesDropped() {
        return total.liquibaseIndexesDropped + current.liquibaseIndexesDropped;
    }

    public static void printSummary(PrintStream out) {
        // Print overall code modification statistics
        out.println("\n" + "=".repeat(80));
        out.println("📝 CODE MODIFICATION SUMMARY");
        out.println("=".repeat(80));
        out.printf("Repositories processed:      %d%n", totalRepositoriesProcessed);
        out.printf("Repository files modified:   %d%n", total.repositoriesModified);
        out.printf("@Query annotations changed:  %d%n", total.queryAnnotationsChanged);
        out.printf("Method signatures changed:   %d%n", total.methodSignaturesChanged);
        out.printf("Method calls updated:        %d%n", total.methodCallsChanged);
        out.printf("Dependent classes modified:  %d%n", total.dependentClassesModified);
        out.printf("Liquibase indexes generated: %d%n", total.liquibaseIndexesGenerated);
        out.printf("Liquibase indexes Dropped:   %d%n", total.liquibaseIndexesDropped);
        out.println("=".repeat(80));
    }
}
