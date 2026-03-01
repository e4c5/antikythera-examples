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

        twoTableView = new ViewDescriptor(
                "old_patient",
                "patient",
                List.of("patient", "address"),
                List.of(
                        new ColumnMapping("id",         "patient", "id"),
                        new ColumnMapping("name",       "patient", "name"),
                        new ColumnMapping("patient_id", "address", "patient_id"),
                        new ColumnMapping("street",     "address", "street")
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
        assertTrue(addressSql.contains("(patient_id, street)"), "Address columns should be patient_id, street");
        assertTrue(addressSql.contains("SELECT patient_id, street"), "SELECT should use view column names");
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

    // -------------------------------------------------------------------------
    // Three-level dependency chain (Task 17)
    // -------------------------------------------------------------------------

    /**
     * Three-level chain: customer (root) → address (child) → phone (grandchild).
     * Expected INSERT order: customer, address, phone.
     */
    @Test
    void testThreeLevelDependencyOrderIsCustomerThenAddressThenPhone() {
        ViewDescriptor threeTableView = new ViewDescriptor(
                "old_customer",
                "customer",
                List.of("customer", "address", "phone"),
                List.of(
                        new ColumnMapping("id",          "customer", "id"),
                        new ColumnMapping("name",        "customer", "name"),
                        new ColumnMapping("customer_id", "address",  "customer_id"),
                        new ColumnMapping("street",      "address",  "street"),
                        new ColumnMapping("address_id",  "phone",    "address_id"),
                        new ColumnMapping("number",      "phone",    "number")
                ),
                List.of(
                        new ForeignKey("address", "customer_id", "customer", "id"),
                        new ForeignKey("phone",   "address_id",  "address",  "id")
                )
        );

        List<String> stmts = generator.generateMigrationSql(threeTableView);
        assertEquals(3, stmts.size(), "Should produce one INSERT per table");

        int customerIdx = -1, addressIdx = -1, phoneIdx = -1;
        for (int i = 0; i < stmts.size(); i++) {
            if (stmts.get(i).startsWith("INSERT INTO customer")) customerIdx = i;
            else if (stmts.get(i).startsWith("INSERT INTO address")) addressIdx = i;
            else if (stmts.get(i).startsWith("INSERT INTO phone"))   phoneIdx = i;
        }

        assertTrue(customerIdx >= 0, "Should have INSERT INTO customer");
        assertTrue(addressIdx  >= 0, "Should have INSERT INTO address");
        assertTrue(phoneIdx    >= 0, "Should have INSERT INTO phone");

        assertTrue(customerIdx < addressIdx,
                "customer (root) must be inserted before address (child)");
        assertTrue(addressIdx < phoneIdx,
                "address (child) must be inserted before phone (grandchild)");
    }

    @Test
    void testThreeLevelDependencyProducesCorrectSql() {
        ViewDescriptor threeTableView = new ViewDescriptor(
                "old_customer",
                "customer",
                List.of("customer", "address", "phone"),
                List.of(
                        new ColumnMapping("id",          "customer", "id"),
                        new ColumnMapping("name",        "customer", "name"),
                        new ColumnMapping("customer_id", "address",  "customer_id"),
                        new ColumnMapping("street",      "address",  "street"),
                        new ColumnMapping("address_id",  "phone",    "address_id"),
                        new ColumnMapping("number",      "phone",    "number")
                ),
                List.of(
                        new ForeignKey("address", "customer_id", "customer", "id"),
                        new ForeignKey("phone",   "address_id",  "address",  "id")
                )
        );

        List<String> stmts = generator.generateMigrationSql(threeTableView);

        // Verify each statement's FROM clause uses the original denormalized table
        for (String sql : stmts) {
            assertTrue(sql.contains("FROM old_customer"), "All INSERT-SELECT must read from old_customer");
        }

        // Verify phone statement references the correct columns
        String phoneSql = stmts.stream().filter(s -> s.startsWith("INSERT INTO phone")).findFirst().orElse("");
        assertTrue(phoneSql.contains("(address_id, number)"), "Phone should insert address_id and number");
        assertTrue(phoneSql.contains("SELECT address_id, number"), "Phone select should use view column names");
    }
}
