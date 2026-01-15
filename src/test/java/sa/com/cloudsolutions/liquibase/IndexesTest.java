package sa.com.cloudsolutions.liquibase;

import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.liquibase.Indexes.IndexInfo;

import java.io.File;
import java.io.FileWriter;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class IndexesTest {

    @Test
    void testLoadBasic() throws Exception {
        // Based on the liquibase-test.xml content I read earlier
        File file = new File("src/test/resources/liquibase-test.xml");
        Map<String, Set<IndexInfo>> result = Indexes.load(file);

        // users table has a primary key on id and unique on email
        assertTrue(result.containsKey("users"));
        Set<IndexInfo> userIndexes = result.get("users");

        boolean foundPk = false;
        boolean foundUnique = false;

        for (IndexInfo i : userIndexes) {
            if (Indexes.PRIMARY_KEY.equals(i.type()) && i.columns().contains("id")) {
                foundPk = true;
            }
            if (Indexes.UNIQUE_CONSTRAINT.equals(i.type()) && i.columns().contains("email")) {
                foundUnique = true;
            }
        }
        assertTrue(foundPk, "Should have PK on id");
        assertTrue(foundUnique, "Should have UNIQUE on email");
    }

    @Test
    void testComplexIndexes() throws Exception {
        File temp = File.createTempFile("index-test", ".xml");
        temp.deleteOnExit();
        try (FileWriter w = new FileWriter(temp)) {
            w.write("""
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-3.8.xsd">
    <changeSet id="1" author="test">
        <createTable tableName="t1">
            <column name="c1" type="int">
                <constraints primaryKey="true" primaryKeyName="pk_t1"/>
            </column>
            <column name="c2" type="int"/>
        </createTable>
        <createIndex indexName="idx_t1_c2" tableName="t1">
            <column name="c2"/>
        </createIndex>
    </changeSet>
    <changeSet id="2" author="test">
        <addUniqueConstraint tableName="t1" columnNames="c2" constraintName="uq_t1_c2"/>
    </changeSet>
    <changeSet id="3" author="test">
        <sql>
            CREATE INDEX idx_t1_sql ON t1(c1, c2);
            DROP INDEX idx_t1_c2;
        </sql>
    </changeSet>
</databaseChangeLog>
            """);
        }

        Map<String, Set<IndexInfo>> result = Indexes.load(temp);
        assertTrue(result.containsKey("t1"));
        Set<IndexInfo> infos = result.get("t1");

        // Check PK
        assertTrue(infos.stream().anyMatch(i -> Indexes.PRIMARY_KEY.equals(i.type()) && i.name().equals("pk_t1")));

        // Check Unique Constraint
        assertTrue(infos.stream().anyMatch(i -> Indexes.UNIQUE_CONSTRAINT.equals(i.type()) && i.name().equals("uq_t1_c2")));

        // Check SQL created index
        assertTrue(infos.stream().anyMatch(i -> Indexes.INDEX.equals(i.type()) && i.name().equals("idx_t1_sql") && i.columns().contains("c1") && i.columns().contains("c2")));

        // Check Dropped index (should NOT be present)
        assertFalse(infos.stream().anyMatch(i -> i.name().equals("idx_t1_c2")), "Dropped index should not be present");
    }
}
