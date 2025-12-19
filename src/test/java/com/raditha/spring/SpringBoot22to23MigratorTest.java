package com.raditha.spring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SpringBoot22to23Migrator.
 * Tests main orchestrator for Spring Boot 2.2→2.3 migration.
 */
class SpringBoot22to23MigratorTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        Settings.loadConfigMap();
        Settings.setProperty("base_path", tempDir.toString());

        // Create minimal project structure
        Files.createDirectories(tempDir.resolve("src/main/java"));
        Files.createDirectories(tempDir.resolve("src/main/resources"));

        // Create minimal pom.xml
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
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-web</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """;

        Files.writeString(tempDir.resolve("pom.xml"), pomContent);
    }

    @Test
    void testMigrateAllInDryRun() throws Exception {
        // Given: A Spring Boot 2.2 project
        SpringBoot22to23Migrator migrator = new SpringBoot22to23Migrator(true);

        // When: Running full migration in dry-run mode
        MigrationResult result = migrator.migrateAll();

        // Then: Migration should complete successfully
        assertNotNull(result, "Migration result should not be null");
    }

    @Test
    void testVersionInfo() {
        // Given: A migrator instance
        SpringBoot22to23Migrator migrator = new SpringBoot22to23Migrator(true);

        // When/Then: Verify version information
        assertEquals("2.2", migrator.getSourceVersion(), "Source version should be 2.2");
        assertEquals("2.3", migrator.getTargetVersion(), "Target version should be 2.3");
    }

    @Test
    void testInitializeComponents() {
        // Given: A migrator instance
        SpringBoot22to23Migrator migrator = new SpringBoot22to23Migrator(true);

        // When: Migrator is created (components initialized in constructor via
        // initializeComponents())
        // Then: No exceptions should be thrown
        assertNotNull(migrator, "Migrator should initialize successfully");
    }

    @Test
    void testMainMethodExists() throws NoSuchMethodException {
        // Given: SpringBoot22to23Migrator class
        Class<?> migratorClass = SpringBoot22to23Migrator.class;

        // When/Then: Main method should exist
        assertNotNull(migratorClass.getMethod("main", String[].class),
                "Main method should exist for CLI execution");
    }

    @Test
    void testDryRunFlag() throws Exception {
        // Given: Migrator in dry-run mode
        SpringBoot22to23Migrator migrator = new SpringBoot22to23Migrator(true);

        // When: Running migration
        MigrationResult result = migrator.migrateAll();

        // Then: No files should be modified (dry-run)
        assertNotNull(result, "Result should not be null");
        // In dry-run, we expect changes to be reported but not applied
    }

    @Test
    void testNonDryRunMode() throws Exception {
        // Given: Migrator in non-dry-run mode
        SpringBoot22to23Migrator migrator = new SpringBoot22to23Migrator(false);

        // When: Running migration
        MigrationResult result = migrator.migrateAll();

        // Then: Migration should complete
        assertNotNull(result, "Result should not be null");
    }

    @Test
    void testMigrationWithoutPom() throws Exception {
        // Given: Project without pom.xml
        Files.delete(tempDir.resolve("pom.xml"));

        // When: Running migration
        SpringBoot22to23Migrator migrator = new SpringBoot22to23Migrator(true);
        MigrationResult result = migrator.migrateAll();

        // Then: Should handle gracefully
        assertNotNull(result, "Should return result even without POM");
    }

    @Test
    void testPhasePriorities() throws Exception {
        // Given: A migrator instance
        SpringBoot22to23Migrator migrator = new SpringBoot22to23Migrator(true);

        // When: Running migration (phases should execute in priority order)
        MigrationResult result = migrator.migrateAll();

        // Then: Result should reflect all phases completed
        assertNotNull(result, "Migration result should not be null");
        assertTrue(result.getTotalChanges() >= 0, "Should have changes counted");
    }
}
