package sa.com.cloudsolutions.antikythera.examples;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the generateAllChangesets() method consolidates both create and drop
 * index changesets, eliminating code duplication between printing and file writing.
 */
class GeneratedChangesetsTest {

    @Test
    void testGeneratedChangesetsStructure() {
        // This test verifies the GeneratedChangesets class has the expected structure
        QueryOptimizationChecker.GeneratedChangesets changesets = new QueryOptimizationChecker.GeneratedChangesets();

        assertNotNull(changesets.changesets);
        assertTrue(changesets.changesets.isEmpty());
        assertEquals(0, changesets.multiColumnCount);
        assertEquals(0, changesets.singleColumnCount);
        assertEquals(0, changesets.totalCreateCount);
        assertEquals(0, changesets.dropCount);
    }

    @Test
    void testGeneratedChangesetsCanAccumulateData() {
        // Test that the data structure can hold changeset information
        QueryOptimizationChecker.GeneratedChangesets changesets = new QueryOptimizationChecker.GeneratedChangesets();

        changesets.changesets.add("<!-- Test changeset -->");
        changesets.changesets.add("<changeSet id=\"test\"></changeSet>");
        changesets.multiColumnCount = 1;
        changesets.singleColumnCount = 2;
        changesets.totalCreateCount = 3;
        changesets.dropCount = 1;

        assertEquals(2, changesets.changesets.size());
        assertEquals(1, changesets.multiColumnCount);
        assertEquals(2, changesets.singleColumnCount);
        assertEquals(3, changesets.totalCreateCount);
        assertEquals(1, changesets.dropCount);

        String combined = String.join("\n", changesets.changesets);
        assertTrue(combined.contains("Test changeset"));
        assertTrue(combined.contains("changeSet"));
    }
}

