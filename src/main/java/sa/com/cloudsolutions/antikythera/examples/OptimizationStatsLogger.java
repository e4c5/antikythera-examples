package sa.com.cloudsolutions.antikythera.examples;

import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Logs optimization statistics to a CSV file for tracking QueryOptimizer changes.
 * The CSV file is appended to on each run and contains detailed metrics about
 * the changes made to repository classes.
 */
public class OptimizationStatsLogger {
    private static final String CSV_FILENAME = "query-optimization-stats.csv";
    private static final String CSV_HEADER = "Time Stamps,Repositories,Queries,Annotations Changed,Signatures Changed,Calls Updated,Dependency Classes,Indexes Created, Indexes Dropped";
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
         * When a method signature change occurs, all calls to that method must be updated.
         */
        private int methodCallsChanged = 0;
        /**
         * The number of classes changed so far!
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
            try {
                Path csvPath = Paths.get(CSV_FILENAME);
                FileWriter fw = new FileWriter(CSV_FILENAME, true);
                PrintWriter out = new PrintWriter(fw);

                if (!csvPath.toFile().exists()) {
                    out.println(CSV_HEADER);
                }
                out.println(String.format("%s,%s,%d,%d,%d,%d,%d,%d,%d",
                        java.time.LocalDateTime.now(),
                        current.repo,
                        current.queriesAnalyzed,
                        current.queryAnnotationsChanged,
                        current.methodSignaturesChanged,
                        current.methodCallsChanged,
                        current.dependentClassesModified,
                        current.liquibaseIndexesGenerated,
                        current.liquibaseIndexesDropped));
                out.close();
                fw.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            current = new Stats(repo);
            totalRepositoriesProcessed++;
        }
    }
    public static void updateQueriesAnalyzed(int queriesAnalyzed) {
        current.queriesAnalyzed += queriesAnalyzed;
        total.queriesAnalyzed += current.queriesAnalyzed;
    }

    public static void updateQueryAnnotationsChanged(int i) {
        current.queryAnnotationsChanged += i;
        total.queryAnnotationsChanged += current.queryAnnotationsChanged;
    }

    public static void updateMethodSignaturesChanged(int i) {
        current.methodSignaturesChanged += i;
        total.methodSignaturesChanged += current.methodSignaturesChanged;
    }

    public static void updateMethodCallsChanged(int i) {
        current.methodCallsChanged += i;
        total.methodCallsChanged += current.methodCallsChanged;
    }

    public static void updateDependentClassesChanged(int i) {
        current.dependentClassesModified += i;
        total.dependentClassesModified += current.dependentClassesModified;
    }

    public static int updateIndexesGenerated(int i) {
        current.liquibaseIndexesGenerated += i;
        total.liquibaseIndexesGenerated += current.liquibaseIndexesGenerated;
        return current.liquibaseIndexesGenerated;
    }

    public static void updateIndexesDropped(int i) {
        current.liquibaseIndexesDropped += i;
        total.liquibaseIndexesDropped += current.liquibaseIndexesDropped;
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
        out.printf("Files modified:              %d%n", total.dependentClassesModified + totalRepositoriesProcessed);
        out.printf("@Query annotations changed:  %d%n", total.queryAnnotationsChanged);
        out.printf("Method signatures changed:   %d%n", total.methodSignaturesChanged);
        out.printf("Method calls updated:        %d%n", total.methodCallsChanged);
        out.printf("Dependent classes modified:  %d%n", total.dependentClassesModified);
        out.printf("Liquibase indexes generated: %d%n", total.liquibaseIndexesGenerated);
        out.printf("Liquibase indexes generated: %d%n", total.liquibaseIndexesDropped);
        out.println("=".repeat(80));
    }
}
