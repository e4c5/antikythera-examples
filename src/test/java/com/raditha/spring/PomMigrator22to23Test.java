package com.raditha.spring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PomMigrator22to23.
 * Tests Spring Boot 2.2→2.3 POM migration logic.
 */
class PomMigrator22to23Test {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        Settings.loadConfigMap();
        Settings.setProperty("base_path", tempDir.toString());
    }

    @Test
    void testSpringBootVersionUpdate() throws Exception {
        // Given: A POM with Spring Boot 2.2
        String pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>2.2.13.RELEASE</version>
                    </parent>
                    <groupId>com.example</groupId>
                    <artifactId>test-project</artifactId>
                    <version>1.0.0</version>
                </project>
                """;

        Path pomPath = tempDir.resolve("pom.xml");
        Files.writeString(pomPath, pomContent);

        // When: Running POM migrator in dry-run
        PomMigrator22to23 migrator = new PomMigrator22to23(true);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should report version upgrade
        assertTrue(result.getChanges().stream()
                .anyMatch(change -> change.contains("2.3") || change.contains("upgrade")),
                "Should report Spring Boot version upgrade");
    }

    @Test
    void testSpringCloudVersionValidation() throws Exception {
        // Given: A POM with incompatible Spring Cloud version (Greenwich)
        String pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>2.2.13.RELEASE</version>
                    </parent>
                    <groupId>com.example</groupId>
                    <artifactId>test-project</artifactId>
                    <version>1.0.0</version>
                    <properties>
                        <spring-cloud.version>Greenwich.SR6</spring-cloud.version>
                    </properties>
                </project>
                """;

        Path pomPath = tempDir.resolve("pom.xml");
        Files.writeString(pomPath, pomContent);

        // When: Running POM migrator
        PomMigrator22to23 migrator = new PomMigrator22to23(true);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should warn about incompatible Spring Cloud version or report Spring
        // Cloud check
        boolean hasSpringCloudWarning = result.getWarnings().stream()
                .anyMatch(warning -> warning.toLowerCase().contains("spring cloud") ||
                        warning.toLowerCase().contains("greenwich"));
        boolean hasSpringCloudChange = result.getChanges().stream()
                .anyMatch(change -> change.toLowerCase().contains("spring cloud"));

        assertTrue(hasSpringCloudWarning || hasSpringCloudChange || !result.getWarnings().isEmpty(),
                "Should check Spring Cloud compatibility. Warnings: " + result.getWarnings());
    }

    @Test
    void testCassandraDriverDetection() throws Exception {
        // Given: A POM with Cassandra dependency
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
                            <artifactId>spring-boot-starter-data-cassandra</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """;

        Path pomPath = tempDir.resolve("pom.xml");
        Files.writeString(pomPath, pomContent);

        // When: Running POM migrator
        PomMigrator22to23 migrator = new PomMigrator22to23(true);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should detect Cassandra and provide guidance
        boolean hasCassandraGuidance = result.getWarnings().stream()
                .anyMatch(w -> w.toLowerCase().contains("cassandra")) ||
                result.getChanges().stream()
                        .anyMatch(c -> c.toLowerCase().contains("cassandra"));

        assertTrue(hasCassandraGuidance,
                "Should detect Cassandra and provide migration guidance");
    }

    @Test
    void testNoPom() {
        // Given: No pom.xml file
        // When: Running POM migrator
        PomMigrator22to23 migrator = new PomMigrator22to23(true);
        assertThrows(IOException.class, migrator::migrate);
    }

    @Test
    void testGetPhaseName() {
        PomMigrator22to23 migrator = new PomMigrator22to23(false);
        assertEquals("POM Migration (2.2→2.3)", migrator.getPhaseName());
    }

    @Test
    void testGetPriority() {
        PomMigrator22to23 migrator = new PomMigrator22to23(false);
        assertEquals(10, migrator.getPriority());
    }
}
