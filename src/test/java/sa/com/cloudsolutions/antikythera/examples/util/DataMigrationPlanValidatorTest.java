package sa.com.cloudsolutions.antikythera.examples.util;

import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.examples.SchemaNormalizationAnalyzer.DataMigrationPlan;
import sa.com.cloudsolutions.antikythera.examples.SchemaNormalizationAnalyzer.EntityProfile;
import sa.com.cloudsolutions.antikythera.examples.SchemaNormalizationAnalyzer.FieldProfile;
import sa.com.cloudsolutions.antikythera.examples.SchemaNormalizationAnalyzer.RelationshipProfile;
import sa.com.cloudsolutions.antikythera.examples.util.DataMigrationPlanValidator.ValidationResult;

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
        ValidationResult result = DataMigrationPlanValidator.validate(validTwoTablePlan(), null);

        assertTrue(result.valid(), "A well-formed plan should be valid");
        assertTrue(result.errors().isEmpty(), "No errors expected for a valid plan");
    }

    @Test
    void testValidPlanWithProfilePassesColumnCoverage() {
        // The plan maps id, name, customer_id, street; profile has id, name, street — all covered
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

        ValidationResult result = DataMigrationPlanValidator.validate(plan, profile);
        assertTrue(result.valid());
        assertTrue(result.warnings().isEmpty(), "All profile columns are mapped — no warnings expected");
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

        ValidationResult result = DataMigrationPlanValidator.validate(plan, null);

        assertFalse(result.valid(), "Plan with unknown baseTable should be invalid");
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("nonexistent_base")),
                "Error should mention the unknown baseTable");
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

        ValidationResult result = DataMigrationPlanValidator.validate(plan, null);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("address")),
                "Error should mention the unknown table 'address'");
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

        ValidationResult result = DataMigrationPlanValidator.validate(plan, null);

        assertFalse(result.valid(), "Plan with cyclic FKs should be invalid");
        assertTrue(result.errors().stream().anyMatch(e -> e.toLowerCase().contains("circular")),
                "Error should mention circular/cycle dependency");
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

        ValidationResult result = DataMigrationPlanValidator.validate(plan, profile);

        assertTrue(result.valid(), "Missing column mapping is a warning, not an error");
        assertFalse(result.warnings().isEmpty(), "Should have at least one warning");
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("email")),
                "Warning should mention the unmapped column 'email'");
    }

    @Test
    void testValidateWithNullProfileSkipsColumnCoverage() {
        ValidationResult result = DataMigrationPlanValidator.validate(validTwoTablePlan(), null);
        // Null profile means no column-coverage check — no warning expected from that check
        assertTrue(result.valid());
    }
}
