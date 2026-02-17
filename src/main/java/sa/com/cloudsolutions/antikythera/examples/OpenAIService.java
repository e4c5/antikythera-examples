package sa.com.cloudsolutions.antikythera.examples;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI implementation of the AI service for query optimization.
 * Uses OpenAI's Chat Completions API with GPT models.
 */
public class OpenAIService extends AbstractAIService {


    public static final String GPT_4_O = "gpt-4o";
    
    /**
     * OpenAI model pricing (per 1M tokens).
     * Prices verified as of February 2026.
     * Note: gpt-4o-mini is the recommended budget option.
     * 
     * IMPORTANT: Using LinkedHashMap to ensure deterministic iteration order.
     * Entries are ordered from most specific to least specific (longest keys first)
     * to ensure correct matching with model.contains() logic.
     */
    private static final Map<String, ModelPricing> MODEL_PRICING;
    
    static {
        MODEL_PRICING = new LinkedHashMap<>();
        MODEL_PRICING.put("gpt-4o-mini", new ModelPricing(0.150, 0.600, 0.25));       // Most specific - check first
        MODEL_PRICING.put("gpt-4-turbo", new ModelPricing(10.00, 30.00, 0.25));       // More specific than gpt-4
        MODEL_PRICING.put(GPT_4_O, new ModelPricing(2.50, 10.00, 0.25));              // Current flagship model
        MODEL_PRICING.put("gpt-4", new ModelPricing(30.00, 60.00, 0.25));             // Least specific - check last
    }


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
        
        String model = getConfigString(MODEL, GPT_4_O);
        root.put(MODEL, model);
        
        // Messages array
        ArrayNode messages = root.putArray("messages");
        
        // System message
        ObjectNode systemMessage = messages.addObject();
        systemMessage.put("role", "system");
        systemMessage.put(CONTENT, systemPrompt);
        
        // User message
        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.put(CONTENT, userContent);
        
        // Note: We don't use json_object mode because it requires a JSON object at root,
        // but we need a JSON array. Instead, we rely on the system prompt to ensure
        // JSON array format. The prompt explicitly requests a JSON array.
        // OpenAI will still return valid JSON, just not strictly enforced by json_object mode.
        
        return objectMapper.writeValueAsString(root);
    }


    @Override
    protected String sendApiRequest(String payload, int retryCount) throws IOException, InterruptedException {
        String apiEndpoint = getConfigString("api_endpoint", "https://api.openai.com/v1/chat/completions");
        String apiKey = getConfigString(API_KEY, null);
        int timeoutSeconds = getConfigInt("timeout_seconds", 90);
        int initialRetryCount = getConfigInt("initial_retry_count", 0);

        // If retryCount is less than initial, we're on a retry attempt
        if (retryCount < initialRetryCount) {
            timeoutSeconds += 30;
            logger.info("Retrying API request with extra 30 seconds timeout (total: {}s)", timeoutSeconds);
        }


        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiEndpoint))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();

        return executeHttpRequest(request, payload, retryCount);
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
            String content = message.path(CONTENT).asText();
            
            return parseRecommendations(content, batch);
        }

        return new ArrayList<>();
    }

    /**
     * Extracts token usage information from the OpenAI API response.
     */
    @Override
    protected void extractTokenUsage(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode usage = root.path("usage");

        if (!usage.isMissingNode()) {
            int inputTokens = usage.path("prompt_tokens").asInt(0);
            int outputTokens = usage.path("completion_tokens").asInt(0);
            int totalTokens = usage.path("total_tokens").asInt(inputTokens + outputTokens);
            
            // Extract cached token count from prompt_tokens_details
            JsonNode promptTokensDetails = usage.path("prompt_tokens_details");
            int cachedContentTokenCount = promptTokensDetails.path("cached_tokens").asInt(0);

            String model = getConfigString(MODEL, GPT_4_O);
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
                double costPer1kTokens = getConfigDouble("cost_per_1k_tokens", 0.0015);
                inputCost = (inputTokens / 1000.0) * costPer1kTokens;
                outputCost = (outputTokens / 1000.0) * costPer1kTokens * 2; // Output typically costs 2x
            }

            lastTokenUsage = new TokenUsage(inputTokens, outputTokens, totalTokens, cachedContentTokenCount, inputCost, outputCost);

            boolean trackUsage = getConfigBoolean("track_usage", true);
            if (trackUsage) {
                logger.info("Token usage: {}", lastTokenUsage.getFormattedReport());
            }
        } else {
            lastTokenUsage = new TokenUsage();
        }
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
