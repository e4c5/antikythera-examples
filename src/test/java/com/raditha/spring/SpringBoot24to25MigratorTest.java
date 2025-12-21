package com.raditha.spring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for Spring Boot 2.4 to 2.5 migrator.
 * 
 * Tests the complete migration workflow on a sample project.
 */
class SpringBoot24to25MigratorTest {

    @TempDir
    Path tempDir;

    private Path projectDir;

    @BeforeEach
    void setUp() throws IOException {
        // Load Settings configuration
        Settings.loadConfigMap(new File("src/test/resources/generator.yml"));

        // Reset Antikythera runtime state
        AntikytheraRunTime.reset();
        AbstractCompiler.reset();

        // Create a test project structure
        projectDir = tempDir.resolve("test-project");
        Files.createDirectories(projectDir);
        Files.createDirectories(projectDir.resolve("src/main/java"));
        Files.createDirectories(projectDir.resolve("src/main/resources"));
        Files.createDirectories(projectDir.resolve("src/test/java"));

        // Set the base path for Settings
        Settings.setProperty(Settings.BASE_PATH, projectDir.toString());
    }

    @Test
    void testMigratorInitialization() {
        // Given
        SpringBoot24to25Migrator migrator = new SpringBoot24to25Migrator(true);

        // Then
        assertNotNull(migrator);
        assertEquals("2.4", migrator.getSourceVersion());
        assertEquals("2.5", migrator.getTargetVersion());
    }

    @Test
    void testDryRunMode() throws Exception {
        // Given: Create a minimal pom.xml
        createTestPom("2.4.5");
        createTestApplicationYml();

        SpringBoot24to25Migrator migrator = new SpringBoot24to25Migrator(true);

        // When: Run migration in dry-run mode
        MigrationResult result = migrator.migrateAll();

        // Then: Should succeed without modifying files
        assertTrue(result.isSuccessful(), "Migration should succeed in dry-run mode");

        // Verify POM not actually modified (still 2.4.5)
        String pomContent = Files.readString(projectDir.resolve("pom.xml"));
        assertTrue(pomContent.contains("2.4.5"), "POM should not be modified in dry-run mode");
    }

    @Test
    void testCompleteMigration() throws Exception {
        // Given: Create a test project with Spring Boot 2.4 configuration
        createTestPom("2.4.5");
        createTestApplicationYml();
        createDeprecatedJavaFile();

        SpringBoot24to25Migrator migrator = new SpringBoot24to25Migrator(false);

        // When: Run complete migration
        MigrationResult result = migrator.migrateAll();

        // Then: Migration should succeed
        assertTrue(result.isSuccessful(), "Migration should succeed");

        // Verify POM updated
        String pomContent = Files.readString(projectDir.resolve("pom.xml"));
        assertTrue(pomContent.contains("2.5"), "POM should be updated to 2.5");

        // Verify application.yml exists (SQL properties or actuator config may be
        // added)
        assertTrue(Files.exists(projectDir.resolve("src/main/resources/application.yml")),
                "Application.yml should exist after migration");
    }

    @Test
    void testMigrationWithErrors() throws Exception {
        // Given: Invalid project structure (no pom.xml)
        SpringBoot24to25Migrator migrator = new SpringBoot24to25Migrator(false);

        assertThrows(IOException.class, () -> migrator.migrateAll());
    }

    // Helper methods

    private void createTestPom(String springBootVersion) throws IOException {
        String pomContent = String.format("""
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
                        <version>%s</version>
                    </parent>

                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-web</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-actuator</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """, springBootVersion);

        Files.writeString(projectDir.resolve("pom.xml"), pomContent);
    }

    private void createTestApplicationYml() throws IOException {
        String ymlContent = """
                spring:
                  datasource:
                    initialization-mode: always
                    schema: classpath:schema.sql
                    data: classpath:data.sql

                management:
                  endpoints:
                    web:
                      exposure:
                        include: health,metrics
                """;

        Files.writeString(
                projectDir.resolve("src/main/resources/application.yml"),
                ymlContent);
    }

    private void createDeprecatedJavaFile() throws IOException {
        Path javaDir = projectDir.resolve("src/main/java/com/example");
        Files.createDirectories(javaDir);

        String javaContent = """
                package com.example;

                import org.springframework.boot.actuate.metrics.export.prometheus.PrometheusPushGatewayManager;

                public class TestClass {
                    // Uses deprecated import that should be fixed
                    private PrometheusPushGatewayManager manager;
                }
                """;

        Files.writeString(javaDir.resolve("TestClass.java"), javaContent);
    }
}
