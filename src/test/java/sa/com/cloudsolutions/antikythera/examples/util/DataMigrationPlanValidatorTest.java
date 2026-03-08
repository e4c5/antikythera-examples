package sa.com.cloudsolutions.antikythera.examples.util;

import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.examples.SchemaNormalizationAnalyzer.DataMigrationPlan;
import sa.com.cloudsolutions.antikythera.examples.SchemaNormalizationAnalyzer.EntityProfile;
import sa.com.cloudsolutions.antikythera.examples.SchemaNormalizationAnalyzer.FieldProfile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DataMigrationPlanValidator}.
 */
class DataMigrationPlanValidatorTest {

    // -------------------------------------------------------------------------
    // Helper builders
    // -------------------------------------------------------------------------

    /** Builds a valid two-table plan: customer (base) + address (child). */
    private DataMigrationPlan validTwoTablePlan() {
        return new DataMigrationPlan(
                "old_customer",
                "customer",
                List.of("customer", "address"),
                List.of(
                        new DataMigrationPlan.ColumnMappingEntry("id",         "customer", "id"),
                        new DataMigrationPlan.ColumnMappingEntry("name",       "customer", "name"),
                        new DataMigrationPlan.ColumnMappingEntry("customer_id","address",  "customer_id"),
                        new DataMigrationPlan.ColumnMappingEntry("street",     "address",  "street")
                ),
                List.of(
                        new DataMigrationPlan.ForeignKeyEntry("address", "customer_id", "customer", "id")
                )
        );
    }

    /** Builds an EntityProfile matching the customer table. */
    private EntityProfile customerProfile() {
        return new EntityProfile(
                "Customer",
                "old_customer",
                List.of(
                        new FieldProfile("id",     "id",      true,  false, "Long"),
                        new FieldProfile("name",   "name",    false, true,  "String"),
                        new FieldProfile("street", "street",  false, true,  "String")
                ),
                List.of()
        );
    }

    // -------------------------------------------------------------------------
    // Valid plan
    // -------------------------------------------------------------------------

    @Test
    void testValidPlanReturnsValid() {
        assertDoesNotThrow(() -> DataMigrationPlanValidator.validate(validTwoTablePlan(), null),
                "A well-formed plan should not throw any exception");
    }

    @Test
    void testValidPlanWithProfilePassesColumnCoverage() {
        // The plan maps id, name, street; profile has id, name, street — all covered
        DataMigrationPlan plan = new DataMigrationPlan(
                "old_customer",
                "customer",
                List.of("customer", "address"),
                List.of(
                        new DataMigrationPlan.ColumnMappingEntry("id",     "customer", "id"),
                        new DataMigrationPlan.ColumnMappingEntry("name",   "customer", "name"),
                        new DataMigrationPlan.ColumnMappingEntry("street", "address",  "street")
                ),
                List.of(
                        new DataMigrationPlan.ForeignKeyEntry("address", "customer_id", "customer", "id")
                )
        );

        EntityProfile profile = new EntityProfile(
                "Customer",
                "old_customer",
                List.of(
                        new FieldProfile("id",     "id",     true,  false, "Long"),
                        new FieldProfile("name",   "name",   false, true,  "String"),
                        new FieldProfile("street", "street", false, true,  "String")
                ),
                List.of()
        );

        assertDoesNotThrow(() -> DataMigrationPlanValidator.validate(plan, profile),
                "All profile columns are mapped — no exception expected");
    }

    // -------------------------------------------------------------------------
    // Invalid base table
    // -------------------------------------------------------------------------

    @Test
    void testBaseTableNotInNewTablesReturnsInvalid() {
        DataMigrationPlan plan = new DataMigrationPlan(
                "old_customer",
                "nonexistent_base",   // not in newTables
                List.of("customer", "address"),
                List.of(
                        new DataMigrationPlan.ColumnMappingEntry("id",   "customer", "id"),
                        new DataMigrationPlan.ColumnMappingEntry("street","address", "street")
                ),
                List.of(
                        new DataMigrationPlan.ForeignKeyEntry("address", "customer_id", "customer", "id")
                )
        );

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> DataMigrationPlanValidator.validate(plan, null),
                "Plan with unknown baseTable should throw IllegalArgumentException");

