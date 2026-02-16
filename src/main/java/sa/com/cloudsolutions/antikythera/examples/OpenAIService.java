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
 * OpenAI implementation of the AI service for query optimization.
 * Uses OpenAI's Chat Completions API with GPT models.
 */
public class OpenAIService extends AbstractAIService {
    
    private static final Map<String, ModelPricing> MODEL_PRICING = Map.ofEntries(
        Map.entry("gpt-4o", new ModelPricing(2.50, 10.00, 0.25)),
        Map.entry("gpt-4o-mini", new ModelPricing(0.150, 0.600, 0.25)),
        Map.entry("gpt-4-turbo", new ModelPricing(10.00, 30.00, 0.25)),
        Map.entry("gpt-4", new ModelPricing(30.00, 60.00, 0.25)),
        Map.entry("gpt-3.5-turbo", new ModelPricing(0.50, 1.50, 0.25))
    );
    
    public static final String API_KEY = "api_key";

    public OpenAIService() throws IOException {
        super();
    }

    /**
     * Builds the request payload for OpenAI's Chat Completions API.
     */
    @Override
    protected String buildRequestPayload(QueryBatch batch) throws IOException {
        String userContent = buildQueryDataArray(batch);
        return buildOpenAIRequest(userContent);
    }

    /**
     * Builds the OpenAI Chat Completions API request structure.
     * Separated from buildRequestPayload to follow Single Responsibility Principle.
     *
     * @param userContent The query data as JSON string
     * @return Complete OpenAI API request as JSON string
     * @throws IOException if JSON serialization fails
     */
    private String buildOpenAIRequest(String userContent) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        
        String model = getConfigString("model", "gpt-4o");
        root.put("model", model);
        
        // Messages array
        ArrayNode messages = root.putArray("messages");
        
        // System message
        ObjectNode systemMessage = messages.addObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", systemPrompt);
        
        // User message
        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.put("content", userContent);
        
        // Response format - request JSON mode
        ObjectNode responseFormat = root.putObject("response_format");
        responseFormat.put("type", "json_object");
        
