package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
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
import sa.com.cloudsolutions.antikythera.parser.converter.EntityMappingResolver;

import java.io.File;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for GeminiAIService with mocked HTTP calls.
 */
class GeminiAIServiceTest {
    public static final String USER_REPO = "sa.com.cloudsolutions.antikythera.testhelper.repository.UserRepository";

    private GeminiAIService geminiAIService;
    private Map<String, Object> config;
    private static BaseRepositoryParser bp;

    @Mock
    private HttpClient mockHttpClient;

    @Mock
    private HttpResponse<String> mockHttpResponse;

    @BeforeAll
    static void setUpAll() throws IOException {
        Settings.loadConfigMap(new File("src/test/resources/generator.yml"));
        AbstractCompiler.reset();
        AbstractCompiler.preProcess();
        EntityMappingResolver.reset();
        EntityMappingResolver.build();
        CardinalityAnalyzer.setIndexMap(new HashMap<>());

        bp = BaseRepositoryParser.create(
                AntikytheraRunTime.getCompilationUnit(USER_REPO));
        bp.processTypes();
        bp.buildQueries();
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
        config.put("initial_retry_count", 1);

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
    void testAnalyzeQueryBatch_SuccessfulResponse() throws Exception {
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
                                "text": "[{\\"optimizedCodeElement\\": \\"User findByUsername(String username);\\", \\"notes\\": \\"Reordered parameters for better performance\\"}]"
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
    void testAnalyzeQueryBatch_HttpError() throws Exception {
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
        java.lang.reflect.Field field = AbstractAIService.class.getDeclaredField("lastTokenUsage");
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



    @Test
    void testParseRecommendations_InvalidJson() throws Exception {
        String invalidJson = "This is not valid JSON";
        QueryBatch batch = createTestQueryBatch();

        List<OptimizationIssue> result = geminiAIService.parseRecommendations(invalidJson, batch);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testParseRecommendations_ValidJson() throws Exception {
        String validJson = "[{\"optimizedCodeElement\": \"User findByNameAndEmail(String name, String email);\", \"notes\": \"Reordered for better performance\"}]";
        QueryBatch batch = createTestQueryBatch();

        List<OptimizationIssue> result = geminiAIService.parseRecommendations(validJson, batch);

        assertNotNull(result);
        assertEquals(1, result.size());

        OptimizationIssue issue = result.get(0);
        assertNotNull(issue);
        assertTrue(issue.aiExplanation().contains("Reordered for better performance"));
    }

    @Test
    void testAnalyzeQueryBatch_TimeoutRetrySuccess() throws Exception {
        try (MockedStatic<HttpClient> httpClientMock = mockStatic(HttpClient.class)) {
            HttpClient.Builder mockBuilder = mock(HttpClient.Builder.class);
            when(mockBuilder.connectTimeout(any())).thenReturn(mockBuilder);
            when(mockBuilder.build()).thenReturn(mockHttpClient);
            httpClientMock.when(HttpClient::newBuilder).thenReturn(mockBuilder);

            GeminiAIService testService = new GeminiAIService();
            testService.configure(config);

            // Mock timeout on first call, success on second
            when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenThrow(new HttpTimeoutException("Timeout"))
                    .thenReturn(mockHttpResponse);

            when(mockHttpResponse.statusCode()).thenReturn(200);
            when(mockHttpResponse.body()).thenReturn("{\"candidates\": []}");

            QueryBatch batch = createTestQueryBatch();
            
            List<OptimizationIssue> result = testService.analyzeQueryBatch(batch);
            
            assertNotNull(result);
            // Verify send was called twice
            verify(mockHttpClient, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
            
            // Verify the second request had increased timeout
            verify(mockHttpClient).send(argThat(request -> 
                request.timeout().isPresent() && request.timeout().get().equals(Duration.ofSeconds(60))), 
                any(HttpResponse.BodyHandler.class));
        }
    }

    @Test
    void testAnalyzeQueryBatch_DoubleTimeoutFails() throws Exception {
        try (MockedStatic<HttpClient> httpClientMock = mockStatic(HttpClient.class)) {
            HttpClient.Builder mockBuilder = mock(HttpClient.Builder.class);
            when(mockBuilder.connectTimeout(any())).thenReturn(mockBuilder);
            when(mockBuilder.build()).thenReturn(mockHttpClient);
            httpClientMock.when(HttpClient::newBuilder).thenReturn(mockBuilder);

            GeminiAIService testService = new GeminiAIService();
            testService.configure(config);

            // Mock timeout on both calls
            when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenThrow(new HttpTimeoutException("Timeout 1"))
                    .thenThrow(new HttpTimeoutException("Timeout 2"));

            QueryBatch batch = createTestQueryBatch();
            
            assertThrows(HttpTimeoutException.class, () -> testService.analyzeQueryBatch(batch));
            
            // Verify send was called exactly twice
            verify(mockHttpClient, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        }
    }

    private QueryBatch createTestQueryBatch() {
        QueryBatch batch = new QueryBatch("UserRepository");
        MethodDeclaration md = bp.getCompilationUnit().findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getNameAsString().equals("findByUsername"))
                .findFirst()
                .orElseThrow();

        RepositoryQuery mockQuery = bp.getQueryFromRepositoryMethod(new Callable(md, null));

        batch.addQuery(mockQuery);

        // Add column cardinalities
        Map<String, CardinalityLevel> cardinalities = new HashMap<>();
        cardinalities.put("username", CardinalityLevel.HIGH);
        cardinalities.put("email", CardinalityLevel.MEDIUM);
        cardinalities.put("age", CardinalityLevel.LOW);
        batch.setColumnCardinalities(cardinalities);

        return batch;
    }

    @Test
    void testBuildTableSchemaString() {
        QueryBatch batch = createTestQueryBatch();
        MethodDeclaration md = bp.getCompilationUnit().findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getNameAsString().equals("findByUsername"))
                .findFirst()
                .orElseThrow();

        RepositoryQuery query = bp.getQueryFromRepositoryMethod(new Callable(md, null));

        String result = geminiAIService.buildTableSchemaString(batch, query);

        assertNotNull(result);
        // The table name should be "users" from the User entity @Table annotation
        assertTrue(result.contains("users"), "Expected 'users' in: " + result);
        assertTrue(result.contains("email:MEDIUM"));
        assertTrue(result.contains("username:HIGH"));
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
    void testBuildGeminiApiRequest() throws IOException {
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
                    "promptTokenCount": 1000000,
                    "candidatesTokenCount": 1000000,
                    "totalTokenCount": 2000000,
                    "cachedContentTokenCount": 0
                  }
                }
                """;

        geminiAIService.extractTokenUsage(responseBody);

        TokenUsage tokenUsage = geminiAIService.getLastTokenUsage();
        assertEquals(1000000, tokenUsage.getInputTokens());
        assertEquals(1000000, tokenUsage.getOutputTokens());
        assertEquals(2000000, tokenUsage.getTotalTokens());
        // Gemini 1.5 Flash (<= 128k prompt): $0.075/1M input, $0.30/1M output
        // Wait, my test use 1M prompt, so it should use the higher tier (> 128k)
        // > 128k: $0.15/1M input, $0.60/1M output
        assertEquals(0.15 + 0.60, tokenUsage.getEstimatedCost(), 0.000001);
    }

    @Test
    void testExtractTokenUsage_WithCaching() throws IOException {
        String responseBody = """
                {
                  "usageMetadata": {
                    "promptTokenCount": 100000,
                    "candidatesTokenCount": 100000,
                    "totalTokenCount": 200000,
                    "cachedContentTokenCount": 50000
                  }
                }
                """;

        geminiAIService.extractTokenUsage(responseBody);

        TokenUsage tokenUsage = geminiAIService.getLastTokenUsage();
        // <= 128k: $0.075/1M input, $0.30/1M output. Cached: $0.01875/1M
        // inputCost = (50000/1M)*0.075 + (50000/1M)*0.01875 = 0.00375 + 0.0009375 = 0.0046875
        // outputCost = (100000/1M)*0.30 = 0.03
        // total = 0.0346875
        assertEquals(0.0346875, tokenUsage.getEstimatedCost(), 0.000001);
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
    void testParseResponse_ValidResponse() throws Exception {
        String responseBody = """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          {
                            "text": "[{\\"optimizedCodeElement\\": \\"User findByUsername(String username);\\", \\"notes\\": \\"Reordered parameters\\"}]"
                          }
                        ]
                      }
                    }
                  ]
                }
                """;

        QueryBatch batch = createTestQueryBatch();

        List<OptimizationIssue> result = geminiAIService.parseResponse(responseBody, batch);

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

        QueryBatch batch = createTestQueryBatch();

        List<OptimizationIssue> result = geminiAIService.parseResponse(responseBody, batch);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testExtractTokenUsage_Gemini2_0_Flash() throws IOException {
        geminiAIService.configure(Map.of("api_key", "test-key", "model", "gemini-2.0-flash"));
        String responseBody = """
                {
                  "usageMetadata": {
                    "promptTokenCount": 1000000,
                    "candidatesTokenCount": 1000000,
                    "totalTokenCount": 2000000
                  }
                }
                """;
        geminiAIService.extractTokenUsage(responseBody);
        TokenUsage tokenUsage = geminiAIService.getLastTokenUsage();
        // $0.10/1M input, $0.40/1M output
        assertEquals(0.10 + 0.40, tokenUsage.getEstimatedCost(), 0.000001);
    }

    @Test
    void testExtractTokenUsage_Gemini2_5_Pro_LowTier() throws IOException {
        geminiAIService.configure(Map.of("api_key", "test-key", "model", "gemini-2.5-pro"));
        String responseBody = """
                {
                  "usageMetadata": {
                    "promptTokenCount": 100000,
                    "candidatesTokenCount": 100000,
                    "totalTokenCount": 200000
                  }
                }
                """;
        geminiAIService.extractTokenUsage(responseBody);
        TokenUsage tokenUsage = geminiAIService.getLastTokenUsage();
        // <= 200k: $1.25/1M input, $10.00/1M output
        // (100k/1M)*1.25 + (100k/1M)*10.00 = 0.125 + 1.0 = 1.125
        assertEquals(1.125, tokenUsage.getEstimatedCost(), 0.000001);
    }

    @Test
    void testExtractTokenUsage_Gemini2_5_Pro_HighTier() throws IOException {
        geminiAIService.configure(Map.of("api_key", "test-key", "model", "gemini-2.5-pro"));
        String responseBody = """
                {
                  "usageMetadata": {
                    "promptTokenCount": 300000,
                    "candidatesTokenCount": 100000,
                    "totalTokenCount": 400000
                  }
                }
                """;
        geminiAIService.extractTokenUsage(responseBody);
        TokenUsage tokenUsage = geminiAIService.getLastTokenUsage();
        // > 200k: $2.50/1M input, $15.00/1M output
        // (300k/1M)*2.50 + (100k/1M)*15.00 = 0.75 + 1.5 = 2.25
        assertEquals(2.25, tokenUsage.getEstimatedCost(), 0.000001);
    }



    @Test
    void testExtractRecommendedColumnOrder_NestedClass() throws Exception {
        // Create a CompilationUnit with a nested class
        String code = """
                package com.example;
                public class Outer {
                    public interface InnerRepository {
                        User findByUsername(String username);
                    }
                }
                """;
        CompilationUnit cu = StaticJavaParser.parse(code);
        ClassOrInterfaceDeclaration inner = cu.findFirst(ClassOrInterfaceDeclaration.class, 
                c -> c.getNameAsString().equals("InnerRepository")).orElseThrow();
        MethodDeclaration md = inner.findFirst(MethodDeclaration.class).orElseThrow();
        
        // Mock RepositoryQuery
        RepositoryQuery mockQuery = mock(RepositoryQuery.class);
        Callable callable = new Callable(md, null);
        when(mockQuery.getMethodDeclaration()).thenReturn(callable);
        Statement stmt = CCJSqlParserUtil.parse("SELECT * FROM users WHERE username = ?");
        when(mockQuery.getStatement()).thenReturn(stmt);
        
        // This should not throw Exception and should correctly find the ancestor
        // It will fail later in BaseRepositoryParser.create(cu) if we don't handle it,
        // but here we just want to verify the findAncestor logic works.
        // Actually, extractRecommendedColumnOrder will call BaseRepositoryParser.create(cu)
        // with the CLONED signature and the NEW method.
        
        String optimizedCode = "User findByUsername(String username);";
        
        // We expect it to fallback to original if parsing fails, which is fine for this test
        // as long as it doesn't throw because of the missing ancestor.
        assertDoesNotThrow(() -> geminiAIService.extractRecommendedColumnOrder(optimizedCode, mockQuery));
    }
}
