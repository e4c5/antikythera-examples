package sa.com.cloudsolutions.antikythera.examples.analysis;

/**
 * Represents different strategies for consolidating similar code patterns.
 */
public enum ConsolidationStrategy {
    UTILITY_CLASS("Utility Class", "Create a utility class with static methods"),
    ENHANCED_COMPONENT("Enhanced Component", "Enhance existing component with consolidated functionality"),
    BUILDER_PATTERN("Builder Pattern", "Use builder pattern for complex object construction"),
    STRATEGY_PATTERN("Strategy Pattern", "Use strategy pattern for varying implementations"),
    TEMPLATE_METHOD("Template Method", "Use template method pattern for similar workflows"),
    DEPENDENCY_INJECTION("Dependency Injection", "Extract as injectable service component"),
    CONFIGURATION_DRIVEN("Configuration Driven", "Make behavior configurable through properties"),
    NO_CONSOLIDATION("No Consolidation", "Methods are too different to consolidate effectively");
    
    private final String displayName;
    private final String description;
    
    ConsolidationStrategy(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    public String getDescription() {
        return description;
    }
    
    @Override
    public String toString() {
        return displayName;
    }
}