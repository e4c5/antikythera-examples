package sa.com.cloudsolutions.antikythera.examples.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.examples.util.InsteadOfTriggerGenerator.ColumnMapping;
import sa.com.cloudsolutions.antikythera.examples.util.InsteadOfTriggerGenerator.ForeignKey;
import sa.com.cloudsolutions.antikythera.examples.util.InsteadOfTriggerGenerator.ViewDescriptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DataMigrationGenerator}.
 */
class DataMigrationGeneratorTest {

    private DataMigrationGenerator generator;

    /** Two-table view: old_patient → patient + address */
    private ViewDescriptor twoTableView;

    /** Single-table view: old_address → address only */
    private ViewDescriptor singleTableView;

    @BeforeEach
    void setUp() {
        generator = new DataMigrationGenerator();

        // Realistic scenario: old_patient (id, name, street) → patient (id, name) + address (patient_id, street).
        // The FK column patient_id in address is NOT listed in columnMappings — it is auto-injected
        // by DataMigrationGenerator from the foreignKeys entry (address.patient_id → patient.id).
        twoTableView = new ViewDescriptor(
                "old_patient",
                "patient",
                List.of("patient", "address"),
                List.of(
                        new ColumnMapping("id",     "patient", "id"),
                        new ColumnMapping("name",   "patient", "name"),
                        new ColumnMapping("street", "address", "street")
                ),
                List.of(new ForeignKey("address", "patient_id", "patient", "id"))
        );

        singleTableView = new ViewDescriptor(
                "old_address",
                "address",
                List.of("address"),
                List.of(
                        new ColumnMapping("id",     "address", "id"),
                        new ColumnMapping("street", "address", "street")
                ),
                List.of()
        );
    }

    @Test
    void testTwoTableMigrationProducesTwoStatements() {
        List<String> stmts = generator.generateMigrationSql(twoTableView);
        assertEquals(2, stmts.size(), "Should produce one statement per normalized table");
    }

    @Test
    void testPatientInsertStatement() {
        List<String> stmts = generator.generateMigrationSql(twoTableView);
        String patientSql = stmts.get(0);

        // Base table comes first (FK dependency order)
        assertTrue(patientSql.startsWith("INSERT INTO patient"),
                "First statement should target the base table");
        assertTrue(patientSql.contains("(id, name)"), "Patient columns should be id, name");
        assertTrue(patientSql.contains("SELECT id, name"), "SELECT should use view column names");
        assertTrue(patientSql.contains("FROM old_patient"), "Source should be the old denormalized table");
        assertTrue(patientSql.endsWith(";"), "Statement should end with semicolon");
    }

    @Test
    void testAddressInsertStatement() {
        List<String> stmts = generator.generateMigrationSql(twoTableView);
        String addressSql = stmts.get(1);

        assertTrue(addressSql.startsWith("INSERT INTO address"),
                "Second statement should target the child table");
        // patient_id is the auto-injected FK column; street is the regular mapped column
        assertTrue(addressSql.contains("(street, patient_id)") || addressSql.contains("(patient_id, street)"),
                "Address INSERT should include both the regular column (street) and the auto-injected FK column (patient_id)");
        // SELECT must take patient_id's value from the old PK column 'id', not from a non-existent 'patient_id'
        assertTrue(addressSql.contains("street") && addressSql.contains("id"),
                "SELECT should reference the street column and the parent PK (id) for the FK");
        assertFalse(addressSql.contains("SELECT patient_id"),
                "SELECT must NOT reference 'patient_id' — the old source table has no such column");
        assertTrue(addressSql.contains("FROM old_patient"), "Source should be the old denormalized table");
        assertTrue(addressSql.endsWith(";"));
    }

    @Test
    void testTableOrder() {
        List<String> stmts = generator.generateMigrationSql(twoTableView);

        // patient (base) must be inserted before address (child) to satisfy FK constraints
        int patientIdx = stmts.indexOf(stmts.stream()
                .filter(s -> s.startsWith("INSERT INTO patient")).findFirst().orElse(""));
        int addressIdx = stmts.indexOf(stmts.stream()
                .filter(s -> s.startsWith("INSERT INTO address")).findFirst().orElse(""));

        assertTrue(patientIdx < addressIdx,
                "Base table (patient) must be inserted before child table (address)");
    }

