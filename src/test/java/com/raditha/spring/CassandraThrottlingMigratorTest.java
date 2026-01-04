package com.raditha.spring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CassandraThrottlingMigrator.
 * Tests that explicit throttling configuration is added when Cassandra is used.
 */
class CassandraThrottlingMigratorTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        Settings.loadConfigMap();
        Settings.setProperty("base_path", tempDir.toString());

        // Create resources directory
        Files.createDirectories(tempDir.resolve("src/main/resources"));
    }

    @Test
    void testCassandraThrottlingAdded() throws Exception {
        // Given: POM with Cassandra dependency
        createPomWithDependency("spring-boot-starter-data-cassandra");

        // And: application.yml without throttling config
        Path yamlPath = tempDir.resolve("src/main/resources/application.yml");
        Files.writeString(yamlPath, "spring:\n  data:\n    cassandra:\n      keyspace-name: test\n");

        // When: Run migrator
        CassandraThrottlingMigrator migrator = new CassandraThrottlingMigrator(false);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Configuration should be added
        assertNotNull(result);

        String content = Files.readString(yamlPath);
        assertTrue(content.contains("request:"), "Should add request section");
        assertTrue(content.contains("throttler:"), "Should add throttler section");
        assertTrue(content.contains("type: rate-limiting"), "Should set default type");
    }

    @Test
    void testNoActionIfAlreadyConfigured() throws Exception {
        // Given: POM with Cassandra dependency
        createPomWithDependency("spring-boot-starter-data-cassandra");

        // And: application.yml WITH throttling config
        String yamlContent = """
            spring:
              data:
                cassandra:
                  request:
                    throttler:
                      type: none
            """;
        Path yamlPath = tempDir.resolve("src/main/resources/application.yml");
        Files.writeString(yamlPath, yamlContent);

        // When: Run migrator
        CassandraThrottlingMigrator migrator = new CassandraThrottlingMigrator(false);
        MigrationPhaseResult result = migrator.migrate();

        // Then: File should remain unchanged
        String content = Files.readString(yamlPath);
        assertEquals(yamlContent, content);
    }

    @Test
    void testNoActionIfNoCassandra() throws Exception {
        // Given: POM without Cassandra
        createPomWithDependency("spring-boot-starter-web");

        // When: Run migrator
        CassandraThrottlingMigrator migrator = new CassandraThrottlingMigrator(false);
        MigrationPhaseResult result = migrator.migrate();

        // Then: No changes
        assertTrue(result.getChanges().isEmpty() || result.getChanges().contains("No Cassandra dependency detected"));
    }

    private void createPomWithDependency(String artifactId) throws Exception {
        String pom = """
                <project>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>%s</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """.formatted(artifactId);
        Files.writeString(tempDir.resolve("pom.xml"), pom);
    }
}
