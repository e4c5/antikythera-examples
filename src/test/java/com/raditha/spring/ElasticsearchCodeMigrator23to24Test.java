package com.raditha.spring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ElasticsearchCodeMigrator23to24.
 * Tests detection of low-level RestClient usage which lost auto-configuration in Spring Boot 2.4.
 */
class ElasticsearchCodeMigrator23to24Test {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        Settings.loadConfigMap();
        Settings.setProperty("base_path", tempDir.toString());
        Files.createDirectories(tempDir.resolve("src/main/java/com/example"));
    }

    @Test
    void testRestClientUsageDetection() throws Exception {
        // Given: Java class using RestClient
        String javaContent = """
            package com.example;
            import org.elasticsearch.client.RestClient;
            import org.springframework.beans.factory.annotation.Autowired;

            public class SearchService {
                @Autowired
                private RestClient restClient;
            }
            """;

        Path javaPath = tempDir.resolve("src/main/java/com/example/SearchService.java");
        Files.writeString(javaPath, javaContent);

        // When: Run migrator
        ElasticsearchCodeMigrator23to24 migrator = new ElasticsearchCodeMigrator23to24(true);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should detect usage and flag for manual review
        assertNotNull(result);
        assertTrue(result.requiresManualReview());
        assertTrue(result.getWarnings().stream()
            .anyMatch(w -> w.contains("Low-level RestClient detected")));
    }

    @Test
    void testRestHighLevelClientUsageIgnored() throws Exception {
        // Given: Java class using RestHighLevelClient (safe)
        String javaContent = """
            package com.example;
            import org.elasticsearch.client.RestHighLevelClient;
            import org.springframework.beans.factory.annotation.Autowired;

            public class SearchService {
                @Autowired
                private RestHighLevelClient client;
            }
            """;

        Path javaPath = tempDir.resolve("src/main/java/com/example/SearchService.java");
        Files.writeString(javaPath, javaContent);

        // When: Run migrator
        ElasticsearchCodeMigrator23to24 migrator = new ElasticsearchCodeMigrator23to24(true);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should NOT flag for manual review
        assertNotNull(result);
        assertFalse(result.requiresManualReview());
        // Might have informational changes about finding the safe usage
    }

    @Test
    void testGetPhaseName() {
        ElasticsearchCodeMigrator23to24 migrator = new ElasticsearchCodeMigrator23to24(false);
        assertEquals("Elasticsearch RestClient Detection", migrator.getPhaseName());
    }
}
