package sa.com.cloudsolutions.antikythera.examples;

/**
 * Configuration class for AI service settings.
 * Manages API endpoints, authentication, timeout settings, and other AI service parameters.
 */
public class AIServiceConfig {
    private String provider;
    private String model;
    private String apiEndpoint;
    private String apiKey;
    private int timeoutSeconds;
    private int maxRetries;
    private int queriesPerRequest;
    private boolean trackUsage;
    private double costPer1kTokens;
    private int maxTokensPerRequest;
    private boolean enableRequestCompression;

    public AIServiceConfig() {
        // Reasonable defaults for most use cases
        this.provider = "gemini";
        this.model = "gemini-1.5-flash";
        this.apiEndpoint = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent";
        this.timeoutSeconds = 60;  // Increased for AI processing time
        this.maxRetries = 2;       // Reduced to avoid excessive retries
        this.queriesPerRequest = 10; // More conservative batch size
        this.trackUsage = true;
        this.costPer1kTokens = 0.00015; // Updated to Gemini 1.5 Flash pricing (input tokens)
        this.maxTokensPerRequest = 8192; // Conservative limit for reliable processing
        this.enableRequestCompression = false; // Disabled by default for simplicity
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getApiEndpoint() {
        return apiEndpoint;
    }

    /**
     * Gets the resolved API endpoint with the model name substituted.
     * @return the complete API endpoint URL
     */
    public String getResolvedApiEndpoint() {
        return apiEndpoint.replace("{model}", model);
    }

    public void setApiEndpoint(String apiEndpoint) {
        this.apiEndpoint = apiEndpoint;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public int getQueriesPerRequest() {
        return queriesPerRequest;
    }

    public void setQueriesPerRequest(int queriesPerRequest) {
        this.queriesPerRequest = queriesPerRequest;
    }

    public boolean isTrackUsage() {
        return trackUsage;
    }

    public void setTrackUsage(boolean trackUsage) {
        this.trackUsage = trackUsage;
    }

    public double getCostPer1kTokens() {
        return costPer1kTokens;
    }

    public void setCostPer1kTokens(double costPer1kTokens) {
        this.costPer1kTokens = costPer1kTokens;
    }

    public int getMaxTokensPerRequest() {
        return maxTokensPerRequest;
    }

    public void setMaxTokensPerRequest(int maxTokensPerRequest) {
        this.maxTokensPerRequest = maxTokensPerRequest;
    }

    public boolean isEnableRequestCompression() {
        return enableRequestCompression;
    }

    public void setEnableRequestCompression(boolean enableRequestCompression) {
        this.enableRequestCompression = enableRequestCompression;
    }

    /**
     * Validates the configuration to ensure required settings are present and reasonable.
     * @throws IllegalStateException if configuration is invalid
     */
    public void validate() {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("AI service API key is required. Set GEMINI_API_KEY environment variable or configure ai_service.api_key in generator.yml");
        }
        if (apiEndpoint == null || apiEndpoint.trim().isEmpty()) {
            throw new IllegalStateException("AI service API endpoint is required.");
        }
        if (timeoutSeconds <= 0 || timeoutSeconds > 300) {
            throw new IllegalStateException("Timeout seconds must be between 1 and 300 seconds. Current value: " + timeoutSeconds);
        }
        if (maxRetries < 0 || maxRetries > 5) {
            throw new IllegalStateException("Max retries must be between 0 and 5. Current value: " + maxRetries);
        }
        if (queriesPerRequest <= 0 || queriesPerRequest > 50) {
            throw new IllegalStateException("Queries per request must be between 1 and 50. Current value: " + queriesPerRequest);
        }
        if (maxTokensPerRequest <= 0 || maxTokensPerRequest > 100000) {
            throw new IllegalStateException("Max tokens per request must be between 1 and 100000. Current value: " + maxTokensPerRequest);
        }
        if (costPer1kTokens < 0) {
            throw new IllegalStateException("Cost per 1k tokens cannot be negative. Current value: " + costPer1kTokens);
        }
    }

    @Override
    public String toString() {
        return String.format("AIServiceConfig{provider='%s', model='%s', apiEndpoint='%s', timeoutSeconds=%d, " +
                        "maxRetries=%d, queriesPerRequest=%d, trackUsage=%s}",
                provider, model, getResolvedApiEndpoint(), timeoutSeconds, maxRetries, queriesPerRequest, trackUsage);
    }
}