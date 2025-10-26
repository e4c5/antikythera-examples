package sa.com.cloudsolutions.antikythera.examples;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles all interactions with the Gemini AI service including request formatting,
 * API communication, and response parsing.
 * Provides simple API communication capabilities for query optimization analysis.
 */
public class GeminiAIService {
    private static final Logger logger = LoggerFactory.getLogger(GeminiAIService.class);
    
    private AIServiceConfig config;
    private HttpClient httpClient;
    private ObjectMapper objectMapper;
    private TokenUsage lastTokenUsage;
    private String systemPrompt;

    public GeminiAIService() throws IOException {
        this.objectMapper = new ObjectMapper();
        this.lastTokenUsage = new TokenUsage();
        this.systemPrompt = loadSystemPrompt();
    }

    /**
     * Configures the AI service with the provided configuration.
     */
    public void configure(AIServiceConfig config) {
        this.config = config;
        config.validate();
        
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .build();
        
        logger.debug("GeminiAIService configured: {}", config);
    }

    /**
     * Analyzes a batch of queries and returns optimization recommendations.
     */
    public List<OptimizationIssue> analyzeQueryBatch(QueryBatch batch) throws IOException, InterruptedException {
        if (config == null) {
            throw new IllegalStateException("AI service not configured. Call configure() first.");
        }
        
        if (batch == null || batch.isEmpty()) {
            logger.debug("Empty batch provided, returning empty results");
            return new ArrayList<>();
        }

        String requestPayload = buildRequestPayload(batch);
        String response = sendApiRequest(requestPayload);
        return parseResponse(response, batch);
    }

    /**
     * Gets the token usage from the last API call.
     */
    public TokenUsage getLastTokenUsage() {
        return lastTokenUsage;
    }

    /**
     * Loads the system prompt from the resource file.
     */
    private String loadSystemPrompt() throws IOException {
        try (InputStream inputStream = getClass().getResourceAsStream("/ai-prompts/query-optimization-system-prompt.txt")) {
            if (inputStream == null) {
                throw new IllegalStateException("System prompt file not found: /ai-prompts/query-optimization-system-prompt.txt");
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Builds the request payload for the Gemini AI API.
     */
    private String buildRequestPayload(QueryBatch batch) {
        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("Repository: ").append(batch.getRepositoryName()).append("\n\n");
        userPrompt.append("Column Cardinalities:\n");
        for (var entry : batch.getColumnCardinalities().entrySet()) {
            userPrompt.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        userPrompt.append("\nQueries to analyze:\n");
        
        for (int i = 0; i < batch.getQueries().size(); i++) {
            RepositoryQuery query = batch.getQueries().get(i);
            userPrompt.append(String.format("%d. Method: %s\n", i + 1, query.getMethodName()));
            userPrompt.append(String.format("   Type: %s\n", query.isNative() ? "NATIVE_SQL" : "HQL"));
            userPrompt.append(String.format("   Query: %s\n\n", query.getOriginalQuery()));
        }

        // Build Gemini API request format
        String requestJson = String.format("""
            {
              "contents": [{
                "parts": [{
                  "text": "%s\\n\\nUser Query:\\n%s"
                }]
              }],
              "generationConfig": {
                "temperature": 0.1,
                "maxOutputTokens": 4000
              }
            }
            """, 
            systemPrompt.replace("\"", "\\\"").replace("\n", "\\n"),
            userPrompt.toString().replace("\"", "\\\"").replace("\n", "\\n"));

        return requestJson;
    }

    /**
     * Sends the API request to Gemini AI service.
     */
    private String sendApiRequest(String payload) throws IOException, InterruptedException {
        String url = config.getApiEndpoint() + "?key=" + config.getApiKey();
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new IOException("API request failed with status: " + response.statusCode() + ", body: " + response.body());
        }

        // Extract token usage if available
        extractTokenUsage(response.body());
        
        return response.body();
    }

    /**
     * Extracts token usage information from the API response.
     */
    private void extractTokenUsage(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode usageMetadata = root.path("usageMetadata");
        
        if (!usageMetadata.isMissingNode()) {
            int inputTokens = usageMetadata.path("promptTokenCount").asInt(0);
            int outputTokens = usageMetadata.path("candidatesTokenCount").asInt(0);
            int totalTokens = usageMetadata.path("totalTokenCount").asInt(inputTokens + outputTokens);
            
            double estimatedCost = (totalTokens / 1000.0) * config.getCostPer1kTokens();
            
            lastTokenUsage = new TokenUsage(inputTokens, outputTokens, totalTokens, estimatedCost);
            
            if (config.isTrackUsage()) {
                logger.info("Token usage: {}", lastTokenUsage.getFormattedReport());
            }
        } else {
            lastTokenUsage = new TokenUsage();
        }
    }

    /**
     * Parses the AI response and converts it to OptimizationIssue objects.
     */
    private List<OptimizationIssue> parseResponse(String responseBody, QueryBatch batch) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode candidates = root.path("candidates");
        
        if (candidates.isArray() && candidates.size() > 0) {
            JsonNode firstCandidate = candidates.get(0);
            JsonNode content = firstCandidate.path("content");
            JsonNode parts = content.path("parts");
            
            if (parts.isArray() && parts.size() > 0) {
                String textResponse = parts.get(0).path("text").asText();
                return parseRecommendations(textResponse, batch);
            }
        }
        
        return new ArrayList<>();
    }

    /**
     * Parses the text response from AI to extract optimization recommendations.
     */
    private List<OptimizationIssue> parseRecommendations(String textResponse, QueryBatch batch) {
        List<OptimizationIssue> issues = new ArrayList<>();
        
        // Simple parsing - in a real implementation, this would be more robust
        // For now, return empty list as this is a basic implementation
        logger.debug("AI Response received: {}", textResponse);
        
        return issues;
    }
}