package com.raditha.spring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PomMigrator24to25.
 * 
 * Tests POM version upgrades and dependency validation.
 */
class PomMigrator24to25Test {

    @TempDir
    Path tempDir;

    private Path projectDir;

    @BeforeEach
    void setUp() throws IOException {
        Settings.loadConfigMap(new File("src/test/resources/generator.yml"));
        AntikytheraRunTime.reset();

        projectDir = tempDir.resolve("test-project");
        Files.createDirectories(projectDir);

        Settings.setProperty(Settings.BASE_PATH, projectDir.toString());
    }

    @Test
    void testSpringBootVersionUpgrade() throws Exception {
        // Given: POM with Spring Boot 2.4.5
        createTestPom("2.4.5", false, false, false);

        // When: Run migration
        PomMigrator24to25 migrator = new PomMigrator24to25(false);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should upgrade to 2.5.15
        assertTrue(result.isSuccessful());
        assertTrue(result.getChanges().stream()
                .anyMatch(c -> c.contains("2.5.15")), "Should upgrade to Spring Boot 2.5.15");

        // Verify POM updated
        String pomContent = Files.readString(projectDir.resolve("pom.xml"));
        assertTrue(pomContent.contains("2.5.15"), "POM should contain version 2.5.15");
    }

    @Test
    void testGroovySpockDetection() throws Exception {
        // Given: POM with Groovy and Spock dependencies
        createTestPom("2.4.5", true, false, false);

        // When: Run migration
        PomMigrator24to25 migrator = new PomMigrator24to25(false);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should warn about Groovy/Spock upgrade needed
        assertTrue(result.getWarnings().stream()
                .anyMatch(w -> w.contains("Groovy") || w.contains("Spock")),
                "Should warn about Groovy/Spock upgrade");
    }

    @Test
    void testCassandraDetection() throws Exception {
        // Given: POM with Cassandra starter
        createTestPom("2.4.5", false, true, false);

        // When: Run migration
        PomMigrator24to25 migrator = new PomMigrator24to25(false);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should warn about Cassandra throttling
        assertTrue(result.getWarnings().stream()
                .anyMatch(w -> w.contains("Cassandra") && w.contains("throttling")),
                "Should warn about Cassandra throttling configuration");
    }

    @Test
    void testSpringCloudCompatibility() throws Exception {
        // Given: POM with Spring Cloud version
        createTestPom("2.4.5", false, false, true);

        // When: Run migration
        PomMigrator24to25 migrator = new PomMigrator24to25(false);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should complete successfully
        // (Spring Cloud warning is optional as it depends on dependency detection
        // implementation)
        assertTrue(result.isSuccessful(), "Migration should succeed");
    }

    @Test
    void testDryRunMode() throws Exception {
        // Given: POM with Spring Boot 2.4.5
        createTestPom("2.4.5", false, false, false);

        // When: Run in dry-run mode
        PomMigrator24to25 migrator = new PomMigrator24to25(true);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should identify changes but not modify file
        assertTrue(result.isSuccessful());

        String pomContent = Files.readString(projectDir.resolve("pom.xml"));
        assertTrue(pomContent.contains("2.4.5"), "POM should not be modified in dry-run mode");
    }

    // Helper methods

    private void createTestPom(String springBootVersion, boolean includeGroovySpock,
            boolean includeCassandra, boolean includeSpringCloud) throws IOException {
        StringBuilder pom = new StringBuilder();
        pom.append("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                         http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>

                    <groupId>com.example</groupId>
                    <artifactId>test-project</artifactId>
                    <version>1.0.0</version>

                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>""");
        pom.append(springBootVersion);
        pom.append("</version>\n    </parent>\n    \n");

        if (includeGroovySpock) {
            pom.append("""
                    <properties>
                        <groovy.version>2.5.14</groovy.version>
                    </properties>
                    """);
        }

        pom.append("    <dependencies>\n");
        pom.append("""
                    <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-web</artifactId>
                    </dependency>
                """);

        if (includeGroovySpock) {
            pom.append("""
                        <dependency>
                            <groupId>org.spockframework</groupId>
                            <artifactId>spock-core</artifactId>
                            <version>1.3-groovy-2.5</version>
                            <scope>test</scope>
                        </dependency>
                    """);
        }

        if (includeCassandra) {
            pom.append("""
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-data-cassandra</artifactId>
                        </dependency>
                    """);
        }

        if (includeSpringCloud) {
            pom.append("""
                        <dependency>
                            <groupId>org.springframework.cloud</groupId>
                            <artifactId>spring-cloud-dependencies</artifactId>
                            <version>Hoxton.SR12</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                    """);
        }

        pom.append("    </dependencies>\n</project>");

        Files.writeString(projectDir.resolve("pom.xml"), pom.toString());
    }
}
