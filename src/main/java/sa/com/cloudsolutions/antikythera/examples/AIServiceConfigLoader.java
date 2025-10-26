package sa.com.cloudsolutions.antikythera.examples;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.util.Map;

/**
 * Loads AI service configuration from generator.yml using the existing Settings class.
 * Integrates with the existing configuration management system in Antikythera.
 */
public class AIServiceConfigLoader {
    private static final Logger logger = LoggerFactory.getLogger(AIServiceConfigLoader.class);
    
    private static final String AI_SERVICE_KEY = "ai_service";
    
    /**
     * Loads AI service configuration from generator.yml using the existing Settings class.
     * Falls back to environment variables and default values as needed.
     */
    public static AIServiceConfig loadConfig() {
        AIServiceConfig config = new AIServiceConfig();
        
        // Get ai_service configuration section from generator.yml
        Map<String, Object> aiServiceConfig = (Map<String, Object>) Settings.getProperty(AI_SERVICE_KEY);
        
        if (aiServiceConfig != null) {
            loadFromConfigMap(config, aiServiceConfig);
        } else {
            logger.debug("No ai_service configuration found in generator.yml, using defaults");
        }
        
        // Override with environment variables if present
        loadFromEnvironmentVariables(config);
        
        logger.debug("Loaded AI service configuration: {}", config);
        
        return config;
    }
    
    /**
     * Loads configuration values from the ai_service section in generator.yml.
     */
    private static void loadFromConfigMap(AIServiceConfig config, Map<String, Object> configMap) {
        if (configMap.get("provider") instanceof String provider) {
            config.setProvider(provider);
        }
        
        if (configMap.get("api_endpoint") instanceof String apiEndpoint) {
            config.setApiEndpoint(apiEndpoint);
        }
        
        if (configMap.get("api_key") instanceof String apiKey) {
            config.setApiKey(apiKey);
        }
        
        if (configMap.get("timeout_seconds") instanceof Integer timeoutSeconds) {
            config.setTimeoutSeconds(timeoutSeconds);
        }
        
        if (configMap.get("max_retries") instanceof Integer maxRetries) {
            config.setMaxRetries(maxRetries);
        }
        
        if (configMap.get("queries_per_request") instanceof Integer queriesPerRequest) {
            config.setQueriesPerRequest(queriesPerRequest);
        }
        
        if (configMap.get("track_usage") instanceof Boolean trackUsage) {
            config.setTrackUsage(trackUsage);
        }
        
        if (configMap.get("cost_per_1k_tokens") instanceof Number costPer1kTokens) {
            config.setCostPer1kTokens(costPer1kTokens.doubleValue());
        }
        
        if (configMap.get("max_tokens_per_request") instanceof Integer maxTokensPerRequest) {
            config.setMaxTokensPerRequest(maxTokensPerRequest);
        }
        
        if (configMap.get("enable_request_compression") instanceof Boolean enableRequestCompression) {
            config.setEnableRequestCompression(enableRequestCompression);
        }
    }
    
    /**
     * Loads configuration values from environment variables, overriding any existing values.
     */
    private static void loadFromEnvironmentVariables(AIServiceConfig config) {
        String geminiApiKey = System.getenv("GEMINI_API_KEY");
        if (geminiApiKey != null && !geminiApiKey.trim().isEmpty()) {
            config.setApiKey(geminiApiKey);
        }
        
        String aiServiceEndpoint = System.getenv("AI_SERVICE_ENDPOINT");
        if (aiServiceEndpoint != null && !aiServiceEndpoint.trim().isEmpty()) {
            config.setApiEndpoint(aiServiceEndpoint);
        }
        
        String aiQueriesPerRequest = System.getenv("AI_QUERIES_PER_REQUEST");
        if (aiQueriesPerRequest != null && !aiQueriesPerRequest.trim().isEmpty()) {
            try {
                config.setQueriesPerRequest(Integer.parseInt(aiQueriesPerRequest));
            } catch (NumberFormatException e) {
                logger.warn("Invalid AI_QUERIES_PER_REQUEST environment variable: {}", aiQueriesPerRequest);
            }
        }
    }
}