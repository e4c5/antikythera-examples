package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;
import sa.com.cloudsolutions.antikythera.parser.BaseRepositoryParser;
import sa.com.cloudsolutions.antikythera.parser.Callable;

import java.io.File;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for GeminiAIService with mocked HTTP calls.
 */
class GeminiAIServiceTest {

    public static final String USER_REPOSITORY = "sa.com.cloudsolutions.antikythera.testhelper.repository.UserRepository";

    private GeminiAIService geminiAIService;
    private Map<String, Object> config;
    
    @Mock
    private HttpClient mockHttpClient;
    
    @Mock
    private HttpResponse<String> mockHttpResponse;

    @BeforeAll
    static void setUpAll() throws IOException {
        Settings.loadConfigMap(new File("src/test/resources/generator.yml"));
        AbstractCompiler.preProcess();
        CardinalityAnalyzer.setIndexMap(new HashMap<>());
    }

    @BeforeEach
    void setUp() throws IOException {
        MockitoAnnotations.openMocks(this);
        
        geminiAIService = new GeminiAIService();
        
        // Create a test configuration
        config = new HashMap<>();
        config.put("api_key", "test-api-key");
        config.put("timeout_seconds", 30);
        config.put("track_usage", true);
        config.put("cost_per_1k_tokens", 0.001);
        
        // Configure the service
        geminiAIService.configure(config);
    }

    @Test
    void testConstructor() throws IOException {
        GeminiAIService service = new GeminiAIService();
        assertNotNull(service);
        assertNotNull(service.getLastTokenUsage());
        assertEquals(0, service.getLastTokenUsage().getTotalTokens());
    }

    @Test
    void testConfigure() throws IOException {
        Map<String, Object> testConfig = new HashMap<>();
        testConfig.put("api_key", "test-key");
        testConfig.put("timeout_seconds", 60);
        
        GeminiAIService service = new GeminiAIService();
        assertDoesNotThrow(() -> service.configure(testConfig));
    }

