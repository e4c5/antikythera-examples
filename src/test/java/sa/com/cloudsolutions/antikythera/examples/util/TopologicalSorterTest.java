package sa.com.cloudsolutions.antikythera.examples.util;

import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.examples.util.InsteadOfTriggerGenerator.ForeignKey;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TopologicalSorter}.
 */
class TopologicalSorterTest {

    // -------------------------------------------------------------------------
    // Linear chain: C depends on B, B depends on A → expected order [A, B, C]
    // -------------------------------------------------------------------------

    @Test
    void testLinearChain() {
        List<String> tables = List.of("A", "B", "C");
        List<ForeignKey> fks = List.of(
                new ForeignKey("B", "a_id", "A", "id"),   // B depends on A
                new ForeignKey("C", "b_id", "B", "id")    // C depends on B
        );

        List<String> order = TopologicalSorter.sort(tables, fks);

        assertEquals(3, order.size());
        assertTrue(order.indexOf("A") < order.indexOf("B"), "A must come before B");
        assertTrue(order.indexOf("B") < order.indexOf("C"), "B must come before C");
    }

    // -------------------------------------------------------------------------
    // Multi-parent: C depends on both A and B → A, B before C
    // -------------------------------------------------------------------------

    @Test
    void testMultiParent() {
        List<String> tables = List.of("A", "B", "C");
        List<ForeignKey> fks = List.of(
                new ForeignKey("C", "a_id", "A", "id"),   // C depends on A
                new ForeignKey("C", "b_id", "B", "id")    // C depends on B
        );

        List<String> order = TopologicalSorter.sort(tables, fks);

        assertEquals(3, order.size());
        assertTrue(order.indexOf("A") < order.indexOf("C"), "A must come before C");
        assertTrue(order.indexOf("B") < order.indexOf("C"), "B must come before C");
    }

    // -------------------------------------------------------------------------
    // Independent tables: no FKs — all tables present in result
    // -------------------------------------------------------------------------

    @Test
    void testIndependentTables() {
        List<String> tables = List.of("X", "Y", "Z");

        List<String> order = TopologicalSorter.sort(tables, List.of());

        assertEquals(3, order.size());
        assertTrue(order.containsAll(tables), "All tables must be present in result");
    }

    // -------------------------------------------------------------------------
    // Single table: no FKs — returned as-is
    // -------------------------------------------------------------------------

    @Test
    void testSingleTable() {
        List<String> order = TopologicalSorter.sort(List.of("only"), List.of());

        assertEquals(List.of("only"), order);
    }

    // -------------------------------------------------------------------------
    // Empty input: empty lists → empty result
    // -------------------------------------------------------------------------

    @Test
    void testEmptyInput() {
        List<String> order = TopologicalSorter.sort(List.of(), List.of());

        assertTrue(order.isEmpty());
    }

    // -------------------------------------------------------------------------
    // Cycle detection: A→B, B→A → IllegalArgumentException
    // -------------------------------------------------------------------------

    @Test
    void testCycleDetected() {
        List<String> tables = List.of("A", "B");
        List<ForeignKey> fks = List.of(
                new ForeignKey("A", "b_id", "B", "id"),   // A depends on B
                new ForeignKey("B", "a_id", "A", "id")    // B depends on A — cycle!
        );

        assertThrows(IllegalArgumentException.class,
                () -> TopologicalSorter.sort(tables, fks),
                "Circular FK dependency should throw IllegalArgumentException");
    }

    // -------------------------------------------------------------------------
    // Wrong input order corrected: tables given in reverse dependency order
    // -------------------------------------------------------------------------

    @Test
    void testWrongInputOrderCorrected() {
        // Supplied in wrong order: child before parent
        List<String> tables = List.of("address", "patient");
        List<ForeignKey> fks = List.of(
                new ForeignKey("address", "patient_id", "patient", "id")
        );

        List<String> order = TopologicalSorter.sort(tables, fks);

        assertEquals(2, order.size());
        assertTrue(order.indexOf("patient") < order.indexOf("address"),
                "patient (parent) must come before address (child) regardless of input order");
    }
}
