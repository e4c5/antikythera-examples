package com.raditha.spring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SpringCloudVersionMigrator.
 * Tests Spring Cloud version compatibility validation for Spring Boot 2.3.
 */
class SpringCloudVersionMigratorTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        Settings.loadConfigMap();
        Settings.setProperty("base_path", tempDir.toString());
    }

    @Test
    void testSpringCloudVersionDetection() throws Exception {
        // Given: A POM with Spring Cloud dependency
        String pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test-project</artifactId>
                    <version>1.0.0</version>
                    <properties>
                        <spring-cloud.version>Hoxton.SR12</spring-cloud.version>
                    </properties>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.springframework.cloud</groupId>
                                <artifactId>spring-cloud-dependencies</artifactId>
                                <version>${spring-cloud.version}</version>
                                <type>pom</type>
                                <scope>import</scope>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """;

        Path pomPath = tempDir.resolve("pom.xml");
        Files.writeString(pomPath, pomContent);

        // When: Running Spring Cloud migrator
        SpringCloudVersionMigrator migrator = new SpringCloudVersionMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should detect Spring Cloud
        assertNotNull(result, "Result should not be null");
        boolean cloudDetected = result.getChanges().stream()
                .anyMatch(change -> change.toLowerCase().contains("spring cloud")) ||
                result.getWarnings().stream()
                        .anyMatch(warning -> warning.toLowerCase().contains("spring cloud"));

        assertTrue(cloudDetected || !result.getChanges().isEmpty(),
                "Should detect or process Spring Cloud. Changes: " + result.getChanges());
    }

    @Test
    void testNoSpringCloudUsage() throws Exception {
        // Given: A POM without Spring Cloud
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
                            <artifactId>spring-boot-starter-web</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """;

        Path pomPath = tempDir.resolve("pom.xml");
        Files.writeString(pomPath, pomContent);

        // When: Running Spring Cloud migrator
        SpringCloudVersionMigrator migrator = new SpringCloudVersionMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should handle gracefully
        assertNotNull(result, "Result should not be null");
    }

    @Test
    void testGetPhaseName() {
        SpringCloudVersionMigrator migrator = new SpringCloudVersionMigrator(false);
        assertEquals("Spring Cloud Version Migration", migrator.getPhaseName());
    }

    @Test
    void testGetPriority() {
        SpringCloudVersionMigrator migrator = new SpringCloudVersionMigrator(false);
        assertEquals(25, migrator.getPriority());
    }
}
