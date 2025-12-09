package com.raditha.cleanunit;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ImportMigrator.
 * Tests for bug fix: scope removal should add static imports.
 */
class ImportMigratorTest {

    private final ImportMigrator migrator = new ImportMigrator();

    @Test
    void testConvertAssertionCallsAddsStaticImport() {
        // Given: code with qualified Assert calls but no static import
        String code = """
            import org.junit.Assert;
            
            class Test {
                void test() {
                    Assert.assertEquals(expected, actual);
                }
            }
            """;
        CompilationUnit cu = StaticJavaParser.parse(code);

        // First migrate imports to JUnit 5
        migrator.migrateImports(cu);
        
        // When: convert assertion calls
        boolean modified = migrator.convertAssertionCalls(cu);

        // Then: should add static import
        assertTrue(modified, "Should modify the code");
        assertTrue(cu.toString().contains("assertEquals(expected, actual)"),
                "Should remove scope from assertion call");
        assertTrue(cu.toString().contains("import static org.junit.jupiter.api.Assertions.*"),
                "Should add static import for Assertions");
    }

    @Test
    void testConvertAssumeCallsAddsStaticImport() {
        // Given: code with qualified Assume calls
        String code = """
            import org.junit.Assume;
            
            class Test {
                void test() {
                    Assume.assumeTrue(condition);
                }
            }
            """;
        CompilationUnit cu = StaticJavaParser.parse(code);

        // First migrate imports to JUnit 5
        migrator.migrateImports(cu);
        
        // When: convert assertion calls
        boolean modified = migrator.convertAssertionCalls(cu);

        // Then: should add static import
        assertTrue(modified, "Should modify the code");
        assertTrue(cu.toString().contains("assumeTrue(condition)"),
                "Should remove scope from assume call");
        assertTrue(cu.toString().contains("import static org.junit.jupiter.api.Assumptions.*"),
                "Should add static import for Assumptions");
    }

    @Test
    void testConvertFullyQualifiedAssertionCalls() {
        // Given: code with fully qualified Assert calls
        String code = """
            class Test {
                void test() {
                    org.junit.Assert.assertEquals(expected, actual);
                }
            }
            """;
        CompilationUnit cu = StaticJavaParser.parse(code);

        // When: convert assertion calls
        boolean modified = migrator.convertAssertionCalls(cu);

        // Then: should add static import
        assertTrue(modified, "Should modify the code");
        assertTrue(cu.toString().contains("assertEquals(expected, actual)"),
                "Should remove fully qualified scope");
        assertTrue(cu.toString().contains("import static org.junit.jupiter.api.Assertions.*"),
                "Should add static import for Assertions");
    }

    @Test
    void testDoesNotAddDuplicateStaticImport() {
        // Given: code already has static import
        String code = """
            import static org.junit.jupiter.api.Assertions.*;
            
            class Test {
                void test() {
                    Assert.assertEquals(expected, actual);
                }
            }
            """;
        CompilationUnit cu = StaticJavaParser.parse(code);

        // When: convert assertion calls
        boolean modified = migrator.convertAssertionCalls(cu);

        // Then: should not add duplicate import
        assertTrue(modified, "Should modify the code");
        long importCount = cu.getImports().stream()
                .filter(imp -> imp.getNameAsString().equals("org.junit.jupiter.api.Assertions") && imp.isStatic())
                .count();
        assertEquals(1, importCount, "Should have exactly one static import for Assertions");
    }

    @Test
    void testMigrateImportsConvertsJUnit4ToJUnit5() {
        // Given: JUnit 4 imports
        String code = """
            import org.junit.Test;
            import org.junit.Assert;
            import org.junit.Before;
            
            class Test {
                void test() {
                }
            }
            """;
        CompilationUnit cu = StaticJavaParser.parse(code);

        // When: migrate imports
        boolean modified = migrator.migrateImports(cu);

        // Then: should convert to JUnit 5
        assertTrue(modified, "Should modify the imports");
        assertTrue(cu.toString().contains("org.junit.jupiter.api.Test"),
                "Should convert Test annotation");
        assertTrue(cu.toString().contains("org.junit.jupiter.api.Assertions"),
                "Should convert Assert to Assertions");
        assertTrue(cu.toString().contains("org.junit.jupiter.api.BeforeEach"),
                "Should convert Before to BeforeEach");
    }

    @Test
    void testMigrateStaticImports() {
        // Given: JUnit 4 static imports
        String code = """
            import static org.junit.Assert.*;
            
            class Test {
                void test() {
                    assertEquals(expected, actual);
                }
            }
            """;
        CompilationUnit cu = StaticJavaParser.parse(code);

        // When: migrate imports
        boolean modified = migrator.migrateImports(cu);

        // Then: should convert to JUnit 5 static import
        assertTrue(modified, "Should modify the imports");
        assertTrue(cu.toString().contains("import static org.junit.jupiter.api.Assertions.*"),
                "Should convert static import to JUnit 5");
        assertFalse(cu.toString().contains("org.junit.Assert"),
                "Should not contain JUnit 4 Assert import");
    }
}
