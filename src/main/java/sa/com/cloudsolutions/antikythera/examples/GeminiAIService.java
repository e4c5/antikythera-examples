package sa.com.cloudsolutions.antikythera.examples;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;


import org.jspecify.annotations.NonNull;
import sa.com.cloudsolutions.antikythera.generator.QueryType;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handles all interactions with the Gemini AI service including request
 * formatting,
 * API communication, and response parsing.
 * Provides simple API communication capabilities for query optimization
 * analysis.
 */
public class GeminiAIService extends AbstractAIService {
    public static final String GEMINI_1_5_FLASH = "gemini-1.5-flash";
    public static final String GEMINI_3_FLASH = "gemini-3-flash";
    private static final Map<String, ModelPricing> MODEL_PRICING = Map.ofEntries(
        Map.entry(GEMINI_1_5_FLASH, new ModelPricing(0.075, 0.15, 0.30, 0.60, 0.25)),
        Map.entry("gemini-1.5-pro", new ModelPricing(3.50, 7.00, 10.50, 21.00, 0.25)),
        Map.entry("gemini-2.0-flash", new ModelPricing(0.10, 0.40, 0.25)),
        Map.entry("gemini-2.0-flash-lite", new ModelPricing(0.075, 0.30, 0.25)),
        Map.entry("gemini-2.5-pro", new ModelPricing(1.25, 2.50, 10.00, 15.00, 0.10, 200000)),
        Map.entry("gemini-2.5-flash", new ModelPricing(0.15, 0.30, 1.25, 1.875, 0.25, 200000)),
        Map.entry("gemini-3-pro", new ModelPricing(2.00, 4.00, 12.00, 18.00, 0.10, 200000)),
        Map.entry(GEMINI_3_FLASH, new ModelPricing(0.50, 1.00, 3.00, 4.50, 0.10, 200000)),
        Map.entry("gemini-1.0-pro", new ModelPricing(0.50, 1.50, 0.25))
    );
    public static final String STRING = "string";
    public static final String PARTS = "parts";
    public static final String API_KEY = "api_key";

    public GeminiAIService() throws IOException {
        super();
    }

    /**
     * Gets cache efficiency as a percentage of cached tokens vs total tokens.
     * Returns 0.0 if no tokens were used or no caching occurred.
     */
    public double getCacheEfficiency() {
        if (lastTokenUsage.getTotalTokens() == 0) {
            return 0.0;
        }
        return (double) lastTokenUsage.getCachedContentTokenCount() / lastTokenUsage.getTotalTokens() * 100.0;
    }



    /**
     * Builds the request payload for the Gemini AI API using proper message
     * structure.
     * Separates system instructions from user query data for better API
     * interaction.
     */
    @Override
    protected String buildRequestPayload(QueryBatch batch) throws IOException {
        String userQueryData = buildQueryDataArray(batch);
        return buildGeminiApiRequest(userQueryData);
    }

    /**
     * Builds the Gemini API request with proper message structure.
     * Uses Gemini's contents format with separate parts for system and user
     * content.
     * Enables JSON Mode via responseMimeType to guarantee valid JSON output.
     */
    String buildGeminiApiRequest(String userQueryData) throws IOException {
        // Create root node
        ObjectNode root = objectMapper.createObjectNode();

        // system_instruction
        ObjectNode systemInstruction = root.putObject("system_instruction");
        ArrayNode parts = systemInstruction.putArray(PARTS);
        parts.addObject().put("text", systemPrompt);

        // contents
        ArrayNode contents = root.putArray("contents");
        ObjectNode content = contents.addObject();
        content.put("role", "user");
        ArrayNode contentParts = content.putArray(PARTS);
        contentParts.addObject().put("text", userQueryData);

        // generationConfig
        ObjectNode generationConfig = root.putObject("generationConfig");
        generationConfig.put("responseMimeType", "application/json");

        ObjectNode responseSchema = generationConfig.putObject("responseSchema");
        responseSchema.put("type", "array");

        ObjectNode items = responseSchema.putObject("items");
        items.put("type", "object");

        ObjectNode properties = items.putObject("properties");
        properties.putObject("originalMethod").put("type", STRING);
        properties.putObject(OPTIMIZED_CODE_ELEMENT).put("type", STRING);
        properties.putObject(NOTES).put("type", STRING);

        ArrayNode required = items.putArray("required");
        required.add("originalMethod");
        required.add(OPTIMIZED_CODE_ELEMENT);
        required.add(NOTES);

        return objectMapper.writeValueAsString(root);
    }