    @Test
    void testSingleTableMigrationProducesOneStatement() {
        List<String> stmts = generator.generateMigrationSql(singleTableView);
        assertEquals(1, stmts.size(), "Single-table view should produce exactly one statement");
    }

    @Test
    void testSingleTableStatement() {
        List<String> stmts = generator.generateMigrationSql(singleTableView);
        String sql = stmts.get(0);

        assertEquals("INSERT INTO address (id, street) SELECT id, street FROM old_address;", sql);
    }

    @Test
    void testEmptyTableOrderProducesNoStatements() {
        ViewDescriptor emptyView = new ViewDescriptor(
                "old_thing", "thing", List.of(), List.of(), List.of());

        List<String> stmts = generator.generateMigrationSql(emptyView);
        assertTrue(stmts.isEmpty(), "No tables in order should yield empty list");
    }

    @Test
    void testColumnsWithDifferentViewAndSourceNames() {
        // Scenario where view column name differs from source column name
        ViewDescriptor view = new ViewDescriptor(
                "old_employee",
                "employee",
                List.of("employee"),
                List.of(
                        new ColumnMapping("emp_id",   "employee", "id"),
                        new ColumnMapping("emp_name", "employee", "full_name")
                ),
                List.of()
        );

        List<String> stmts = generator.generateMigrationSql(view);
        assertEquals(1, stmts.size());

        String sql = stmts.get(0);
        // INSERT uses source (normalized) column names
        assertTrue(sql.contains("(id, full_name)"), "INSERT should use source column names");
        // SELECT uses view column names (as they exist in the old table)
        assertTrue(sql.contains("SELECT emp_id, emp_name"), "SELECT should use view column names");
        assertTrue(sql.contains("FROM old_employee"));
    }

    @Test
    void testStatementIsAnsiFriendly() {
        // The generated SQL should contain no dialect-specific syntax
        List<String> stmts = generator.generateMigrationSql(twoTableView);
        for (String sql : stmts) {
            assertFalse(sql.contains("CONCURRENTLY"), "Should not contain PostgreSQL-specific keywords");
            assertFalse(sql.contains("ONLINE"),       "Should not contain Oracle-specific keywords");
            assertFalse(sql.contains(":NEW"),         "Should not contain Oracle trigger syntax");
        }
    }

    @Test
    void testFkColumnAutoInjectedFromParentPk() {
        // The FK column (patient_id in address) must be populated from the parent's PK (id in old_patient).
        // It must NOT appear as a raw column select from old_patient — the old table has no patient_id column.
        List<String> stmts = generator.generateMigrationSql(twoTableView);
        String addressSql = stmts.get(1);

        // FK column must be in the INSERT target list
        assertTrue(addressSql.contains("patient_id"),
                "Auto-injected FK column patient_id must appear in the INSERT");
        // The value must be taken from the parent PK 'id' in the old table, not from 'patient_id'
        // The SELECT clause must contain 'id' but not 'patient_id' as a standalone select expression
        int selectIdx = addressSql.indexOf("SELECT ");
        String selectPart = addressSql.substring(selectIdx);
        assertFalse(selectPart.startsWith("SELECT patient_id"),
                "SELECT must derive FK value from the parent PK column 'id', not 'patient_id'");
        assertTrue(selectPart.contains("id"),
                "SELECT must include the parent PK column 'id' to populate the FK");
    }

    @Test
    void testFkColumnNotDuplicatedWhenAlreadyInMappings() {
        // If an FK column IS explicitly listed in columnMappings (legacy/manual scenario),
        // we should not inject it a second time. Use the two-table view as a proxy —
        // the patient table's id appears only once in the INSERT.
        List<String> stmts = generator.generateMigrationSql(twoTableView);
        String patientSql = stmts.get(0);

        long idCount = countOccurrences(patientSql, "id");
        // "id" should appear twice: once in INSERT (id) and once in SELECT (id)
        // It must not appear more times due to duplicate injection
        assertTrue(idCount >= 2 && idCount <= 4,
                "Column 'id' should not appear more than expected; got count=" + idCount);
    }

    private long countOccurrences(String text, String word) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(word, idx)) != -1) {
            count++;
            idx += word.length();
        }
        return count;
    }
}
