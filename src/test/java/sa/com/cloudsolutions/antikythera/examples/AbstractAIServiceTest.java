package sa.com.cloudsolutions.antikythera.examples;

import org.junit.jupiter.api.Test;

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

    @Test
    void testExtractJsonFromCodeBlocks_WithPlainCodeBlock() {
        String response = """
                ```
                {
                  "name": "test",
                  "value": 123
                }
                ```
                """;

        String result = AbstractAIService.extractJsonFromCodeBlocks(response);
        
        assertTrue(result.contains("\"name\": \"test\""));
        assertTrue(result.contains("\"value\": 123"));
    }

    @Test
    void testExtractJsonFromCodeBlocks_WithArrayInCodeBlock() {
        String response = "```json\n[\n  {\"id\": 1},\n  {\"id\": 2}\n]\n```\n";

        String result = AbstractAIService.extractJsonFromCodeBlocks(response);
        
        // The method should extract the JSON array content
        assertFalse(result.isEmpty(), "Result should not be empty");
        assertTrue(result.contains("\"id\": 1"), "Should contain first object");
        assertTrue(result.contains("\"id\": 2"), "Should contain second object");
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

    @Test
    void testExtractJsonFromCodeBlocks_WithTextBeforeJson() {
        String response = """
                Some explanatory text here.
                {
                  "data": "value"
                }
                """;

        String result = AbstractAIService.extractJsonFromCodeBlocks(response);
        
        assertTrue(result.contains("\"data\": \"value\""));
        assertFalse(result.contains("Some explanatory text"));
    }

    @Test
    void testExtractJsonFromCodeBlocks_WithTextAfterJson() {
        String response = """
                {
                  "data": "value"
                }
                Some text after the JSON should be ignored.
                """;

        String result = AbstractAIService.extractJsonFromCodeBlocks(response);
        
        assertTrue(result.contains("\"data\": \"value\""));
        assertFalse(result.contains("Some text after"));
    }

    @Test
    void testExtractJsonFromCodeBlocks_WithNestedJson() {
        String response = """
                ```
                {
                  "outer": {
                    "inner": {
                      "value": 42
                    }
                  }
                }
                ```
                """;

        String result = AbstractAIService.extractJsonFromCodeBlocks(response);
        
        assertTrue(result.contains("\"outer\""));
        assertTrue(result.contains("\"inner\""));
        assertTrue(result.contains("\"value\": 42"));
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
    void testExtractJsonFromCodeBlocks_WithMixedContent() {
        String response = """
                Here's some explanation.
                
                ```json
                {
                  "status": "success",
                  "count": 10
                }
                ```
                
                And here's more text after.
                """;

        String result = AbstractAIService.extractJsonFromCodeBlocks(response);
        
        assertTrue(result.contains("\"status\": \"success\""));
        assertTrue(result.contains("\"count\": 10"));
        assertFalse(result.contains("explanation"));
        assertFalse(result.contains("more text"));
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
