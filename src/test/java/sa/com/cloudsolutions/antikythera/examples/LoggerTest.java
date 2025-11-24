package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class to verify Logger utility behavior
 */
public class LoggerTest {

    @Test
    public void testIsDebugEnabledNotModified() {
        String code = """
            package test;
            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;
            
            public class TestClass {
                private static final Logger logger = LoggerFactory.getLogger(TestClass.class);
                
                public void testMethod() {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Debug message");
                    }
                }
            }
            """;
        
        CompilationUnit cu = StaticJavaParser.parse(code);
        LexicalPreservingPrinter.setup(cu);
        
        // Process with LoggerVisitor
        Logger.loggerField = "logger";
        cu.findAll(MethodDeclaration.class).forEach(m -> 
            m.accept(new Logger.LoggerVisitor(), false)
        );
        
        String result = LexicalPreservingPrinter.print(cu);
        
        // Verify isDebugEnabled() is not changed
        assertTrue(result.contains("isDebugEnabled()"), 
            "isDebugEnabled() should not be modified");
    }
    
    @Test
    public void testEmptyForEachRemoved() {
        String code = """
            package test;
            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;
            import java.util.List;
            
            public class TestClass {
                private static final Logger logger = LoggerFactory.getLogger(TestClass.class);
                
                public void testMethod(List<String> items) {
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
        Logger.loggerField = "logger";
        cu.findAll(MethodDeclaration.class).forEach(m -> {
            m.accept(new Logger.LoggerVisitor(), false);
            m.accept(new Logger.EmptyForEachRemover(), null);
        });
        
        String result = LexicalPreservingPrinter.print(cu);
        
        // The forEach should be removed since logger calls in loops are removed
        // Note: This test validates the behavior - empty forEach statements should be cleaned up
        assertFalse(result.contains("forEach(item -> {});"), 
            "Empty forEach should be removed");
    }
    
    @Test
    public void testLoggerInCatchBlockChangedToError() {
        String code = """
            package test;
            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;
            
            public class TestClass {
                private static final Logger logger = LoggerFactory.getLogger(TestClass.class);
                
                public void testMethod() {
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
        Logger.loggerField = "logger";
        cu.findAll(MethodDeclaration.class).forEach(m -> 
            m.accept(new Logger.LoggerVisitor(), false)
        );
        
        String result = LexicalPreservingPrinter.print(cu);
        
        // Logger in catch block should be changed to error
        assertTrue(result.contains("logger.error("), 
            "Logger in catch block should be changed to error level");
    }
}

