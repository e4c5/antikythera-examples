package sa.com.cloudsolutions.antikythera.examples;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables;
import uk.org.webcompere.systemstubs.jupiter.SystemStub;
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Comprehensive tests for GeminiAIService.getConfigString method.
 * Uses system-stubs to test environment variable fallback without global modifications.
 */
@ExtendWith(SystemStubsExtension.class)
class GeminiAIServiceConfigTest {

    @SystemStub
    private EnvironmentVariables environmentVariables;

    private TestableGeminiAIService service;

    @BeforeEach
    void setUp() throws IOException {
        service = new TestableGeminiAIService();
    }

    @Test
    void testGetConfigString_NullConfig() {
        // When config is null, should return default value
        service.setConfig(null);

        String result = service.getConfigString("api_key", "default-key");

        assertEquals("default-key", result);
    }

    @Test
    void testGetConfigString_ConfigValuePresent() {
        // When config has non-empty value, should return config value (ignoring env vars)
        Map<String, Object> config = new HashMap<>();
        config.put("api_key", "config-api-key");
        service.setConfig(config);

        environmentVariables.set("GEMINI_API_KEY", "env-api-key");

        String result = service.getConfigString("api_key", "default-key");

        assertEquals("config-api-key", result, "Config value should take precedence over environment variable");
    }

    @Test
    void testGetConfigString_ConfigValueEmpty() {
        // When config value is empty string, should fall back to environment variable
        Map<String, Object> config = new HashMap<>();
        config.put("api_key", "");
        service.setConfig(config);

        environmentVariables.set("GEMINI_API_KEY", "env-api-key");

        String result = service.getConfigString("api_key", "default-key");

        assertEquals("env-api-key", result, "Should fall back to env var when config value is empty");
    }

    @Test
    void testGetConfigString_ConfigValueWhitespace() {
        // When config value is whitespace only, should fall back to environment variable
        Map<String, Object> config = new HashMap<>();
        config.put("api_key", "   ");
        service.setConfig(config);

        environmentVariables.set("GEMINI_API_KEY", "env-api-key");

        String result = service.getConfigString("api_key", "default-key");

        assertEquals("env-api-key", result, "Should fall back to env var when config value is whitespace");
    }

    @Test
    void testGetConfigString_ApiKeyFromEnv() {
        // When config doesn't have api_key, should fall back to GEMINI_API_KEY env var
        Map<String, Object> config = new HashMap<>();
        service.setConfig(config);

        environmentVariables.set("GEMINI_API_KEY", "env-api-key-value");

        String result = service.getConfigString("api_key", "default-key");

        assertEquals("env-api-key-value", result, "Should use GEMINI_API_KEY environment variable");
    }

    @Test
    void testGetConfigString_ApiEndpointFromEnv() {
        // When config doesn't have api_endpoint, should fall back to AI_SERVICE_ENDPOINT env var
        Map<String, Object> config = new HashMap<>();
        service.setConfig(config);

        environmentVariables.set("AI_SERVICE_ENDPOINT", "https://custom-endpoint.com");

        String result = service.getConfigString("api_endpoint", "https://default-endpoint.com");

        assertEquals("https://custom-endpoint.com", result, "Should use AI_SERVICE_ENDPOINT environment variable");
    }

    @Test
    void testGetConfigString_OtherKeyNoEnvFallback() {
        // For keys other than api_key/api_endpoint, should not use env vars
        Map<String, Object> config = new HashMap<>();
        service.setConfig(config);

        environmentVariables.set("SOME_OTHER_VAR", "env-value");

        String result = service.getConfigString("other_key", "default-value");

        assertEquals("default-value", result, "Should not fall back to env vars for non-special keys");
    }

    @Test
    void testGetConfigString_EnvVarEmpty() {
        // When env var exists but is empty, should return default value
        Map<String, Object> config = new HashMap<>();
        service.setConfig(config);

        environmentVariables.set("GEMINI_API_KEY", "");

        String result = service.getConfigString("api_key", "default-key");

        assertEquals("default-key", result, "Should return default when env var is empty");
    }

    @Test
    void testGetConfigString_EnvVarWhitespace() {
        // When env var exists but is whitespace, should return default value
        Map<String, Object> config = new HashMap<>();
        service.setConfig(config);

        environmentVariables.set("GEMINI_API_KEY", "   ");

        String result = service.getConfigString("api_key", "default-key");

        assertEquals("default-key", result, "Should return default when env var is whitespace");
    }

    @Test
    void testGetConfigString_NoConfigNoEnvUsesDefault() {
        // When neither config nor env var exists, should return default value
        Map<String, Object> config = new HashMap<>();
        service.setConfig(config);

        // Explicitly clear the environment variable so real env doesn't leak through
        environmentVariables.set("GEMINI_API_KEY", "");

        String result = service.getConfigString("api_key", "default-key");

        assertEquals("default-key", result, "Should return default when no config or env var");
    }

    @Test
    void testGetConfigString_ConfigPrecedenceOverEnv() {
        // Verify config always takes precedence over environment variables
        Map<String, Object> config = new HashMap<>();
        config.put("api_endpoint", "https://config-endpoint.com");
        service.setConfig(config);

        environmentVariables.set("AI_SERVICE_ENDPOINT", "https://env-endpoint.com");

        String result = service.getConfigString("api_endpoint", "https://default-endpoint.com");

        assertEquals("https://config-endpoint.com", result, "Config should take precedence over env var");
    }

    @Test
    void testGetConfigString_NullDefaultValue() {
        // Should handle null default value gracefully
        Map<String, Object> config = new HashMap<>();
        service.setConfig(config);

        String result = service.getConfigString("nonexistent_key", null);

        assertNull(result, "Should return null when default is null and no value found");
    }

    @Test
    void testGetConfigString_ModelKey() {
        // Test with a typical non-special key like "model"
        Map<String, Object> config = new HashMap<>();
        config.put("model", "gemini-2.0-flash");
        service.setConfig(config);

        String result = service.getConfigString("model", "gemini-1.5-flash");

        assertEquals("gemini-2.0-flash", result, "Should return config value for model key");
    }

    @Test
    void testGetConfigString_NonStringConfigValue() {
        // Test behavior when config contains non-String value
        Map<String, Object> config = new HashMap<>();
        config.put("api_key", 12345); // Integer instead of String
        service.setConfig(config);

        // Explicitly clear the environment variable so real env doesn't leak through
        environmentVariables.set("GEMINI_API_KEY", "");

        String result = service.getConfigString("api_key", "default-key");

        assertEquals("default-key", result, "Should return default when config value is not a String");
    }

    /**
     * Test subclass that exposes the protected getConfigString method.
     */
    private static class TestableGeminiAIService extends GeminiAIService {
        public TestableGeminiAIService() throws IOException {
            super();
        }

        @Override
        public String getConfigString(String key, String defaultValue) {
            return super.getConfigString(key, defaultValue);
        }

        public void setConfig(Map<String, Object> config) {
            this.config = config;
        }
    }
}
