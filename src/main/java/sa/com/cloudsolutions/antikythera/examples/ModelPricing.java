package sa.com.cloudsolutions.antikythera.examples;

/**
 * Encapsulates pricing information and logic for an AI model.
 */
public record ModelPricing(
    double inputPriceLow,      // Price per 1M tokens for prompts <= tierLimit
    double inputPriceHigh,     // Price per 1M tokens for prompts > tierLimit
    double outputPriceLow,     // Price per 1M tokens for prompts <= tierLimit
    double outputPriceHigh,    // Price per 1M tokens for prompts > tierLimit
    double cachePriceRatio,    // Ratio of input price for cached tokens (e.g., 0.25)
    int tierLimit              // Token limit for tiered pricing (e.g., 128000)
) {
    public ModelPricing(double inputPriceLow, double inputPriceHigh, double outputPriceLow, double outputPriceHigh, double cachePriceRatio) {
        this(inputPriceLow, inputPriceHigh, outputPriceLow, outputPriceHigh, cachePriceRatio, 128000);
    }

    public ModelPricing(double inputPrice, double outputPrice, double cachePriceRatio) {
        this(inputPrice, inputPrice, outputPrice, outputPrice, cachePriceRatio, Integer.MAX_VALUE);
    }

    /**
     * Calculates input cost based on token count and caching.
     */
    public double calculateInputCost(int inputTokens, int cachedTokens) {
        double price = (inputTokens <= tierLimit) ? inputPriceLow : inputPriceHigh;
        double cachePrice = price * cachePriceRatio;
        
        return ((inputTokens - cachedTokens) / 1000000.0) * price + 
               (cachedTokens / 1000000.0) * cachePrice;
    }

    /**
     * Calculates output cost based on token count.
     */
    public double calculateOutputCost(int inputTokens, int outputTokens) {
        double price = (inputTokens <= tierLimit) ? outputPriceLow : outputPriceHigh;
        return (outputTokens / 1000000.0) * price;
    }
}
