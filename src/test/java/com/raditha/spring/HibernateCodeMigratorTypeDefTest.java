package com.raditha.spring;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class HibernateCodeMigratorTypeDefTest {

    private static String originalContent;
    private static Path sampleEntityPath;

    @BeforeAll
    static void setup() throws Exception {
        File configFile = new File("src/test/resources/hb-generator.yml");
        assertTrue(configFile.exists(), "hb-generator.yml should exist");
        Settings.loadConfigMap(configFile);

        // Backup SampleEntity.java
        sampleEntityPath = Paths.get("src/test/resources/hb-samples/src/main/java/com/example/hb/SampleEntity.java");
        assertTrue(Files.exists(sampleEntityPath), "SampleEntity.java should exist");
        originalContent = Files.readString(sampleEntityPath);

        // Ensure pre-migration state
        String preMigrationContent = """
                package com.example.hb;

                import org.hibernate.annotations.Type;
                import org.hibernate.annotations.TypeDef;
                import javax.persistence.Entity;
                import javax.persistence.Id;

                @TypeDef(name = "jsonb", typeClass = java.lang.Object.class)
                @Entity
                public class SampleEntity {

                    @Id
                    private Long id;

                    @Type(type = "jsonb")
                    private Object data;

                    public Long getId() {
                        return id;
                    }

                    public void setId(Long id) {
                        this.id = id;
                    }

                    public Object getData() {
                        return data;
                    }

                    public void setData(Object data) {
                        this.data = data;
                    }
                }
                """;
        Files.writeString(sampleEntityPath, preMigrationContent);

        AntikytheraRunTime.resetAll();
        AbstractCompiler.reset();
        AbstractCompiler.preProcess();
    }

    @org.junit.jupiter.api.AfterAll
    static void cleanup() throws Exception {
        // Restore SampleEntity.java
        if (originalContent != null && sampleEntityPath != null) {
            Files.writeString(sampleEntityPath, originalContent);
        }

        // Cleanup generated converter
        Path converterPath = Paths.get(Settings.getBasePath())
                .resolve("src/main/java/com/example/hb/converters/JsonbAttributeConverter.java");
        Files.deleteIfExists(converterPath);

        // Cleanup directories if empty
        Path parent = converterPath.getParent();
        if (parent != null && Files.exists(parent) && Files.list(parent).findAny().isEmpty()) {
            Files.delete(parent);
        }
    }

    @Test
    void testTypeDefDetectedInDryRun() throws Exception {
        assertFalse(AntikytheraRunTime.getResolvedCompilationUnits().isEmpty(), "Compilation units should be loaded");
        HibernateCodeMigrator migrator = new HibernateCodeMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        // Should mark class as modified and include a would-generate message
        assertTrue(result.getModifiedClasses().stream().anyMatch(c -> c.contains("com.example.hb.SampleEntity")));
        assertTrue(result.getChanges().stream()
                .anyMatch(c -> c.contains("Would generate AttributeConverter for @TypeDef(name=\"jsonb\")")));
        assertTrue(result.getChanges().stream().anyMatch(c -> c.contains("Detected ")));

        // In dry-run, manual review should not be required yet
        assertFalse(result.requiresManualReview(), "Dry-run should not require manual review");
        assertTrue(result.getManualReviewItems().isEmpty(), "No manual review items in dry-run");
    }

    @Test
    void testTypeDefGeneratesConverterAndWarnings() throws Exception {
        // Re-load config to ensure base path is correct and reset compiler state
        Settings.loadConfigMap(new File("src/test/resources/hb-generator.yml"));
        AntikytheraRunTime.resetAll();
        AbstractCompiler.reset();
        AbstractCompiler.preProcess();

        HibernateCodeMigrator migrator = new HibernateCodeMigrator(false);
        MigrationPhaseResult result = migrator.migrate();

        // Should report generation and set manual review flags
        assertTrue(result.getChanges().stream()
                .anyMatch(c -> c.contains("Generated AttributeConverter stub for @TypeDef(name=\"jsonb\")")));
        assertTrue(result.requiresManualReview(), "Should require manual review when converters are generated");
        assertFalse(result.getManualReviewItems().isEmpty(), "Manual review items should be populated");

        // Should include a warning to replace @Type with @Convert
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("Replace @Type annotation with @Convert")),
                "Should include warning about replacing @Type annotation");

        // Verify that the converter file was created in the expected location
        String basePath = Settings.getBasePath();
        Path expected = Paths.get(basePath)
                .resolve("src/main/java")
                .resolve("com/example/hb/converters/JsonbAttributeConverter.java");
        assertTrue(Files.exists(expected), "Expected converter file to be generated: " + expected);

        // Basic sanity check on file content
        String content = Files.readString(expected);
        assertTrue(content.contains("package com.example.hb.converters;"));
        assertTrue(content.contains("class JsonbAttributeConverter"));
    }
}
