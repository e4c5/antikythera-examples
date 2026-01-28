package sa.com.cloudsolutions.liquibase;

import liquibase.exception.LiquibaseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test cases for the Indexes class, specifically testing the path resolution
 * fix
 * for Spring Boot project structures with includes.
 */
class IndexesTest {

    @Test
    void testLoadWithIncludeDirectives(@TempDir Path tempDir) throws IOException, LiquibaseException {
        // Create a simulated Spring Boot project structure
        Path resourcesDir = tempDir.resolve("src/test/resources");
        Path changelogDir = resourcesDir.resolve("db/changelog");
        changelogDir.toFile().mkdirs();

        // Create included file 1
        File includedFile1 = changelogDir.resolve("tables.xml").toFile();
        try (FileWriter fw = new FileWriter(includedFile1)) {
            fw.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            fw.write("<databaseChangeLog\n");
            fw.write("    xmlns=\"http://www.liquibase.org/xml/ns/dbchangelog\"\n");
            fw.write("    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
            fw.write("    xsi:schemaLocation=\"http://www.liquibase.org/xml/ns/dbchangelog\n");
            fw.write("    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-3.8.xsd\">\n");
            fw.write("  <changeSet id=\"1\" author=\"test\">\n");
            fw.write("    <createTable tableName=\"users\">\n");
            fw.write("      <column name=\"id\" type=\"bigint\" autoIncrement=\"true\">\n");
            fw.write("        <constraints primaryKey=\"true\" nullable=\"false\"/>\n");
            fw.write("      </column>\n");
            fw.write("      <column name=\"email\" type=\"varchar(100)\">\n");
            fw.write("        <constraints nullable=\"false\" unique=\"true\"/>\n");
            fw.write("      </column>\n");
            fw.write("    </createTable>\n");
            fw.write("  </changeSet>\n");
            fw.write("</databaseChangeLog>\n");
        }

        // Create included file 2
        File includedFile2 = changelogDir.resolve("indexes.xml").toFile();
        try (FileWriter fw = new FileWriter(includedFile2)) {
            fw.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            fw.write("<databaseChangeLog\n");
            fw.write("    xmlns=\"http://www.liquibase.org/xml/ns/dbchangelog\"\n");
            fw.write("    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
            fw.write("    xsi:schemaLocation=\"http://www.liquibase.org/xml/ns/dbchangelog\n");
            fw.write("    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-3.8.xsd\">\n");
            fw.write("  <changeSet id=\"2\" author=\"test\">\n");
            fw.write("    <createIndex tableName=\"users\" indexName=\"idx_users_email\">\n");
            fw.write("      <column name=\"email\"/>\n");
            fw.write("    </createIndex>\n");
            fw.write("  </changeSet>\n");
            fw.write("</databaseChangeLog>\n");
        }

        // Create master changelog with includes
        File masterChangelog = changelogDir.resolve("changelog-master.xml").toFile();
        try (FileWriter fw = new FileWriter(masterChangelog)) {
            fw.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            fw.write("<databaseChangeLog\n");
            fw.write("    logicalFilePath=\"db/changelog/changelog-master.xml\"\n");
            fw.write("    xmlns=\"http://www.liquibase.org/xml/ns/dbchangelog\"\n");
            fw.write("    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
            fw.write("    xsi:schemaLocation=\"http://www.liquibase.org/xml/ns/dbchangelog\n");
            fw.write("    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-3.8.xsd\">\n");
            fw.write("  <include file=\"db/changelog/tables.xml\"/>\n");
            fw.write("  <include file=\"db/changelog/indexes.xml\"/>\n");
            fw.write("</databaseChangeLog>\n");
        }

        // Test that the includes are resolved correctly
        Map<String, Set<Indexes.IndexInfo>> indexMap = Indexes.load(masterChangelog);

        // Verify the results
        assertNotNull(indexMap, "Index map should not be null");
        assertTrue(indexMap.containsKey("users"), "Index map should contain 'users' table");

        Set<Indexes.IndexInfo> userIndexes = indexMap.get("users");
        assertEquals(3, userIndexes.size(),
                "Users table should have 3 indexes (PK + unique constraint + explicit index)");

        // Verify primary key
        boolean hasPrimaryKey = userIndexes.stream()
                .anyMatch(idx -> idx.type().equals(Indexes.PRIMARY_KEY) && idx.columns().contains("id"));
        assertTrue(hasPrimaryKey, "Should have primary key on id column");

        // Verify unique constraint (from createTable)
        boolean hasUniqueConstraint = userIndexes.stream()
                .anyMatch(idx -> idx.type().equals(Indexes.UNIQUE_CONSTRAINT) && idx.columns().contains("email"));
        assertTrue(hasUniqueConstraint, "Should have unique constraint on email column");

        // Note: The explicit index created in the second file might be merged or
        // considered redundant with the unique constraint, depending on Liquibase logic
    }

    @Test
    void testLoadWithoutSpringBootStructure(@TempDir Path tempDir) throws IOException, LiquibaseException {
        // Test fallback behavior when not in Spring Boot structure
        File simpleChangelog = tempDir.resolve("changelog.xml").toFile();
        try (FileWriter fw = new FileWriter(simpleChangelog)) {
            fw.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            fw.write("<databaseChangeLog\n");
            fw.write("    xmlns=\"http://www.liquibase.org/xml/ns/dbchangelog\"\n");
            fw.write("    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
            fw.write("    xsi:schemaLocation=\"http://www.liquibase.org/xml/ns/dbchangelog\n");
            fw.write("    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-3.8.xsd\">\n");
            fw.write("  <changeSet id=\"1\" author=\"test\">\n");
            fw.write("    <createTable tableName=\"products\">\n");
            fw.write("      <column name=\"id\" type=\"bigint\">\n");
            fw.write("        <constraints primaryKey=\"true\"/>\n");
            fw.write("      </column>\n");
            fw.write("    </createTable>\n");
            fw.write("  </changeSet>\n");
            fw.write("</databaseChangeLog>\n");
        }

        Map<String, Set<Indexes.IndexInfo>> indexMap = Indexes.load(simpleChangelog);

        assertNotNull(indexMap);
        assertTrue(indexMap.containsKey("products"));
        assertEquals(1, indexMap.get("products").size());
    }

    @Test
    void testLoadFromActualTestResources() throws IOException, LiquibaseException {
        // Test with the actual test resources we created
        File changelogWithIncludes = new File("src/test/resources/db/changelog/changelog-with-includes.xml");

        // Only run this test if the file exists (it might not in CI environments)
        if (changelogWithIncludes.exists()) {
            Map<String, Set<Indexes.IndexInfo>> indexMap = Indexes.load(changelogWithIncludes);

            assertNotNull(indexMap, "Index map should not be null");

            // Verify users table from included-tables.xml
            assertTrue(indexMap.containsKey("users"), "Should contain users table");
            Set<Indexes.IndexInfo> userIndexes = indexMap.get("users");
            assertTrue(userIndexes.size() >= 2, "Users should have at least PK and unique constraint");

            // Verify posts table from included-posts.xml
            assertTrue(indexMap.containsKey("posts"), "Should contain posts table");
            Set<Indexes.IndexInfo> postIndexes = indexMap.get("posts");
            assertTrue(postIndexes.size() >= 1, "Posts should have at least PK");
        }
    }

    @Test
    void testRawSqlCreateIndex(@TempDir Path tempDir) throws IOException, LiquibaseException {
        File changelog = tempDir.resolve("changelog.xml").toFile();
        try (FileWriter fw = new FileWriter(changelog)) {
            fw.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            fw.write("<databaseChangeLog\n");
            fw.write("    xmlns=\"http://www.liquibase.org/xml/ns/dbchangelog\"\n");
            fw.write("    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
            fw.write("    xsi:schemaLocation=\"http://www.liquibase.org/xml/ns/dbchangelog\n");
            fw.write("    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-3.8.xsd\">\n");
            fw.write("  <changeSet id=\"1\" author=\"test\">\n");
            fw.write("    <createTable tableName=\"orders\">\n");
            fw.write("      <column name=\"id\" type=\"bigint\"><constraints primaryKey=\"true\"/></column>\n");
            fw.write("      <column name=\"customer_id\" type=\"bigint\"/>\n");
            fw.write("      <column name=\"order_date\" type=\"date\"/>\n");
            fw.write("    </createTable>\n");
            fw.write("  </changeSet>\n");
            fw.write("  <changeSet id=\"2\" author=\"test\">\n");
            fw.write("    <sql>CREATE INDEX idx_orders_customer ON orders (customer_id);</sql>\n");
            fw.write("  </changeSet>\n");
            fw.write("  <changeSet id=\"3\" author=\"test\">\n");
            fw.write("    <sql>CREATE UNIQUE INDEX idx_orders_date ON orders (order_date, customer_id);</sql>\n");
            fw.write("  </changeSet>\n");
            fw.write("</databaseChangeLog>\n");
        }

        Map<String, Set<Indexes.IndexInfo>> indexMap = Indexes.load(changelog);

        assertNotNull(indexMap);
        assertTrue(indexMap.containsKey("orders"), "Should contain orders table");

        Set<Indexes.IndexInfo> orderIndexes = indexMap.get("orders");
        assertEquals(3, orderIndexes.size(), "Orders should have PK + 2 indexes from raw SQL");

        // Verify regular index from raw SQL
        boolean hasCustomerIndex = orderIndexes.stream()
                .anyMatch(idx -> idx.type().equals(Indexes.INDEX)
                        && "idx_orders_customer".equals(idx.name())
                        && idx.columns().equals(java.util.List.of("customer_id")));
        assertTrue(hasCustomerIndex, "Should have index on customer_id from raw SQL");

        // Verify unique index from raw SQL
        boolean hasDateIndex = orderIndexes.stream()
                .anyMatch(idx -> idx.type().equals(Indexes.UNIQUE_INDEX)
                        && "idx_orders_date".equals(idx.name())
                        && idx.columns().equals(java.util.List.of("order_date", "customer_id")));
        assertTrue(hasDateIndex, "Should have unique index on order_date, customer_id from raw SQL");
    }

    @Test
    void testRawSqlDropIndex(@TempDir Path tempDir) throws IOException, LiquibaseException {
        File changelog = tempDir.resolve("changelog.xml").toFile();
        try (FileWriter fw = new FileWriter(changelog)) {
            fw.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            fw.write("<databaseChangeLog\n");
            fw.write("    xmlns=\"http://www.liquibase.org/xml/ns/dbchangelog\"\n");
            fw.write("    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
            fw.write("    xsi:schemaLocation=\"http://www.liquibase.org/xml/ns/dbchangelog\n");
            fw.write("    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-3.8.xsd\">\n");
            fw.write("  <changeSet id=\"1\" author=\"test\">\n");
            fw.write("    <createTable tableName=\"items\">\n");
            fw.write("      <column name=\"id\" type=\"bigint\"><constraints primaryKey=\"true\"/></column>\n");
            fw.write("      <column name=\"sku\" type=\"varchar(50)\"/>\n");
            fw.write("    </createTable>\n");
            fw.write("  </changeSet>\n");
            fw.write("  <changeSet id=\"2\" author=\"test\">\n");
            fw.write("    <sql>CREATE INDEX idx_items_sku ON items (sku);</sql>\n");
            fw.write("  </changeSet>\n");
            fw.write("  <changeSet id=\"3\" author=\"test\">\n");
            fw.write("    <sql>DROP INDEX idx_items_sku;</sql>\n");
            fw.write("  </changeSet>\n");
            fw.write("</databaseChangeLog>\n");
        }

        Map<String, Set<Indexes.IndexInfo>> indexMap = Indexes.load(changelog);

        assertNotNull(indexMap);
        assertTrue(indexMap.containsKey("items"), "Should contain items table");

        Set<Indexes.IndexInfo> itemIndexes = indexMap.get("items");
        assertEquals(1, itemIndexes.size(), "Items should only have PK after DROP INDEX");

        // Verify the dropped index is not present
        boolean hasSkuIndex = itemIndexes.stream()
                .anyMatch(idx -> "idx_items_sku".equals(idx.name()));
        assertFalse(hasSkuIndex, "Index idx_items_sku should have been dropped");
    }

    @Test
    void testRawSqlMultipleStatements(@TempDir Path tempDir) throws IOException, LiquibaseException {
        File changelog = tempDir.resolve("changelog.xml").toFile();
        try (FileWriter fw = new FileWriter(changelog)) {
            fw.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            fw.write("<databaseChangeLog\n");
            fw.write("    xmlns=\"http://www.liquibase.org/xml/ns/dbchangelog\"\n");
            fw.write("    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
            fw.write("    xsi:schemaLocation=\"http://www.liquibase.org/xml/ns/dbchangelog\n");
            fw.write("    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-3.8.xsd\">\n");
            fw.write("  <changeSet id=\"1\" author=\"test\">\n");
            fw.write("    <createTable tableName=\"events\">\n");
            fw.write("      <column name=\"id\" type=\"bigint\"><constraints primaryKey=\"true\"/></column>\n");
            fw.write("      <column name=\"event_type\" type=\"varchar(50)\"/>\n");
            fw.write("      <column name=\"event_date\" type=\"timestamp\"/>\n");
            fw.write("    </createTable>\n");
            fw.write("  </changeSet>\n");
            fw.write("  <changeSet id=\"2\" author=\"test\">\n");
            fw.write("    <sql>\n");
            fw.write("      CREATE INDEX idx_events_type ON events (event_type);\n");
            fw.write("      CREATE INDEX idx_events_date ON events (event_date);\n");
            fw.write("    </sql>\n");
            fw.write("  </changeSet>\n");
            fw.write("</databaseChangeLog>\n");
        }

        Map<String, Set<Indexes.IndexInfo>> indexMap = Indexes.load(changelog);

        assertNotNull(indexMap);
        Set<Indexes.IndexInfo> eventIndexes = indexMap.get("events");
        assertEquals(3, eventIndexes.size(), "Events should have PK + 2 indexes from single SQL block");

        boolean hasTypeIndex = eventIndexes.stream()
                .anyMatch(idx -> "idx_events_type".equals(idx.name()));
        boolean hasDateIndex = eventIndexes.stream()
                .anyMatch(idx -> "idx_events_date".equals(idx.name()));

        assertTrue(hasTypeIndex, "Should have idx_events_type");
        assertTrue(hasDateIndex, "Should have idx_events_date");
    }
}
