package com.raditha.cleanunit;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AssertionMigrator.
 * Tests for bug fix: two-argument assertions should not be reordered.
 */
class AssertionMigratorTest {

    private final AssertionMigrator migrator = new AssertionMigrator();

    @Test
    void testTwoArgumentAssertEqualsShouldNotBeReordered() {
        // Given: two-argument assertEquals without message
        String code = """
            class Test {
                void test() {
                    assertEquals("expected", actual);
                }
            }
            """;
        CompilationUnit cu = StaticJavaParser.parse(code);

        // When
        boolean modified = migrator.migrate(cu);

        // Then: should NOT be modified (no message to reorder)
        assertFalse(modified, "Two-argument assertEquals should not be reordered");
        assertTrue(cu.toString().contains("assertEquals(\"expected\", actual)"),
                "Two-argument assertEquals should remain unchanged");
    }

    @Test
    void testThreeArgumentAssertEqualsShouldBeReordered() {
        // Given: three-argument assertEquals with message
        String code = """
            class Test {
                void test() {
                    assertEquals("message", expected, actual);
                }
            }
            """;
        CompilationUnit cu = StaticJavaParser.parse(code);

        // When
        boolean modified = migrator.migrate(cu);

        // Then: should be modified (message moved to end)
        assertTrue(modified, "Three-argument assertEquals should be reordered");
        assertTrue(cu.toString().contains("assertEquals(expected, actual, \"message\")"),
                "Message should be moved to end");
    }

    @Test
    void testTwoArgumentAssertTrueShouldBeReordered() {
        // Given: two-argument assertTrue with message
        String code = """
            class Test {
                void test() {
                    assertTrue("message", condition);
                }
            }
            """;
        CompilationUnit cu = StaticJavaParser.parse(code);

        // When
        boolean modified = migrator.migrate(cu);

        // Then: should be modified (assertTrue only needs 2 args for message version)
        assertTrue(modified, "Two-argument assertTrue should be reordered");
        assertTrue(cu.toString().contains("assertTrue(condition, \"message\")"),
                "Message should be moved to end");
    }

    @Test
    void testTwoArgumentAssertNotEqualsShouldNotBeReordered() {
        // Given: two-argument assertNotEquals without message
        String code = """
            class Test {
                void test() {
                    assertNotEquals("unexpected", actual);
                }
            }
            """;
        CompilationUnit cu = StaticJavaParser.parse(code);

        // When
        boolean modified = migrator.migrate(cu);

        // Then: should NOT be modified
        assertFalse(modified, "Two-argument assertNotEquals should not be reordered");
        assertTrue(cu.toString().contains("assertNotEquals(\"unexpected\", actual)"),
                "Two-argument assertNotEquals should remain unchanged");
    }

    @Test
    void testThreeArgumentAssertNotEqualsShouldBeReordered() {
        // Given: three-argument assertNotEquals with message
        String code = """
            class Test {
                void test() {
                    assertNotEquals("message", unexpected, actual);
                }
            }
            """;
        CompilationUnit cu = StaticJavaParser.parse(code);

        // When
        boolean modified = migrator.migrate(cu);

        // Then: should be modified
        assertTrue(modified, "Three-argument assertNotEquals should be reordered");
        assertTrue(cu.toString().contains("assertNotEquals(unexpected, actual, \"message\")"),
                "Message should be moved to end");
    }

    @Test
    void testTwoArgumentAssertSameShouldNotBeReordered() {
        // Given: two-argument assertSame without message
        String code = """
            class Test {
                void test() {
                    assertSame(expected, actual);
                }
            }
            """;
        CompilationUnit cu = StaticJavaParser.parse(code);

        // When
        boolean modified = migrator.migrate(cu);

        // Then: should NOT be modified
        assertFalse(modified, "Two-argument assertSame should not be reordered");
        assertTrue(cu.toString().contains("assertSame(expected, actual)"),
                "Two-argument assertSame should remain unchanged");
    }

    @Test
    void testTwoArgumentAssertArrayEqualsShouldNotBeReordered() {
        // Given: two-argument assertArrayEquals without message
        String code = """
            class Test {
                void test() {
                    assertArrayEquals(expected, actual);
                }
            }
            """;
        CompilationUnit cu = StaticJavaParser.parse(code);

        // When
        boolean modified = migrator.migrate(cu);

        // Then: should NOT be modified
        assertFalse(modified, "Two-argument assertArrayEquals should not be reordered");
        assertTrue(cu.toString().contains("assertArrayEquals(expected, actual)"),
                "Two-argument assertArrayEquals should remain unchanged");
    }
}
