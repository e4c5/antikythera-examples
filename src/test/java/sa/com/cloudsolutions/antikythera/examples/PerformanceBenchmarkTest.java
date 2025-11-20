package sa.com.cloudsolutions.antikythera.examples;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.examples.util.LiquibaseGenerator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance benchmarking tests to ensure refactored components
 * maintain or improve performance compared to original implementations.
 */
class PerformanceBenchmarkTest {
    private LiquibaseGenerator liquibaseGenerator;
    
    private static final int BENCHMARK_ITERATIONS = 100;

    @BeforeEach
    void setUp() {
       liquibaseGenerator = new LiquibaseGenerator();
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
}
