package sa.com.cloudsolutions.antikythera.examples.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.examples.util.InsteadOfTriggerGenerator.ColumnMapping;
import sa.com.cloudsolutions.antikythera.examples.util.InsteadOfTriggerGenerator.ForeignKey;
import sa.com.cloudsolutions.antikythera.examples.util.InsteadOfTriggerGenerator.ViewDescriptor;
import sa.com.cloudsolutions.antikythera.examples.util.LiquibaseGenerator.DatabaseDialect;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link InsteadOfTriggerGenerator}.
 *
 * <p>Uses a two-table example: {@code old_patient} view backed by {@code patient} (base table)
 * and {@code address} (child table). The view columns map as follows:
 * <pre>
 *   id         → patient.id       (base PK)
 *   name       → patient.name
 *   patient_id → address.patient_id  (child FK referencing patient.id)
 *   street     → address.street
 * </pre>
 */
class InsteadOfTriggerGeneratorTest {

    private InsteadOfTriggerGenerator generator;

    /** Two-table view: old_patient → patient + address */
    private ViewDescriptor twoTableView;

    /** Single-table view: old_address → address only */
    private ViewDescriptor singleTableView;

    @BeforeEach
    void setUp() {
        generator = new InsteadOfTriggerGenerator();

        twoTableView = new ViewDescriptor(
                "old_patient",
                "patient",
                List.of("patient", "address"),
                List.of(
                        new ColumnMapping("id", "patient", "id"),
                        new ColumnMapping("name", "patient", "name"),
                        new ColumnMapping("patient_id", "address", "patient_id"),
                        new ColumnMapping("street", "address", "street")
                ),
                List.of(new ForeignKey("address", "patient_id", "patient", "id"))
        );

        singleTableView = new ViewDescriptor(
                "old_address",
                "address",
                List.of("address"),
                List.of(
                        new ColumnMapping("id", "address", "id"),
                        new ColumnMapping("street", "address", "street")
                ),
                List.of()
        );
    }

    // -------------------------------------------------------------------------
    // INSERT — PostgreSQL
    // -------------------------------------------------------------------------

    @Test
    void testGenerateInsertPostgresqlFunction() {
        Map<DatabaseDialect, String> result = generator.generateInsert(twoTableView);

        String pg = result.get(DatabaseDialect.POSTGRESQL);
        assertNotNull(pg);
        assertTrue(pg.contains("CREATE OR REPLACE FUNCTION fn_old_patient_insert()"),
                "Should declare a PL/pgSQL function");
        assertTrue(pg.contains("RETURNS TRIGGER AS $$"));
        assertTrue(pg.contains("LANGUAGE plpgsql"));
    }

    @Test
    void testGenerateInsertPostgresqlTrigger() {
        Map<DatabaseDialect, String> result = generator.generateInsert(twoTableView);

        String pg = result.get(DatabaseDialect.POSTGRESQL);
        assertTrue(pg.contains("CREATE OR REPLACE TRIGGER trig_old_patient_insert"));
        assertTrue(pg.contains("INSTEAD OF INSERT ON old_patient"));
        assertTrue(pg.contains("EXECUTE FUNCTION fn_old_patient_insert()"));
    }

    @Test
    void testGenerateInsertPostgresqlNewPrefix() {
        Map<DatabaseDialect, String> result = generator.generateInsert(twoTableView);

        String pg = result.get(DatabaseDialect.POSTGRESQL);
        // PostgreSQL uses NEW. (not :NEW.)
        assertTrue(pg.contains("NEW.id") || pg.contains("NEW.name") || pg.contains("NEW.street"),
                "PostgreSQL trigger should use NEW. prefix");
        assertFalse(pg.contains(":NEW."), "PostgreSQL trigger should NOT use :NEW. prefix");
    }

    @Test
    void testGenerateInsertPostgresqlBothTables() {
        Map<DatabaseDialect, String> result = generator.generateInsert(twoTableView);

        String pg = result.get(DatabaseDialect.POSTGRESQL);
        assertTrue(pg.contains("INSERT INTO patient"), "Should insert into base table");
        assertTrue(pg.contains("INSERT INTO address"), "Should insert into child table");
    }

