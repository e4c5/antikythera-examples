package com.raditha.spring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class AICodeGenerationHelperTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void testBuildAIRequest() throws Exception {
        String prompt = "Create a Java class named Test";
        String jsonRequest = AICodeGenerationHelper.buildAIRequest(prompt);

        assertNotNull(jsonRequest);

        // Verify it is valid JSON
        JsonNode root = mapper.readTree(jsonRequest);
        assertTrue(root.has("contents"));
        assertTrue(root.get("contents").isArray());
        assertEquals("user", root.get("contents").get(0).get("role").asText());
        assertEquals(prompt, root.get("contents").get(0).get("parts").get(0).get("text").asText());
    }

    @Test
    public void testBuildAIRequestWithSpecialCharacters() throws Exception {
        String prompt = "Test with \"quotes\" and \n newlines and \\ backslashes";
        String jsonRequest = AICodeGenerationHelper.buildAIRequest(prompt);

        // Verify it is valid JSON
        JsonNode root = mapper.readTree(jsonRequest);
        assertEquals(prompt, root.get("contents").get(0).get("parts").get(0).get("text").asText());
    }

    @Test
    public void testBuildAIRequestWithNull() throws Exception {
        String jsonRequest = AICodeGenerationHelper.buildAIRequest(null);

        JsonNode root = mapper.readTree(jsonRequest);
        assertEquals("", root.get("contents").get(0).get("parts").get(0).get("text").asText());
    }
}
