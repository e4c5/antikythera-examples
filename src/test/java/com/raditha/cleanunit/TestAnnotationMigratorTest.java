package com.raditha.cleanunit;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TestAnnotationMigrator} covering:
 * - @Test(expected=...) → assertThrows wrapping
 * - cleanBodyForAssertThrows try/catch unwrap behavior
 * - @Test(timeout=...) → @Timeout annotation
 */
class TestAnnotationMigratorTest {

    private final TestAnnotationMigrator migrator = new TestAnnotationMigrator();

    private MethodDeclaration firstMethodOf(CompilationUnit cu) {
        Optional<ClassOrInterfaceDeclaration> clazz = cu.findFirst(ClassOrInterfaceDeclaration.class);
        assertTrue(clazz.isPresent(), "Parsed class not found");
        Optional<MethodDeclaration> method = clazz.get().getMethods().stream().findFirst();
        assertTrue(method.isPresent(), "Parsed method not found");
        return method.get();
    }

    @Test
    void convertsExpectedToAssertThrows_simpleBody() {
        String code = """
                import org.junit.Test;
                class T {
                    @Test(expected = IllegalArgumentException.class)
                    void m() {
                        doSomething();
                    }
                }
                """;
        CompilationUnit cu = StaticJavaParser.parse(code);
        MethodDeclaration method = firstMethodOf(cu);

        boolean modified = migrator.migrateTestAnnotation(method);

        assertTrue(modified, "Method should be modified");
        String out = cu.toString();
        assertTrue(out.contains("assertThrows(IllegalArgumentException.class"), "Should wrap with assertThrows");
        assertTrue(out.contains("() ->"), "Should create a lambda");
        assertTrue(out.contains("doSomething();"), "Original body should remain inside lambda");
        assertFalse(out.contains("expected ="), "@Test should not contain expected param after migration");
    }

    @Test
    void unwrapsTryCatchThatRethrows_intoLambdaBody() {
        String code = """
                import org.junit.Test;
                class T {
                    @Test(expected = IllegalArgumentException.class)
                    void m() {
                        try {
                            run();
                        } catch (IllegalArgumentException ex) {
                            someAssert();
                            throw ex;
                        }
                    }
                }
                """;
        CompilationUnit cu = StaticJavaParser.parse(code);
        MethodDeclaration method = firstMethodOf(cu);

        boolean modified = migrator.migrateTestAnnotation(method);

        assertTrue(modified, "Method should be modified");
        String out = cu.toString();
        // Should be unwrapped so that only run(); remains in lambda body
        assertTrue(out.contains("assertThrows(IllegalArgumentException.class"));
        assertTrue(out.contains("() ->"));
        assertTrue(out.contains("run();"), "run() should be inside lambda body");
        assertFalse(out.contains("catch (IllegalArgumentException"), "try/catch wrapper should be removed");
    }

    @Test
    void doesNotUnwrapWhenFinallyPresent() {
        String code = """
                import org.junit.Test;
                class T {
                    @Test(expected = IllegalArgumentException.class)
                    void m() {
                        try {
                            run();
                        } catch (IllegalArgumentException ex) {
                            throw ex;
                        } finally {
                            cleanup();
                        }
                    }
                }
                """;
        CompilationUnit cu = StaticJavaParser.parse(code);
        MethodDeclaration method = firstMethodOf(cu);

        boolean modified = migrator.migrateTestAnnotation(method);

        assertTrue(modified, "Method should be modified");
        String out = cu.toString();
        // Still wrapped with assertThrows but try/finally remains in the lambda body
        assertTrue(out.contains("assertThrows(IllegalArgumentException.class"));
        assertTrue(out.contains("finally"), "Finally block should remain");
    }

    @Test
    void unwrapsMultiCatchThatIncludesExpected() {
        String code = """
                import org.junit.Test;
                class T {
                    @Test(expected = IllegalArgumentException.class)
                    void m() {
                        try {
                            run();
                        } catch (IllegalArgumentException | RuntimeException ex) {
                            throw ex;
                        }
                    }
                }
                """;
        CompilationUnit cu = StaticJavaParser.parse(code);
        MethodDeclaration method = firstMethodOf(cu);

        boolean modified = migrator.migrateTestAnnotation(method);

        assertTrue(modified, "Method should be modified");
        String out = cu.toString();
        assertTrue(out.contains("assertThrows(IllegalArgumentException.class"));
        assertTrue(out.contains("run();"), "run() should be inside lambda body after unwrap");
        assertFalse(out.contains("catch (IllegalArgumentException | RuntimeException"), "multi-catch should be removed after unwrap");
    }

    @Test
    void convertsTimeoutToTimeoutAnnotation() {
        String code = """
                import org.junit.Test;
                class T {
                    @Test(timeout = 500L)
                    void m() {
                        doWork();
                    }
                }
                """;
        CompilationUnit cu = StaticJavaParser.parse(code);
        MethodDeclaration method = firstMethodOf(cu);

        boolean modified = migrator.migrateTestAnnotation(method);

        assertTrue(modified, "Method should be modified");
        String out = cu.toString();
        assertTrue(out.contains("@Timeout"), "Should add @Timeout annotation");
        assertTrue(out.contains("unit = TimeUnit.MILLISECONDS"), "Should specify MILLISECONDS unit");
        assertFalse(out.contains("timeout ="), "@Test should not contain timeout param after migration");
    }
}
