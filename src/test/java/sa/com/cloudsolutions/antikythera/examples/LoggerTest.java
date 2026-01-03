package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class to verify Logger utility behavior
 */
 class LoggerTest {

    @Test
     void testIsDebugEnabledNotModified() {
        String code = """
            package test;
            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;
            
             class TestClass {
                private static final Logger logger = LoggerFactory.getLogger(TestClass.class);
                
                 void testMethod() {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Debug message");
                    }
                }
            }
            """;
        
        CompilationUnit cu = StaticJavaParser.parse(code);
        LexicalPreservingPrinter.setup(cu);
        
        // Process with LoggerVisitor
        Logger.loggerFields.clear();
        Logger.loggerFields.add("logger");
        cu.findAll(MethodDeclaration.class).forEach(m ->
            m.accept(new Logger.LoggerVisitor(m.findAncestor(TypeDeclaration.class).orElseThrow()), false)
        );
        
        String result = LexicalPreservingPrinter.print(cu);
        
        // Verify isDebugEnabled() is not changed
        assertTrue(result.contains("isDebugEnabled()"), 
            "isDebugEnabled() should not be modified");
    }
    
    @Test
     void testEmptyForEachRemoved() {
        String code = """
            package test;
            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;
            import java.util.List;
            
             class TestClass {
                private static final Logger logger = LoggerFactory.getLogger(TestClass.class);
                
                 void testMethod(List<String> items) {
                    items.forEach(item -> {
                        logger.info("Processing: " + item);
                    });
                    
                    items.stream().forEach(item -> logger.debug("Item: " + item));
                }
            }
            """;
        
        CompilationUnit cu = StaticJavaParser.parse(code);
        LexicalPreservingPrinter.setup(cu);
        
        // Process with LoggerVisitor
        Logger.loggerFields.clear();
        Logger.loggerFields.add("logger");
        cu.findAll(MethodDeclaration.class).forEach(m -> {
            m.accept(new Logger.LoggerVisitor(m.findAncestor(TypeDeclaration.class).orElseThrow()), false);
            m.accept(new Logger.EmptyForEachRemover(), null);
        });
        
        String result = LexicalPreservingPrinter.print(cu);
        
        // The forEach should be removed since logger calls in loops are removed
        // Note: This test validates the behavior - empty forEach statements should be cleaned up
        assertFalse(result.contains("forEach(item -> {});"), 
            "Empty forEach should be removed");
    }
    
    @Test
     void testLoggerInCatchBlockChangedToError() {
        String code = """
            package test;
            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;
            
             class TestClass {
                private static final Logger logger = LoggerFactory.getLogger(TestClass.class);
                
                 void testMethod() {
                    try {
                        // some code
                    } catch (Exception e) {
                        logger.info("Error occurred");
                    }
                }
            }
            """;
        
        CompilationUnit cu = StaticJavaParser.parse(code);
        LexicalPreservingPrinter.setup(cu);
        
        // Process with LoggerVisitor
        Logger.loggerFields.clear();
        Logger.loggerFields.add("logger");
        cu.findAll(MethodDeclaration.class).forEach(m ->
            m.accept(new Logger.LoggerVisitor(m.findAncestor(TypeDeclaration.class).orElseThrow()), false)
        );
        
        String result = LexicalPreservingPrinter.print(cu);
        
        // Logger in catch block should be changed to error
        assertTrue(result.contains("logger.error("), 
            "Logger in catch block should be changed to error level");
    }

    @Test
     void testSystemOutPrintlnConvertedToLogger() {
        String code = """
            package test;
            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;
            
             class TestClass {
                private static final Logger logger = LoggerFactory.getLogger(TestClass.class);
                
                 void testMethod() {
                    System.out.println("This is a message");
                    System.out.print("Another message");
                    System.out.printf("Formatted: %s", "value");
                }
            }
            """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        LexicalPreservingPrinter.setup(cu);

        // Process with LoggerVisitor
        Logger.loggerFields.clear();
        Logger.loggerFields.add("logger");
        cu.findAll(MethodDeclaration.class).forEach(m ->
            m.accept(new Logger.LoggerVisitor(m.findAncestor(TypeDeclaration.class).orElseThrow()), false)
        );

        String result = LexicalPreservingPrinter.print(cu);

        // System.out calls should be completely removed
        assertFalse(result.contains("System.out.println"),
            "System.out.println should be removed");
        assertFalse(result.contains("System.out.print"),
            "System.out.print should be removed");
        assertFalse(result.contains("System.out.printf"),
            "System.out.printf should be removed");
        assertFalse(result.contains("logger.debug("),
            "System.out calls should be removed, not converted to logger");
    }

    @Test
     void testSystemOutInLoopRemoved() {
        String code = """
            package test;
            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;
            import java.util.List;
            
             class TestClass {
                private static final Logger logger = LoggerFactory.getLogger(TestClass.class);
                
                 void testMethod(List<String> items) {
                    items.forEach(item -> {
                        System.out.println("Processing: " + item);
                    });
                }
            }
            """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        LexicalPreservingPrinter.setup(cu);

        // Process with LoggerVisitor and EmptyForEachRemover
        Logger.loggerFields.clear();
        Logger.loggerFields.add("logger");
        cu.findAll(MethodDeclaration.class).forEach(m -> {
            m.accept(new Logger.LoggerVisitor(m.findAncestor(TypeDeclaration.class).orElseThrow()), false);
            m.accept(new Logger.EmptyForEachRemover(), null);
        });

        String result = LexicalPreservingPrinter.print(cu);

        // System.out in loop should be removed, and forEach should be removed too
        assertFalse(result.contains("System.out.println"),
            "System.out.println in loop should be removed");
        assertFalse(result.contains("forEach"),
            "Empty forEach should be removed");
    }

    @Test
     void testSystemErrInCatchBlockRemoved() {
        String code = """
            package test;
            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;
            
             class TestClass {
                private static final Logger logger = LoggerFactory.getLogger(TestClass.class);
                
                 void testMethod() {
                    try {
                        // some code
                    } catch (Exception e) {
                        System.err.println("Error: " + e.getMessage());
                    }
                }
            }
            """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        LexicalPreservingPrinter.setup(cu);

        // Process with LoggerVisitor
        Logger.loggerFields.clear();
        Logger.loggerFields.add("logger");
        cu.findAll(MethodDeclaration.class).forEach(m ->
            m.accept(new Logger.LoggerVisitor(m.findAncestor(TypeDeclaration.class).orElseThrow()), false)
        );

        String result = LexicalPreservingPrinter.print(cu);

        // System.err should be completely removed
        assertFalse(result.contains("System.err"),
            "System.err should be removed");
        assertFalse(result.contains("logger.error("),
            "System.err should be removed, not converted to logger");
    }

    @Test
     void testMultipleLoggerFields() {
        String code = """
            package test;
            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;
            
             class TestClass {
                private static final Logger log = LoggerFactory.getLogger(TestClass.class);
                private static final Logger auditLogger = LoggerFactory.getLogger("audit");
                
                 void testMethod() {
                    log.info("Main log message");
                    auditLogger.info("Audit message");
                    
                    if (log.isDebugEnabled()) {
                        log.debug("Debug message");
                    }
                    
                    if (auditLogger.isInfoEnabled()) {
                        auditLogger.info("Audit info");
                    }
                }
            }
            """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        LexicalPreservingPrinter.setup(cu);

        // Process with LoggerVisitor - simulate both loggers being detected
        Logger.loggerFields.clear();
        Logger.loggerFields.add("log");
        Logger.loggerFields.add("auditLogger");

        cu.findAll(MethodDeclaration.class).forEach(m ->
            m.accept(new Logger.LoggerVisitor(m.findAncestor(TypeDeclaration.class).orElseThrow()), false)
        );

        String result = LexicalPreservingPrinter.print(cu);

        // Both logger utility methods should be preserved
        assertTrue(result.contains("log.isDebugEnabled()"),
            "log.isDebugEnabled() should be preserved");
        assertTrue(result.contains("auditLogger.isInfoEnabled()"),
            "auditLogger.isInfoEnabled() should be preserved");

        // Both logger calls should be changed to debug
        assertTrue(result.contains("log.debug("),
            "log.info should be changed to log.debug");
        assertTrue(result.contains("auditLogger.debug("),
            "auditLogger.info should be changed to auditLogger.debug");
    }
}
