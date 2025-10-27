package sa.com.cloudsolutions.antikythera.examples;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;
import sa.com.cloudsolutions.antikythera.generator.QueryMethodParameter;
import sa.com.cloudsolutions.antikythera.parser.BaseRepositoryParser;
import sa.com.cloudsolutions.antikythera.parser.Callable;
import com.github.javaparser.ast.body.CallableDeclaration;
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
import java.util.Optional;

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
        String tableName = query.getTable();
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
        String url = config.getResolvedApiEndpoint() + "?key=" + config.getApiKey();
        
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
            
            if (jsonResponse == null || jsonResponse.trim().isEmpty()) {
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
        
        String result = jsonBuilder.toString().trim();
        return result.isEmpty() ? null : result;
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
        List<String> currentColumnOrder = extractColumnOrderFromRepositoryQuery(originalQuery);
        List<String> recommendedColumnOrder = (optimizationNeeded)
                ? extractRecommendedColumnOrder(optimizedCodeElement, originalQuery)
                : currentColumnOrder;
        // Index recommendations will be handled by QueryOptimizationChecker with proper Liquibase checking
        List<String> requiredIndexes = new ArrayList<>();

        if (!optimizationNeeded) {
            return new OptimizationIssue(
                    originalQuery,
                    currentColumnOrder,
                    recommendedColumnOrder, // Same as current since no optimization needed
                    "Where clause is already optimized",
                    OptimizationIssue.Severity.LOW, // Low severity since no action needed
                    originalQuery.getQuery(),
                    notes, // AI explanation
                    requiredIndexes // No index recommendations needed
            );
        }
        OptimizationIssue.Severity severity = determineSeverity(notes, currentColumnOrder, recommendedColumnOrder);
        String description = buildOptimizationDescription(notes, currentColumnOrder, recommendedColumnOrder);

        return new OptimizationIssue(
            originalQuery,
            currentColumnOrder,
            recommendedColumnOrder,
            description,
            severity,
            originalQuery.getQuery(),
            notes, // AI explanation
            requiredIndexes
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
     * Extracts recommended column order from the optimized code element.
     * Creates a new RepositoryQuery that clones the original method but with the optimized method signature,
     * then passes it through to extractColumnOrderFromRepositoryQuery.
     */
        private List<String> extractRecommendedColumnOrder(String optimizedCodeElement, RepositoryQuery originalQuery) throws IOException {
            CompilationUnit cu = new CompilationUnit();
            MethodDeclaration old = originalQuery.getMethodDeclaration().asMethodDeclaration();
    
            // Copy imports to handle annotation parsing correctly, especially in fallback.
            old.findCompilationUnit().ifPresent(oldCu -> oldCu.getImports().forEach(cu::addImport));
    
            ClassOrInterfaceDeclaration cdecl = old.findAncestor(ClassOrInterfaceDeclaration.class).orElseThrow().clone();
            cu.addType(cdecl);
            Optional<AnnotationExpr> ann = old.getAnnotationByName("Query");
            MethodDeclaration newMethod = cdecl.getMethods().stream()
                    .filter(method -> method.getSignature().equals(old.getSignature()))
                    .findFirst().orElseThrow();
    
            if (ann.isPresent()) {
                // Find the method declaration that matches the old method
                AnnotationExpr targetAnn = newMethod.getAnnotationByName("Query").orElseThrow();
                AnnotationExpr sourceAnn = ann.get();

                if (sourceAnn.isNormalAnnotationExpr() && targetAnn.isNormalAnnotationExpr()) {
                    NormalAnnotationExpr targetNormal = targetAnn.asNormalAnnotationExpr();
                    NormalAnnotationExpr sourceNormal = sourceAnn.asNormalAnnotationExpr();
                    targetNormal.getPairs().clear();
                    for (MemberValuePair pair : sourceNormal.getPairs()) {
                        targetNormal.addPair(pair.getNameAsString(), pair.getValue().clone());
                    }
                } else if (sourceAnn.isSingleMemberAnnotationExpr() && targetAnn.isSingleMemberAnnotationExpr()) {
                    targetAnn.asSingleMemberAnnotationExpr().setMemberValue(sourceAnn.asSingleMemberAnnotationExpr().getMemberValue().clone());
                } else {
                    // Fallback for mismatched annotation types.
                    newMethod.remove(targetAnn);
                    newMethod.addAnnotation(optimizedCodeElement);
                }
            }
            BaseRepositoryParser parser = BaseRepositoryParser.create(cu);
            RepositoryQuery rq = parser.getQueryFromRepositoryMethod(new Callable(newMethod, null));
            return extractColumnOrderFromRepositoryQuery(rq);
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
     * Builds a description for the optimization issue.
     */
    private String buildOptimizationDescription(String notes, List<String> currentOrder, List<String> recommendedOrder) {
        if (currentOrder.isEmpty() || recommendedOrder.isEmpty()) {
            return "Query optimization recommended: " + notes;
        }
        
        return String.format("Reorder WHERE conditions: move '%s' before '%s' for better performance", 
                           recommendedOrder.get(0), currentOrder.get(0));
    }


}
