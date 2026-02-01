package com.raditha.spring;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import sa.com.cloudsolutions.antikythera.examples.GeminiAIService;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Utility class for AI-powered code generation in migration phases.
 * Provides common functionality for interacting with GeminiAIService.
 */
public class AICodeGenerationHelper {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Builds a standard AI request payload for Gemini API.
     *
     * @param prompt The prompt to send to the AI
     * @return JSON request payload
     */
    public static String buildAIRequest(String prompt) {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        ArrayNode contents = root.putArray("contents");
        ObjectNode content = contents.addObject();
        content.put("role", "user");
        ArrayNode parts = content.putArray("parts");
        ObjectNode part = parts.addObject();
        part.put("text", prompt != null ? prompt : "");

        try {
            return OBJECT_MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to construct AI request JSON", e);
        }
    }

    /**
     * Extracts generated Java code from AI response.
     * Handles responses with or without markdown code fences.
     *
     * @param response The raw API response from GeminiAIService
     * @return Extracted Java code, or null if extraction fails
     * @throws IOException If JSON parsing fails
     */
    public static String extractGeneratedCode(String response) throws IOException {
        JsonNode root = OBJECT_MAPPER.readTree(response);

        JsonNode candidates = root.path("candidates");
        if (candidates.isArray() && !candidates.isEmpty()) {
            String text = candidates.get(0)
                    .path("content")
                    .path("parts")
                    .get(0)
                    .path("text")
                    .asText();

            return removeMarkdownCodeFences(text);
        }

        return null;
    }

    /**
     * Removes markdown code fences from generated code.
     *
     * @param text Text potentially wrapped in markdown code fences
     * @return Clean code without fences
     */
    private static String removeMarkdownCodeFences(String text) {
        if (text == null) {
            return null;
        }

        String cleaned = text;

        // Remove ``` ... ``` fences (handles both ```java and generic ```)
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(cleaned.indexOf('\n') + 1);
            cleaned = cleaned.substring(0, cleaned.lastIndexOf("```"));
        }

        return cleaned.trim();
    }

    /**
     * Creates and configures a GeminiAIService instance.
     * Uses GEMINI_API_KEY environment variable if available.
     *
     * @return Configured GeminiAIService
     * @throws IOException If service initialization fails
     */
    public static GeminiAIService createConfiguredService() throws IOException {
        GeminiAIService aiService = new GeminiAIService();

        Map<String, Object> aiConfig = new HashMap<>();
        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            aiConfig.put("api_key", apiKey);
        }

        aiService.configure(aiConfig);
        return aiService;
    }

    /**
     * Generates code using AI and returns the extracted code.
     * Convenience method that combines service creation, request, and extraction.
     *
     * @param prompt The prompt describing what code to generate
     * @return Generated code, or null if generation fails
     * @throws IOException          If any step fails
     * @throws InterruptedException If the request is interrupted
     */
    public static String generateCode(String prompt) throws IOException, InterruptedException {
        GeminiAIService aiService = createConfiguredService();
        String request = buildAIRequest(prompt);
        String response = aiService.sendApiRequest(request);
        return extractGeneratedCode(response);
    }
}
