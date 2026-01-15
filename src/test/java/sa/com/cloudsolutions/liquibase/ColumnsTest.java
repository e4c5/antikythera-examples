package sa.com.cloudsolutions.liquibase;

import org.junit.jupiter.api.Test;
import java.io.File;
import java.io.FileWriter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ColumnsTest {

    @Test
    void testLoadBasic() throws Exception {
        File file = new File("src/test/resources/liquibase-test.xml");
        Map<String, List<String>> result = Columns.load(file);

        assertTrue(result.containsKey("users"), "Should contain users table");
        List<String> userCols = result.get("users");
        assertEquals(3, userCols.size());
        assertEquals("id", userCols.get(0));
        assertEquals("email", userCols.get(1));
        assertEquals("status", userCols.get(2));

        assertTrue(result.containsKey("orders"), "Should contain orders table");
        List<String> orderCols = result.get("orders");
        assertEquals(4, orderCols.size());
        assertEquals("id", orderCols.get(0));
        assertEquals("customer_id", orderCols.get(1));
    }

    @Test
    void testComplexOperations() throws Exception {
        // Create a temporary file with more complex operations
        File temp = File.createTempFile("complex-test", ".xml");
        temp.deleteOnExit();

        try (FileWriter w = new FileWriter(temp)) {
            w.write("""
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-3.8.xsd">
    <changeSet id="1" author="me">
        <createTable tableName="t1">
            <column name="c1" type="int"/>
            <column name="c2" type="int"/>
        </createTable>
    </changeSet>
    <changeSet id="2" author="me">
        <addColumn tableName="t1">
            <column name="c3" type="int"/>
        </addColumn>
    </changeSet>
    <changeSet id="3" author="me">
        <dropColumn tableName="t1" columnName="c2"/>
    </changeSet>
    <changeSet id="4" author="me">
        <renameColumn tableName="t1" oldColumnName="c1" newColumnName="c1_renamed"/>
    </changeSet>
    <changeSet id="5" author="me">
        <sql>
            CREATE TABLE t2 (a int, b int);
            ALTER TABLE t2 ADD COLUMN c int;
            ALTER TABLE t2 DROP COLUMN b;
            DROP TABLE t2;
            CREATE TABLE t3 (x int);
        </sql>
    </changeSet>
</databaseChangeLog>
            """);
        }

        Map<String, List<String>> result = Columns.load(temp);

        // t1 should have c1_renamed, c3. (c2 dropped)
        assertTrue(result.containsKey("t1"), "t1 should exist");
        List<String> t1 = result.get("t1");
        assertEquals(2, t1.size());
        assertEquals("c1_renamed", t1.get(0));
        assertEquals("c3", t1.get(1));

        // t2 should be gone
        assertFalse(result.containsKey("t2"), "t2 should be dropped");

        // t3 should exist
        assertTrue(result.containsKey("t3"), "t3 should exist");
        assertEquals(1, result.get("t3").size());
        assertEquals("x", result.get("t3").get(0));
    }
}
