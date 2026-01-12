package com.raditha.spring.cycle;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to investigate why fields are missing from the stored compilation unit.
 */
class InvestigateFieldLossTest {

    @BeforeEach
    void setUp() throws IOException, InterruptedException {
        TestbedResetHelper.resetTestbed();
        // Remove Unknown.java to avoid duplicate class definition errors
        TestbedResetHelper.removeUnknownJava();
        
        File configFile = new File("src/test/resources/cycle-detector.yml");
        Settings.loadConfigMap(configFile);
        
        AbstractCompiler.reset();
        AntikytheraRunTime.resetAll();
        AbstractCompiler.preProcess();
    }
    
    @org.junit.jupiter.api.AfterEach
    void tearDown() throws IOException {
        TestbedResetHelper.restoreUnknownJava();
    }

    @Test
    void testDirectParsingVsAbstractCompiler() throws Exception {
        Path workspaceRoot = Paths.get(System.getProperty("user.dir"));
        if (!workspaceRoot.toString().endsWith("antikythera-examples")) {
            workspaceRoot = workspaceRoot.resolve("antikythera-examples");
        }
        Path filePath = workspaceRoot.resolve("testbeds/spring-boot-cycles/src/main/java/com/example/cycles/extraction/OrderProcessingService.java");
        
        // Parse directly
        JavaParser parser = new JavaParser(new ParserConfiguration());
        CompilationUnit directCu = parser.parse(new FileInputStream(filePath.toFile())).getResult().orElseThrow();
        
        ClassOrInterfaceDeclaration directClass = directCu.findFirst(ClassOrInterfaceDeclaration.class,
                c -> c.getNameAsString().equals("OrderProcessingService")).orElseThrow();
        
        int directFieldCount = directClass.getFields().size();
        boolean directHasPaymentField = directClass.getFields().stream()
                .anyMatch(f -> f.getVariables().size() > 0 && 
                        f.getVariables().get(0).getNameAsString().equals("paymentProcessingService"));
        
        // Now use AbstractCompiler
        AbstractCompiler compiler = new AbstractCompiler();
        String relativePath = "com/example/cycles/extraction/OrderProcessingService.java";
        compiler.compile(relativePath);
        
        CompilationUnit compilerCu = compiler.getCompilationUnit();
        assertNotNull(compilerCu, "Compiler should have parsed the file");
        
        ClassOrInterfaceDeclaration compilerClass = compilerCu.findFirst(ClassOrInterfaceDeclaration.class,
                c -> c.getNameAsString().equals("OrderProcessingService")).orElseThrow();
        
        int compilerFieldCount = compilerClass.getFields().size();
        boolean compilerHasPaymentField = compilerClass.getFields().stream()
                .anyMatch(f -> f.getVariables().size() > 0 && 
                        f.getVariables().get(0).getNameAsString().equals("paymentProcessingService"));

        
        // Now check what's in AntikytheraRunTime
        AntikytheraRunTime.resetAll();
        AbstractCompiler.preProcess();
        CompilationUnit runtimeCu = AntikytheraRunTime.getCompilationUnit(
                "com.example.cycles.extraction.OrderProcessingService");
        assertNotNull(runtimeCu, "Should be in AntikytheraRunTime");
        
        ClassOrInterfaceDeclaration runtimeClass = runtimeCu.findFirst(ClassOrInterfaceDeclaration.class,
                c -> c.getNameAsString().equals("OrderProcessingService")).orElseThrow();
        
        int runtimeFieldCount = runtimeClass.getFields().size();
        boolean runtimeHasPaymentField = runtimeClass.getFields().stream()
                .anyMatch(f -> f.getVariables().size() > 0 && 
                        f.getVariables().get(0).getNameAsString().equals("paymentProcessingService"));

        // Assertions
        assertEquals(directFieldCount, compilerFieldCount, 
                "AbstractCompiler should parse the same number of fields as direct parsing");
        assertEquals(directHasPaymentField, compilerHasPaymentField,
                "AbstractCompiler should find the payment field if direct parsing does");
        assertEquals(compilerFieldCount, runtimeFieldCount,
                "AntikytheraRunTime should have the same field count as AbstractCompiler");
        assertEquals(compilerHasPaymentField, runtimeHasPaymentField,
                "AntikytheraRunTime should have the payment field if AbstractCompiler does");
    }
}