    /**
     * Escapes a string for JSON format.
     */
    String escapeJsonString(String str) {
        if (str == null) {
            return "";
        }
        return str.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Sends the API request to Gemini AI service.
     */
    @Override
    protected String sendApiRequest(String payload) throws IOException, InterruptedException {
        return sendApiRequest(payload, 0);
    }

    /**
     * Public method for sending raw API requests.
     * This is for external callers like AICodeGenerationHelper.
     */
    public String sendRawRequest(String payload) throws IOException, InterruptedException {
        return sendApiRequest(payload);
    }

    private String sendApiRequest(String payload, int retryCount) throws IOException, InterruptedException {
        String apiEndpoint = getConfigString("api_endpoint",
                "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent");
        String model = getConfigString("model", GEMINI_3_FLASH);
        String apiKey = getConfigString(API_KEY, null);
        int timeoutSeconds = getConfigInt("timeout_seconds", 90);

        if (retryCount > 0) {
            timeoutSeconds += 30;
            logger.info("Retrying API request with extra 30 seconds timeout (total: {}s)", timeoutSeconds);
        }

        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException(
                    "AI service API key is required. Set GEMINI_API_KEY environment variable or configure ai_service.api_key in generator.yml");
        }

        String url = apiEndpoint.replace("{model}", model) + "?key=" + apiKey;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new IOException(
                        "API request failed with status: " + response.statusCode() + ", body: " + response.body());
            }

            // Extract token usage if available
            extractTokenUsage(response.body());

