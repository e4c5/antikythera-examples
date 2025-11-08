package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.ast.body.MethodDeclaration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;
import sa.com.cloudsolutions.antikythera.parser.Callable;

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

    private GeminiAIService geminiAIService;
    private Map<String, Object> config;
    
    @Mock
    private HttpClient mockHttpClient;
    
    @Mock
    private HttpResponse<String> mockHttpResponse;
    
    @Mock
    private RepositoryQuery mockRepositoryQuery;
    
    @Mock
    private Callable mockCallable;
    
    @Mock
    private MethodDeclaration mockMethodDeclaration;

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
                            "text": "[{\\"optimizedCodeElement\\": \\"findByEmailAndName(String email, String name)\\", \\"notes\\": \\"Reordered parameters for better performance\\"}]"
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
    void testEscapeJsonString() throws Exception {
        java.lang.reflect.Method method = GeminiAIService.class.getDeclaredMethod("escapeJsonString", String.class);
        method.setAccessible(true);
        
        String input = "Test \"string\" with\nnewlines\tand\\backslashes";
        String result = (String) method.invoke(geminiAIService, input);
        
        assertEquals("Test \\\"string\\\" with\\nnewlines\\tand\\\\backslashes", result);
    }

    @Test
    void testEscapeJsonString_Null() throws Exception {
        java.lang.reflect.Method method = GeminiAIService.class.getDeclaredMethod("escapeJsonString", String.class);
        method.setAccessible(true);
        
        String result = (String) method.invoke(geminiAIService, (String) null);
        
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
    void testDetermineSeverity_High() throws Exception {
        java.lang.reflect.Method method = GeminiAIService.class.getDeclaredMethod("determineSeverity", String.class, List.class, List.class);
        method.setAccessible(true);
        
        String notes = "High priority optimization needed";
        List<String> currentOrder = Arrays.asList("email", "name");
        List<String> recommendedOrder = Arrays.asList("name", "email");
        
        OptimizationIssue.Severity result = (OptimizationIssue.Severity) method.invoke(geminiAIService, notes, currentOrder, recommendedOrder);
        
        assertEquals(OptimizationIssue.Severity.HIGH, result);
    }

    @Test
    void testDetermineSeverity_Medium() throws Exception {
        java.lang.reflect.Method method = GeminiAIService.class.getDeclaredMethod("determineSeverity", String.class, List.class, List.class);
        method.setAccessible(true);
        
        String notes = "Medium priority optimization";
        List<String> currentOrder = Arrays.asList("email", "name");
        List<String> recommendedOrder = Arrays.asList("name", "email");
        
        OptimizationIssue.Severity result = (OptimizationIssue.Severity) method.invoke(geminiAIService, notes, currentOrder, recommendedOrder);
        
        assertEquals(OptimizationIssue.Severity.MEDIUM, result);
    }

    @Test
    void testDetermineSeverity_Low() throws Exception {
        java.lang.reflect.Method method = GeminiAIService.class.getDeclaredMethod("determineSeverity", String.class, List.class, List.class);
        method.setAccessible(true);
        
        String notes = "Minor optimization";
        List<String> currentOrder = Arrays.asList("email", "name");
        List<String> recommendedOrder = Arrays.asList("email", "name"); // Same order
        
        OptimizationIssue.Severity result = (OptimizationIssue.Severity) method.invoke(geminiAIService, notes, currentOrder, recommendedOrder);
        
        assertEquals(OptimizationIssue.Severity.LOW, result);
    }

    @Test
    void testParseRecommendations_InvalidJson() throws Exception {
        java.lang.reflect.Method method = GeminiAIService.class.getDeclaredMethod("parseRecommendations", String.class, QueryBatch.class);
        method.setAccessible(true);
        
        String invalidJson = "This is not valid JSON";
        QueryBatch batch = createTestQueryBatch();
        
        @SuppressWarnings("unchecked")
        List<OptimizationIssue> result = (List<OptimizationIssue>) method.invoke(geminiAIService, invalidJson, batch);
        
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testParseRecommendations_ValidJson() throws Exception {
        java.lang.reflect.Method method = GeminiAIService.class.getDeclaredMethod("parseRecommendations", String.class, QueryBatch.class);
        method.setAccessible(true);
        
        String validJson = "[{\"optimizedCodeElement\": \"findByNameAndEmail(String name, String email)\", \"notes\": \"Reordered for better performance\"}]";
        QueryBatch batch = createTestQueryBatch();
        
        @SuppressWarnings("unchecked")
        List<OptimizationIssue> result = (List<OptimizationIssue>) method.invoke(geminiAIService, validJson, batch);
        
        assertNotNull(result);
        assertEquals(1, result.size());
        
        OptimizationIssue issue = result.get(0);
        assertNotNull(issue);
        assertTrue(issue.aiExplanation().contains("Reordered for better performance"));
    }

    /**
     * Helper method to create a test QueryBatch with mock data.
     */
    private QueryBatch createTestQueryBatch() {
        QueryBatch batch = new QueryBatch("TestRepository");
        
        // Create mock repository query
        RepositoryQuery mockQuery = mock(RepositoryQuery.class);
        when(mockQuery.getMethodName()).thenReturn("findByName");
        when(mockQuery.getPrimaryTable()).thenReturn("users");
        when(mockQuery.getQuery()).thenReturn("SELECT * FROM users WHERE name = ?");
        when(mockQuery.getOriginalQuery()).thenReturn("SELECT * FROM users WHERE name = ?");
        
        // Mock method declaration with proper callable declaration
        Callable callable = mock(Callable.class);
        MethodDeclaration mockMethod = mock(MethodDeclaration.class);
        
        when(mockQuery.getMethodDeclaration()).thenReturn(callable);
        when(callable.isMethodDeclaration()).thenReturn(true);
        when(callable.asMethodDeclaration()).thenReturn(mockMethod);
        doReturn(mockMethod).when(callable).getCallableDeclaration();
        when(callable.toString()).thenReturn("findByName(String name)");
        when(mockMethod.toString()).thenReturn("findByName(String name)");
        when(mockMethod.isAnnotationPresent("Query")).thenReturn(false);
        
        batch.addQuery(mockQuery);
        
        // Add column cardinalities
        Map<String, CardinalityLevel> cardinalities = new HashMap<>();
        cardinalities.put("name", CardinalityLevel.HIGH);
        cardinalities.put("email", CardinalityLevel.MEDIUM);
        batch.setColumnCardinalities(cardinalities);
        
        return batch;
    }

    @Test
    void testBuildTableSchemaString() throws Exception {
        QueryBatch batch = new QueryBatch("TestRepository");
        Map<String, CardinalityLevel> cardinalities = new HashMap<>();
        cardinalities.put("email", CardinalityLevel.HIGH);
        cardinalities.put("name", CardinalityLevel.MEDIUM);
        batch.setColumnCardinalities(cardinalities);
        
        RepositoryQuery mockQuery = mock(RepositoryQuery.class);
        when(mockQuery.getPrimaryTable()).thenReturn("users");
        
        java.lang.reflect.Method method = GeminiAIService.class.getDeclaredMethod("buildTableSchemaString", QueryBatch.class, RepositoryQuery.class);
        method.setAccessible(true);
        
        String result = (String) method.invoke(geminiAIService, batch, mockQuery);
        
        assertNotNull(result);
        assertTrue(result.contains("users"));
        assertTrue(result.contains("email:HIGH"));
        assertTrue(result.contains("name:MEDIUM"));
    }

    @Test
    void testBuildTableSchemaString_NullTable() throws Exception {
        QueryBatch batch = new QueryBatch("TestRepository");
        Map<String, CardinalityLevel> cardinalities = new HashMap<>();
        cardinalities.put("id", CardinalityLevel.LOW);
        batch.setColumnCardinalities(cardinalities);
        
        RepositoryQuery mockQuery = mock(RepositoryQuery.class);
        when(mockQuery.getPrimaryTable()).thenReturn(null);
        
        java.lang.reflect.Method method = GeminiAIService.class.getDeclaredMethod("buildTableSchemaString", QueryBatch.class, RepositoryQuery.class);
        method.setAccessible(true);
        
        String result = (String) method.invoke(geminiAIService, batch, mockQuery);
        
        assertNotNull(result);
        assertTrue(result.contains("UnknownTable"));
        assertTrue(result.contains("id:LOW"));
    }

    @Test
    void testBuildGeminiApiRequest() throws Exception {
        String userQueryData = "[{\"method\": \"findByName\", \"queryType\": \"DERIVED\"}]";
        
        java.lang.reflect.Method method = GeminiAIService.class.getDeclaredMethod("buildGeminiApiRequest", String.class);
        method.setAccessible(true);
        
        String result = (String) method.invoke(geminiAIService, userQueryData);
        
        assertNotNull(result);
        assertTrue(result.contains("system_instruction"));
        assertTrue(result.contains("contents"));
        assertTrue(result.contains("findByName"));
        assertTrue(result.contains("DERIVED"));
    }

    @Test
    void testExtractTokenUsage_ValidResponse() throws Exception {
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
        
        java.lang.reflect.Method method = GeminiAIService.class.getDeclaredMethod("extractTokenUsage", String.class);
        method.setAccessible(true);
        
        method.invoke(geminiAIService, responseBody);
        
        TokenUsage tokenUsage = geminiAIService.getLastTokenUsage();
        assertEquals(100, tokenUsage.getInputTokens());
        assertEquals(50, tokenUsage.getOutputTokens());
        assertEquals(150, tokenUsage.getTotalTokens());
        assertEquals(25, tokenUsage.getCachedContentTokenCount());
    }

    @Test
    void testExtractTokenUsage_NoMetadata() throws Exception {
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
        
        java.lang.reflect.Method method = GeminiAIService.class.getDeclaredMethod("extractTokenUsage", String.class);
        method.setAccessible(true);
        
        method.invoke(geminiAIService, responseBody);
        
        TokenUsage tokenUsage = geminiAIService.getLastTokenUsage();
        assertEquals(0, tokenUsage.getTotalTokens());
    }

    @Test
    void testParseResponse_ValidResponse() throws Exception {
        String responseBody = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "[{\\"optimizedCodeElement\\": \\"findByNameAndEmail(String name, String email)\\", \\"notes\\": \\"Reordered parameters\\"}]"
                      }
                    ]
                  }
                }
              ]
            }
            """;
        
        QueryBatch batch = createSimpleTestBatch();
        
        java.lang.reflect.Method method = GeminiAIService.class.getDeclaredMethod("parseResponse", String.class, QueryBatch.class);
        method.setAccessible(true);
        
        @SuppressWarnings("unchecked")
        List<OptimizationIssue> result = (List<OptimizationIssue>) method.invoke(geminiAIService, responseBody, batch);
        
        assertNotNull(result);
        assertEquals(1, result.size());
    }

    @Test
    void testParseResponse_EmptyResponse() throws Exception {
        String responseBody = """
            {
              "candidates": []
            }
            """;
        
        QueryBatch batch = createSimpleTestBatch();
        
        java.lang.reflect.Method method = GeminiAIService.class.getDeclaredMethod("parseResponse", String.class, QueryBatch.class);
        method.setAccessible(true);
        
        @SuppressWarnings("unchecked")
        List<OptimizationIssue> result = (List<OptimizationIssue>) method.invoke(geminiAIService, responseBody, batch);
        
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testExtractJsonFromResponse_MultipleFormats() throws Exception {
        java.lang.reflect.Method method = GeminiAIService.class.getDeclaredMethod("extractJsonFromResponse", String.class);
        method.setAccessible(true);
        
        // Test with markdown code block
        String codeBlockResponse = "```json\n[{\"test\": \"value\"}]\n```";
        String result1 = (String) method.invoke(geminiAIService, codeBlockResponse);
        assertEquals("[{\"test\": \"value\"}]", result1);
        
        // Test with plain JSON
        String plainResponse = "Here is the result: [{\"test\": \"value\"}] end";
        String result2 = (String) method.invoke(geminiAIService, plainResponse);
        assertEquals("[{\"test\": \"value\"}]", result2);
        
        // Test with no JSON
        String noJsonResponse = "No JSON content here";
        String result3 = (String) method.invoke(geminiAIService, noJsonResponse);
        assertEquals("", result3);
        
        // Test with null input
        String result4 = (String) method.invoke(geminiAIService, (String) null);
        assertNull(result4);
    }

    /**
     * Helper method to create a simple test batch without complex mocking.
     */
    private QueryBatch createSimpleTestBatch() {
        QueryBatch batch = new QueryBatch("TestRepository");
        
        // Create a simple mock query that doesn't require complex callable mocking
        RepositoryQuery mockQuery = mock(RepositoryQuery.class);
        when(mockQuery.getMethodName()).thenReturn("findByName");
        when(mockQuery.getPrimaryTable()).thenReturn("users");
        when(mockQuery.getQuery()).thenReturn("SELECT * FROM users WHERE name = ?");
        
        batch.addQuery(mockQuery);
        
        Map<String, CardinalityLevel> cardinalities = new HashMap<>();
        cardinalities.put("name", CardinalityLevel.HIGH);
        batch.setColumnCardinalities(cardinalities);
        
        return batch;
    }
}
