package com.raditha.cleanunit;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AssertionMigrator.
 * Tests for bug fix: two-argument assertions should not be reordered.
 */
class AssertionMigratorTest {

    private final AssertionMigrator migrator = new AssertionMigrator();

    /**
     * Provides test cases for two-argument assertions that should NOT be reordered.
     * These assertions have expected/actual parameters, not a message.
     */
    static Stream<Arguments> twoArgumentAssertionsThatShouldNotBeReordered() {
        return Stream.of(
                Arguments.of("assertEquals", "assertEquals(\"expected\", actual)", "assertEquals(\"expected\", actual)"),
                Arguments.of("assertNotEquals", "assertNotEquals(\"unexpected\", actual)", "assertNotEquals(\"unexpected\", actual)"),
                Arguments.of("assertSame", "assertSame(expected, actual)", "assertSame(expected, actual)"),
                Arguments.of("assertArrayEquals", "assertArrayEquals(expected, actual)", "assertArrayEquals(expected, actual)")
        );
    }

    @ParameterizedTest(name = "Two-argument {0} should not be reordered")
    @MethodSource("twoArgumentAssertionsThatShouldNotBeReordered")
    void testTwoArgumentAssertionsShouldNotBeReordered(String assertionName, String inputAssertion, String expectedAssertion) {
        // Given: two-argument assertion without message
        String code = """
            class Test {
                void test() {
                    %s;
                }
            }
            """.formatted(inputAssertion);
        CompilationUnit cu = StaticJavaParser.parse(code);

        // When
        boolean modified = migrator.migrate(cu);

        // Then: should NOT be modified (no message to reorder)
        assertFalse(modified, "Two-argument " + assertionName + " should not be reordered");
        assertTrue(cu.toString().contains(expectedAssertion),
                "Two-argument " + assertionName + " should remain unchanged");
    }

    /**
     * Provides test cases for three-argument assertions that SHOULD be reordered.
     * The message (first argument) should be moved to the end.
     */
    static Stream<Arguments> threeArgumentAssertionsThatShouldBeReordered() {
        return Stream.of(
                Arguments.of("assertEquals",
                        "assertEquals(\"message\", expected, actual)",
                        "assertEquals(expected, actual, \"message\")"),
                Arguments.of("assertNotEquals",
                        "assertNotEquals(\"message\", unexpected, actual)",
                        "assertNotEquals(unexpected, actual, \"message\")")
        );
    }

    @ParameterizedTest(name = "Three-argument {0} should be reordered")
    @MethodSource("threeArgumentAssertionsThatShouldBeReordered")
    void testThreeArgumentAssertionsShouldBeReordered(String assertionName, String inputAssertion, String expectedAssertion) {
        // Given: three-argument assertion with message
        String code = """
            class Test {
                void test() {
                    %s;
                }
            }
            """.formatted(inputAssertion);
        CompilationUnit cu = StaticJavaParser.parse(code);

        // When
        boolean modified = migrator.migrate(cu);

        // Then: should be modified (message moved to end)
        assertTrue(modified, "Three-argument " + assertionName + " should be reordered");
        assertTrue(cu.toString().contains(expectedAssertion),
                "Message should be moved to end for " + assertionName);
    }

    @Test
    void testTwoArgumentAssertTrueShouldBeReordered() {
        // Given: two-argument assertTrue with message
        // Note: assertTrue is special because it only takes one boolean argument,
        // so a two-argument version must have a message
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
}
