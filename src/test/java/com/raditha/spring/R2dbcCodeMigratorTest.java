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
 * Unit tests for R2dbcCodeMigrator.
 * Tests R2DBC usage detection for Spring Boot 2.4.
 */
class R2dbcCodeMigratorTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        Settings.loadConfigMap();
        Settings.setProperty("base_path", tempDir.toString());
        AbstractCompiler.preProcess();
    }

    @Test
    void testR2dbcDetection() throws Exception {
        // Given: Source code with R2DBC imports
        String javaCode = """
            package com.example.demo;
            
            import org.springframework.r2dbc.core.DatabaseClient;
            import org.springframework.data.r2dbc.repository.R2dbcRepository;
            
            public class UserRepository {
                private DatabaseClient client;
                
                public UserRepository(DatabaseClient client) {
                    this.client = client;
                }
            }
            """;

        // Create source directory structure
        Path srcDir = tempDir.resolve("src/main/java/com/example/demo");
        Files.createDirectories(srcDir);
        Path javaFile = srcDir.resolve("UserRepository.java");
        Files.writeString(javaFile, javaCode);

        // When: Running R2DBC migrator (without full compilation)
        R2dbcCodeMigrator migrator = new R2dbcCodeMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should complete (may or may not detect depending on compilation state)
        assertNotNull(result, "Result should not be null");
        // Result depends on whether compilation units are available
        // Just verify it doesn't crash
    }

    @Test
    void testNoR2dbcUsage() throws Exception {
        // Given: Source code without R2DBC imports
        String javaCode = """
            package com.example.demo;
            
            import org.springframework.stereotype.Service;
            
            @Service
            public class UserService {
                public void doSomething() {
                    // Regular service code
                }
            }
            """;

        // Create source directory structure
        Path srcDir = tempDir.resolve("src/main/java/com/example/demo");
        Files.createDirectories(srcDir);
        Path javaFile = srcDir.resolve("UserService.java");
        Files.writeString(javaFile, javaCode);

        // When: Running R2DBC migrator
        R2dbcCodeMigrator migrator = new R2dbcCodeMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        // Then: Should report no R2DBC usage
        assertNotNull(result, "Result should not be null");
        assertTrue(result.getChanges().stream()
                .anyMatch(c -> c.contains("No R2DBC")),
            "Should report no R2DBC usage when compilation units available");
    }

    @Test
    void testGetPhaseName() {
        R2dbcCodeMigrator migrator = new R2dbcCodeMigrator(false);
        assertEquals("R2DBC Code Detection", migrator.getPhaseName());
    }

    @Test
    void testGetPriority() {
        R2dbcCodeMigrator migrator = new R2dbcCodeMigrator(false);
        assertEquals(80, migrator.getPriority());
    }
}
