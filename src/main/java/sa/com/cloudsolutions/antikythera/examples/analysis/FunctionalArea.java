package sa.com.cloudsolutions.antikythera.examples.analysis;

/**
 * Represents different functional areas for categorizing similar code patterns.
 * Implements requirement 1.2: Categorize patterns by functional area.
 */
public enum FunctionalArea {
    FILE_OPERATIONS("File I/O Operations", "Methods that read, write, or modify files"),
    GIT_OPERATIONS("Git Operations", "Methods that perform Git repository operations"),
    LIQUIBASE_GENERATION("Liquibase Generation", "Methods that generate Liquibase changesets"),
    REPOSITORY_ANALYSIS("Repository Analysis", "Methods that analyze JPA repositories"),
    CONFIGURATION_LOADING("Configuration Loading", "Methods that load YAML/properties configuration"),
    QUERY_PROCESSING("Query Processing", "Methods that process and analyze SQL/HQL queries"),
    ERROR_HANDLING("Error Handling", "Methods that handle exceptions and errors"),
    UTILITY_OPERATIONS("Utility Operations", "General utility and helper methods"),
    UNKNOWN("Unknown", "Methods that don't fit into other categories");
    
    private final String displayName;
    private final String description;
    
    FunctionalArea(String displayName, String description) {
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