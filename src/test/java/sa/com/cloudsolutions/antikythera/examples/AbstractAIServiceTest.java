package sa.com.cloudsolutions.antikythera.examples;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for AbstractAIService static utility methods.
 */
class AbstractAIServiceTest {

    @Test
    void testExtractJsonFromCodeBlocks_WithMarkdownCodeBlock() {
        String response = """
                Here's the JSON:
                ```json
                {
                  "key": "value",
                  "number": 42
                }
                ```
                """;

        String result = AbstractAIService.extractJsonFromCodeBlocks(response);
        
        assertTrue(result.contains("\"key\": \"value\""));
        assertTrue(result.contains("\"number\": 42"));
        assertTrue(result.startsWith("{"));
        assertTrue(result.endsWith("}"));
    }

    /**
     * Provides test cases for code block extraction scenarios.
     */
    static Stream<Arguments> codeBlockTestCases() {
        return Stream.of(
            Arguments.of(
                "Plain code block",
                """
                ```
                {
                  "name": "test",
                  "value": 123
                }
                ```
                """,
                "\"name\": \"test\"",
                "\"value\": 123"
            ),
            Arguments.of(
                "Array in code block",
                "```json\n[\n  {\"id\": 1},\n  {\"id\": 2}\n]\n```\n",
                "\"id\": 1",
                "\"id\": 2"
            ),
            Arguments.of(
                "Nested JSON in code block",
                """
                ```
                {
                  "outer": {
                    "inner": {
                      "value": 42
                    }
                  }
                }
                ```
                """,
                "\"outer\"",
                "\"inner\""
            )
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("codeBlockTestCases")
    void testExtractJsonFromCodeBlocks_WithCodeBlocks(String testName, String response, 
                                                       String expectedContent1, String expectedContent2) {
        String result = AbstractAIService.extractJsonFromCodeBlocks(response);
        
        assertFalse(result.isEmpty(), "Result should not be empty");
        assertTrue(result.contains(expectedContent1), "Should contain: " + expectedContent1);
        assertTrue(result.contains(expectedContent2), "Should contain: " + expectedContent2);
    }

    @Test
    void testExtractJsonFromCodeBlocks_WithoutCodeBlock() {
        String response = """
                {
                  "direct": "json",
                  "no": "codeblock"
                }
                """;

        String result = AbstractAIService.extractJsonFromCodeBlocks(response);
        
        assertTrue(result.contains("\"direct\": \"json\""));
        assertTrue(result.contains("\"no\": \"codeblock\""));
    }

    @Test
    void testExtractJsonFromCodeBlocks_WithArrayWithoutCodeBlock() {
        String response = """
                [
                  {"item": 1},
                  {"item": 2}
                ]
                """;

        String result = AbstractAIService.extractJsonFromCodeBlocks(response);
        
        assertTrue(result.startsWith("["));
        assertTrue(result.contains("\"item\": 1"));
    }

    /**
     * Provides test cases for JSON with surrounding text.
     */
    static Stream<Arguments> textAroundJsonTestCases() {
        return Stream.of(
            Arguments.of(
                "Text before JSON",
                """
                Some explanatory text here.
                {
                  "data": "value"
                }
                """,
                "\"data\": \"value\"",
                "Some explanatory text",
                false
            ),
            Arguments.of(
                "Text after JSON",
                """
                {
                  "data": "value"
                }
                Some text after the JSON should be ignored.
                """,
                "\"data\": \"value\"",
                "Some text after",
                false
            ),
            Arguments.of(
                "Mixed content with code block",
                """
                Here's some explanation.
                
                ```json
                {
                  "status": "success",
                  "count": 10
                }
                ```
                
                And here's more text after.
                """,
                "\"status\": \"success\"",
                "explanation",
                false
            )
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("textAroundJsonTestCases")
    void testExtractJsonFromCodeBlocks_WithSurroundingText(String testName, String response,
                                                            String expectedContent, String unexpectedContent,
                                                            boolean shouldContainUnexpected) {
        String result = AbstractAIService.extractJsonFromCodeBlocks(response);
        
        assertTrue(result.contains(expectedContent), "Should contain: " + expectedContent);
        assertEquals(shouldContainUnexpected, result.contains(unexpectedContent),
                "Unexpected content handling for: " + unexpectedContent);
    }

    @Test
    void testExtractJsonFromCodeBlocks_WithEmptyString() {
        String response = "";

        String result = AbstractAIService.extractJsonFromCodeBlocks(response);
        
        assertEquals("", result);
    }

    @Test
    void testExtractJsonFromCodeBlocks_WithNoJson() {
        String response = """
                This is just plain text.
                No JSON here at all.
                Just some random content.
                """;

        String result = AbstractAIService.extractJsonFromCodeBlocks(response);
        
        assertEquals("", result);
    }

    @Test
    void testExtractJsonFromCodeBlocks_WithMultipleCodeBlocks() {
        String response = """
                First block:
                ```
                {"first": 1}
                ```
                Second block:
                ```
                {"second": 2}
                ```
                """;

        String result = AbstractAIService.extractJsonFromCodeBlocks(response);
        
        // Should extract the first JSON found
        assertTrue(result.contains("\"first\": 1"));
    }

    @Test
    void testExtractJsonFromCodeBlocks_WithWhitespaceAroundBraces() {
        String response = """
                   {
                     "key": "value"
                   }
                """;

        String result = AbstractAIService.extractJsonFromCodeBlocks(response);
        
        assertTrue(result.contains("\"key\": \"value\""));
    }

    @Test
    void testExtractJsonFromCodeBlocks_WithSingleLineJson() {
        String response = """
                ```
                {"compact": "json", "single": "line"}
                ```
                """;

        String result = AbstractAIService.extractJsonFromCodeBlocks(response);
        
        assertTrue(result.contains("\"compact\": \"json\""));
        assertTrue(result.contains("\"single\": \"line\""));
    }

    @Test
    void testExtractJsonFromCodeBlocks_WithArrayOfPrimitives() {
        String response = """
                ```json
                [1, 2, 3, 4, 5]
                ```
                """;

        String result = AbstractAIService.extractJsonFromCodeBlocks(response);
        
        assertTrue(result.startsWith("["));
        assertTrue(result.contains("1, 2, 3, 4, 5"));
        assertTrue(result.endsWith("]"));
    }

    @Test
    void testExtractJsonFromCodeBlocks_StopsAtFirstClosingBrace() {
        String response = """
                {
                  "first": "object"
                }
                {
                  "second": "object"
                }
                """;

        String result = AbstractAIService.extractJsonFromCodeBlocks(response);
        
        // Should stop at the first closing brace
        assertTrue(result.contains("\"first\": \"object\""));
        assertFalse(result.contains("\"second\": \"object\""));
    }
}
