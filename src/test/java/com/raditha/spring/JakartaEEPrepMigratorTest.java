package com.raditha.spring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class JakartaEEPrepMigratorTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        Settings.loadConfigMap();
        Settings.setProperty("base_path", tempDir.toString());
        AntikytheraRunTime.resetAll();
        AbstractCompiler.reset();
    }

    @Test
    void testAddComments() throws IOException {
        // Given
        Path javaFile = tempDir.resolve("src/main/java/com/example/Entity.java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, "package com.example;\nimport javax.persistence.Entity;\npublic class Entity {}");

        // When
        JakartaEEPrepMigrator migrator = new JakartaEEPrepMigrator(false, true);
        MigrationPhaseResult result = migrator.migrate();

        // Then
        assertNotNull(result);
        assertFalse(result.getModifiedClasses().isEmpty());
        // Can't easily check comment content without re-parsing, but checking result is good enough for now
        assertTrue(result.getChanges().stream().anyMatch(c -> c.contains("Added Jakarta EE migration TODO comments")));
    }

    @Test
    void testDisabled() throws IOException {
        // Given
        Path javaFile = tempDir.resolve("src/main/java/com/example/Entity.java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, "package com.example;\nimport javax.persistence.Entity;\npublic class Entity {}");

        // When
        JakartaEEPrepMigrator migrator = new JakartaEEPrepMigrator(false, false);
        MigrationPhaseResult result = migrator.migrate();

        // Then
        assertTrue(result.getModifiedClasses().isEmpty());
        assertTrue(result.getChanges().stream().anyMatch(c -> c.contains("Jakarta EE prep comments not enabled")));
    }
}