    @Test
    void testGenerateInsertPostgresqlColumnLists() {
        Map<DatabaseDialect, String> result = generator.generateInsert(twoTableView);

        String pg = result.get(DatabaseDialect.POSTGRESQL);
        assertTrue(pg.contains("INSERT INTO patient (id, name)"), "Patient columns should include id and name");
        assertTrue(pg.contains("INSERT INTO address (patient_id, street)"), "Address columns should include patient_id and street");
    }

    // -------------------------------------------------------------------------
    // INSERT — Oracle
    // -------------------------------------------------------------------------

    @Test
    void testGenerateInsertOracleTrigger() {
        Map<DatabaseDialect, String> result = generator.generateInsert(twoTableView);

        String oracle = result.get(DatabaseDialect.ORACLE);
        assertNotNull(oracle);
        assertTrue(oracle.contains("CREATE OR REPLACE TRIGGER trig_old_patient_insert"),
                "Oracle trigger should use TRIGGER keyword directly");
        assertTrue(oracle.contains("INSTEAD OF INSERT ON old_patient"));
        assertTrue(oracle.contains("FOR EACH ROW"));
    }

    @Test
    void testGenerateInsertOracleNewPrefix() {
        Map<DatabaseDialect, String> result = generator.generateInsert(twoTableView);

        String oracle = result.get(DatabaseDialect.ORACLE);
        // Oracle uses :NEW. prefix
        assertTrue(oracle.contains(":NEW."), "Oracle trigger should use :NEW. prefix");
        // Should NOT have bare NEW. (without colon) inside the trigger body
        // (the word NEW can appear in CREATE OR REPLACE TRIGGER ... but :NEW. is the bind variable)
    }

    @Test
    void testGenerateInsertOracleEndsWithSlash() {
        Map<DatabaseDialect, String> result = generator.generateInsert(twoTableView);

        String oracle = result.get(DatabaseDialect.ORACLE);
        assertTrue(oracle.endsWith("/"), "Oracle trigger DDL should end with /");
    }

    @Test
    void testGenerateInsertOracleNoFunction() {
        Map<DatabaseDialect, String> result = generator.generateInsert(twoTableView);

        String oracle = result.get(DatabaseDialect.ORACLE);
        assertFalse(oracle.contains("LANGUAGE plpgsql"), "Oracle trigger should not contain plpgsql");
        assertFalse(oracle.contains("CREATE OR REPLACE FUNCTION"), "Oracle uses inline trigger, not function");
    }

    // -------------------------------------------------------------------------
    // UPDATE
    // -------------------------------------------------------------------------

    @Test
    void testGenerateUpdatePostgresql() {
        Map<DatabaseDialect, String> result = generator.generateUpdate(twoTableView);

        String pg = result.get(DatabaseDialect.POSTGRESQL);
        assertNotNull(pg);
        assertTrue(pg.contains("INSTEAD OF UPDATE ON old_patient"));
        assertTrue(pg.contains("UPDATE patient SET"), "Should update base table");
        assertTrue(pg.contains("UPDATE address SET"), "Should update child table");
    }

    @Test
    void testGenerateUpdatePostgresqlColumnSets() {
        Map<DatabaseDialect, String> result = generator.generateUpdate(twoTableView);

        String pg = result.get(DatabaseDialect.POSTGRESQL);
        // patient: SET name=NEW.name WHERE id=NEW.id
        assertTrue(pg.contains("name = NEW.name"), "Patient update should set name");
        assertTrue(pg.contains("WHERE id = NEW.id"), "Patient update WHERE should use PK");
        // address: SET street=NEW.street WHERE patient_id=NEW.id
        assertTrue(pg.contains("street = NEW.street"), "Address update should set street");
        assertTrue(pg.contains("WHERE patient_id = NEW.id"), "Address update WHERE should use FK=base PK");
    }

    @Test
    void testGenerateUpdateOracle() {
        Map<DatabaseDialect, String> result = generator.generateUpdate(twoTableView);

        String oracle = result.get(DatabaseDialect.ORACLE);
        assertNotNull(oracle);
        assertTrue(oracle.contains("INSTEAD OF UPDATE ON old_patient"));
        assertTrue(oracle.contains(":NEW."), "Oracle update should use :NEW. prefix");
        assertTrue(oracle.endsWith("/"), "Oracle DDL should end with /");
    }

