package sa.com.cloudsolutions.antikythera.examples.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.examples.SchemaNormalizationAnalyzer.EntityProfile;
import sa.com.cloudsolutions.antikythera.examples.SchemaNormalizationAnalyzer.FieldProfile;
import sa.com.cloudsolutions.antikythera.examples.util.InsteadOfTriggerGenerator.ColumnMapping;
import sa.com.cloudsolutions.antikythera.examples.util.InsteadOfTriggerGenerator.ForeignKey;
import sa.com.cloudsolutions.antikythera.examples.util.InsteadOfTriggerGenerator.ViewDescriptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link NormalizedTableDDLGenerator}.
 *
 * <p>Uses a two-table example: {@code old_customer} → {@code customer} (base) + {@code address}
 * (child with FK to customer).
 */
class NormalizedTableDDLGeneratorTest {

    private NormalizedTableDDLGenerator generator;
    private ViewDescriptor twoTableView;
    private EntityProfile customerProfile;

    @BeforeEach
    void setUp() {
        generator = new NormalizedTableDDLGenerator();

        twoTableView = new ViewDescriptor(
                "old_customer",
                "customer",
                List.of("customer", "address"),
                List.of(
                        new ColumnMapping("id",          "customer", "id"),
                        new ColumnMapping("name",        "customer", "name"),
                        new ColumnMapping("customer_id", "address",  "customer_id"),
                        new ColumnMapping("street",      "address",  "street")
                ),
                List.of(new ForeignKey("address", "customer_id", "customer", "id"))
        );

        customerProfile = new EntityProfile(
                "Customer",
                "old_customer",
                List.of(
                        new FieldProfile("id",     "id",     true,  false, "Long"),
                        new FieldProfile("name",   "name",   false, true,  "String"),
                        new FieldProfile("street", "street", false, true,  "String")
                ),
                List.of()
        );
    }

    // -------------------------------------------------------------------------
    // Liquibase mode
    // -------------------------------------------------------------------------

    @Test
    void testLiquibaseModeProducesTwoChangesets() {
        List<String> result = generator.generate(twoTableView, customerProfile, NormalizedTableDDLGenerator.MODE_LIQUIBASE);
        assertEquals(2, result.size(), "Should produce one changeset per table");
    }

    @Test
    void testLiquibaseModeCustomerFirst() {
        List<String> result = generator.generate(twoTableView, customerProfile, NormalizedTableDDLGenerator.MODE_LIQUIBASE);
        // Topological order: customer (parent) before address (child)
        assertTrue(result.get(0).contains("tableName=\"customer\""), "First changeset should create customer table");
        assertTrue(result.get(1).contains("tableName=\"address\""), "Second changeset should create address table");
    }

    @Test
    void testLiquibaseModeCustomerColumns() {
        List<String> result = generator.generate(twoTableView, customerProfile, NormalizedTableDDLGenerator.MODE_LIQUIBASE);
        String customerCs = result.get(0);

        assertTrue(customerCs.contains("<createTable"), "Should use Liquibase createTable tag");
        assertTrue(customerCs.contains("name=\"id\""), "Should include id column");
        assertTrue(customerCs.contains("name=\"name\""), "Should include name column");
    }

    @Test
    void testLiquibaseModeIdColumnHasPrimaryKeyConstraint() {
        List<String> result = generator.generate(twoTableView, customerProfile, NormalizedTableDDLGenerator.MODE_LIQUIBASE);
        String customerCs = result.get(0);

        assertTrue(customerCs.contains("primaryKey=\"true\""), "ID column should have primaryKey constraint");
        assertTrue(customerCs.contains("nullable=\"false\""), "ID column should be not null");
    }

    @Test
    void testLiquibaseModeTypeMappingLong() {
        List<String> result = generator.generate(twoTableView, customerProfile, NormalizedTableDDLGenerator.MODE_LIQUIBASE);
        // id is Long → bigint
        assertTrue(result.get(0).contains("type=\"bigint\""), "Long should map to bigint in Liquibase mode");
    }

    @Test
    void testLiquibaseModeTypeMappingString() {
        List<String> result = generator.generate(twoTableView, customerProfile, NormalizedTableDDLGenerator.MODE_LIQUIBASE);
        // name is String → varchar(255)
        assertTrue(result.get(0).contains("type=\"varchar(255)\""), "String should map to varchar(255)");
    }

    @Test
    void testLiquibaseModeChangesetHasRollback() {
        List<String> result = generator.generate(twoTableView, customerProfile, NormalizedTableDDLGenerator.MODE_LIQUIBASE);
        for (String cs : result) {
            assertTrue(cs.contains("<rollback>"), "Each changeset should have a rollback");
            assertTrue(cs.contains("<dropTable"), "Rollback should drop the table");
        }
    }

    // -------------------------------------------------------------------------
    // Raw SQL mode
    // -------------------------------------------------------------------------

