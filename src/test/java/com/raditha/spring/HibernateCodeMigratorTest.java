package com.raditha.spring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for HibernateCodeMigrator.
 */
class HibernateCodeMigratorTest {

    @BeforeEach
    void setup() throws IOException {
        File configFile = new File("src/test/resources/generator.yml");
        Settings.loadConfigMap(configFile);

        AbstractCompiler.reset();
        AbstractCompiler.preProcess();
    }

    @Test
    void testNoTypeDefAnnotations() throws Exception {
        HibernateCodeMigrator migrator = new HibernateCodeMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        assertTrue(result.isSuccessful());
        assertTrue(result.getChanges().stream()
                .anyMatch(c -> c.contains("No Hibernate @TypeDef annotations found")));
    }

    @Test
    void testMigratorAccessesParsedClasses() throws Exception {
        assertFalse(AntikytheraRunTime.getResolvedCompilationUnits().isEmpty());

        HibernateCodeMigrator migrator = new HibernateCodeMigrator(true);
        MigrationPhaseResult result = migrator.migrate();

        assertNotNull(result);
        assertTrue(result.isSuccessful());
    }
}