    @Test
    void testGenerateUpdateOracleColumnSets() {
        Map<DatabaseDialect, String> result = generator.generateUpdate(twoTableView);

        String oracle = result.get(DatabaseDialect.ORACLE);
        assertTrue(oracle.contains("name = :NEW.name"));
        assertTrue(oracle.contains("street = :NEW.street"));
    }

    // -------------------------------------------------------------------------
    // DELETE
    // -------------------------------------------------------------------------

    @Test
    void testGenerateDeletePostgresqlReverseOrder() {
        Map<DatabaseDialect, String> result = generator.generateDelete(twoTableView);

        String pg = result.get(DatabaseDialect.POSTGRESQL);
        assertNotNull(pg);
        // address (child) must be deleted BEFORE patient (base) to respect FK
        int addressIdx = pg.indexOf("DELETE FROM address");
        int patientIdx = pg.indexOf("DELETE FROM patient");
        assertTrue(addressIdx >= 0, "Should delete from address");
        assertTrue(patientIdx >= 0, "Should delete from patient");
        assertTrue(addressIdx < patientIdx,
                "address should be deleted before patient (FK constraint order)");
    }

    @Test
    void testGenerateDeletePostgresqlOldPrefix() {
        Map<DatabaseDialect, String> result = generator.generateDelete(twoTableView);

        String pg = result.get(DatabaseDialect.POSTGRESQL);
        assertTrue(pg.contains("OLD."), "PostgreSQL delete should use OLD. prefix");
        assertFalse(pg.contains(":OLD."), "PostgreSQL delete should NOT use :OLD. prefix");
    }

    @Test
    void testGenerateDeletePostgresqlTrigger() {
        Map<DatabaseDialect, String> result = generator.generateDelete(twoTableView);

        String pg = result.get(DatabaseDialect.POSTGRESQL);
        assertTrue(pg.contains("INSTEAD OF DELETE ON old_patient"));
        assertTrue(pg.contains("RETURN OLD"));
    }

    @Test
    void testGenerateDeleteOracleReverseOrder() {
        Map<DatabaseDialect, String> result = generator.generateDelete(twoTableView);

        String oracle = result.get(DatabaseDialect.ORACLE);
        assertNotNull(oracle);
        int addressIdx = oracle.indexOf("DELETE FROM address");
        int patientIdx = oracle.indexOf("DELETE FROM patient");
        assertTrue(addressIdx >= 0);
        assertTrue(patientIdx >= 0);
        assertTrue(addressIdx < patientIdx, "Oracle: address should be deleted before patient");
    }

    @Test
    void testGenerateDeleteOracleOldPrefix() {
        Map<DatabaseDialect, String> result = generator.generateDelete(twoTableView);

        String oracle = result.get(DatabaseDialect.ORACLE);
        assertTrue(oracle.contains(":OLD."), "Oracle delete should use :OLD. prefix");
        assertTrue(oracle.endsWith("/"), "Oracle DDL should end with /");
    }

    // -------------------------------------------------------------------------
    // Edge case: single-table view
    // -------------------------------------------------------------------------

    @Test
    void testSingleTableInsertPostgresql() {
        Map<DatabaseDialect, String> result = generator.generateInsert(singleTableView);

        String pg = result.get(DatabaseDialect.POSTGRESQL);
        assertNotNull(pg);
        assertTrue(pg.contains("INSERT INTO address"));
        assertFalse(pg.contains("INSERT INTO patient"), "Single-table view should not reference patient");
        assertTrue(pg.contains("INSTEAD OF INSERT ON old_address"));
    }

    @Test
    void testSingleTableInsertOracle() {
        Map<DatabaseDialect, String> result = generator.generateInsert(singleTableView);

        String oracle = result.get(DatabaseDialect.ORACLE);
        assertNotNull(oracle);
        assertTrue(oracle.contains("INSTEAD OF INSERT ON old_address"));
        assertTrue(oracle.contains("INSERT INTO address"));
        assertFalse(oracle.contains("INSERT INTO patient"), "Single-table view should not reference patient");
        assertTrue(oracle.endsWith("/"));
    }

