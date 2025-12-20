package com.raditha.spring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SpringBoot24to25Migrator.
 * Tests main orchestrator for Spring Boot 2.4→2.5 migration.
 */
class SpringBoot24to25MigratorTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        Settings.loadConfigMap();
        Settings.setProperty("base_path", tempDir.toString());

        // Reset and preprocess to load test helper sources
        AbstractCompiler.reset();
        AbstractCompiler.preProcess();

        // Create minimal project structure
        Files.createDirectories(tempDir.resolve("src/main/java"));
        Files.createDirectories(tempDir.resolve("src/main/resources"));

        // Create minimal pom.xml with Spring Boot 2.4
        String pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>2.4.13</version>
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
        // Given: A Spring Boot 2.4 project
        SpringBoot24to25Migrator migrator = new SpringBoot24to25Migrator(true);

        // When: Running full migration in dry-run mode
        MigrationResult result = migrator.migrateAll();

        // Then: Migration should complete successfully
        assertNotNull(result, "Migration result should not be null");
    }

    @Test
    void testVersionInfo() {
        // Given: A migrator instance
        SpringBoot24to25Migrator migrator = new SpringBoot24to25Migrator(true);

        // When/Then: Verify version information
        assertEquals("2.4", migrator.getSourceVersion(), "Source version should be 2.4");
        assertEquals("2.5", migrator.getTargetVersion(), "Target version should be 2.5");
    }

    @Test
    void testSqlScriptPropertiesMigration() throws Exception {
        // Given: A Spring Boot 2.4 project with old SQL script properties
        String yamlContent = """
                spring:
                  datasource:
                    initialization-mode: always
                    schema: classpath:schema.sql
                    data: classpath:data.sql
                    platform: postgresql
                """;

        Files.writeString(tempDir.resolve("src/main/resources/application.yml"), yamlContent);

        // When: Running migration in dry-run mode
        SpringBoot24to25Migrator migrator = new SpringBoot24to25Migrator(true);
        MigrationResult result = migrator.migrateAll();

        // Then: SQL script properties migration should be detected
        assertNotNull(result, "Migration result should not be null");
        assertTrue(result.getPhases().containsKey("SQL Script Properties"), 
                "Should include SQL Script Properties phase");
    }

    @Test
    void testActuatorInfoMigration() throws Exception {
        // Given: A Spring Boot 2.4 project with /info endpoint usage
        String javaContent = """
                package com.example;
                
                public class MonitoringService {
                    private static final String INFO_URL = "/actuator/info";
                    
                    public void checkHealth() {
                        // Check /actuator/info endpoint
                    }
                }
                """;

        Path javaFile = tempDir.resolve("src/main/java/com/example/MonitoringService.java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, javaContent);

        // When: Running migration in dry-run mode
        SpringBoot24to25Migrator migrator = new SpringBoot24to25Migrator(true);
        MigrationResult result = migrator.migrateAll();

        // Then: Actuator info migration should be detected
        assertNotNull(result, "Migration result should not be null");
        assertTrue(result.getPhases().containsKey("Actuator /info Endpoint"), 
                "Should include Actuator /info Endpoint phase");
    }

    @Test
    void testCassandraMigration() throws Exception {
        // Given: A Spring Boot 2.4 project with Cassandra dependency
        String pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>2.4.13</version>
                    </parent>
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

        Files.writeString(tempDir.resolve("pom.xml"), pomContent);

        // When: Running migration in dry-run mode
        SpringBoot24to25Migrator migrator = new SpringBoot24to25Migrator(true);
        MigrationResult result = migrator.migrateAll();

        // Then: Cassandra throttling migration should be detected
        assertNotNull(result, "Migration result should not be null");
        assertTrue(result.getPhases().containsKey("Cassandra Throttling"), 
                "Should include Cassandra Throttling phase");
    }

    @Test
    void testGroovySpockMigration() throws Exception {
        // Given: A Spring Boot 2.4 project with Spock 1.x
        String pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>2.4.13</version>
                    </parent>
                    <groupId>com.example</groupId>
                    <artifactId>test-project</artifactId>
                    <version>1.0.0</version>
                    <properties>
                        <groovy.version>2.5.14</groovy.version>
                        <spock.version>1.3-groovy-2.5</spock.version>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>org.spockframework</groupId>
                            <artifactId>spock-core</artifactId>
                            <version>${spock.version}</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                </project>
                """;

        Files.writeString(tempDir.resolve("pom.xml"), pomContent);

        // When: Running migration in dry-run mode
        SpringBoot24to25Migrator migrator = new SpringBoot24to25Migrator(true);
        MigrationResult result = migrator.migrateAll();

        // Then: Groovy/Spock migration should be detected
        assertNotNull(result, "Migration result should not be null");
        assertTrue(result.getPhases().containsKey("Groovy/Spock Upgrade"), 
                "Should include Groovy/Spock Upgrade phase");
    }
}
