package sa.com.cloudsolutions.antikythera.examples;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;
import sa.com.cloudsolutions.antikythera.parser.BaseRepositoryParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
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
     * Builds the request payload for the AI API.
     * Provider-specific implementation required.
     */
    protected abstract String buildRequestPayload(QueryBatch batch) throws IOException;

    /**
     * Sends the API request to the AI service.
     * Provider-specific implementation required.
     */
    protected abstract String sendApiRequest(String payload) throws IOException, InterruptedException;

    /**
     * Parses the AI response and converts it to OptimizationIssue objects.
     * Provider-specific implementation required.
     */
    protected abstract List<OptimizationIssue> parseResponse(String responseBody, QueryBatch batch) throws IOException;

    /**
     * Validates the configuration to ensure required settings are present.
     * Can be overridden by subclasses for provider-specific validation.
     */
    protected void validateConfig() {
        String apiKey = getConfigString("api_key", null);
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
                logger.warn("Failed to parse optimized query, falling back to original column order. Code: {}",
                        optimizedCodeElement.substring(0, Math.min(100, optimizedCodeElement.length())));
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
}
