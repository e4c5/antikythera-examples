package sa.com.cloudsolutions.antikythera.examples;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sa.com.cloudsolutions.antikythera.examples.util.FileOperationsManager;
import sa.com.cloudsolutions.antikythera.examples.util.LiquibaseGenerator;
import sa.com.cloudsolutions.antikythera.examples.util.RepositoryAnalyzer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance benchmarking tests to ensure refactored components
 * maintain or improve performance compared to original implementations.
 */
public class PerformanceBenchmarkTest {

    @TempDir
    Path tempDir;
    
    private FileOperationsManager fileOpsManager;
    private LiquibaseGenerator liquibaseGenerator;
    private RepositoryAnalyzer repositoryAnalyzer;
    
    private static final int BENCHMARK_ITERATIONS = 100;
    private static final long PERFORMANCE_THRESHOLD_MS = 5000; // 5 seconds max for benchmark operations

    @BeforeEach
    void setUp() {
        fileOpsManager = new FileOperationsManager();
        liquibaseGenerator = new LiquibaseGenerator();
        repositoryAnalyzer = new RepositoryAnalyzer();
    }

    @Test
    void benchmarkFileOperationsPerformance() throws IOException {
        // Benchmark file operations to ensure they perform adequately
        List<Long> writeTimes = new ArrayList<>();
        List<Long> readTimes = new ArrayList<>();
        
        String testContent = "This is test content for performance benchmarking.\n".repeat(100);
        
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            Path testFile = tempDir.resolve("benchmark_" + i + ".txt");
            
            // Benchmark write operations
            long writeStart = System.nanoTime();
            fileOpsManager.writeFileContent(testFile, testContent);
            long writeEnd = System.nanoTime();
            writeTimes.add((writeEnd - writeStart) / 1_000_000); // Convert to milliseconds
            
            // Benchmark read operations
            long readStart = System.nanoTime();
            String readContent = fileOpsManager.readFileContent(testFile);
            long readEnd = System.nanoTime();
            readTimes.add((readEnd - readStart) / 1_000_000); // Convert to milliseconds
            
            assertEquals(testContent, readContent, "Content should match for iteration " + i);
        }
        
        // Calculate average times
        double avgWriteTime = writeTimes.stream().mapToLong(Long::longValue).average().orElse(0.0);
        double avgReadTime = readTimes.stream().mapToLong(Long::longValue).average().orElse(0.0);
        
        System.out.println("Average write time: " + avgWriteTime + " ms");
        System.out.println("Average read time: " + avgReadTime + " ms");
        
        // Performance assertions - operations should be reasonably fast
        assertTrue(avgWriteTime < 100, "Average write time should be under 100ms, was: " + avgWriteTime);
        assertTrue(avgReadTime < 50, "Average read time should be under 50ms, was: " + avgReadTime);
        
