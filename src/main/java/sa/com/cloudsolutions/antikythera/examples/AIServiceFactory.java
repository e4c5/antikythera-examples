package sa.com.cloudsolutions.antikythera.examples;

import java.io.IOException;
import java.util.Map;

/**
 * Factory class for creating AI service instances based on configuration.
 */
public class AIServiceFactory {

    private AIServiceFactory() {
        // Private constructor to prevent instantiation
    }

    /**
     * Creates an AI service instance based on the provider specified in the configuration.
     * 
     * @param config Configuration map containing provider and other settings
     * @return An instance of AbstractAIService (GeminiAIService or OpenAIService)
     * @throws IOException if service initialization fails
     * @throws IllegalArgumentException if an unknown provider is specified
     */
    public static AbstractAIService create(Map<String, Object> config) throws IOException {
        String provider = (String) config.getOrDefault("provider", "gemini");
        
        return switch (provider.toLowerCase()) {
            case "gemini"      -> new GeminiAIService();
            case "openai"      -> new OpenAIService();
            case "openrouter"  -> new OpenRouterService();
            default -> throw new IllegalArgumentException(
                "Unknown AI provider: " + provider + ". Supported providers: gemini, openai, openrouter");
        };
    }
}
