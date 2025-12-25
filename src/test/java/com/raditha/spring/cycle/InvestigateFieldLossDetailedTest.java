package com.raditha.spring.cycle;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Detailed test to investigate field loss - checks at each step of preProcess.
 */
class InvestigateFieldLossDetailedTest {

    @BeforeEach
    void setUp() throws IOException {
        TestbedResetHelper.resetTestbed();
        // Remove Unknown.java to avoid duplicate class definition errors
        TestbedResetHelper.removeUnknownJava();
        
        File configFile = new File("src/test/resources/cycle-detector.yml");
        Settings.loadConfigMap(configFile);
        
        AbstractCompiler.reset();
        AntikytheraRunTime.resetAll();
    }
    
    @org.junit.jupiter.api.AfterEach
    void tearDown() throws IOException {
        TestbedResetHelper.restoreUnknownJava();
    }

    @Test
    void testFieldLossDuringPreProcess() throws Exception {
        String className = "com.example.cycles.extraction.OrderProcessingService";
        
        // Step 1: Parse just this one file
        AbstractCompiler compiler = new AbstractCompiler();
        compiler.compile("com/example/cycles/extraction/OrderProcessingService.java");
        CompilationUnit cu1 = compiler.getCompilationUnit();
        
        ClassOrInterfaceDeclaration class1 = cu1.findFirst(ClassOrInterfaceDeclaration.class,
                c -> c.getNameAsString().equals("OrderProcessingService")).orElseThrow();
        int fields1 = class1.getFields().size();
        boolean hasPayment1 = class1.getFields().stream()
                .anyMatch(f -> f.getVariables().size() > 0 && 
                        f.getVariables().get(0).getNameAsString().equals("paymentProcessingService"));
        
        System.out.println("After compile() - Fields: " + fields1 + ", Has payment: " + hasPayment1);
        
        // Step 2: Check what's in AntikytheraRunTime after compile()
        CompilationUnit cu2 = AntikytheraRunTime.getCompilationUnit(className);
        assertNotNull(cu2, "Should be in AntikytheraRunTime after compile()");
        
        ClassOrInterfaceDeclaration class2 = cu2.findFirst(ClassOrInterfaceDeclaration.class,
                c -> c.getNameAsString().equals("OrderProcessingService")).orElseThrow();
        int fields2 = class2.getFields().size();
        boolean hasPayment2 = class2.getFields().stream()
                .anyMatch(f -> f.getVariables().size() > 0 && 
                        f.getVariables().get(0).getNameAsString().equals("paymentProcessingService"));
        
        System.out.println("In AntikytheraRunTime after compile() - Fields: " + fields2 + ", Has payment: " + hasPayment2);
        System.out.println("cu1 == cu2: " + (cu1 == cu2));
        
        // Step 3: Now call compileAndSolveInterfaces (what preProcess does)
        AbstractCompiler compiler2 = new AbstractCompiler();
        compiler2.compileAndSolveInterfaces("com/example/cycles/extraction/OrderProcessingService.java");
        CompilationUnit cu3 = compiler2.getCompilationUnit();
        
        ClassOrInterfaceDeclaration class3 = cu3.findFirst(ClassOrInterfaceDeclaration.class,
                c -> c.getNameAsString().equals("OrderProcessingService")).orElseThrow();
        int fields3 = class3.getFields().size();
        boolean hasPayment3 = class3.getFields().stream()
                .anyMatch(f -> f.getVariables().size() > 0 && 
                        f.getVariables().get(0).getNameAsString().equals("paymentProcessingService"));
        
        System.out.println("After compileAndSolveInterfaces() - Fields: " + fields3 + ", Has payment: " + hasPayment3);
        System.out.println("cu1 == cu3: " + (cu1 == cu3));
        
        // Step 4: Check AntikytheraRunTime again
        CompilationUnit cu4 = AntikytheraRunTime.getCompilationUnit(className);
        ClassOrInterfaceDeclaration class4 = cu4.findFirst(ClassOrInterfaceDeclaration.class,
                c -> c.getNameAsString().equals("OrderProcessingService")).orElseThrow();
        int fields4 = class4.getFields().size();
        boolean hasPayment4 = class4.getFields().stream()
                .anyMatch(f -> f.getVariables().size() > 0 && 
                        f.getVariables().get(0).getNameAsString().equals("paymentProcessingService"));
        
        System.out.println("In AntikytheraRunTime after compileAndSolveInterfaces() - Fields: " + fields4 + ", Has payment: " + hasPayment4);
        System.out.println("cu1 == cu4: " + (cu1 == cu4));
        
        // Step 5: Now call preProcess (processes all files)
        AntikytheraRunTime.resetAll();
        AbstractCompiler.preProcess();
        CompilationUnit cu5 = AntikytheraRunTime.getCompilationUnit(className);
        
        ClassOrInterfaceDeclaration class5 = cu5.findFirst(ClassOrInterfaceDeclaration.class,
                c -> c.getNameAsString().equals("OrderProcessingService")).orElseThrow();
        int fields5 = class5.getFields().size();
        boolean hasPayment5 = class5.getFields().stream()
                .anyMatch(f -> f.getVariables().size() > 0 && 
                        f.getVariables().get(0).getNameAsString().equals("paymentProcessingService"));
        
        System.out.println("In AntikytheraRunTime after preProcess() - Fields: " + fields5 + ", Has payment: " + hasPayment5);
        System.out.println("cu1 == cu5: " + (cu1 == cu5));
        
        // Print all field names at each step
        System.out.println("\nField names at each step:");
        System.out.println("Step 1 (after compile): " + class1.getFields().stream()
                .flatMap(f -> f.getVariables().stream().map(v -> v.getNameAsString()))
                .toList());
        System.out.println("Step 2 (AntikytheraRunTime after compile): " + class2.getFields().stream()
                .flatMap(f -> f.getVariables().stream().map(v -> v.getNameAsString()))
                .toList());
        System.out.println("Step 3 (after compileAndSolveInterfaces): " + class3.getFields().stream()
                .flatMap(f -> f.getVariables().stream().map(v -> v.getNameAsString()))
                .toList());
        System.out.println("Step 4 (AntikytheraRunTime after compileAndSolveInterfaces): " + class4.getFields().stream()
                .flatMap(f -> f.getVariables().stream().map(v -> v.getNameAsString()))
                .toList());
        System.out.println("Step 5 (AntikytheraRunTime after preProcess): " + class5.getFields().stream()
                .flatMap(f -> f.getVariables().stream().map(v -> v.getNameAsString()))
                .toList());
        
        // Assertions
        assertEquals(fields1, fields2, "Fields should be preserved after compile()");
        assertEquals(hasPayment1, hasPayment2, "Payment field should be preserved after compile()");
    }
}