    @Test
    void testRawSqlModeProducesTwoStatements() {
        List<String> result = generator.generate(twoTableView, customerProfile, NormalizedTableDDLGenerator.MODE_RAW_SQL);
        assertEquals(2, result.size());
    }

    @Test
    void testRawSqlModeCustomerFirst() {
        List<String> result = generator.generate(twoTableView, customerProfile, NormalizedTableDDLGenerator.MODE_RAW_SQL);
        assertTrue(result.get(0).startsWith("CREATE TABLE customer"), "First should be CREATE TABLE customer");
        assertTrue(result.get(1).startsWith("CREATE TABLE address"), "Second should be CREATE TABLE address");
    }

    @Test
    void testRawSqlModeCustomerHasPrimaryKey() {
        List<String> result = generator.generate(twoTableView, customerProfile, NormalizedTableDDLGenerator.MODE_RAW_SQL);
        String customerDdl = result.get(0);

        assertTrue(customerDdl.contains("PRIMARY KEY"), "Should declare a primary key");
        assertTrue(customerDdl.contains("NOT NULL"), "PK column should be NOT NULL");
    }

    @Test
    void testRawSqlModeLongToBigint() {
        List<String> result = generator.generate(twoTableView, customerProfile, NormalizedTableDDLGenerator.MODE_RAW_SQL);
        assertTrue(result.get(0).contains("BIGINT"), "Long should map to BIGINT in raw SQL mode");
    }

    @Test
    void testRawSqlModeStringToVarchar() {
        List<String> result = generator.generate(twoTableView, customerProfile, NormalizedTableDDLGenerator.MODE_RAW_SQL);
        assertTrue(result.get(0).contains("VARCHAR(255)"), "String should map to VARCHAR(255)");
    }

    // -------------------------------------------------------------------------
    // Topological order correctness
    // -------------------------------------------------------------------------

    @Test
    void testWrongInputOrderIsFixed() {
        // Deliberately supply tables in wrong order (child before parent)
        ViewDescriptor wrongOrder = new ViewDescriptor(
                "old_customer",
                "customer",
                List.of("address", "customer"),   // wrong order
                twoTableView.columnMappings(),
                twoTableView.foreignKeys()
        );

        List<String> result = generator.generate(wrongOrder, customerProfile, NormalizedTableDDLGenerator.MODE_LIQUIBASE);
        assertEquals(2, result.size());
        assertTrue(result.get(0).contains("tableName=\"customer\""),
                "Topological sort should fix order: customer before address");
    }

    // -------------------------------------------------------------------------
    // Null profile (no type info)
    // -------------------------------------------------------------------------

    @Test
    void testNullProfileDefaultsToVarchar() {
        List<String> result = generator.generate(twoTableView, null, NormalizedTableDDLGenerator.MODE_LIQUIBASE);
        assertEquals(2, result.size());
        // All columns should default to varchar(255) when no profile type info is available
        for (String cs : result) {
            assertTrue(cs.contains("varchar(255)"), "Columns with unknown type should default to varchar(255)");
        }
    }

    // -------------------------------------------------------------------------
    // Type mapping helpers
    // -------------------------------------------------------------------------

    @Test
    void testToSqlTypeKnownTypes() {
        assertEquals("BIGINT",        NormalizedTableDDLGenerator.toSqlType("Long"));
        assertEquals("INTEGER",       NormalizedTableDDLGenerator.toSqlType("Integer"));
        assertEquals("VARCHAR(255)",  NormalizedTableDDLGenerator.toSqlType("String"));
        assertEquals("NUMERIC(19,2)", NormalizedTableDDLGenerator.toSqlType("BigDecimal"));
        assertEquals("BOOLEAN",       NormalizedTableDDLGenerator.toSqlType("Boolean"));
        assertEquals("TIMESTAMP",     NormalizedTableDDLGenerator.toSqlType("LocalDateTime"));
    }

    @Test
    void testToSqlTypeUnknownDefaultsToVarchar() {
        assertEquals("VARCHAR(255)", NormalizedTableDDLGenerator.toSqlType("UnknownType"));
        assertEquals("VARCHAR(255)", NormalizedTableDDLGenerator.toSqlType(null));
    }

    @Test
    void testToLiquibaseTypeKnownTypes() {
        assertEquals("bigint",       NormalizedTableDDLGenerator.toLiquibaseType("Long"));
        assertEquals("int",          NormalizedTableDDLGenerator.toLiquibaseType("Integer"));
        assertEquals("varchar(255)", NormalizedTableDDLGenerator.toLiquibaseType("String"));
        assertEquals("decimal(19,2)",NormalizedTableDDLGenerator.toLiquibaseType("BigDecimal"));
        assertEquals("boolean",      NormalizedTableDDLGenerator.toLiquibaseType("Boolean"));
        assertEquals("timestamp",    NormalizedTableDDLGenerator.toLiquibaseType("LocalDateTime"));
    }
}
