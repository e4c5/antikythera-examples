package sa.com.cloudsolutions.antikythera.examples;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.liquibase.Indexes;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for index redundancy removal methods in QueryOptimizationChecker.
 * Tests both removeRedundantMultiColumnIndexes and removeRedundantSingleColumnIndexes methods.
 */
class QueryOptimizationCheckerRedundancyTest {

    private QueryOptimizationChecker checker;
    private static File liquibaseFile;

    @BeforeAll
    static void setupClass() throws Exception {
        // Create temporary Liquibase file for testing
        Path tmpDir = Files.createTempDirectory("qoc-redundancy-test");
        liquibaseFile = tmpDir.resolve("db.changelog-master.xml").toFile();
        try (FileWriter fw = new FileWriter(liquibaseFile)) {
            fw.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            fw.write("<databaseChangeLog\n");
            fw.write("    xmlns=\"http://www.liquibase.org/xml/ns/dbchangelog\"\n");
            fw.write("    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
            fw.write("    xsi:schemaLocation=\"http://www.liquibase.org/xml/ns/dbchangelog\n");
            fw.write("    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.0.xsd\">\n");
            fw.write("</databaseChangeLog>");
        }
        assertTrue(Indexes.load(liquibaseFile).isEmpty(), "Expected empty index map for minimal Liquibase file");

        Settings.loadConfigMap(new File("src/test/resources/generator.yml"));
    }

    @BeforeEach
    void setUp() throws Exception {
        checker = new QueryOptimizationChecker(liquibaseFile);
        OptimizationStatsLogger.initialize("redundancy-test");
        QueryOptimizationChecker.setQuietMode(true); // Suppress logging during tests
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        // Reset static state to avoid interfering with other tests
        QueryOptimizationChecker.setQuietMode(false);
    }

    // ==================== Tests for removeRedundantMultiColumnIndexes ====================

    @Test
    void testRemoveRedundantMultiColumnIndexes_BasicRedundancy() {
        // Setup: Add indexes where (A,B) is covered by (A,B,C)
        LinkedHashSet<String> multiColumnIndexes = checker.getSuggestedMultiColumnIndexes();
        multiColumnIndexes.add("users|id,name");           // Should be removed
        multiColumnIndexes.add("users|id,name,email");     // Covers the above

        Set<String> toRemove = new HashSet<>();
        checker.removeRedundantMultiColumnIndexes(toRemove);

        // Verify: The smaller index should be marked for removal
        assertTrue(toRemove.contains("users|id,name"), 
            "Index (id,name) should be marked for removal as it's covered by (id,name,email)");
        assertFalse(toRemove.contains("users|id,name,email"), 
            "Index (id,name,email) should NOT be marked for removal");
        assertEquals(1, toRemove.size(), "Only one index should be marked for removal");
    }

    @Test
    void testRemoveRedundantMultiColumnIndexes_NoDifferentTables() {
        // Setup: Indexes on different tables should not interfere
        LinkedHashSet<String> multiColumnIndexes = checker.getSuggestedMultiColumnIndexes();
        multiColumnIndexes.add("users|id,name");
        multiColumnIndexes.add("orders|id,name,email");    // Different table

        Set<String> toRemove = new HashSet<>();
        checker.removeRedundantMultiColumnIndexes(toRemove);

        // Verify: No indexes should be removed (different tables)
        assertTrue(toRemove.isEmpty(), 
            "No indexes should be removed when they're on different tables");
    }

    @Test
    void testRemoveRedundantMultiColumnIndexes_DifferentColumnOrder() {
        // Setup: Different column order means no coverage
        LinkedHashSet<String> multiColumnIndexes = checker.getSuggestedMultiColumnIndexes();
        multiColumnIndexes.add("users|id,name");
        multiColumnIndexes.add("users|name,id,email");     // Different order

        Set<String> toRemove = new HashSet<>();
        checker.removeRedundantMultiColumnIndexes(toRemove);

        // Verify: No indexes should be removed (different column order)
        assertTrue(toRemove.isEmpty(), 
            "No indexes should be removed when column order differs");
    }

    @Test
    void testRemoveRedundantMultiColumnIndexes_MultipleCoveringIndexes() {
        // Setup: One index covered by multiple larger indexes
        LinkedHashSet<String> multiColumnIndexes = checker.getSuggestedMultiColumnIndexes();
        multiColumnIndexes.add("users|id,name");           // Should be removed
        multiColumnIndexes.add("users|id,name,email");     // Covers the above
        multiColumnIndexes.add("users|id,name,status");    // Also covers the above

        Set<String> toRemove = new HashSet<>();
        checker.removeRedundantMultiColumnIndexes(toRemove);

        // Verify: The smaller index should be marked for removal (break on first match)
        assertTrue(toRemove.contains("users|id,name"), 
            "Index (id,name) should be marked for removal");
        assertEquals(1, toRemove.size(), 
            "Only the redundant index should be marked for removal");
    }

    @Test
    void testRemoveRedundantMultiColumnIndexes_EmptySet() {
        // Setup: Empty index set
        Set<String> toRemove = new HashSet<>();
        checker.removeRedundantMultiColumnIndexes(toRemove);

        // Verify: Should handle empty set without errors
        assertTrue(toRemove.isEmpty(), "toRemove should remain empty");
    }

    @Test
    void testRemoveRedundantMultiColumnIndexes_SingleIndex() {
        // Setup: Only one index
        LinkedHashSet<String> multiColumnIndexes = checker.getSuggestedMultiColumnIndexes();
        multiColumnIndexes.add("users|id,name,email");

        Set<String> toRemove = new HashSet<>();
        checker.removeRedundantMultiColumnIndexes(toRemove);

        // Verify: Single index should not be removed
        assertTrue(toRemove.isEmpty(), 
            "Single index should not be marked for removal");
    }

    @Test
    void testRemoveRedundantMultiColumnIndexes_CaseInsensitive() {
        // Setup: Test case-insensitive comparison
        LinkedHashSet<String> multiColumnIndexes = checker.getSuggestedMultiColumnIndexes();
        multiColumnIndexes.add("users|email,name");        // Should be removed
        multiColumnIndexes.add("users|EMAIL,NAME,id");     // Covers the above (case-insensitive)

        Set<String> toRemove = new HashSet<>();
        checker.removeRedundantMultiColumnIndexes(toRemove);

        // Verify: Case-insensitive matching should work
        assertTrue(toRemove.contains("users|email,name"), 
            "Index should be marked for removal with case-insensitive matching");
    }

    @Test
    void testRemoveRedundantMultiColumnIndexes_ChainedRedundancy() {
        // Setup: (A,B) covered by (A,B,C) covered by (A,B,C,D)
        LinkedHashSet<String> multiColumnIndexes = checker.getSuggestedMultiColumnIndexes();
        multiColumnIndexes.add("users|id,name");
        multiColumnIndexes.add("users|id,name,email");
        multiColumnIndexes.add("users|id,name,email,status");

        Set<String> toRemove = new HashSet<>();
        checker.removeRedundantMultiColumnIndexes(toRemove);

        // Verify: Both smaller indexes should be marked for removal
        assertTrue(toRemove.contains("users|id,name"), 
            "Smallest index should be marked for removal");
        assertTrue(toRemove.contains("users|id,name,email"), 
            "Middle index should be marked for removal");
        assertFalse(toRemove.contains("users|id,name,email,status"), 
            "Largest index should NOT be marked for removal");
        assertEquals(2, toRemove.size(), "Two indexes should be marked for removal");
    }

    // ==================== Tests for removeRedundantSingleColumnIndexes ====================

    @Test
    void testRemoveRedundantSingleColumnIndexes_CoveredByMultiColumn() {
        // Setup: Single-column index covered by multi-column index with same leading column
        LinkedHashSet<String> singleIndexes = checker.getSuggestedNewIndexes();
        LinkedHashSet<String> multiColumnIndexes = checker.getSuggestedMultiColumnIndexes();
        
        singleIndexes.add("users|email");                  // Should be removed
        multiColumnIndexes.add("users|email,name");        // Covers the above

        Set<String> toRemove = new HashSet<>();
        checker.removeRedundantSingleColumnIndexes(toRemove);

        // Verify: Single-column index should be marked for removal
        assertTrue(toRemove.contains("users|email"), 
            "Single-column index should be marked for removal when covered by multi-column");
        assertEquals(1, toRemove.size(), "Only one index should be marked for removal");
    }

    @Test
    void testRemoveRedundantSingleColumnIndexes_NotCoveredNonLeadingColumn() {
        // Setup: Single-column index NOT covered (not the leading column)
        LinkedHashSet<String> singleIndexes = checker.getSuggestedNewIndexes();
        LinkedHashSet<String> multiColumnIndexes = checker.getSuggestedMultiColumnIndexes();
        
        singleIndexes.add("users|name");                   // Should NOT be removed
        multiColumnIndexes.add("users|email,name");        // name is not leading column

        Set<String> toRemove = new HashSet<>();
        checker.removeRedundantSingleColumnIndexes(toRemove);

        // Verify: Single-column index should NOT be marked for removal
        assertFalse(toRemove.contains("users|name"), 
            "Single-column index should NOT be removed when it's not the leading column");
        assertTrue(toRemove.isEmpty(), "No indexes should be marked for removal");
    }

    @Test
    void testRemoveRedundantSingleColumnIndexes_MultipleSingleColumns() {
        // Setup: Multiple single-column indexes, some covered, some not
        LinkedHashSet<String> singleIndexes = checker.getSuggestedNewIndexes();
        LinkedHashSet<String> multiColumnIndexes = checker.getSuggestedMultiColumnIndexes();
        
        singleIndexes.add("users|email");                  // Should be removed
        singleIndexes.add("users|name");                   // Should NOT be removed
        singleIndexes.add("users|status");                 // Should be removed
        
        multiColumnIndexes.add("users|email,created_at");  // Covers email
        multiColumnIndexes.add("users|status,updated_at"); // Covers status

        Set<String> toRemove = new HashSet<>();
        checker.removeRedundantSingleColumnIndexes(toRemove);

        // Verify: Only covered indexes should be marked for removal
        assertTrue(toRemove.contains("users|email"), 
            "Email index should be marked for removal");
        assertTrue(toRemove.contains("users|status"), 
            "Status index should be marked for removal");
        assertFalse(toRemove.contains("users|name"), 
            "Name index should NOT be marked for removal");
        assertEquals(2, toRemove.size(), "Two indexes should be marked for removal");
    }

    @Test
    void testRemoveRedundantSingleColumnIndexes_DifferentTables() {
        // Setup: Single-column and multi-column on different tables
        LinkedHashSet<String> singleIndexes = checker.getSuggestedNewIndexes();
        LinkedHashSet<String> multiColumnIndexes = checker.getSuggestedMultiColumnIndexes();
        
        singleIndexes.add("users|email");
        multiColumnIndexes.add("orders|email,created_at"); // Different table

        Set<String> toRemove = new HashSet<>();
        checker.removeRedundantSingleColumnIndexes(toRemove);

        // Verify: No removal when tables differ
        assertTrue(toRemove.isEmpty(), 
            "No indexes should be removed when tables are different");
    }

    @Test
    void testRemoveRedundantSingleColumnIndexes_EmptyMultiColumnSet() {
        // Setup: Single-column indexes but no multi-column indexes
        LinkedHashSet<String> singleIndexes = checker.getSuggestedNewIndexes();
        singleIndexes.add("users|email");
        singleIndexes.add("users|name");

        Set<String> toRemove = new HashSet<>();
        checker.removeRedundantSingleColumnIndexes(toRemove);

        // Verify: No removal when there are no multi-column indexes
        assertTrue(toRemove.isEmpty(), 
            "No indexes should be removed when there are no multi-column indexes");
    }

    @Test
    void testRemoveRedundantSingleColumnIndexes_EmptySingleColumnSet() {
        // Setup: Multi-column indexes but no single-column indexes
        LinkedHashSet<String> multiColumnIndexes = checker.getSuggestedMultiColumnIndexes();
        multiColumnIndexes.add("users|email,name");

        Set<String> toRemove = new HashSet<>();
        checker.removeRedundantSingleColumnIndexes(toRemove);

        // Verify: Should handle empty single-column set without errors
        assertTrue(toRemove.isEmpty(), "toRemove should remain empty");
    }

    @Test
    void testRemoveRedundantSingleColumnIndexes_CaseInsensitive() {
        // Setup: Test case-insensitive comparison
        LinkedHashSet<String> singleIndexes = checker.getSuggestedNewIndexes();
        LinkedHashSet<String> multiColumnIndexes = checker.getSuggestedMultiColumnIndexes();
        
        singleIndexes.add("users|email");                  // Should be removed
        multiColumnIndexes.add("users|EMAIL,name");        // Covers email (case-insensitive)

        Set<String> toRemove = new HashSet<>();
        checker.removeRedundantSingleColumnIndexes(toRemove);

        // Verify: Case-insensitive matching should work
        assertTrue(toRemove.contains("users|email"), 
            "Single-column index should be removed with case-insensitive matching");
    }

    // ==================== Integration Tests ====================

    @Test
    void testBothMethodsTogether_ComplexScenario() {
        // Setup: Complex scenario with both types of redundancy
        LinkedHashSet<String> singleIndexes = checker.getSuggestedNewIndexes();
        LinkedHashSet<String> multiColumnIndexes = checker.getSuggestedMultiColumnIndexes();
        
        // Single-column indexes
        singleIndexes.add("users|email");                  // Covered by multi-column
        singleIndexes.add("users|status");                 // Not covered
        
        // Multi-column indexes
        multiColumnIndexes.add("users|email,name");        // Covers single email
        multiColumnIndexes.add("users|id,created_at");     // Standalone
        multiColumnIndexes.add("users|id,created_at,updated_at"); // Covers above

        Set<String> toRemove = new HashSet<>();
        
        // Test both methods
        checker.removeRedundantMultiColumnIndexes(toRemove);
        checker.removeRedundantSingleColumnIndexes(toRemove);

        // Verify: Correct indexes marked for removal
        assertTrue(toRemove.contains("users|email"), 
            "Single-column email should be removed");
        assertTrue(toRemove.contains("users|id,created_at"), 
            "Multi-column (id,created_at) should be removed");
        assertFalse(toRemove.contains("users|status"), 
            "Single-column status should NOT be removed");
        assertFalse(toRemove.contains("users|email,name"), 
            "Multi-column (email,name) should NOT be removed");
        assertFalse(toRemove.contains("users|id,created_at,updated_at"), 
            "Multi-column (id,created_at,updated_at) should NOT be removed");
        assertEquals(2, toRemove.size(), "Two indexes should be marked for removal");
    }

    @Test
    void testMalformedIndexKeys() {
        // Setup: Test with malformed keys (missing pipe separator)
        LinkedHashSet<String> multiColumnIndexes = checker.getSuggestedMultiColumnIndexes();
        multiColumnIndexes.add("users|id,name");
        multiColumnIndexes.add("malformed_key");           // No pipe separator
        multiColumnIndexes.add("|");                       // Only pipe
        multiColumnIndexes.add("table|");                  // No columns

        Set<String> toRemove = new HashSet<>();
        checker.removeRedundantMultiColumnIndexes(toRemove);

        // Verify: Should handle malformed keys gracefully without errors
        assertTrue(toRemove.isEmpty(), 
            "Malformed keys should be skipped without causing errors");
    }
}