    @Test
    void testSingleTableDeleteOrder() {
        Map<DatabaseDialect, String> result = generator.generateDelete(singleTableView);

        String pg = result.get(DatabaseDialect.POSTGRESQL);
        // Only one table — just verify the single DELETE is present
        assertTrue(pg.contains("DELETE FROM address"));
        assertFalse(pg.contains("DELETE FROM patient"), "Single-table view should only delete from address");
    }

    // -------------------------------------------------------------------------
    // Return value structure
    // -------------------------------------------------------------------------

    @Test
    void testGenerateInsertReturnsBothDialects() {
        Map<DatabaseDialect, String> result = generator.generateInsert(twoTableView);

        assertTrue(result.containsKey(DatabaseDialect.POSTGRESQL));
        assertTrue(result.containsKey(DatabaseDialect.ORACLE));
        assertNotNull(result.get(DatabaseDialect.POSTGRESQL));
        assertNotNull(result.get(DatabaseDialect.ORACLE));
    }

    @Test
    void testGenerateUpdateReturnsBothDialects() {
        Map<DatabaseDialect, String> result = generator.generateUpdate(twoTableView);

        assertTrue(result.containsKey(DatabaseDialect.POSTGRESQL));
        assertTrue(result.containsKey(DatabaseDialect.ORACLE));
    }

    @Test
    void testGenerateDeleteReturnsBothDialects() {
        Map<DatabaseDialect, String> result = generator.generateDelete(twoTableView);

        assertTrue(result.containsKey(DatabaseDialect.POSTGRESQL));
        assertTrue(result.containsKey(DatabaseDialect.ORACLE));
    }

    // -------------------------------------------------------------------------
    // Wrong-order input: topological sort must fix it
    // -------------------------------------------------------------------------

    @Test
    void testInsertOrderCorrectWhenTablesSuppliedInWrongOrder() {
        // Tables deliberately supplied in wrong order: address before patient
        ViewDescriptor wrongOrder = new ViewDescriptor(
                "old_patient",
                "patient",
                List.of("address", "patient"),   // wrong order — address (child) listed first
                List.of(
                        new ColumnMapping("id",         "patient",  "id"),
                        new ColumnMapping("name",       "patient",  "name"),
                        new ColumnMapping("patient_id", "address",  "patient_id"),
                        new ColumnMapping("street",     "address",  "street")
                ),
                List.of(new ForeignKey("address", "patient_id", "patient", "id"))
        );

        Map<DatabaseDialect, String> result = generator.generateInsert(wrongOrder);

        String pg = result.get(DatabaseDialect.POSTGRESQL);
        // patient (parent) must be inserted BEFORE address (child) regardless of input order
        int patientIdx = pg.indexOf("INSERT INTO patient");
        int addressIdx = pg.indexOf("INSERT INTO address");
        assertTrue(patientIdx >= 0, "Should contain INSERT INTO patient");
        assertTrue(addressIdx >= 0, "Should contain INSERT INTO address");
        assertTrue(patientIdx < addressIdx,
                "patient must be inserted before address even when tables list was in wrong order");
    }

    // -------------------------------------------------------------------------
    // DELETE reverse order assertion (Task 18)
    // -------------------------------------------------------------------------

    @Test
    void testDeleteReverseOrderThreeTables() {
        // Star schema: customer (base) with two direct children — address and phone
        // (both FK directly to customer). Topological order: customer, address, phone
        // DELETE must be in reverse: address and phone before customer.
        ViewDescriptor starView = new ViewDescriptor(
                "old_customer",
                "customer",
                List.of("customer", "address", "phone"),
                List.of(
                        new ColumnMapping("id",          "customer", "id"),
                        new ColumnMapping("name",        "customer", "name"),
                        new ColumnMapping("customer_id", "address",  "customer_id"),
                        new ColumnMapping("street",      "address",  "street"),
                        new ColumnMapping("cust_id",     "phone",    "cust_id"),
                        new ColumnMapping("number",      "phone",    "number")
                ),
                List.of(
                        new ForeignKey("address", "customer_id", "customer", "id"),
                        new ForeignKey("phone",   "cust_id",     "customer", "id")
                )
        );

        Map<DatabaseDialect, String> result = generator.generateDelete(starView);
        String pg = result.get(DatabaseDialect.POSTGRESQL);

        int phoneIdx    = pg.indexOf("DELETE FROM phone");
        int addressIdx  = pg.indexOf("DELETE FROM address");
        int customerIdx = pg.indexOf("DELETE FROM customer");

        assertTrue(phoneIdx    >= 0, "Should delete from phone");
        assertTrue(addressIdx  >= 0, "Should delete from address");
        assertTrue(customerIdx >= 0, "Should delete from customer");

        // Customer (root) must be deleted LAST (after both its children)
        assertTrue(phoneIdx    < customerIdx, "phone should be deleted before customer");
        assertTrue(addressIdx  < customerIdx, "address should be deleted before customer");
    }