        // Total time for all operations should be reasonable
        long totalTime = writeTimes.stream().mapToLong(Long::longValue).sum() + 
                        readTimes.stream().mapToLong(Long::longValue).sum();
        assertTrue(totalTime < PERFORMANCE_THRESHOLD_MS, 
                  "Total file operations time should be under " + PERFORMANCE_THRESHOLD_MS + "ms, was: " + totalTime);
    }

    @Test
    void benchmarkLiquibaseGenerationPerformance() {
        // Benchmark Liquibase changeset generation performance
        List<Long> singleIndexTimes = new ArrayList<>();
        List<Long> multiIndexTimes = new ArrayList<>();
        List<Long> dropIndexTimes = new ArrayList<>();
        
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            // Benchmark single column index generation
            long singleStart = System.nanoTime();
            String singleIndex = liquibaseGenerator.createIndexChangeset("table_" + i, "column_" + i);
            long singleEnd = System.nanoTime();
            singleIndexTimes.add((singleEnd - singleStart) / 1_000_000);
            
            assertNotNull(singleIndex, "Single index changeset should not be null");
            assertTrue(singleIndex.contains("table_" + i), "Should contain table name");
            
            // Benchmark multi-column index generation
            List<String> columns = Arrays.asList("col1_" + i, "col2_" + i, "col3_" + i);
            long multiStart = System.nanoTime();
            String multiIndex = liquibaseGenerator.createMultiColumnIndexChangeset("table_" + i, columns);
            long multiEnd = System.nanoTime();
            multiIndexTimes.add((multiEnd - multiStart) / 1_000_000);
            
            assertNotNull(multiIndex, "Multi-column index changeset should not be null");
            
            // Benchmark drop index generation
            long dropStart = System.nanoTime();
            String dropIndex = liquibaseGenerator.createDropIndexChangeset("index_" + i);
            long dropEnd = System.nanoTime();
            dropIndexTimes.add((dropEnd - dropStart) / 1_000_000);
            
            assertNotNull(dropIndex, "Drop index changeset should not be null");
        }
        
        // Calculate averages
        double avgSingleTime = singleIndexTimes.stream().mapToLong(Long::longValue).average().orElse(0.0);
        double avgMultiTime = multiIndexTimes.stream().mapToLong(Long::longValue).average().orElse(0.0);
        double avgDropTime = dropIndexTimes.stream().mapToLong(Long::longValue).average().orElse(0.0);
        
        System.out.println("Average single index generation time: " + avgSingleTime + " ms");
        System.out.println("Average multi-column index generation time: " + avgMultiTime + " ms");
        System.out.println("Average drop index generation time: " + avgDropTime + " ms");
        
        // Performance assertions - generation should be very fast
        assertTrue(avgSingleTime < 10, "Single index generation should be under 10ms, was: " + avgSingleTime);
        assertTrue(avgMultiTime < 20, "Multi-column index generation should be under 20ms, was: " + avgMultiTime);
        assertTrue(avgDropTime < 5, "Drop index generation should be under 5ms, was: " + avgDropTime);
    }

    @Test
    void benchmarkBulkFileOperations() throws IOException {
        // Test performance with bulk file operations
        int fileCount = 50;
        List<Path> testFiles = new ArrayList<>();
        String content = "Bulk operation test content\n".repeat(50);
        
        // Benchmark bulk write operations
        long bulkWriteStart = System.nanoTime();
        for (int i = 0; i < fileCount; i++) {
            Path file = tempDir.resolve("bulk_" + i + ".txt");
            testFiles.add(file);
            fileOpsManager.writeFileContent(file, content + i);
        }
        long bulkWriteEnd = System.nanoTime();
        long bulkWriteTime = (bulkWriteEnd - bulkWriteStart) / 1_000_000;
        
        // Benchmark bulk read operations
        long bulkReadStart = System.nanoTime();
        for (Path file : testFiles) {
            String readContent = fileOpsManager.readFileContent(file);
            assertNotNull(readContent, "Content should not be null");
            assertTrue(readContent.contains("Bulk operation test content"), "Should contain expected content");
        }
        long bulkReadEnd = System.nanoTime();
        long bulkReadTime = (bulkReadEnd - bulkReadStart) / 1_000_000;
        
        System.out.println("Bulk write time for " + fileCount + " files: " + bulkWriteTime + " ms");
        System.out.println("Bulk read time for " + fileCount + " files: " + bulkReadTime + " ms");
        
        // Performance assertions
        assertTrue(bulkWriteTime < 2000, "Bulk write should complete in under 2 seconds, was: " + bulkWriteTime + "ms");
        assertTrue(bulkReadTime < 1000, "Bulk read should complete in under 1 second, was: " + bulkReadTime + "ms");
        
        // Average per file should be reasonable
        double avgWritePerFile = (double) bulkWriteTime / fileCount;
        double avgReadPerFile = (double) bulkReadTime / fileCount;
        
        assertTrue(avgWritePerFile < 50, "Average write per file should be under 50ms, was: " + avgWritePerFile);
        assertTrue(avgReadPerFile < 25, "Average read per file should be under 25ms, was: " + avgReadPerFile);
    }

    @Test
    void benchmarkMemoryUsage() throws IOException {
        // Test memory usage patterns during operations
        Runtime runtime = Runtime.getRuntime();
        
        // Force garbage collection to get baseline
        System.gc();
        long initialMemory = runtime.totalMemory() - runtime.freeMemory();
        
        // Perform memory-intensive operations
        List<String> largeContent = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            String content = "Large content block " + i + "\n".repeat(100);
            largeContent.add(content);
            
            Path file = tempDir.resolve("memory_test_" + i + ".txt");
            fileOpsManager.writeFileContent(file, content);
        }
        
        long peakMemory = runtime.totalMemory() - runtime.freeMemory();
        long memoryIncrease = peakMemory - initialMemory;
        
        System.out.println("Initial memory usage: " + (initialMemory / 1024 / 1024) + " MB");
        System.out.println("Peak memory usage: " + (peakMemory / 1024 / 1024) + " MB");
        System.out.println("Memory increase: " + (memoryIncrease / 1024 / 1024) + " MB");
        
        // Memory usage should be reasonable (under 100MB increase for this test)
        assertTrue(memoryIncrease < 100 * 1024 * 1024, 
                  "Memory increase should be under 100MB, was: " + (memoryIncrease / 1024 / 1024) + "MB");
        
        // Clear references and force GC
        largeContent.clear();
        System.gc();
        
        long finalMemory = runtime.totalMemory() - runtime.freeMemory();
        long memoryAfterGC = finalMemory - initialMemory;
        
        // Memory should be mostly reclaimed after GC
        assertTrue(memoryAfterGC < memoryIncrease / 2, 
                  "Memory should be mostly reclaimed after GC");
    }

    @Test
    void benchmarkConcurrentOperations() throws IOException, InterruptedException {
        // Test performance under concurrent access
        int threadCount = 10;
        int operationsPerThread = 20;
        List<Thread> threads = new ArrayList<>();
        List<Long> threadTimes = new ArrayList<>();
        
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            Thread thread = new Thread(() -> {
                try {
                    long threadStart = System.nanoTime();
                    
                    for (int i = 0; i < operationsPerThread; i++) {
                        Path file = tempDir.resolve("concurrent_" + threadId + "_" + i + ".txt");
                        String content = "Thread " + threadId + " operation " + i;
                        
                        fileOpsManager.writeFileContent(file, content);
                        String readContent = fileOpsManager.readFileContent(file);
                        assertEquals(content, readContent, "Content should match");
                    }
                    
                    long threadEnd = System.nanoTime();
                    synchronized (threadTimes) {
                        threadTimes.add((threadEnd - threadStart) / 1_000_000);
                    }
                } catch (IOException e) {
                    fail("Thread " + threadId + " failed with IOException: " + e.getMessage());
                }
            });
            threads.add(thread);
        }
        
        // Start all threads
        long overallStart = System.nanoTime();
        for (Thread thread : threads) {
            thread.start();
        }
        
        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }
        long overallEnd = System.nanoTime();
        
        long overallTime = (overallEnd - overallStart) / 1_000_000;
        double avgThreadTime = threadTimes.stream().mapToLong(Long::longValue).average().orElse(0.0);
        
        System.out.println("Overall concurrent operation time: " + overallTime + " ms");
        System.out.println("Average thread completion time: " + avgThreadTime + " ms");
        
        // Performance assertions for concurrent operations
        assertTrue(overallTime < 10000, "Concurrent operations should complete in under 10 seconds, was: " + overallTime + "ms");
        assertTrue(avgThreadTime < 5000, "Average thread time should be under 5 seconds, was: " + avgThreadTime + "ms");
        
        // Verify all operations completed successfully
        assertEquals(threadCount, threadTimes.size(), "All threads should have completed");
    }

    @Test
    void benchmarkLargeFileOperations() throws IOException {
        // Test performance with larger files
        StringBuilder largeContent = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            largeContent.append("Line ").append(i).append(" with some content to make it longer\n");
        }
        
        String content = largeContent.toString();
        Path largeFile = tempDir.resolve("large_file.txt");
        
        // Benchmark large file write
        long writeStart = System.nanoTime();
        fileOpsManager.writeFileContent(largeFile, content);
        long writeEnd = System.nanoTime();
        long writeTime = (writeEnd - writeStart) / 1_000_000;
        
        // Benchmark large file read
        long readStart = System.nanoTime();
        String readContent = fileOpsManager.readFileContent(largeFile);
        long readEnd = System.nanoTime();
        long readTime = (readEnd - readStart) / 1_000_000;
        
        System.out.println("Large file write time: " + writeTime + " ms");
        System.out.println("Large file read time: " + readTime + " ms");
        System.out.println("File size: " + (content.length() / 1024) + " KB");
        
        assertEquals(content, readContent, "Large file content should match");
        
        // Performance assertions for large files
        assertTrue(writeTime < 1000, "Large file write should complete in under 1 second, was: " + writeTime + "ms");
        assertTrue(readTime < 500, "Large file read should complete in under 500ms, was: " + readTime + "ms");
    }
}