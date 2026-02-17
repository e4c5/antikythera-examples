package sa.com.cloudsolutions.antikythera.examples;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.generator.QueryType;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;
import sa.com.cloudsolutions.antikythera.parser.BaseRepositoryParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Abstract base class for AI service implementations.
 * Provides common functionality for interacting with LLM providers for query optimization.
 * Subclasses must implement provider-specific request building, API communication, and response parsing.
 */
public abstract class AbstractAIService {
    protected static final Logger logger = LoggerFactory.getLogger(AbstractAIService.class);
    
    protected Map<String, Object> config;
    protected HttpClient httpClient;
    protected final ObjectMapper objectMapper;
    protected TokenUsage lastTokenUsage;
    protected final String systemPrompt;

    public static final String OPTIMIZED_CODE_ELEMENT = "optimizedCodeElement";
    public static final String NOTES = "notes";
    public static final String API_KEY = "api_key";
    public static final String CONTENT = "content";
    public static final String MODEL = "model";
    public static final String STRING = "string";
    public static final String PARTS = "parts";

    /**
     * Constructor initializes common components.
     */
    protected AbstractAIService() throws IOException {
        // Configure JavaParser to support Java 21 features (including text blocks)
        ParserConfiguration parserConfig = new ParserConfiguration();
        parserConfig.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        StaticJavaParser.setConfiguration(parserConfig);

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

        int timeoutSeconds = getConfigInt("timeout_seconds", 90);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    /**
     * Analyzes a batch of queries and returns optimization recommendations.
     * This method orchestrates the request/response flow using abstract methods.
     */
    public List<OptimizationIssue> analyzeQueryBatch(QueryBatch batch) throws IOException, InterruptedException {
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
     * Sends an API request with the given payload.
     * Default implementation delegates to sendApiRequest with initial retry count from config.
     * Subclasses can override for provider-specific behavior.
     */
    protected String sendApiRequest(String payload) throws IOException, InterruptedException {
        int remainingRetries = getConfigInt("initial_retry_count", 0);
        return sendApiRequest(payload, remainingRetries);
    }

    /**
     * Executes an HTTP request with retry logic on timeout.
     * Common logic for sending requests and handling responses.
     * 
     * @param request The HTTP request to send
     * @param payload The original payload (for retry)
     * @param retryCount The number of remaining retry attempts
     * @return The response body
     */
    protected String executeHttpRequest(HttpRequest request, String payload, int retryCount) 
            throws IOException, InterruptedException {
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
            if (retryCount > 0) {
                logger.info("HTTP timeout occurred, retrying... ({} retries remaining)", retryCount);
                return sendApiRequest(payload, retryCount - 1);
            }
            throw e;
        }
    }

    /**
     * Public method for sending raw API requests.
     * This is for external callers like AICodeGenerationHelper.
     */
    public String sendRawRequest(String payload) throws IOException, InterruptedException {
        return sendApiRequest(payload);
    }

    /**
     * Builds the request payload specific to the AI provider.
     */
    protected abstract String buildRequestPayload(QueryBatch batch) throws IOException;


    /**
     * Sends the API request to the AI service with retry support.
     * Provider-specific implementation required.
     * 
     * @param payload The request payload
     * @param retryCount The number of remaining retry attempts
     */
    protected abstract String sendApiRequest(String payload, int retryCount) throws IOException, InterruptedException;

    /**
     * Parses the AI response and converts it to OptimizationIssue objects.
     * Provider-specific implementation required.
     */
    protected abstract List<OptimizationIssue> parseResponse(String responseBody, QueryBatch batch) throws IOException;

    /**
     * Extracts token usage information from the API response.
     * Provider-specific implementation required.
     * 
     * @param responseBody The API response body
     */
    protected abstract void extractTokenUsage(String responseBody) throws IOException;

    /**
     * Validates the configuration to ensure required settings are present.
     * Can be overridden by subclasses for provider-specific validation.
     */
    protected void validateConfig() {
        String apiKey = getConfigString(API_KEY, null);
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException(
                    "AI service API key is required. Configure ai_service.api_key in generator.yml or set the appropriate environment variable");
        }
    }