    @Test
    void testUpdateWhereClauseUsesBaseTablePk() {
        // The UPDATE WHERE clause for the base table should use the PK source column
        Map<DatabaseDialect, String> result = generator.generateUpdate(twoTableView);
        String pg = result.get(DatabaseDialect.POSTGRESQL);

        // patient is the base table; PK is id; UPDATE should use WHERE id = NEW.id
        assertTrue(pg.contains("WHERE id = NEW.id"),
                "Update WHERE for base table should use base table PK");
    }

    @Test
    void testUpdateWhereClauseUsesChildFk() {
        // The UPDATE WHERE clause for a child table should use the FK column
        Map<DatabaseDialect, String> result = generator.generateUpdate(twoTableView);
        String pg = result.get(DatabaseDialect.POSTGRESQL);

        // address is the child table; FK is patient_id; UPDATE WHERE patient_id = NEW.id
        assertTrue(pg.contains("WHERE patient_id = NEW.id"),
                "Update WHERE for child table should use FK column = base PK");
    }

    // -------------------------------------------------------------------------
    // Rollback DDL (Phase 7) — Task 18
    // -------------------------------------------------------------------------

    @Test
    void testInsertRollbackPostgresqlDropsTriggerAndFunction() {
        Map<DatabaseDialect, String> rollback = generator.generateInsertRollback(twoTableView);

        String pg = rollback.get(DatabaseDialect.POSTGRESQL);
        assertNotNull(pg, "PostgreSQL rollback should not be null");
        assertTrue(pg.contains("DROP TRIGGER IF EXISTS trig_old_patient_insert"),
                "Rollback should drop the INSERT trigger");
        assertTrue(pg.contains("DROP FUNCTION IF EXISTS fn_old_patient_insert()"),
                "Rollback should drop the INSERT function (PostgreSQL-specific)");
    }

    @Test
    void testInsertRollbackOracleDropsTriggerOnly() {
        Map<DatabaseDialect, String> rollback = generator.generateInsertRollback(twoTableView);

        String oracle = rollback.get(DatabaseDialect.ORACLE);
        assertNotNull(oracle, "Oracle rollback should not be null");
        assertTrue(oracle.contains("DROP TRIGGER trig_old_patient_insert"),
                "Oracle rollback should drop the INSERT trigger");
        assertFalse(oracle.contains("DROP FUNCTION"),
                "Oracle rollback should NOT contain DROP FUNCTION");
    }

    @Test
    void testUpdateRollbackReturnsBothDialects() {
        Map<DatabaseDialect, String> rollback = generator.generateUpdateRollback(twoTableView);

        assertNotNull(rollback.get(DatabaseDialect.POSTGRESQL));
        assertNotNull(rollback.get(DatabaseDialect.ORACLE));
        assertTrue(rollback.get(DatabaseDialect.POSTGRESQL).contains("trig_old_patient_update"));
        assertTrue(rollback.get(DatabaseDialect.ORACLE).contains("trig_old_patient_update"));
    }

    @Test
    void testDeleteRollbackReturnsBothDialects() {
        Map<DatabaseDialect, String> rollback = generator.generateDeleteRollback(twoTableView);

        assertNotNull(rollback.get(DatabaseDialect.POSTGRESQL));
        assertNotNull(rollback.get(DatabaseDialect.ORACLE));
        assertTrue(rollback.get(DatabaseDialect.POSTGRESQL).contains("trig_old_patient_delete"));
        assertTrue(rollback.get(DatabaseDialect.ORACLE).contains("trig_old_patient_delete"));
    }
}
