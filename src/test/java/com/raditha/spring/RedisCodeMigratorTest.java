package com.raditha.spring;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RedisCodeMigrator.
 */
class RedisCodeMigratorTest {

    @BeforeAll
    static void setup() throws IOException {
        File configFile = new File("src/test/resources/spring-migration-test.yml");
        Settings.loadConfigMap(configFile);

        AbstractCompiler.reset();
        AbstractCompiler.preProcess();
    }

    @Test
    void testNoRedisCode() {
        RedisCodeMigrator migrator = new RedisCodeMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        assertTrue(result.isSuccessful());
        assertTrue(result.getChanges().stream()
                .anyMatch(c -> c.contains("No Redis migrations needed")));
    }

    @Test
    void testMigratorScansAllClasses() {
        assertFalse(AntikytheraRunTime.getResolvedCompilationUnits().isEmpty());

        RedisCodeMigrator migrator = new RedisCodeMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        assertNotNull(result);
        assertTrue(result.isSuccessful());
    }
}