    /**
     * Loads the system prompt from the resource file.
     */
    protected String loadSystemPrompt() throws IOException {
        try (InputStream inputStream = getClass()
                .getResourceAsStream("/ai-prompts/query-optimization-system-prompt.md")) {
            if (inputStream == null) {
                throw new IllegalStateException(
                        "System prompt file not found: /ai-prompts/query-optimization-system-prompt.md");
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Parses a single optimization recommendation from the AI response.
     * Always returns an OptimizationIssue - never returns null.
     * If no optimization is needed, returns an issue indicating no action required.
     */
    protected OptimizationIssue parseOptimizationRecommendation(JsonNode recommendation, RepositoryQuery originalQuery)
            throws IOException {
        String optimizedCodeElement = recommendation.path(OPTIMIZED_CODE_ELEMENT).asText();
        String notes = recommendation.path(NOTES).asText();

        // Determine if optimization was applied
        boolean optimizationNeeded = !notes.contains("N/A") && !notes.contains("unchanged")
                && !notes.contains("already optimized");

        /*
         * Extract the current column order from the original RepositoryQuery.
         * For the recommended order, we need to create a new RepositoryQuery from the
         * optimized code element.
         */
        List<String> currentColumnOrder;
        List<String> recommendedColumnOrder;
        RepositoryQuery optimizedQuery = null;

        currentColumnOrder = extractColumnOrderFromRepositoryQuery(originalQuery);
        if (optimizationNeeded) {
            OptimizedQueryResult optimizedResult = extractRecommendedColumnOrder(optimizedCodeElement, originalQuery);
            recommendedColumnOrder = optimizedResult.columnOrder();
            optimizedQuery = optimizedResult.optimizedQuery();
        } else {
            recommendedColumnOrder = currentColumnOrder;
        }

        if (!optimizationNeeded) {
            return new OptimizationIssue(
                    originalQuery,
                    currentColumnOrder,
                    recommendedColumnOrder, // Same as current since no optimization needed
                    "Where clause is already optimized",
                    notes,
                    null);
        }

        return new OptimizationIssue(
                originalQuery,
                currentColumnOrder,
                recommendedColumnOrder,
                notes,
                notes,
                optimizedQuery);
    }

    /**
     * Extracts column order from a RepositoryQuery using QueryOptimizationExtractor.
     * This replaces the manual method signature parsing with proper WHERE clause analysis.
     */
    protected List<String> extractColumnOrderFromRepositoryQuery(RepositoryQuery repositoryQuery) {
        List<String> columns = new ArrayList<>();

        if (repositoryQuery == null) {
            return columns;
        }

        List<WhereCondition> whereConditions = QueryOptimizationExtractor
                .extractWhereConditions(repositoryQuery.getStatement());

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
     * Record to hold both the optimized query and its column order.
     */
    protected record OptimizedQueryResult(RepositoryQuery optimizedQuery, List<String> columnOrder) {
    }

    /**
     * Extracts recommended column order from the optimized code element.
     * Creates a new RepositoryQuery that clones the original method but with the
     * optimized method signature, then passes it through to extractColumnOrderFromRepositoryQuery.
     */
    protected OptimizedQueryResult extractRecommendedColumnOrder(String optimizedCodeElement, RepositoryQuery originalQuery)
            throws IOException {
        try {
            CompilationUnit originalCompilationUnit = originalQuery.getMethodDeclaration()
                    .getCallableDeclaration().findCompilationUnit().orElseThrow();

            ClassOrInterfaceDeclaration cdecl = cloneClassSignature(
                    originalQuery.getMethodDeclaration()
                            .getCallableDeclaration()
                            .findAncestor(ClassOrInterfaceDeclaration.class)
                            .orElseThrow());
            CompilationUnit cu = new CompilationUnit();
            cu.addType(cdecl);

            CompilationUnit tmp = StaticJavaParser.parse(String.format("interface Dummy{ %s }", optimizedCodeElement));
            for (ImportDeclaration importDecl : originalCompilationUnit.getImports()) {
                cu.addImport(importDecl);
            }
            MethodDeclaration newMethod = tmp.findFirst(MethodDeclaration.class).orElseThrow();
            // Replace it with the newly created method instance
            cdecl.addMember(newMethod);

            BaseRepositoryParser parser = BaseRepositoryParser.create(cu);
            parser.processTypes();
            parser.buildQueries();

            // Handle case where no queries were produced (e.g., parsing failed or query not recognized)
            var queries = parser.getAllQueries();
            if (queries.isEmpty()) {
                if (logger.isWarnEnabled()) {
                    logger.warn("Failed to parse optimized query, falling back to original column order. Code: {}",
                            optimizedCodeElement.substring(0, Math.min(100, optimizedCodeElement.length())));
                }
                return new OptimizedQueryResult(originalQuery, extractColumnOrderFromRepositoryQuery(originalQuery));
            }

            RepositoryQuery rq = queries.stream().findFirst().orElseThrow();
            return new OptimizedQueryResult(rq, extractColumnOrderFromRepositoryQuery(rq));
        } catch (Exception e) {
            logger.warn("Error parsing optimized code element, falling back to original: {}", e.getMessage());
            return new OptimizedQueryResult(originalQuery, extractColumnOrderFromRepositoryQuery(originalQuery));
        }
    }

    /**
     * Efficiently creates a new ClassOrInterfaceDeclaration with the exact same
     * signature as the original, but with an empty body (no members).
     *
     * @param original The original class declaration to copy the signature from.
     * @return A new declaration containing only the signature.
     */
    protected ClassOrInterfaceDeclaration cloneClassSignature(ClassOrInterfaceDeclaration original) {
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
    protected <T extends Node> NodeList<T> cloneNodeList(NodeList<T> list) {
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
     * Gets a string configuration value with fallback to environment variables.
     * Can be overridden by subclasses for provider-specific environment variable names.
     */
    protected String getConfigString(String key, String defaultValue) {
        if (config == null)
            return defaultValue;

        Object value = config.get(key);
        if (value instanceof String str && !str.trim().isEmpty()) {
            return str;
        }

        return defaultValue;
    }

    /**
     * Gets an integer configuration value.
     */
    protected int getConfigInt(String key, int defaultValue) {
        if (config == null)
            return defaultValue;

        Object value = config.get(key);
        if (value instanceof Integer i) {
            return i;
        } else if (value instanceof String str) {
            return Integer.parseInt(str);
        }
        return defaultValue;
    }

    /**
     * Gets a double configuration value.
     */
    protected double getConfigDouble(String key, double defaultValue) {
        if (config == null)
            return defaultValue;

        Object value = config.get(key);
        if (value instanceof Number n) {
            return n.doubleValue();
        } else if (value instanceof String str) {
            return Double.parseDouble(str);
        }

        return defaultValue;
    }

    /**
     * Gets a boolean configuration value.
     */
    protected boolean getConfigBoolean(String key, boolean defaultValue) {
        if (config == null)
            return defaultValue;

        Object value = config.get(key);
        if (value instanceof Boolean b) {
            return b;
        } else if (value instanceof String str) {
            return Boolean.parseBoolean(str);
        }

        return defaultValue;
    }

    /**
     * Builds the query data array that is common to all AI providers.
     * This creates a JSON array with query information including method signature,
     * query type, query text, and table schema with cardinality.
     *
     * @param batch The query batch containing queries and cardinality information
     * @return JSON string representing the array of query data
     * @throws IOException if JSON serialization fails
     */
    protected String buildQueryDataArray(QueryBatch batch) throws IOException {
        ArrayNode queries = objectMapper.createArrayNode();

        for (RepositoryQuery query : batch.getQueries()) {
            ObjectNode queryNode = queries.addObject();

            String tableSchemaAndCardinality = buildTableSchemaString(batch, query);
            String fullMethodSignature = query.getMethodDeclaration().getCallableDeclaration().toString();
            String queryText = getQueryText(query);

            queryNode.put("method", fullMethodSignature);
            queryNode.put("queryType", query.getQueryType().toString());
            queryNode.put("queryText", queryText);
            queryNode.put("tableSchemaAndCardinality", tableSchemaAndCardinality);
        }

        return objectMapper.writeValueAsString(queries);
    }

    /**
     * Gets the appropriate query text based on the query type.
     * For DERIVED queries, returns the method name; otherwise returns the original query.
     *
     * @param query The repository query
     * @return The query text
     */
    protected String getQueryText(RepositoryQuery query) {
        if (query.getQueryType() == QueryType.DERIVED) {
            return query.getMethodName();
        }
        return query.getOriginalQuery();
    }

    /**
     * Builds the table schema and cardinality string for the AI prompt.
     *
     * @param batch The query batch containing cardinality information
     * @param query The repository query
     * @return Schema string in format "tableName (col1:CARDINALITY, col2:CARDINALITY, ...)"
     */
    protected String buildTableSchemaString(QueryBatch batch, RepositoryQuery query) {
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
     * Extracts JSON content from the AI response, handling markdown code blocks.
     * Looks for both JSON objects and arrays, preferring the outermost complete structure.
     */
    @SuppressWarnings("java:S1066")
    protected String extractJsonFromResponse(String response) {
        if (response == null) {
            return null;
        }

        // Look for JSON array first (most common case for our responses)
        int arrayStart = response.indexOf('[');
        int arrayEnd = response.lastIndexOf(']');
        
        // Look for JSON object
        int objectStart = response.indexOf('{');
        int objectEnd = response.lastIndexOf('}');

        // Prefer array if both exist and array is the outermost structure
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            // Check if the array contains the object or vice versa
            if (objectStart < 0 || arrayStart < objectStart) {
                return response.substring(arrayStart, arrayEnd + 1);
            }
        }
        
        // Fall back to object if no valid array found
        if (objectStart >= 0 && objectEnd > objectStart) {
            return response.substring(objectStart, objectEnd + 1);
        }

        return extractJsonFromCodeBlocks(response);
    }

    /**
     * Extracts JSON from markdown code blocks.
     */
    protected static String extractJsonFromCodeBlocks(String response) {
        // If no JSON found, try to find JSON in code blocks
        String[] lines = response.split("\\n");
        StringBuilder jsonBuilder = new StringBuilder();
        boolean inCodeBlock = false;
        boolean foundJson = false;
        int braceDepth = 0;
        int bracketDepth = 0;

        for (String line : lines) {
            if (line.trim().startsWith("```")) {
                inCodeBlock = !inCodeBlock;
            }
            else if (inCodeBlock || line.trim().startsWith("{") || line.trim().startsWith("[") || foundJson) {
                jsonBuilder.append(line).append("\n");
                foundJson = true;
                
                // Count braces and brackets to handle nested structures
                for (char c : line.toCharArray()) {
                    if (c == '{') braceDepth++;
                    else if (c == '}') braceDepth--;
                    else if (c == '[') bracketDepth++;
                    else if (c == ']') bracketDepth--;
                }
                
                // Break when we've closed all opened braces/brackets
                if (braceDepth == 0 && bracketDepth == 0) {
                    break;
                }
            }
        }

        return jsonBuilder.toString().trim();
    }

    /**
     * Common method to parse recommendations from JSON response.
     * Handles both direct arrays and wrapped objects.
     */
    protected List<OptimizationIssue> parseRecommendationsFromJson(String jsonResponse, QueryBatch batch) throws IOException {
        List<OptimizationIssue> issues = new ArrayList<>();

        if (jsonResponse.trim().isEmpty()) {
            logger.warn("No valid JSON found in AI response");
            return issues;
        }

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
            } else if (responseNode.fields().hasNext()) {
                // Try to find the first array field
                responseArray = responseNode.fields().next().getValue();
            }
        }

        if (!responseArray.isArray()) {
            logger.warn("AI response does not contain a JSON array as expected");
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
}
