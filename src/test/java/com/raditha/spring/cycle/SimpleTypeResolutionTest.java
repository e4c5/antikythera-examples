package com.raditha.spring.cycle;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple test to debug why PaymentProcessingService type isn't resolved from OrderProcessingService.
 */
class SimpleTypeResolutionTest {

    @BeforeEach
    void setUp() throws IOException, InterruptedException {
        // Reset testbed to clean state first
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
    void testResolvePaymentProcessingServiceFromOrderProcessingService() {
        CompilationUnit orderCu = AntikytheraRunTime.getCompilationUnit(
                "com.example.cycles.extraction.OrderProcessingService");
        assertNotNull(orderCu, "OrderProcessingService compilation unit should be available");
        
        ClassOrInterfaceDeclaration orderClass = orderCu.findFirst(ClassOrInterfaceDeclaration.class,
                c -> c.getNameAsString().equals("OrderProcessingService")).orElse(null);
        assertNotNull(orderClass, "OrderProcessingService class should be found");
        
        // Find paymentProcessingService field
        com.github.javaparser.ast.body.FieldDeclaration paymentField = null;
        for (com.github.javaparser.ast.body.FieldDeclaration field : orderClass.getFields()) {
            if (!field.getVariables().isEmpty()) {
                String fieldName = field.getVariables().get(0).getNameAsString();
                if (fieldName.equals("paymentProcessingService")) {
                    paymentField = field;
                    break;
                }
            }
        }
        
        assertNotNull(paymentField, "paymentProcessingService field should be found");
        
        com.github.javaparser.ast.type.Type fieldType = paymentField.getVariables().get(0).getType();
        assertInstanceOf(ClassOrInterfaceType.class, fieldType, "Field type should be ClassOrInterfaceType");
        
        ClassOrInterfaceType paymentType = (ClassOrInterfaceType) fieldType;
        String typeName = paymentType.getNameAsString();
        
        // Test AbstractCompiler.findFullyQualifiedName - this is what BeanDependencyGraph uses
        String fqn = AbstractCompiler.findFullyQualifiedName(orderCu, typeName);
        
        assertEquals("com.example.cycles.extraction.PaymentProcessingService", fqn,
                "Should resolve PaymentProcessingService from OrderProcessingService");
    }
}

