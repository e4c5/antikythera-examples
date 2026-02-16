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
 * Comprehensive tests for OpenAIService with mocked HTTP calls.
 * Adapted from GeminiAIServiceTest to ensure consistent test coverage.
 */
class OpenAIServiceTest {
    public static final String USER_REPO = "sa.com.cloudsolutions.antikythera.testhelper.repository.UserRepository";

    private OpenAIService openAIService;
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

        openAIService = new OpenAIService();

        // Create a test configuration
        config = new HashMap<>();
        config.put("api_key", "test-api-key");
        config.put("timeout_seconds", 30);
        config.put("track_usage", true);
        config.put("cost_per_1k_tokens", 0.001);
        config.put("initial_retry_count", 1);

        // Configure the service
        openAIService.configure(config);
    }

    @Test
    void testConstructor() throws IOException {
        OpenAIService service = new OpenAIService();
        assertNotNull(service);
        assertNotNull(service.getLastTokenUsage());
        assertEquals(0, service.getLastTokenUsage().getTotalTokens());
    }

    @Test
    void testConfigure() throws IOException {
        Map<String, Object> testConfig = new HashMap<>();
        testConfig.put("api_key", "test-key");
        testConfig.put("timeout_seconds", 60);

        OpenAIService service = new OpenAIService();
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
            OpenAIService testService = new OpenAIService();
            testService.configure(config);

            // Mock successful HTTP response with OpenAI format
            String mockResponseBody = """
                    {
                      "choices": [
                        {
                          "message": {
                            "role": "assistant",
                            "content": "[{\\"optimizedCodeElement\\": \\"User findByUsername(String username);\\", \\"notes\\": \\"Reordered parameters for better performance\\"}]"
                          }
                        }
                      ],
                      "usage": {
                        "prompt_tokens": 100,
                        "completion_tokens": 50,
                        "total_tokens": 150,
                        "prompt_tokens_details": {
                          "cached_tokens": 20
                        }
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
        }
    }

    @Test
    void testAnalyzeQueryBatch_HttpError() throws Exception {
        try (MockedStatic<HttpClient> httpClientMock = mockStatic(HttpClient.class)) {
            HttpClient.Builder mockBuilder = mock(HttpClient.Builder.class);
            when(mockBuilder.connectTimeout(any())).thenReturn(mockBuilder);
            when(mockBuilder.build()).thenReturn(mockHttpClient);
            httpClientMock.when(HttpClient::newBuilder).thenReturn(mockBuilder);

            OpenAIService testService = new OpenAIService();
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
    void testGetLastTokenUsage() {
        TokenUsage tokenUsage = openAIService.getLastTokenUsage();
        assertNotNull(tokenUsage);
        assertEquals(0, tokenUsage.getTotalTokens());
    }

    @ParameterizedTest
    @CsvSource({
            "'Here is the JSON response:\\n[{\\\"test\\\": \\\"value\\\"}]\\nEnd of response.', '[{\\\"test\\\": \\\"value\\\"}]'",
            "'```json\\n[{\\\"test\\\": \\\"value\\\"}]\\n```', '[{\\\"test\\\": \\\"value\\\"}]'",
            "'No JSON here, just plain text.', ''"
    })
    void testExtractJsonFromResponse(String input, String expected) {
        String result = openAIService.extractJsonFromResponse(input);
        assertEquals(expected, result);
    }

    @Test
    void testAnalyzeQueryBatch_TimeoutRetrySuccess() throws Exception {
        try (MockedStatic<HttpClient> httpClientMock = mockStatic(HttpClient.class)) {
            HttpClient.Builder mockBuilder = mock(HttpClient.Builder.class);
            when(mockBuilder.connectTimeout(any())).thenReturn(mockBuilder);
            when(mockBuilder.build()).thenReturn(mockHttpClient);
            httpClientMock.when(HttpClient::newBuilder).thenReturn(mockBuilder);

            OpenAIService testService = new OpenAIService();
            testService.configure(config);

            // Mock timeout on first call, success on second
            when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenThrow(new HttpTimeoutException("Timeout"))
                    .thenReturn(mockHttpResponse);

            when(mockHttpResponse.statusCode()).thenReturn(200);
            when(mockHttpResponse.body()).thenReturn("{\"choices\": []}");

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

            OpenAIService testService = new OpenAIService();
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

        String result = openAIService.buildTableSchemaString(batch, query);

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

        String result = openAIService.buildTableSchemaString(batch, mockQuery);

        assertNotNull(result);
        assertTrue(result.contains("UnknownTable"));
        assertTrue(result.contains("id:LOW"));
    }

    @Test
    void testExtractTokenUsage_ValidResponse() throws IOException {
        String responseBody = """
                {
                  "usage": {
                    "prompt_tokens": 1000000,
                    "completion_tokens": 1000000,
                    "total_tokens": 2000000,
                    "prompt_tokens_details": {
                      "cached_tokens": 0
                    }
                  }
                }
                """;

        openAIService.extractTokenUsage(responseBody);

        TokenUsage tokenUsage = openAIService.getLastTokenUsage();
        assertEquals(1000000, tokenUsage.getInputTokens());
        assertEquals(1000000, tokenUsage.getOutputTokens());
        assertEquals(2000000, tokenUsage.getTotalTokens());
        // Default model is gpt-4o, pricing: $2.50/1M input, $10.00/1M output
        assertEquals(2.50 + 10.00, tokenUsage.getEstimatedCost(), 0.000001);
    }

    @Test
    void testExtractTokenUsage_WithCaching() throws IOException {
        String responseBody = """
                {
                  "usage": {
                    "prompt_tokens": 100000,
                    "completion_tokens": 100000,
                    "total_tokens": 200000,
                    "prompt_tokens_details": {
                      "cached_tokens": 50000
                    }
                  }
                }
                """;

        openAIService.extractTokenUsage(responseBody);

        TokenUsage tokenUsage = openAIService.getLastTokenUsage();
        assertEquals(50000, tokenUsage.getCachedContentTokenCount());
        // Default model is gpt-4o, pricing: $2.50/1M input, cache ratio 0.25
        // inputCost = (50000/1M)*2.50 + (50000/1M)*0.625 = 0.125 + 0.03125 = 0.15625
        // outputCost = (100000/1M)*10.00 = 1.0
        // total = 1.15625
        assertEquals(1.15625, tokenUsage.getEstimatedCost(), 0.000001);
    }

    @Test
    void testExtractTokenUsage_NoMetadata() throws IOException {
        String responseBody = """
                {
                  "choices": [
                    {
                      "message": {
                        "content": "response"
                      }
                    }
                  ]
                }
                """;

        openAIService.extractTokenUsage(responseBody);

        TokenUsage tokenUsage = openAIService.getLastTokenUsage();
        assertEquals(0, tokenUsage.getTotalTokens());
    }

    @Test
    void testParseResponse_ValidResponse() throws Exception {
        String responseBody = """
                {
                  "choices": [
                    {
                      "message": {
                        "role": "assistant",
                        "content": "[{\\"optimizedCodeElement\\": \\"User findByUsername(String username);\\", \\"notes\\": \\"Reordered parameters\\"}]"
                      }
                    }
                  ]
                }
                """;

        QueryBatch batch = createTestQueryBatch();

        List<OptimizationIssue> result = openAIService.parseResponse(responseBody, batch);

        assertNotNull(result);
        assertEquals(1, result.size());
    }

    @Test
    void testParseResponse_EmptyResponse() throws Exception {
        String responseBody = """
                {
                  "choices": []
                }
                """;

        QueryBatch batch = createTestQueryBatch();

        List<OptimizationIssue> result = openAIService.parseResponse(responseBody, batch);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testExtractTokenUsage_GPT4oMini() throws IOException {
        openAIService.configure(Map.of("api_key", "test-key", "model", "gpt-4o-mini"));
        String responseBody = """
                {
                  "usage": {
                    "prompt_tokens": 1000000,
                    "completion_tokens": 1000000,
                    "total_tokens": 2000000
                  }
                }
                """;
        openAIService.extractTokenUsage(responseBody);
        TokenUsage tokenUsage = openAIService.getLastTokenUsage();
        // gpt-4o-mini: $0.150/1M input, $0.600/1M output
        assertEquals(0.150 + 0.600, tokenUsage.getEstimatedCost(), 0.000001);
    }

    @Test
    void testExtractTokenUsage_GPT4Turbo() throws IOException {
        openAIService.configure(Map.of("api_key", "test-key", "model", "gpt-4-turbo"));
        String responseBody = """
                {
                  "usage": {
                    "prompt_tokens": 1000000,
                    "completion_tokens": 1000000,
                    "total_tokens": 2000000
                  }
                }
                """;
        openAIService.extractTokenUsage(responseBody);
        TokenUsage tokenUsage = openAIService.getLastTokenUsage();
        // gpt-4-turbo: $10.00/1M input, $30.00/1M output
        assertEquals(10.00 + 30.00, tokenUsage.getEstimatedCost(), 0.000001);
    }

    @Test
    void testExtractJsonFromResponse_MultipleFormats() {
        // Test with markdown code block
        String codeBlockResponse = "```json\\n[{\\\"test\\\": \\\"value\\\"}]\\n```";
        String result1 = openAIService.extractJsonFromResponse(codeBlockResponse);
        assertEquals("[{\\\"test\\\": \\\"value\\\"}]", result1);

        // Test with plain JSON
        String plainResponse = "Here is the result: [{\\\"test\\\": \\\"value\\\"}] end";
        String result2 = openAIService.extractJsonFromResponse(plainResponse);
        assertEquals("[{\\\"test\\\": \\\"value\\\"}]", result2);

        // Test with no JSON
        String noJsonResponse = "No JSON content here";
        String result3 = openAIService.extractJsonFromResponse(noJsonResponse);
        assertEquals("", result3);

        // Test with null input
        String result4 = openAIService.extractJsonFromResponse(null);
        assertNull(result4);
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
        
        String optimizedCode = "User findByUsername(String username);";
        
        // We expect it to fallback to original if parsing fails, which is fine for this test
        // as long as it doesn't throw because of the missing ancestor.
        assertDoesNotThrow(() -> openAIService.extractRecommendedColumnOrder(optimizedCode, mockQuery));
    }

    @Test
    void testExtractTokenUsage_MissingCachedTokensField() throws IOException {
        // Test when prompt_tokens_details exists but cached_tokens is missing
        String responseBody = """
                {
                  "usage": {
                    "prompt_tokens": 100000,
                    "completion_tokens": 50000,
                    "total_tokens": 150000,
                    "prompt_tokens_details": {}
                  }
                }
                """;

        openAIService.extractTokenUsage(responseBody);

        TokenUsage tokenUsage = openAIService.getLastTokenUsage();
        assertEquals(100000, tokenUsage.getInputTokens());
        assertEquals(50000, tokenUsage.getOutputTokens());
        assertEquals(150000, tokenUsage.getTotalTokens());
        assertEquals(0, tokenUsage.getCachedContentTokenCount());
    }

    @Test
    void testExtractTokenUsage_MissingPromptTokensDetails() throws IOException {
        // Test when prompt_tokens_details is completely missing
        String responseBody = """
                {
                  "usage": {
                    "prompt_tokens": 100000,
                    "completion_tokens": 50000,
                    "total_tokens": 150000
                  }
                }
                """;

        openAIService.extractTokenUsage(responseBody);

        TokenUsage tokenUsage = openAIService.getLastTokenUsage();
        assertEquals(100000, tokenUsage.getInputTokens());
        assertEquals(50000, tokenUsage.getOutputTokens());
        assertEquals(150000, tokenUsage.getTotalTokens());
        assertEquals(0, tokenUsage.getCachedContentTokenCount());
    }
}