        assertTrue(exception.getMessage().contains("nonexistent_base"),
                "Exception message should mention the unknown baseTable");
    }

    // -------------------------------------------------------------------------
    // Unknown table in column mapping
    // -------------------------------------------------------------------------

    @Test
    void testColumnMappingWithUnknownTableReturnsInvalid() {
        DataMigrationPlan plan = new DataMigrationPlan(
                "old_customer",
                "customer",
                List.of("customer"),   // address is missing from newTables
                List.of(
                        new DataMigrationPlan.ColumnMappingEntry("id",     "customer", "id"),
                        new DataMigrationPlan.ColumnMappingEntry("street", "address",  "street")  // address not in newTables
                ),
                List.of()
        );

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> DataMigrationPlanValidator.validate(plan, null),
                "Plan with unknown table in column mapping should throw IllegalArgumentException");

        assertTrue(exception.getMessage().contains("address"),
                "Exception message should mention the unknown table 'address'");
    }

    // -------------------------------------------------------------------------
    // Cycle detection
    // -------------------------------------------------------------------------

    @Test
    void testCyclicForeignKeysReturnsInvalid() {
        DataMigrationPlan plan = new DataMigrationPlan(
                "old_cycle",
                "a",
                List.of("a", "b", "c"),
                List.of(
                        new DataMigrationPlan.ColumnMappingEntry("col1", "a", "col1"),
                        new DataMigrationPlan.ColumnMappingEntry("col2", "b", "col2"),
                        new DataMigrationPlan.ColumnMappingEntry("col3", "c", "col3")
                ),
                List.of(
                        // a → b → c → a forms a cycle
                        new DataMigrationPlan.ForeignKeyEntry("a", "b_id", "b", "id"),
                        new DataMigrationPlan.ForeignKeyEntry("b", "c_id", "c", "id"),
                        new DataMigrationPlan.ForeignKeyEntry("c", "a_id", "a", "id")
                )
        );

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> DataMigrationPlanValidator.validate(plan, null),
                "Plan with cyclic FKs should throw IllegalArgumentException");

        assertTrue(exception.getMessage().toLowerCase().contains("circular") ||
                   exception.getMessage().toLowerCase().contains("cycle"),
                "Exception message should mention circular/cycle dependency");
    }

    // -------------------------------------------------------------------------
    // Column coverage warning
    // -------------------------------------------------------------------------

    @Test
    void testMissingProfileColumnGeneratesWarning() {
        DataMigrationPlan plan = new DataMigrationPlan(
                "old_customer",
                "customer",
                List.of("customer"),
                List.of(
                        new DataMigrationPlan.ColumnMappingEntry("id",   "customer", "id")
                        // "email" column not mapped
                ),
                List.of()
        );

        EntityProfile profile = new EntityProfile(
                "Customer",
                "old_customer",
                List.of(
                        new FieldProfile("id",    "id",    true,  false, "Long"),
                        new FieldProfile("email", "email", false, true,  "String")
                ),
                List.of()
        );

        // Missing column mapping generates a warning to stderr, but validation still passes
        assertDoesNotThrow(() -> DataMigrationPlanValidator.validate(plan, profile),
                "Missing column mapping is a warning, not an error");
        // Note: The warning "Column 'email' from source profile is not mapped" will be printed to stderr
    }

    @Test
    void testValidateWithNullProfileSkipsColumnCoverage() {
        // Null profile means no column-coverage check — validation passes without warnings
        assertDoesNotThrow(() -> DataMigrationPlanValidator.validate(validTwoTablePlan(), null),
                "Validation with null profile should not throw exception");
    }
}
