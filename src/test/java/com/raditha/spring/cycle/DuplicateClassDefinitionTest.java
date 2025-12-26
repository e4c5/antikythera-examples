package com.raditha.spring.cycle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify that duplicate class definitions are detected and throw an exception.
 * Uses Unknown.java which contains a duplicate OrderProcessingService class.
 */
class DuplicateClassDefinitionTest {

    @BeforeEach
    void setUp() throws IOException, InterruptedException {
        TestbedResetHelper.resetTestbed();
        
        File configFile = new File("src/test/resources/cycle-detector.yml");
        Settings.loadConfigMap(configFile);
        
        AbstractCompiler.reset();
        AntikytheraRunTime.resetAll();
    }

    @Test
    void testDuplicateClassDefinitionThrowsException() throws Exception {
        // First, compile OrderProcessingService.java (the correct one)
        AbstractCompiler compiler1 = new AbstractCompiler();
        compiler1.compile("com/example/cycles/extraction/OrderProcessingService.java");
        
        // Verify it was stored
        assertNotNull(AntikytheraRunTime.getCompilationUnit(
            "com.example.cycles.extraction.OrderProcessingService"),
            "OrderProcessingService should be in AntikytheraRunTime");
        
        // Now try to compile Unknown.java which contains a duplicate OrderProcessingService
        AbstractCompiler compiler2 = new AbstractCompiler();
        
        // This should throw an IllegalStateException
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> compiler2.compile("com/example/cycles/extraction/Unknown.java"),
            "Should throw IllegalStateException when duplicate class definition is detected");
        
        assertTrue(exception.getMessage().contains("Duplicate class definition detected"),
            "Exception message should mention duplicate class definition");
        assertTrue(exception.getMessage().contains("OrderProcessingService"),
            "Exception message should mention the class name");
        assertTrue(exception.getMessage().contains("violates Java's one-class-per-file rule"),
            "Exception message should mention the rule violation");
    }

    @Test
    void testPreProcessDetectsDuplicateClassDefinition()  {
        // preProcess() should detect the duplicate when processing all files
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
                AbstractCompiler::preProcess,
            "preProcess() should throw IllegalStateException when duplicate class definition is detected");
        
        assertTrue(exception.getMessage().contains("Duplicate class definition detected"),
            "Exception message should mention duplicate class definition");
        assertTrue(exception.getMessage().contains("OrderProcessingService"),
            "Exception message should mention the class name");
    }
}