            return response.body();
        } catch (HttpTimeoutException e) {
            if (retryCount == 0) {
                return sendApiRequest(payload, 1);
            }
            throw e;
        }
    }

    /**
     * Extracts token usage information from the API response.
     */
    void extractTokenUsage(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode usageMetadata = root.path("usageMetadata");

        if (!usageMetadata.isMissingNode()) {
            int inputTokens = usageMetadata.path("promptTokenCount").asInt(0);
            int outputTokens = usageMetadata.path("candidatesTokenCount").asInt(0);
            int totalTokens = usageMetadata.path("totalTokenCount").asInt(inputTokens + outputTokens);
            int cachedContentTokenCount = usageMetadata.path("cachedContentTokenCount").asInt(0);

            String model = getConfigString("model", GEMINI_1_5_FLASH);
            double inputCost = 0;
            double outputCost = 0;

            ModelPricing pricing = MODEL_PRICING.entrySet().stream()
                    .filter(entry -> model.contains(entry.getKey()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);

            if (pricing != null) {
                inputCost = pricing.calculateInputCost(inputTokens, cachedContentTokenCount);
                outputCost = pricing.calculateOutputCost(inputTokens, outputTokens);
            } else {
                // Fallback to a default if model is unknown
                double costPer1kTokens = getConfigDouble("cost_per_1k_tokens", 0.00015);
                inputCost = (inputTokens / 1000.0) * costPer1kTokens;
                outputCost = 0; // Legacy behavior didn't separate
            }

            lastTokenUsage = new TokenUsage(inputTokens, outputTokens, totalTokens, cachedContentTokenCount, inputCost, outputCost);

            boolean trackUsage = getConfigBoolean("track_usage", true);
            if (trackUsage) {
                logger.info("Token usage: {}", lastTokenUsage.getFormattedReport());
                if (cachedContentTokenCount > 0) {
                    logger.info("🚀 Cache hit! {} tokens served from cache, saving API costs", cachedContentTokenCount);
                }
            }
        } else {
            lastTokenUsage = new TokenUsage();
        }
    }

    /**
     * Parses the AI response and converts it to OptimizationIssue objects.
     */
    @Override
    protected List<OptimizationIssue> parseResponse(String responseBody, QueryBatch batch) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode candidates = root.path("candidates");

        if (candidates.isArray() && !candidates.isEmpty()) {
            JsonNode firstCandidate = candidates.get(0);
            JsonNode content = firstCandidate.path("content");
            JsonNode parts = content.path(PARTS);

            if (parts.isArray() && !parts.isEmpty()) {
                String textResponse = parts.get(0).path("text").asText();
                return parseRecommendations(textResponse, batch);
            }
        }

        return new ArrayList<>();
    }

    /**
     * Parses the text response from AI to extract optimization recommendations.
     * Expects a JSON array response format as defined in the new prompt.
     */
    List<OptimizationIssue> parseRecommendations(String textResponse, QueryBatch batch) throws IOException {
        List<OptimizationIssue> issues = new ArrayList<>();

        // Extract JSON from the response (it might be wrapped in markdown code blocks)
        String jsonResponse = extractJsonFromResponse(textResponse);

        if (jsonResponse.trim().isEmpty()) {
            logger.warn("No valid JSON found in AI response");
            return issues;
        }

        // Parse the JSON array
        JsonNode responseArray = objectMapper.readTree(jsonResponse);

        if (!responseArray.isArray()) {
            logger.warn("AI response is not a JSON array as expected");
            return issues;
        }

        // Process each optimization recommendation
        List<RepositoryQuery> queries = batch.getQueries();
        for (int i = 0; i < responseArray.size() && i < queries.size(); i++) {
            JsonNode recommendation = responseArray.get(i);
            RepositoryQuery originalQuery = queries.get(i);

            OptimizationIssue issue = parseOptimizationRecommendation(recommendation, originalQuery);
            issues.add(issue);
        }

        return issues;
    }

    /**
     * Extracts JSON content from the AI response, handling markdown code blocks.
     */
    String extractJsonFromResponse(String response) {
        if (response == null) {
            return null;
        }

        // Look for JSON array patterns
        int jsonStart = response.indexOf('[');
        int jsonEnd = response.lastIndexOf(']');

        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            return response.substring(jsonStart, jsonEnd + 1);
        }

        return extractJsonFromCodeBlocks(response);
    }

    private static @NonNull String extractJsonFromCodeBlocks(String response) {
        // If no array found, try to find JSON in code blocks
        String[] lines = response.split("\n");
        StringBuilder jsonBuilder = new StringBuilder();
        boolean inCodeBlock = false;
        boolean foundJson = false;

        for (String line : lines) {
            if (line.trim().startsWith("```")) {
                inCodeBlock = !inCodeBlock;
                continue;
            }

            if (inCodeBlock || line.trim().startsWith("[") || foundJson) {
                jsonBuilder.append(line).append("\n");
                foundJson = true;
                if (line.trim().endsWith("]")) {
                    break;
                }
            }
        }

        return jsonBuilder.toString().trim();
    }



    /**
     * Validates the configuration to ensure required settings are present.
     */
    @Override
    protected void validateConfig() {
        String apiKey = getConfigString(API_KEY, null);
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException(
                    "AI service API key is required. Set GEMINI_API_KEY environment variable or configure ai_service.api_key in generator.yml");
        }
    }

    /**
     * Gets a string configuration value with fallback to environment variables.
     * Overrides base class to add Gemini-specific environment variable support.
     */
    @Override
    protected String getConfigString(String key, String defaultValue) {
        if (config == null)
            return defaultValue;

        Object value = config.get(key);
        if (value instanceof String str && !str.trim().isEmpty()) {
            return str;
        }

        // Fallback to environment variables
        if (API_KEY.equals(key)) {
            String envValue = System.getenv("GEMINI_API_KEY");
            if (envValue != null && !envValue.trim().isEmpty()) {
                return envValue;
            }
        } else if ("api_endpoint".equals(key)) {
            String envValue = System.getenv("AI_SERVICE_ENDPOINT");
            if (envValue != null && !envValue.trim().isEmpty()) {
                return envValue;
            }
        }

        return defaultValue;
    }


}
