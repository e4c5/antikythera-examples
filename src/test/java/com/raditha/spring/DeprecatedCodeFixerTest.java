package com.raditha.spring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
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
 * Unit tests for DeprecatedCodeFixer.
 * 
 * Tests AST-based deprecated code fixing.
 */
class DeprecatedCodeFixerTest {

    @TempDir
    Path tempDir;

    private Path projectDir;
    private Path javaDir;

    @BeforeEach
    void setUp() throws IOException {
        Settings.loadConfigMap(new File("src/test/resources/generator.yml"));
        AntikytheraRunTime.reset();
        AbstractCompiler.reset();

        projectDir = tempDir.resolve("test-project");
        javaDir = projectDir.resolve("src/main/java/com/example");
        Files.createDirectories(javaDir);

        Settings.setProperty(Settings.BASE_PATH, projectDir.toString());
    }

    @Test
    void testDeprecatedImportReplacement() throws Exception {
        // Given: Java file with deprecated Prometheus import
        String javaCode = """
                package com.example;

                import org.springframework.boot.actuate.metrics.export.prometheus.PrometheusPushGatewayManager;

                public class MetricsConfig {
                    private PrometheusPushGatewayManager manager;
                }
                """;

        Files.writeString(javaDir.resolve("MetricsConfig.java"), javaCode);

        // Load into AbstractCompiler
        AbstractCompiler.preProcess();

        // When: Run deprecated code fixer
        DeprecatedCodeFixer fixer = new DeprecatedCodeFixer(false);
        MigrationPhaseResult result = fixer.migrate();

        // Then: Should replace deprecated import
        assertTrue(result.isSuccessful());
        assertTrue(result.getChangeCount() > 0, "Should have changes");

        assertTrue(result.getChanges().stream()
                .anyMatch(c -> c.contains("prometheus") && c.contains("micrometer")),
                "Should replace Prometheus import with Micrometer equivalent");
    }

    @Test
    void testMultipleDeprecatedImports() throws Exception {
        // Given: Java file with multiple deprecated imports
        String javaCode = """
                package com.example;

                import org.springframework.boot.actuate.metrics.export.prometheus.PrometheusPushGatewayManager;
                import org.springframework.boot.actuate.metrics.export.graphite.GraphiteMetricsExporter;
                import org.springframework.boot.autoconfigure.security.SecurityProperties;

                public class Config {
                }
                """;

        Files.writeString(javaDir.resolve("Config.java"), javaCode);
        AbstractCompiler.preProcess();

        // When: Run fixer
        DeprecatedCodeFixer fixer = new DeprecatedCodeFixer(false);
        MigrationPhaseResult result = fixer.migrate();

        // Then: Should replace all deprecated imports
        assertTrue(result.isSuccessful());
        assertTrue(result.getChangeCount() >= 3, "Should fix multiple imports");
    }

    @Test
    void testNoChangesWhenNoDeprecatedCode() throws Exception {
        // Given: Java file with no deprecated code
        String javaCode = """
                package com.example;

                import org.springframework.stereotype.Service;

                @Service
                public class MyService {
                }
                """;

        Files.writeString(javaDir.resolve("MyService.java"), javaCode);
        AbstractCompiler.preProcess();

        // When: Run fixer
        DeprecatedCodeFixer fixer = new DeprecatedCodeFixer(false);
        MigrationPhaseResult result = fixer.migrate();

        // Then: Should complete with no changes
        assertTrue(result.isSuccessful());
        assertTrue(result.getChanges().stream()
                .anyMatch(c -> c.contains("No deprecated code")),
                "Should report no deprecated code found");
    }

    @Test
    void testDryRunMode() throws Exception {
        // Given: Java file with deprecated import
        String javaCode = """
                package com.example;

                import org.springframework.boot.actuate.metrics.export.prometheus.PrometheusPushGatewayManager;

                public class TestClass {
                }
                """;

        Path javaFile = javaDir.resolve("TestClass.java");
        Files.writeString(javaFile, javaCode);
        AbstractCompiler.preProcess();

        // When: Run in dry-run mode
        DeprecatedCodeFixer fixer = new DeprecatedCodeFixer(true);
        MigrationPhaseResult result = fixer.migrate();

        // Then: Should identify changes but not modify file
        assertTrue(result.isSuccessful());

        String fileContent = Files.readString(javaFile);
        assertTrue(fileContent.contains("org.springframework.boot.actuate.metrics.export.prometheus"),
                "File should not be modified in dry-run mode");
    }
}
