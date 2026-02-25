package sa.com.cloudsolutions.antikythera.examples;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;

/**
 * OpenRouter implementation of the AI service.
 *
 * <p>OpenRouter exposes an OpenAI-compatible Chat Completions endpoint and
 * supports hundreds of models from different providers (Anthropic, Google,
 * Meta, Mistral, OpenAI, …). The request/response format is identical to
 * OpenAI, so this class extends {@link OpenAIService} and only overrides
 * the endpoint default, the environment-variable name for the API key,
 * and the two optional headers that OpenRouter recommends.
 *
 * <p>Example {@code generator.yml} configuration:
 * <pre>
 * ai_service:
 *   provider: "openrouter"
 *   model: "anthropic/claude-3.5-sonnet"   # any model slug from openrouter.ai/models
 *   api_key: "sk-or-..."                    # or set OPENROUTER_API_KEY env var
 *   site_url: "https://your-app.example.com"  # optional — shown in OpenRouter dashboard
 *   site_name: "My App"                       # optional — shown in OpenRouter dashboard
 *   timeout_seconds: 120
 * </pre>
 */
public class OpenRouterService extends OpenAIService {

    private static final String DEFAULT_ENDPOINT = "https://openrouter.ai/api/v1/chat/completions";
    private static final String DEFAULT_MODEL     = "anthropic/claude-3.5-sonnet";

    public OpenRouterService() throws IOException {
        super();
    }

    /**
     * Sends the request to the OpenRouter endpoint, adding the two optional
     * headers ({@code HTTP-Referer} and {@code X-Title}) that OpenRouter uses
     * for dashboard attribution.
     */
    @Override
    protected String sendApiRequest(String payload, int retryCount) throws IOException, InterruptedException {
        String apiEndpoint   = getConfigString("api_endpoint", DEFAULT_ENDPOINT);
        String apiKey        = getConfigString(API_KEY, null);
        int    timeoutSeconds = getConfigInt("timeout_seconds", 90);

        int initialRetryCount = getConfigInt("initial_retry_count", 0);
        if (retryCount < initialRetryCount) {
            timeoutSeconds += 30;
            logger.info("Retrying OpenRouter request with extra 30 s timeout (total: {}s)", timeoutSeconds);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(apiEndpoint))
                .header("Content-Type",  "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .timeout(Duration.ofSeconds(timeoutSeconds));

        // Optional headers for OpenRouter usage dashboard
        String siteUrl  = getConfigString("site_url",  null);
        String siteName = getConfigString("site_name", null);
        if (siteUrl  != null) builder.header("HTTP-Referer", siteUrl);
        if (siteName != null) builder.header("X-Title",      siteName);

        return executeHttpRequest(builder.build(), payload, retryCount);
    }

    /**
     * Falls back to the {@code OPENROUTER_API_KEY} environment variable when
     * the key is not set explicitly in {@code generator.yml}.
     */
    @Override
    protected String getConfigString(String key, String defaultValue) {
        // Let the parent handle yaml config first
        String value = super.getConfigString(key, null);
        if (value != null) return value;

        // OpenRouter-specific env-var fallbacks
        if (API_KEY.equals(key)) {
            String env = System.getenv("OPENROUTER_API_KEY");
            if (env != null && !env.isBlank()) return env;
        } else if ("api_endpoint".equals(key)) {
            String env = System.getenv("OPENROUTER_API_ENDPOINT");
            if (env != null && !env.isBlank()) return env;
        } else if (MODEL.equals(key)) {
            return DEFAULT_MODEL;
        }

        return defaultValue;
    }

    @Override
    protected void validateConfig() {
        String apiKey = getConfigString(API_KEY, null);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "OpenRouter API key is required. Set OPENROUTER_API_KEY environment variable " +
                    "or configure ai_service.api_key in generator.yml");
        }
    }
}
