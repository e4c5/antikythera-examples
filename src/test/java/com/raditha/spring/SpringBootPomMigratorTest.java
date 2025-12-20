package com.raditha.spring;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SpringBootPomMigrator - the main POM migrator for Spring Boot 2.1
 * to 2.2.
 * <p>
 * Creates test POMs and uses Settings.setBasePath() so migrators can find them.
 */
class SpringBootPomMigratorTest {

    private static final String TESTBED_PATH = System.getProperty("user.home") +
            "/csi/Antikythera/antikythera-examples/testbeds/spring-boot-2.1";

    @TempDir
    Path tempDir;

    private Path testPomPath;

    @BeforeAll
    static void resetTestbed() throws IOException, InterruptedException {
        // Reset testbed to clean state using Git
        Path testbedPath = Paths.get(TESTBED_PATH);
        if (Files.exists(testbedPath.resolve(".git"))) {
            ProcessBuilder pb = new ProcessBuilder("git", "checkout", "HEAD", "pom.xml");
            pb.directory(testbedPath.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.waitFor();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        Settings.loadConfigMap();

        // Create a test POM with common migration scenarios
        testPomPath = tempDir.resolve("pom.xml");
        createTestPom();

        // Set base path to temp directory so migrator finds the POM
        Settings.setProperty("base_path", tempDir.toString());
    }

    private void createTestPom() throws IOException {
        String pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                         http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>

                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>2.1.18.RELEASE</version>
                    </parent>

                    <groupId>com.example</groupId>
                    <artifactId>test-project</artifactId>
                    <version>1.0.0</version>

                    <properties>
                        <spring-cloud.version>Greenwich.SR6</spring-cloud.version>
                    </properties>

                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-web</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>javax.mail</groupId>
                            <artifactId>javax.mail-api</artifactId>
                            <version>1.6.2</version>
                        </dependency>
                        <dependency>
                            <groupId>org.apache.kafka</groupId>
                            <artifactId>kafka-clients</artifactId>
                            <version>2.0.0</version>
                        </dependency>
                        <dependency>
                            <groupId>io.springfox</groupId>
                            <artifactId>springfox-swagger2</artifactId>
                            <version>2.9.2</version>
                        </dependency>
                        <dependency>
                            <groupId>io.springfox</groupId>
                            <artifactId>springfox-swagger-ui</artifactId>
                            <version>2.9.2</version>
                        </dependency>
                        <dependency>
                            <groupId>net.javacrumbs.shedlock</groupId>
                            <artifactId>shedlock-spring</artifactId>
                            <version>2.5.0</version>
                        </dependency>
                        <dependency>
                            <groupId>net.javacrumbs.shedlock</groupId>
                            <artifactId>shedlock-provider-jdbc</artifactId>
                            <version>2.6.0</version>
                        </dependency>
                    </dependencies>

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
        Files.writeString(testPomPath, pomContent);
    }

    @Test
    void testSpringBootVersionUpgrade() throws Exception {
        // When: Running migrator in dry-run
        SpringBootPomMigrator migrator = new SpringBootPomMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should report version upgrade
        assertTrue(result.getChanges().stream()
                .anyMatch(change -> change.contains("2.1") && change.contains("2.2")),
                "Should report Spring Boot version upgrade");
    }

    @Test
    void testJakartaMailMigrationDetected() throws Exception {
        // When: Running migrator in dry-run
        SpringBootPomMigrator migrator = new SpringBootPomMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should detect mail-related changes
        boolean hasMailChange = result.getChanges().stream()
                .anyMatch(change -> change.toLowerCase().contains("mail"));

        assertTrue(hasMailChange, "Should detect and report mail migration");
    }

    @Test
    void testKafkaVersionWarning() throws Exception {
        // When: Running migrator
        SpringBootPomMigrator migrator = new SpringBootPomMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should warn about Kafka version
        assertTrue(result.getWarnings().stream()
                .anyMatch(warning -> warning.toLowerCase().contains("kafka")),
                "Should warn about Kafka version requirement");
    }

    @Test
    void testSpringCloudUpgradeDetected() throws Exception {
        // When: Running migrator
        SpringBootPomMigrator migrator = new SpringBootPomMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should report Spring Cloud upgrade
        boolean hasCloudChange = result.getChanges().stream()
                .anyMatch(change -> change.contains("Greenwich") || change.contains("Hoxton"));

        assertTrue(hasCloudChange, "Should report Spring Cloud upgrade");
    }

    @Test
    void testDryRunDoesNotModifyPom() throws Exception {
        // Given: Original POM content
        String originalContent = Files.readString(testPomPath);

        // When: Running migrator in dry-run
        SpringBootPomMigrator migrator = new SpringBootPomMigrator(true);
        migrator.migrate();

        // Then: POM should not be modified
        String afterContent = Files.readString(testPomPath);
        assertEquals(originalContent, afterContent,
                "POM should not be modified in dry-run mode");
    }

    @Test
    void testGetPhaseName() {
        SpringBootPomMigrator migrator = new SpringBootPomMigrator(false);
        assertEquals("POM Migration", migrator.getPhaseName());
    }

    @Test
    void testGetPriority() {
        SpringBootPomMigrator migrator = new SpringBootPomMigrator(false);
        assertEquals(10, migrator.getPriority());
    }
}
