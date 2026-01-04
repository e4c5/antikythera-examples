package com.raditha.spring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LazyInitializationConfigurerTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        Settings.loadConfigMap();
        Settings.setProperty("base_path", tempDir.toString());
        Files.createDirectories(tempDir.resolve("src/main/resources")); // For detection, though class looks recursively
    }

    @Test
    void testAddLazyInit() throws IOException {
        // Given
        Path yamlFile = tempDir.resolve("application-test.yml");
        Files.writeString(yamlFile, "spring:\n  profiles: test");

        // When
        LazyInitializationConfigurer configurer = new LazyInitializationConfigurer(false, true);
        MigrationPhaseResult result = configurer.migrate();

        // Then
        assertNotNull(result);
        String content = Files.readString(yamlFile);
        assertTrue(content.contains("lazy-initialization: true"));
    }

    @Test
    void testDisabled() throws IOException {
        // Given
        Path yamlFile = tempDir.resolve("application-test.yml");
        Files.writeString(yamlFile, "spring:\n  profiles: test");

        // When
        LazyInitializationConfigurer configurer = new LazyInitializationConfigurer(false, false);
        MigrationPhaseResult result = configurer.migrate();

        // Then
        String content = Files.readString(yamlFile);
        assertFalse(content.contains("lazy-initialization"));
        assertTrue(result.getChanges().stream().anyMatch(c -> c.contains("Lazy initialization not enabled")));
    }
}
