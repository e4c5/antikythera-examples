package sa.com.cloudsolutions.antikythera.examples;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;
import sa.com.cloudsolutions.antikythera.parser.BaseRepositoryParser;
import sa.com.cloudsolutions.antikythera.parser.Callable;
import com.github.javaparser.ast.body.MethodDeclaration;

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
import java.util.Map;

/**
 * Handles all interactions with the Gemini AI service including request formatting,
 * API communication, and response parsing.
 * Provides simple API communication capabilities for query optimization analysis.
 */
public class GeminiAIService {
    private static final Logger logger = LoggerFactory.getLogger(GeminiAIService.class);

    private Map<String, Object> config;
    private HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private TokenUsage lastTokenUsage;
    private final String systemPrompt;

    public GeminiAIService() throws IOException {
        this.objectMapper = new ObjectMapper();
        this.lastTokenUsage = new TokenUsage();
        this.systemPrompt = loadSystemPrompt();
    }

    /**
     * Configures the AI service with the provided configuration.
     */
    public void configure(Map<String, Object> config) {
        this.config = config;
        validateConfig();

        int timeoutSeconds = getConfigInt("timeout_seconds", 60);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();

        logger.debug("GeminiAIService configured with timeout: {}s", timeoutSeconds);
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
     * Builds the request payload for the Gemini AI API using proper message structure.
     * Separates system instructions from user query data for better API interaction.
     */
    private String buildRequestPayload(QueryBatch batch) {
        // Build the JSON array of queries as expected by the new prompt
        StringBuilder queriesJson = new StringBuilder();
        queriesJson.append("[");

        for (int i = 0; i < batch.getQueries().size(); i++) {
            RepositoryQuery query = batch.getQueries().get(i);
            if (i > 0) {
                queriesJson.append(",");
            }

            // Determine query type
            String queryType = determineQueryType(query);

            // Build table schema and cardinality string
            String tableSchemaAndCardinality = buildTableSchemaString(batch, query);

            // Build full method signature and escape JSON strings properly
            String fullMethodSignature = escapeJsonString(query.getMethodDeclaration().getCallableDeclaration().toString());
            String queryText = escapeJsonString(getQueryText(query));

            queriesJson.append(String.format("""
                    {
                      "method": "%s",
                      "queryType": "%s",
                      "queryText": "%s",
                      "tableSchemaAndCardinality": "%s"
                    }""", fullMethodSignature, queryType, queryText, tableSchemaAndCardinality));
        }

        queriesJson.append("]");
        return buildGeminiApiRequest(queriesJson.toString());
    }

    /**
     * Builds the Gemini API request with proper message structure.
     * Uses Gemini's contents format with separate parts for system and user content.
     */
    private String buildGeminiApiRequest(String userQueryData) {
        // Escape strings for JSON
        String escapedSystemPrompt = escapeJsonString(systemPrompt);
        String escapedUserData = escapeJsonString(userQueryData);

        return String.format("""
                {
                  "system_instruction": {
                    "parts": [
                      { "text": "%s" }
                    ]
                  },
                  "contents": [
                    {
                      "role": "user",
                      "parts": [
                        { "text": "%s" }
                      ]
                    }
                  ]
                }
                """, escapedSystemPrompt, escapedUserData);
    }

    /**
     * Determines the query type based on the RepositoryQuery.
     * Now properly checks for @Query annotation presence instead of blindly relying on isNative flag.
     */
    private String determineQueryType(RepositoryQuery query) {
        // Check if the method has @Query annotation by examining the method declaration
        if (hasQueryAnnotation(query)) {
            // If @Query annotation is present, check if it's native
            if (isNativeQuery(query)) {
                return "NATIVE_SQL";
            } else {
                return "HQL";
            }
        } else {
            // No @Query annotation means it's a derived query
            return "DERIVED";
        }
    }

    /**
     * Checks if the method has a @Query annotation.
     */
    private boolean hasQueryAnnotation(RepositoryQuery query) {
        if (query.getMethodDeclaration() == null) {
            return false;
        }

        // Check for @Query annotation on the method
        if (query.getMethodDeclaration().isMethodDeclaration()) {
            MethodDeclaration methodDecl = query.getMethodDeclaration().asMethodDeclaration();
            return methodDecl.isAnnotationPresent("Query");
        }
        return false;
    }

    /**
     * Checks if the query is native by examining the @Query annotation's nativeQuery parameter.
     */
    private boolean isNativeQuery(RepositoryQuery query) {
        return hasQueryAnnotation(query) && query.isNative();
    }

    /**
     * Gets the appropriate query text based on the query type.
     */
    private String getQueryText(RepositoryQuery query) {
        String queryType = determineQueryType(query);
        return switch (queryType) {
            case "DERIVED" -> query.getMethodName();
            case "HQL", "NATIVE_SQL" -> query.getOriginalQuery() != null ? query.getOriginalQuery() : query.getQuery();
            default -> query.getMethodName();
        };
    }

    /**
     * Builds the table schema and cardinality string for the AI prompt.
     */
    private String buildTableSchemaString(QueryBatch batch, RepositoryQuery query) {
        StringBuilder schema = new StringBuilder();
        String tableName = query.getPrimaryTable();
        if (tableName == null || tableName.isEmpty()) {
            tableName = "UnknownTable";
        }

        schema.append(tableName).append(" (");

        boolean first = true;
        for (var entry : batch.getColumnCardinalities().entrySet()) {
            if (!first) {
                schema.append(", ");
            }
            schema.append(entry.getKey()).append(":").append(entry.getValue());
            first = false;
        }

        schema.append(")");
        return schema.toString();
    }

    /**
     * Escapes a string for JSON format.
     */
    private String escapeJsonString(String str) {
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
    private String sendApiRequest(String payload) throws IOException, InterruptedException {
        String apiEndpoint = getConfigString("api_endpoint", "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent");
        String model = getConfigString("model", "gemini-1.5-flash");
        String apiKey = getConfigString("api_key", null);
        int timeoutSeconds = getConfigInt("timeout_seconds", 60);
        
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("AI service API key is required. Set GEMINI_API_KEY environment variable or configure ai_service.api_key in generator.yml");
        }
        
        String url = apiEndpoint.replace("{model}", model) + "?key=" + apiKey;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .timeout(Duration.ofSeconds(timeoutSeconds))
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
            int cachedContentTokenCount = usageMetadata.path("cachedContentTokenCount").asInt(0);

            double costPer1kTokens = getConfigDouble("cost_per_1k_tokens", 0.00015);
            double estimatedCost = (totalTokens / 1000.0) * costPer1kTokens;

            lastTokenUsage = new TokenUsage(inputTokens, outputTokens, totalTokens, estimatedCost, cachedContentTokenCount);

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
    private List<OptimizationIssue> parseResponse(String responseBody, QueryBatch batch) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode candidates = root.path("candidates");

        if (candidates.isArray() && !candidates.isEmpty()) {
            JsonNode firstCandidate = candidates.get(0);
            JsonNode content = firstCandidate.path("content");
            JsonNode parts = content.path("parts");

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
    private List<OptimizationIssue> parseRecommendations(String textResponse, QueryBatch batch) {
        List<OptimizationIssue> issues = new ArrayList<>();

        try {
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

        } catch (Exception e) {
            logger.error("Error parsing AI response: {}", e.getMessage(), e);
            // Return empty list on parsing errors to avoid breaking the flow
        }

        return issues;
    }

    /**
     * Extracts JSON content from the AI response, handling markdown code blocks.
     */
    private String extractJsonFromResponse(String response) {
        if (response == null) {
            return null;
        }

        // Look for JSON array patterns
        int jsonStart = response.indexOf('[');
        int jsonEnd = response.lastIndexOf(']');

        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            return response.substring(jsonStart, jsonEnd + 1);
        }

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
     * Parses a single optimization recommendation from the AI response.
     * Always returns an OptimizationIssue - never returns null.
     * If no optimization is needed, returns an issue indicating no action required.
     */
    private OptimizationIssue parseOptimizationRecommendation(JsonNode recommendation, RepositoryQuery originalQuery) throws IOException {
        String optimizedCodeElement = recommendation.path("optimizedCodeElement").asText();
        String notes = recommendation.path("notes").asText();

        // Determine if optimization was applied
        boolean optimizationNeeded = !notes.contains("N/A") && !notes.contains("unchanged") && !notes.contains("already optimized");

        /*
         * Extract the current column order from the original RepositoryQuery.
         * For recommended order, we need to create a new RepositoryQuery from the optimized code element.
         */
        List<String> currentColumnOrder;
        List<String> recommendedColumnOrder;
        RepositoryQuery optimizedQuery = null;
        
        try {
            currentColumnOrder = extractColumnOrderFromRepositoryQuery(originalQuery);
            if (optimizationNeeded) {
                OptimizedQueryResult optimizedResult = extractRecommendedColumnOrder(optimizedCodeElement, originalQuery);
                recommendedColumnOrder = optimizedResult.columnOrder();
                optimizedQuery = optimizedResult.optimizedQuery();
            } else {
                recommendedColumnOrder = currentColumnOrder;
            }
        } catch (Exception e) {
            // Fallback for test scenarios or when extraction fails
            logger.debug("Failed to extract column orders, using fallback approach: {}", e.getMessage());
            currentColumnOrder = extractSimpleColumnOrder(originalQuery);
            recommendedColumnOrder = optimizationNeeded ? extractSimpleColumnOrder(optimizedCodeElement) : currentColumnOrder;
        }

        if (!optimizationNeeded) {
            return new OptimizationIssue(
                    originalQuery,
                    currentColumnOrder,
                    recommendedColumnOrder, // Same as current since no optimization needed
                    "Where clause is already optimized",
                    OptimizationIssue.Severity.LOW, // Low severity since no action needed
                    originalQuery.getQuery(),
                    notes,
                    null
            );
        }
        OptimizationIssue.Severity severity = determineSeverity(notes, currentColumnOrder, recommendedColumnOrder);

        return new OptimizationIssue(
                originalQuery,
                currentColumnOrder,
                recommendedColumnOrder,
                notes,
                severity,
                originalQuery.getQuery(),
                notes,
                optimizedQuery
        );
    }

    /**
     * Extracts column order from a RepositoryQuery using QueryOptimizationExtractor.
     * This replaces the manual method signature parsing with proper WHERE clause analysis.
     */
    private List<String> extractColumnOrderFromRepositoryQuery(RepositoryQuery repositoryQuery) {
        List<String> columns = new ArrayList<>();

        if (repositoryQuery == null) {
            return columns;
        }
        // Use QueryOptimizationExtractor to properly analyze WHERE conditions
        QueryOptimizationExtractor extractor = new QueryOptimizationExtractor();
        List<WhereCondition> whereConditions = extractor.extractWhereConditions(repositoryQuery);

        // Extract column names in the order they appear in WHERE clause
        for (WhereCondition condition : whereConditions) {
            String columnName = condition.columnName();
            if (columnName != null && !columnName.trim().isEmpty()) {
                columns.add(columnName);
            }
        }
        return columns;
    }

    /**
     * Simple fallback method to extract column names from method signature or query.
     * Used when the full QueryOptimizationExtractor approach fails (e.g., in tests).
     */
    private List<String> extractSimpleColumnOrder(RepositoryQuery repositoryQuery) {
        List<String> columns = new ArrayList<>();
        if (repositoryQuery == null) {
            return columns;
        }
        
        String methodName = repositoryQuery.getMethodName();
        if (methodName != null && methodName.startsWith("findBy")) {
            // Extract column names from method name like "findByEmailAndName"
            String columnsStr = methodName.substring(6); // Remove "findBy"
            String[] parts = columnsStr.split("And");
            for (String part : parts) {
                if (!part.trim().isEmpty()) {
                    // Convert camelCase to lowercase
                    String columnName = part.substring(0, 1).toLowerCase() + part.substring(1);
                    columns.add(columnName);
                }
            }
        }
        return columns;
    }

    /**
     * Simple fallback method to extract column names from optimized code element.
     * Used when the full parsing approach fails (e.g., in tests).
     */
    private List<String> extractSimpleColumnOrder(String optimizedCodeElement) {
        List<String> columns = new ArrayList<>();
        if (optimizedCodeElement == null) {
            return columns;
        }
        
        // Extract method name from signature like "findByNameAndEmail(String name, String email)"
        int parenIndex = optimizedCodeElement.indexOf('(');
        if (parenIndex > 0) {
            String methodName = optimizedCodeElement.substring(0, parenIndex);
            if (methodName.startsWith("findBy")) {
                String columnsStr = methodName.substring(6); // Remove "findBy"
                String[] parts = columnsStr.split("And");
                for (String part : parts) {
                    if (!part.trim().isEmpty()) {
                        // Convert camelCase to lowercase
                        String columnName = part.substring(0, 1).toLowerCase() + part.substring(1);
                        columns.add(columnName);
                    }
                }
            }
        }
        return columns;
    }

    /**
     * Record to hold both the optimized query and its column order.
     */
    private record OptimizedQueryResult(RepositoryQuery optimizedQuery, List<String> columnOrder) {}


    /**
     * Extracts recommended column order from the optimized code element.
     * Creates a new RepositoryQuery that clones the original method but with the optimized method signature,
     * then passes it through to extractColumnOrderFromRepositoryQuery.
     */
    private OptimizedQueryResult extractRecommendedColumnOrder(String optimizedCodeElement, RepositoryQuery originalQuery) throws IOException {
        CompilationUnit cu = originalQuery.getMethodDeclaration().getCallableDeclaration().findCompilationUnit().orElseThrow();

        MethodDeclaration old = originalQuery.getMethodDeclaration().asMethodDeclaration();

        ClassOrInterfaceDeclaration cdecl = cloneClassSignature(cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow());

        CompilationUnit tmp = StaticJavaParser.parse(String.format("class Dummy{ %s }", optimizedCodeElement));
        MethodDeclaration newMethod = tmp.findFirst(MethodDeclaration.class).orElseThrow();

        // Remove the old method that matches the signature of the old MethodDeclaration
        cdecl.getMethodsBySignature(old.getName().asString(), old.getParameters().stream()
                        .map(param -> param.getType().asString())
                        .toArray(String[]::new))
                .forEach(cdecl::remove);

        // Replace it with the newly created method instance
        cdecl.addMember(newMethod);

        BaseRepositoryParser parser = BaseRepositoryParser.create(cu);
        parser.processTypes();
        RepositoryQuery rq = parser.getQueryFromRepositoryMethod(new Callable(newMethod, null));
        return new OptimizedQueryResult(rq, extractColumnOrderFromRepositoryQuery(rq));
    }

    /**
     * Efficiently creates a new ClassOrInterfaceDeclaration with the exact same
     * signature as the original, but with an empty body (no members).
     *
     * @param original The original class declaration to copy the signature from.
     * @return A new declaration containing only the signature.
     */
    public ClassOrInterfaceDeclaration cloneClassSignature(ClassOrInterfaceDeclaration original) {
        if (original == null) {
            return null;
        }

        // 1. Create a new, completely empty declaration
        ClassOrInterfaceDeclaration signatureClone = new ClassOrInterfaceDeclaration();

        // 2. Set the signature properties by cloning them.

        // Copy Modifiers (e.g., public, abstract)
        signatureClone.setModifiers(cloneNodeList(original.getModifiers()));

        // Copy Annotations (e.g., @Deprecated)
        signatureClone.setAnnotations(cloneNodeList(original.getAnnotations()));

        // Copy 'interface' or 'class' flag
        signatureClone.setInterface(original.isInterface());

        // Copy Name
        signatureClone.setName(original.getName().clone());

        // Copy Type Parameters (e.g., <T, E>)
        signatureClone.setTypeParameters(cloneNodeList(original.getTypeParameters()));

        // Copy 'extends' clause
        signatureClone.setExtendedTypes(cloneNodeList(original.getExtendedTypes()));

        // Copy 'implements' clause
        signatureClone.setImplementedTypes(cloneNodeList(original.getImplementedTypes()));

        // We deliberately do not copy the members.

        return signatureClone;
    }

    /**
     * A helper method to correctly "clone" a NodeList.
     * It creates a new NodeList and fills it with deep clones of each
     * node from the original list.
     *
     * @param list The original list of nodes to clone.
     * @param <T>  The type of Node in the list.
     * @return A new NodeList containing cloned nodes.
     */
    private <T extends Node> NodeList<T> cloneNodeList(NodeList<T> list) {
        if (list == null || list.isEmpty()) {
            return new NodeList<>();
        }

        NodeList<T> clonedList = new NodeList<>();
        for (T node : list) {
            clonedList.add((T) node.clone());
        }
        return clonedList;
    }

    /**
     * Determines the severity of the optimization issue.
     */
    private OptimizationIssue.Severity determineSeverity(String notes, List<String> currentOrder, List<String> recommendedOrder) {
        if (notes.toLowerCase().contains("high") || notes.toLowerCase().contains("primary key")) {
            return OptimizationIssue.Severity.HIGH;
        } else if (notes.toLowerCase().contains("medium") || !currentOrder.equals(recommendedOrder)) {
            return OptimizationIssue.Severity.MEDIUM;
        } else {
            return OptimizationIssue.Severity.LOW;
        }
    }

    /**
     * Validates the configuration to ensure required settings are present.
     */
    private void validateConfig() {
        String apiKey = getConfigString("api_key", null);
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("AI service API key is required. Set GEMINI_API_KEY environment variable or configure ai_service.api_key in generator.yml");
        }
    }

    /**
     * Gets a string configuration value with fallback to environment variables.
     */
    private String getConfigString(String key, String defaultValue) {
        if (config == null) return defaultValue;
        
        Object value = config.get(key);
        if (value instanceof String str && !str.trim().isEmpty()) {
            return str;
        }
        
        // Fallback to environment variables
        if ("api_key".equals(key)) {
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

    /**
     * Gets an integer configuration value.
     */
    private int getConfigInt(String key, int defaultValue) {
        if (config == null) return defaultValue;
        
        Object value = config.get(key);
        if (value instanceof Integer) {
            return (Integer) value;
        } else if (value instanceof String str) {
            try {
                return Integer.parseInt(str);
            } catch (NumberFormatException e) {
                logger.warn("Invalid integer value for {}: {}", key, str);
            }
        }
        
        // Fallback to environment variables
        if ("queries_per_request".equals(key)) {
            String envValue = System.getenv("AI_QUERIES_PER_REQUEST");
            if (envValue != null && !envValue.trim().isEmpty()) {
                try {
                    return Integer.parseInt(envValue);
                } catch (NumberFormatException e) {
                    logger.warn("Invalid AI_QUERIES_PER_REQUEST environment variable: {}", envValue);
                }
            }
        }
        
        return defaultValue;
    }

    /**
     * Gets a double configuration value.
     */
    private double getConfigDouble(String key, double defaultValue) {
        if (config == null) return defaultValue;
        
        Object value = config.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        } else if (value instanceof String str) {
            try {
                return Double.parseDouble(str);
            } catch (NumberFormatException e) {
                logger.warn("Invalid double value for {}: {}", key, str);
            }
        }
        
        return defaultValue;
    }

    /**
     * Gets a boolean configuration value.
     */
    private boolean getConfigBoolean(String key, boolean defaultValue) {
        if (config == null) return defaultValue;
        
        Object value = config.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        } else if (value instanceof String str) {
            return Boolean.parseBoolean(str);
        }
        
        return defaultValue;
    }
}
