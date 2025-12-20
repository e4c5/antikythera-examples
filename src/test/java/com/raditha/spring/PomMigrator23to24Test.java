package com.raditha.spring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PomMigrator23to24.
 * Tests Spring Boot 2.3→2.4 POM migration logic.
 */
class PomMigrator23to24Test {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        Settings.loadConfigMap();
        Settings.setProperty("base_path", tempDir.toString());
    }

    @Test
    void testSpringBootVersionUpdate() throws Exception {
        // Given: A POM with Spring Boot 2.3
        String pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>2.3.12.RELEASE</version>
                    </parent>
                    <groupId>com.example</groupId>
                    <artifactId>test-project</artifactId>
                    <version>1.0.0</version>
                </project>
                """;

        Path pomPath = tempDir.resolve("pom.xml");
        Files.writeString(pomPath, pomContent);

        // When: Running POM migrator in dry-run
        PomMigrator23to24 migrator = new PomMigrator23to24(true);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should report version upgrade
        assertTrue(result.getChanges().stream()
                .anyMatch(change -> change.contains("2.4") || change.contains("upgrade")),
                "Should report Spring Boot version upgrade");
    }

    @Test
    void testJUnitVintageEngineDetection() throws Exception {
        // Given: A POM with spring-boot-starter-test but no vintage engine
        String pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test-project</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-test</artifactId>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                </project>
                """;

        Path pomPath = tempDir.resolve("pom.xml");
        Files.writeString(pomPath, pomContent);

        // When: Running POM migrator
        PomMigrator23to24 migrator = new PomMigrator23to24(true);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should warn about JUnit Vintage Engine
        assertTrue(result.getWarnings().stream()
                .anyMatch(w -> w.contains("JUNIT") || w.contains("Vintage")),
                "Should warn about JUnit Vintage Engine");
    }

    @Test
    void testNeo4jDriverDetection() throws Exception {
        // Given: A POM with Neo4j dependency
        String pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test-project</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-data-neo4j</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """;

        Path pomPath = tempDir.resolve("pom.xml");
        Files.writeString(pomPath, pomContent);

        // When: Running POM migrator
        PomMigrator23to24 migrator = new PomMigrator23to24(true);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should detect Neo4j and provide guidance
        assertTrue(result.getWarnings().stream()
                .anyMatch(w -> w.contains("NEO4J") || w.contains("Neo4j")),
                "Should detect Neo4j and provide migration guidance");
    }

    @Test
    void testHazelcastDetection() throws Exception {
        // Given: A POM with Hazelcast dependency
        String pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test-project</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.hazelcast</groupId>
                            <artifactId>hazelcast</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """;

        Path pomPath = tempDir.resolve("pom.xml");
        Files.writeString(pomPath, pomContent);

        // When: Running POM migrator
        PomMigrator23to24 migrator = new PomMigrator23to24(true);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should detect Hazelcast 4.x upgrade
        assertTrue(result.getWarnings().stream()
                .anyMatch(w -> w.contains("HAZELCAST") || w.contains("Hazelcast")),
                "Should detect Hazelcast and warn about 4.x upgrade");
    }

    @Test
    void testSpringCloudCompatibility() throws Exception {
        // Given: A POM with incompatible Spring Cloud version
        String pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test-project</artifactId>
                    <version>1.0.0</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.springframework.cloud</groupId>
                                <artifactId>spring-cloud-dependencies</artifactId>
                                <version>Hoxton.SR12</version>
                                <type>pom</type>
                                <scope>import</scope>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """;

        Path pomPath = tempDir.resolve("pom.xml");
        Files.writeString(pomPath, pomContent);

        // When: Running POM migrator
        PomMigrator23to24 migrator = new PomMigrator23to24(true);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should report Spring Cloud incompatibility or check
        boolean hasCloudCheck = result.getErrors().stream()
                .anyMatch(e -> e.contains("Spring Cloud") || e.contains("2020.0")) ||
                result.getWarnings().stream()
                        .anyMatch(w -> w.contains("Spring Cloud"));

        assertTrue(hasCloudCheck, "Should check Spring Cloud compatibility");
    }

    @Test
    void testNoPom() throws Exception {
        // Given: No pom.xml file
        // When: Running POM migrator
        PomMigrator23to24 migrator = new PomMigrator23to24(true);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should handle gracefully
        assertNotNull(result, "Should return result even without POM");
    }

    @Test
    void testGetPhaseName() {
        PomMigrator23to24 migrator = new PomMigrator23to24(false);
        assertEquals("POM Migration (2.3→2.4)", migrator.getPhaseName());
    }

    @Test
    void testGetPriority() {
        PomMigrator23to24 migrator = new PomMigrator23to24(false);
        assertEquals(10, migrator.getPriority());
    }
}