        return objectMapper.writeValueAsString(root);
    }

    /**
     * Sends the API request to OpenAI.
     */
    @Override
    protected String sendApiRequest(String payload) throws IOException, InterruptedException {
        return sendApiRequest(payload, 0);
    }

    private String sendApiRequest(String payload, int retryCount) throws IOException, InterruptedException {
        String apiEndpoint = getConfigString("api_endpoint", "https://api.openai.com/v1/chat/completions");
        String apiKey = getConfigString(API_KEY, null);
        int timeoutSeconds = getConfigInt("timeout_seconds", 90);

        if (retryCount > 0) {
            timeoutSeconds += 30;
            logger.info("Retrying API request with extra 30 seconds timeout (total: {}s)", timeoutSeconds);
        }

        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException(
                    "OpenAI API key is required. Set OPENAI_API_KEY environment variable or configure ai_service.api_key in generator.yml");
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiEndpoint))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
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
     * Parses the OpenAI response and converts it to OptimizationIssue objects.
     */
    @Override
    protected List<OptimizationIssue> parseResponse(String responseBody, QueryBatch batch) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode choices = root.path("choices");

        if (choices.isArray() && !choices.isEmpty()) {
            JsonNode firstChoice = choices.get(0);
            JsonNode message = firstChoice.path("message");
            String content = message.path("content").asText();
            
            return parseRecommendations(content, batch);
        }

        return new ArrayList<>();
    }

    /**
     * Parses the text response from OpenAI to extract optimization recommendations.
     */
    private List<OptimizationIssue> parseRecommendations(String textResponse, QueryBatch batch) throws IOException {
        List<OptimizationIssue> issues = new ArrayList<>();

        // Extract JSON from the response
        String jsonResponse = extractJsonFromResponse(textResponse);

        if (jsonResponse.trim().isEmpty()) {
            logger.warn("No valid JSON found in OpenAI response");
            return issues;
        }

        // Parse the JSON - OpenAI returns an object with an array, not a direct array
        JsonNode responseNode = objectMapper.readTree(jsonResponse);
        JsonNode responseArray = responseNode;
        
        // If the response is wrapped in an object, try to find the array
        if (responseNode.isObject()) {
            // Look for common array field names
            if (responseNode.has("recommendations")) {
                responseArray = responseNode.get("recommendations");
            } else if (responseNode.has("results")) {
                responseArray = responseNode.get("results");
            } else if (responseNode.has("data")) {
                responseArray = responseNode.get("data");
            } else {
                // Try to find the first array field
                responseArray = responseNode.fields().next().getValue();
            }
        }

        if (!responseArray.isArray()) {
            logger.warn("OpenAI response does not contain a JSON array as expected");
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
     * Extracts token usage information from the OpenAI API response.
     */
    private void extractTokenUsage(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode usage = root.path("usage");

        if (!usage.isMissingNode()) {
            int inputTokens = usage.path("prompt_tokens").asInt(0);
            int outputTokens = usage.path("completion_tokens").asInt(0);
            int totalTokens = usage.path("total_tokens").asInt(inputTokens + outputTokens);

            String model = getConfigString("model", "gpt-4o");
            double inputCost = 0;
            double outputCost = 0;

            ModelPricing pricing = MODEL_PRICING.entrySet().stream()
                    .filter(entry -> model.contains(entry.getKey()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);

            if (pricing != null) {
                inputCost = pricing.calculateInputCost(inputTokens, 0);
                outputCost = pricing.calculateOutputCost(inputTokens, outputTokens);
            } else {
                // Fallback to a default if model is unknown
                double costPer1kTokens = getConfigDouble("cost_per_1k_tokens", 0.0015);
                inputCost = (inputTokens / 1000.0) * costPer1kTokens;
                outputCost = (outputTokens / 1000.0) * costPer1kTokens * 2; // Output typically costs 2x
            }

            lastTokenUsage = new TokenUsage(inputTokens, outputTokens, totalTokens, 0, inputCost, outputCost);

            boolean trackUsage = getConfigBoolean("track_usage", true);
            if (trackUsage) {
                logger.info("Token usage: {}", lastTokenUsage.getFormattedReport());
            }
        } else {
            lastTokenUsage = new TokenUsage();
        }
    }



    /**
     * Extracts JSON content from the AI response, handling markdown code blocks.
     */
    private String extractJsonFromResponse(String response) {
        if (response == null) {
            return "";
        }

        // Look for JSON object or array patterns
        int jsonStart = Math.max(response.indexOf('{'), response.indexOf('['));
        int jsonEnd = Math.max(response.lastIndexOf('}'), response.lastIndexOf(']'));

        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            return response.substring(jsonStart, jsonEnd + 1);
        }

        return extractJsonFromCodeBlocks(response);
    }

    private static @NonNull String extractJsonFromCodeBlocks(String response) {
        // If no JSON found, try to find JSON in code blocks
        String[] lines = response.split("\n");
        StringBuilder jsonBuilder = new StringBuilder();
        boolean inCodeBlock = false;
        boolean foundJson = false;

        for (String line : lines) {
            if (line.trim().startsWith("```")) {
                inCodeBlock = !inCodeBlock;
                continue;
            }

            if (inCodeBlock || line.trim().startsWith("{") || line.trim().startsWith("[") || foundJson) {
                jsonBuilder.append(line).append("\n");
                foundJson = true;
                if (line.trim().endsWith("}") || line.trim().endsWith("]")) {
                    break;
                }
            }
        }

        return jsonBuilder.toString().trim();
    }

    /**
     * Validates the configuration to ensure required settings are present.
     * Overrides base class for OpenAI-specific validation.
     */
    @Override
    protected void validateConfig() {
        String apiKey = getConfigString(API_KEY, null);
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException(
                    "OpenAI API key is required. Set OPENAI_API_KEY environment variable or configure ai_service.api_key in generator.yml");
        }
    }

    /**
     * Gets a string configuration value with fallback to environment variables.
     * Overrides base class to add OpenAI-specific environment variable support.
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
            String envValue = System.getenv("OPENAI_API_KEY");
            if (envValue != null && !envValue.trim().isEmpty()) {
                return envValue;
            }
        } else if ("api_endpoint".equals(key)) {
            String envValue = System.getenv("OPENAI_API_ENDPOINT");
            if (envValue != null && !envValue.trim().isEmpty()) {
                return envValue;
            }
        }

        return defaultValue;
    }
}
