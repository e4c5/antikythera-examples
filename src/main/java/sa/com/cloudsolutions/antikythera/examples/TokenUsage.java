package sa.com.cloudsolutions.antikythera.examples;

/**
 * Tracks token usage information from AI service API calls.
 * Provides simple tracking and reporting functionality for monitoring costs and performance.
 */
public class TokenUsage {
    private int inputTokens;
    private int outputTokens;
    private int totalTokens;
    private double estimatedCost;
    private int cachedContentTokenCount;

    public TokenUsage() {
        this.inputTokens = 0;
        this.outputTokens = 0;
        this.totalTokens = 0;
        this.estimatedCost = 0.0;
        this.cachedContentTokenCount = 0;
    }

    public TokenUsage(int inputTokens, int outputTokens, int totalTokens, double estimatedCost) {
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.totalTokens = totalTokens;
        this.estimatedCost = estimatedCost;
        this.cachedContentTokenCount = 0;
    }

    public TokenUsage(int inputTokens, int outputTokens, int totalTokens, double estimatedCost, int cachedContentTokenCount) {
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.totalTokens = totalTokens;
        this.estimatedCost = estimatedCost;
        this.cachedContentTokenCount = cachedContentTokenCount;
    }

    public int getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(int inputTokens) {
        this.inputTokens = inputTokens;
    }

    public int getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(int outputTokens) {
        this.outputTokens = outputTokens;
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(int totalTokens) {
        this.totalTokens = totalTokens;
    }

    public double getEstimatedCost() {
        return estimatedCost;
    }

    public void setEstimatedCost(double estimatedCost) {
        this.estimatedCost = estimatedCost;
    }

    public int getCachedContentTokenCount() {
        return cachedContentTokenCount;
    }

    public void setCachedContentTokenCount(int cachedContentTokenCount) {
        this.cachedContentTokenCount = cachedContentTokenCount;
    }

    /**
     * Adds token usage from another TokenUsage object to this one.
     * Used for accumulating usage across multiple API calls.
     */
    public void add(TokenUsage other) {
        if (other != null) {
            this.inputTokens += other.inputTokens;
            this.outputTokens += other.outputTokens;
            this.totalTokens += other.totalTokens;
            this.estimatedCost += other.estimatedCost;
            this.cachedContentTokenCount += other.cachedContentTokenCount;
        }
    }

    /**
     * Returns a formatted string representation of token usage for reporting.
     */
    public String getFormattedReport() {
        if (cachedContentTokenCount > 0) {
            double cacheEfficiency = getCacheEfficiency();
            return String.format("Token Usage: Input=%d, Output=%d, Total=%d, Cached=%d (%.1f%%), Estimated Cost=$%.4f",
                    inputTokens, outputTokens, totalTokens, cachedContentTokenCount, cacheEfficiency, estimatedCost);
        } else {
            return String.format("Token Usage: Input=%d, Output=%d, Total=%d, Estimated Cost=$%.4f",
                    inputTokens, outputTokens, totalTokens, estimatedCost);
        }
    }

    /**
     * Calculates cache efficiency as a percentage of cached tokens vs total tokens.
     * Returns 0.0 if no tokens were used or no caching occurred.
     */
    public double getCacheEfficiency() {
        if (totalTokens == 0) {
            return 0.0;
        }
        return (double) cachedContentTokenCount / totalTokens * 100.0;
    }

    @Override
    public String toString() {
        return String.format("TokenUsage{inputTokens=%d, outputTokens=%d, totalTokens=%d, cachedContentTokenCount=%d, estimatedCost=%.4f}",
                inputTokens, outputTokens, totalTokens, cachedContentTokenCount, estimatedCost);
    }
}