    @Test
    void testAnalyzeQueryBatch_SuccessfulResponse() throws IOException, InterruptedException {
        // Setup mock HTTP client and response
        try (MockedStatic<HttpClient> httpClientMock = mockStatic(HttpClient.class)) {
            HttpClient.Builder mockBuilder = mock(HttpClient.Builder.class);
            when(mockBuilder.connectTimeout(any())).thenReturn(mockBuilder);
            when(mockBuilder.build()).thenReturn(mockHttpClient);
            httpClientMock.when(HttpClient::newBuilder).thenReturn(mockBuilder);
            
            // Create a new service instance to use the mocked HttpClient
            GeminiAIService testService = new GeminiAIService();
            testService.configure(config);
            
            // Mock successful HTTP response
            String mockResponseBody = """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          {
                            "text": "[{\\"optimizedCodeElement\\": \\"User findByEmailAndName(String email, String name);\\", \\"notes\\": \\"Reordered parameters for better performance\\"}]"
                          }
                        ]
                      }
                    }
                  ],
                  "usageMetadata": {
                    "promptTokenCount": 100,
                    "candidatesTokenCount": 50,
                    "totalTokenCount": 150,
                    "cachedContentTokenCount": 20
                  }
                }
                """;
            
            when(mockHttpResponse.statusCode()).thenReturn(200);
            when(mockHttpResponse.body()).thenReturn(mockResponseBody);
            when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockHttpResponse);
            
            // Create test batch
            QueryBatch batch = createTestQueryBatch();
            
            List<OptimizationIssue> result = testService.analyzeQueryBatch(batch);
            
            assertNotNull(result);
            assertEquals(1, result.size());
            
            // Verify token usage was extracted
            TokenUsage tokenUsage = testService.getLastTokenUsage();
            assertEquals(100, tokenUsage.getInputTokens());
            assertEquals(50, tokenUsage.getOutputTokens());
            assertEquals(150, tokenUsage.getTotalTokens());
            assertEquals(20, tokenUsage.getCachedContentTokenCount());
            
            // Verify cache efficiency calculation
            double expectedEfficiency = (20.0 / 150.0) * 100.0;
            assertEquals(expectedEfficiency, testService.getCacheEfficiency(), 0.01);
        }
    }

    @Test
    void testAnalyzeQueryBatch_HttpError() throws IOException, InterruptedException {
        try (MockedStatic<HttpClient> httpClientMock = mockStatic(HttpClient.class)) {
            HttpClient.Builder mockBuilder = mock(HttpClient.Builder.class);
            when(mockBuilder.connectTimeout(any())).thenReturn(mockBuilder);
            when(mockBuilder.build()).thenReturn(mockHttpClient);
            httpClientMock.when(HttpClient::newBuilder).thenReturn(mockBuilder);
            
            GeminiAIService testService = new GeminiAIService();
            testService.configure(config);
            
            // Mock HTTP error response
            when(mockHttpResponse.statusCode()).thenReturn(400);
            when(mockHttpResponse.body()).thenReturn("Bad Request");
            when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockHttpResponse);
            
            QueryBatch batch = createTestQueryBatch();
            
            IOException exception = assertThrows(IOException.class, 
                () -> testService.analyzeQueryBatch(batch));
            
            assertTrue(exception.getMessage().contains("API request failed"));
            assertTrue(exception.getMessage().contains("400"));
        }
    }

    @Test
    void testGetCacheEfficiency_NoTokens() {
        assertEquals(0.0, geminiAIService.getCacheEfficiency());
    }

    @Test
    void testGetCacheEfficiency_WithTokens() throws Exception {
        // Use reflection to set token usage for testing
        TokenUsage tokenUsage = new TokenUsage(100, 50, 150, 0.15, 30);
        java.lang.reflect.Field field = GeminiAIService.class.getDeclaredField("lastTokenUsage");
        field.setAccessible(true);
        field.set(geminiAIService, tokenUsage);
        
        double expectedEfficiency = (30.0 / 150.0) * 100.0;
        assertEquals(expectedEfficiency, geminiAIService.getCacheEfficiency(), 0.01);
    }

    @Test
    void testGetLastTokenUsage() {
        TokenUsage tokenUsage = geminiAIService.getLastTokenUsage();
        assertNotNull(tokenUsage);
        assertEquals(0, tokenUsage.getTotalTokens());
    }


    @Test
    void testEscapeJsonString() {
        String input = "Test \"string\" with\nnewlines\tand\\backslashes";
        String result = geminiAIService.escapeJsonString(input);

        assertEquals("Test \\\"string\\\" with\\nnewlines\\tand\\\\backslashes", result);
    }

    @Test
    void testEscapeJsonString_Null() {
        String result = geminiAIService.escapeJsonString(null);

        assertEquals("", result);
    }

    @ParameterizedTest
    @CsvSource({
        "'Here is the JSON response:\n[{\"test\": \"value\"}]\nEnd of response.', '[{\"test\": \"value\"}]'",
        "'```json\n[{\"test\": \"value\"}]\n```', '[{\"test\": \"value\"}]'",
        "'No JSON here, just plain text.', ''"
    })
    void testExtractJsonFromResponse(String input, String expected) {
        String result = geminiAIService.extractJsonFromResponse(input);
        
        assertEquals(expected, result);
    }

    @Test
    void testDetermineSeverity_High() {
        String notes = "High priority optimization needed";
        List<String> currentOrder = Arrays.asList("email", "name");
        List<String> recommendedOrder = Arrays.asList("name", "email");
        
        OptimizationIssue.Severity result = geminiAIService.determineSeverity(notes, currentOrder, recommendedOrder);

        assertEquals(OptimizationIssue.Severity.HIGH, result);
    }

    @Test
    void testDetermineSeverity_Medium() {
        String notes = "Medium priority optimization";
        List<String> currentOrder = Arrays.asList("email", "name");
        List<String> recommendedOrder = Arrays.asList("name", "email");
        
        OptimizationIssue.Severity result = geminiAIService.determineSeverity(notes, currentOrder, recommendedOrder);

        assertEquals(OptimizationIssue.Severity.MEDIUM, result);
    }

    @Test
    void testDetermineSeverity_Low() {
        String notes = "Minor optimization";
        List<String> currentOrder = Arrays.asList("email", "name");
        List<String> recommendedOrder = Arrays.asList("email", "name"); // Same order
        
        OptimizationIssue.Severity result = geminiAIService.determineSeverity(notes, currentOrder, recommendedOrder);

        assertEquals(OptimizationIssue.Severity.LOW, result);
    }

    @Test
    void testParseRecommendations_InvalidJson() throws IOException {
        String invalidJson = "This is not valid JSON";
        QueryBatch batch = createTestQueryBatch();
        
        List<OptimizationIssue> result = geminiAIService.parseRecommendations(invalidJson, batch);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testParseRecommendations_ValidJson() throws IOException {
        String validJson = "[{\"optimizedCodeElement\": \"User findByNameAndEmail(String name, String email);\", \"notes\": \"Reordered for better performance\"}]";
        QueryBatch batch = createTestQueryBatch();
        
        List<OptimizationIssue> result = geminiAIService.parseRecommendations(validJson, batch);

        assertNotNull(result);
        assertEquals(1, result.size());
        
        OptimizationIssue issue = result.get(0);
        assertNotNull(issue);
        assertTrue(issue.aiExplanation().contains("Reordered for better performance"));
    }

    /**
     * Helper method to create a test QueryBatch using real repository sources.
     */
    private QueryBatch createTestQueryBatch() throws IOException {
        CompilationUnit repoUnit = AntikytheraRunTime.getCompilationUnit(USER_REPOSITORY);
        BaseRepositoryParser parser = BaseRepositoryParser.create(repoUnit);
        parser.processTypes();
        parser.buildQueries();
        
        QueryBatch batch = new QueryBatch("UserRepository");
        
        // Get a real query from the parsed repository
        MethodDeclaration findByUsername = repoUnit.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getNameAsString().equals("findByUsername"))
                .findFirst()
                .orElseThrow();
        
        Callable callable = new Callable(findByUsername, null);
        RepositoryQuery query = parser.getQueryFromRepositoryMethod(callable);
        
        if (query != null) {
            batch.addQuery(query);
        }
        
        // Add column cardinalities
        Map<String, CardinalityLevel> cardinalities = new HashMap<>();
        cardinalities.put("username", CardinalityLevel.HIGH);
        cardinalities.put("email", CardinalityLevel.MEDIUM);
        cardinalities.put("age", CardinalityLevel.LOW);
        batch.setColumnCardinalities(cardinalities);
        
        return batch;
    }

    @Test
    void testBuildTableSchemaString() throws IOException {
        CompilationUnit repoUnit = AntikytheraRunTime.getCompilationUnit(USER_REPOSITORY);
        BaseRepositoryParser parser = BaseRepositoryParser.create(repoUnit);
        parser.processTypes();
        parser.buildQueries();
        
        QueryBatch batch = new QueryBatch("UserRepository");
        Map<String, CardinalityLevel> cardinalities = new HashMap<>();
        cardinalities.put("email", CardinalityLevel.HIGH);
        cardinalities.put("username", CardinalityLevel.MEDIUM);
        batch.setColumnCardinalities(cardinalities);
        
        MethodDeclaration findByUsername = repoUnit.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getNameAsString().equals("findByUsername"))
                .findFirst()
                .orElseThrow();
        
        Callable callable = new Callable(findByUsername, null);
        RepositoryQuery query = parser.getQueryFromRepositoryMethod(callable);
        
        String result = geminiAIService.buildTableSchemaString(batch, query);

        assertNotNull(result);
        // The table name should be "users" from the User entity @Table annotation
        assertTrue(result.contains("users"), "Expected 'users' in: " + result);
        assertTrue(result.contains("email:HIGH"));
        assertTrue(result.contains("username:MEDIUM"));
    }

    @Test
    void testBuildTableSchemaString_NullTable() {
        QueryBatch batch = new QueryBatch("TestRepository");
        Map<String, CardinalityLevel> cardinalities = new HashMap<>();
        cardinalities.put("id", CardinalityLevel.LOW);
        batch.setColumnCardinalities(cardinalities);
        
        RepositoryQuery mockQuery = mock(RepositoryQuery.class);
        when(mockQuery.getPrimaryTable()).thenReturn(null);
        
        String result = geminiAIService.buildTableSchemaString(batch, mockQuery);

        assertNotNull(result);
        assertTrue(result.contains("UnknownTable"));
        assertTrue(result.contains("id:LOW"));
    }

    @Test
    void testBuildGeminiApiRequest() {
        String userQueryData = "[{\"method\": \"findByName\", \"queryType\": \"DERIVED\"}]";
        
        String result = geminiAIService.buildGeminiApiRequest(userQueryData);

        assertNotNull(result);
        assertTrue(result.contains("system_instruction"));
        assertTrue(result.contains("contents"));
        assertTrue(result.contains("findByName"));
        assertTrue(result.contains("DERIVED"));
    }

    @Test
    void testExtractTokenUsage_ValidResponse() throws IOException {
        String responseBody = """
            {
              "usageMetadata": {
                "promptTokenCount": 100,
                "candidatesTokenCount": 50,
                "totalTokenCount": 150,
                "cachedContentTokenCount": 25
              }
            }
            """;
        
        geminiAIService.extractTokenUsage(responseBody);

        TokenUsage tokenUsage = geminiAIService.getLastTokenUsage();
        assertEquals(100, tokenUsage.getInputTokens());
        assertEquals(50, tokenUsage.getOutputTokens());
        assertEquals(150, tokenUsage.getTotalTokens());
        assertEquals(25, tokenUsage.getCachedContentTokenCount());
    }

    @Test
    void testExtractTokenUsage_NoMetadata() throws IOException {
        String responseBody = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [{"text": "response"}]
                  }
                }
              ]
            }
            """;
        
        geminiAIService.extractTokenUsage(responseBody);

        TokenUsage tokenUsage = geminiAIService.getLastTokenUsage();
        assertEquals(0, tokenUsage.getTotalTokens());
    }

    @Test
    void testParseResponse_ValidResponse() throws IOException {
        String responseBody = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "[{\\"optimizedCodeElement\\": \\"User findByNameAndEmail(String name, String email);\\", \\"notes\\": \\"Reordered parameters\\"}]"
                      }
                    ]
                  }
                }
              ]
            }
            """;
        
        QueryBatch batch = createSimpleTestBatch();
        
        List<OptimizationIssue> result = geminiAIService.parseResponse(responseBody, batch);

        assertNotNull(result);
        assertEquals(1, result.size());
    }

    @Test
    void testParseResponse_EmptyResponse() throws IOException {
        String responseBody = """
            {
              "candidates": []
            }
            """;
        
        QueryBatch batch = createSimpleTestBatch();
        
        List<OptimizationIssue> result = geminiAIService.parseResponse(responseBody, batch);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testExtractJsonFromResponse_MultipleFormats() {
        // Test with markdown code block
        String codeBlockResponse = "```json\n[{\"test\": \"value\"}]\n```";
        String result1 = geminiAIService.extractJsonFromResponse(codeBlockResponse);
        assertEquals("[{\"test\": \"value\"}]", result1);
        
        // Test with plain JSON
        String plainResponse = "Here is the result: [{\"test\": \"value\"}] end";
        String result2 = geminiAIService.extractJsonFromResponse(plainResponse);
        assertEquals("[{\"test\": \"value\"}]", result2);
        
        // Test with no JSON
        String noJsonResponse = "No JSON content here";
        String result3 = geminiAIService.extractJsonFromResponse(noJsonResponse);
        assertEquals("", result3);
        
        // Test with null input
        String result4 = geminiAIService.extractJsonFromResponse(null);
        assertNull(result4);
    }

    /**
     * Helper method to create a simple test batch using real repository sources.
     */
    private QueryBatch createSimpleTestBatch() throws IOException {
        CompilationUnit repoUnit = AntikytheraRunTime.getCompilationUnit(USER_REPOSITORY);
        BaseRepositoryParser parser = BaseRepositoryParser.create(repoUnit);
        parser.processTypes();
        parser.buildQueries();
        
        QueryBatch batch = new QueryBatch("UserRepository");
        
        // Get a simple query from the parsed repository
        MethodDeclaration findByEmail = repoUnit.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getNameAsString().equals("findByEmail"))
                .findFirst()
                .orElseThrow();
        
        Callable callable = new Callable(findByEmail, null);
        RepositoryQuery query = parser.getQueryFromRepositoryMethod(callable);
        
        if (query != null) {
            batch.addQuery(query);
        }
        
        Map<String, CardinalityLevel> cardinalities = new HashMap<>();
        cardinalities.put("email", CardinalityLevel.HIGH);
        batch.setColumnCardinalities(cardinalities);
        
        return batch;
    }
}
