package sa.com.cloudsolutions.antikythera.examples;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Logs optimization statistics to a CSV file for tracking QueryOptimizer changes.
 * The CSV file is appended to on each run and contains detailed metrics about
 * the changes made to repository classes.
 */
public class OptimizationStatsLogger {
    
    private static final String CSV_FILENAME = "query-optimization-stats.csv";
    private static final String CSV_HEADER = "timestamp,repository_class,queries_analyzed,query_annotations_changed,method_signatures_changed,method_calls_updated,dependent_classes_modified,liquibase_indexes_generated";
    
    /**
     * Statistics for a single repository optimization run.
     */
    public static class RepositoryStats {
        private final String repositoryClass;
        private int queriesAnalyzed = 0;
        private int queryAnnotationsChanged = 0;
        private int methodSignaturesChanged = 0;
        private int methodCallsUpdated = 0;
        private int dependentClassesModified = 0;
        private int liquibaseIndexesGenerated = 0;
        
        public RepositoryStats(String repositoryClass) {
            this.repositoryClass = repositoryClass;
        }
        
        // Getters and setters
        public String getRepositoryClass() { return repositoryClass; }
        public int getQueriesAnalyzed() { return queriesAnalyzed; }
        public void setQueriesAnalyzed(int queriesAnalyzed) { this.queriesAnalyzed = queriesAnalyzed; }
        public int getQueryAnnotationsChanged() { return queryAnnotationsChanged; }
        public void setQueryAnnotationsChanged(int queryAnnotationsChanged) { this.queryAnnotationsChanged = queryAnnotationsChanged; }
        public int getMethodSignaturesChanged() { return methodSignaturesChanged; }
        public void setMethodSignaturesChanged(int methodSignaturesChanged) { this.methodSignaturesChanged = methodSignaturesChanged; }
        public int getMethodCallsUpdated() { return methodCallsUpdated; }
        public void setMethodCallsUpdated(int methodCallsUpdated) { this.methodCallsUpdated = methodCallsUpdated; }
        public int getDependentClassesModified() { return dependentClassesModified; }
        public void setDependentClassesModified(int dependentClassesModified) { this.dependentClassesModified = dependentClassesModified; }
        public int getLiquibaseIndexesGenerated() { return liquibaseIndexesGenerated; }
        public void setLiquibaseIndexesGenerated(int liquibaseIndexesGenerated) { this.liquibaseIndexesGenerated = liquibaseIndexesGenerated; }
        
        // Increment methods for easier tracking
        public void incrementQueryAnnotationsChanged() { queryAnnotationsChanged++; }
        public void incrementMethodSignaturesChanged() { methodSignaturesChanged++; }
        public void incrementMethodCallsUpdated() { methodCallsUpdated++; }
        public void incrementDependentClassesModified() { dependentClassesModified++; }
        
        @Override
        public String toString() {
            return String.format("RepositoryStats{class=%s, queries=%d, annotations=%d, signatures=%d, calls=%d, classes=%d, indexes=%d}",
                    repositoryClass, queriesAnalyzed, queryAnnotationsChanged, methodSignaturesChanged, 
                    methodCallsUpdated, dependentClassesModified, liquibaseIndexesGenerated);
        }
    }
    
    /**
     * Logs repository optimization statistics to the CSV file.
     * Creates the file with headers if it doesn't exist, otherwise appends.
     * 
     * @param stats the statistics to log
     * @throws IOException if file writing fails
     */
    public static void logStats(RepositoryStats stats) throws IOException {
        Path csvPath = Paths.get(CSV_FILENAME);
        boolean fileExists = Files.exists(csvPath);
        
        try (FileWriter writer = new FileWriter(csvPath.toFile(), true)) {
            // Write header if file is new
            if (!fileExists) {
                writer.write(CSV_HEADER + "\n");
            }
            
            // Write stats row
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            String row = String.format("%s,%s,%d,%d,%d,%d,%d,%d\n",
                    timestamp,
                    stats.getRepositoryClass(),
                    stats.getQueriesAnalyzed(),
                    stats.getQueryAnnotationsChanged(),
                    stats.getMethodSignaturesChanged(),
                    stats.getMethodCallsUpdated(),
                    stats.getDependentClassesModified(),
                    stats.getLiquibaseIndexesGenerated());
            
            writer.write(row);
        }
        
        // Also log to console for immediate feedback (skip if quiet mode enabled)
        if (!QueryOptimizationChecker.isQuietMode()) {
            System.out.printf("📊 OPTIMIZATION STATS: %s%n", stats);
            System.out.printf("📝 Stats logged to: %s%n", csvPath.toAbsolutePath());
        }
    }
    
    /**
     * Creates a new RepositoryStats instance for tracking.
     * 
     * @param repositoryClass the fully qualified repository class name
     * @return new RepositoryStats instance
     */
    public static RepositoryStats createStats(String repositoryClass) {
        return new RepositoryStats(repositoryClass);
    }
}