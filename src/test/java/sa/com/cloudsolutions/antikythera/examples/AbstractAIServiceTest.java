package sa.com.cloudsolutions.antikythera.examples;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class AbstractAIServiceTest {

    @ParameterizedTest
    @MethodSource("provideJsonExtractionTestCases")
    void testExtractJson(String response, String expected) {
        String extracted = AbstractAIService.extractJson(response);
        assertEquals(expected, extracted);
    }

    private static Stream<Arguments> provideJsonExtractionTestCases() {
        return Stream.of(
            Arguments.of(
                "Here is the result:\n```json\n[{\"id\": 1}]\n```\nSome footer.",
                "[{\"id\": 1}]"
            ),
            Arguments.of(
                "Attempt 1:\n```json\n[{\"id\": 0}]\n```\nActual response:\n```json\n[{\"id\": 1}]\n```",
                "[{\"id\": 1}]"
            ),
            Arguments.of(
                "The JSON is below:\n[{\"id\": 1}]",
                "[{\"id\": 1}]"
            ),
            Arguments.of(
                "The JSON is below:\n{\"id\": 1}",
                "{\"id\": 1}"
            ),
            Arguments.of(
                "Some text [not json] then {\"id\": 1}",
                "[not json] then {\"id\": 1}"
            ),
            Arguments.of(
                "Mixed content\n{\"a\": 1}\nMore content\n[{\"b\": 2}]",
                "[{\"b\": 2}]"
            ),
            Arguments.of(
                "[{\"c\": 3}] starts at beginning",
                "[{\"c\": 3}] starts at beginning"
            ),
            Arguments.of(
                "Only array [1, 2, 3]",
                "[1, 2, 3]"
            ),
            Arguments.of(
                "Only object {\"x\": 1}",
                "{\"x\": 1}"
            ),
            Arguments.of(
                "Object before array {\"x\": 1} then [1]",
                "{\"x\": 1} then [1]"
            ),
            Arguments.of(
                "Array before object [1] then {\"x\": 1}",
                "[1] then {\"x\": 1}"
            ),
            Arguments.of(
                "```\n[1, 2, 3]\n```",
                "[1, 2, 3]"
            ),
            Arguments.of(
                "```json\n{\"id\": 1}\n```",
                "{\"id\": 1}"
            ),
            Arguments.of(
                "```\n[1, 2]\n```\n```\n{\"id\": 2}\n```",
                "{\"id\": 2}"
            )
        );
    }

    @Test
    void testExtractJson_NoJson_ThrowsException() {
        String response = "No JSON here at all.";
        assertThrows(AIResponseException.class, () -> AbstractAIService.extractJson(response));
    }
}
