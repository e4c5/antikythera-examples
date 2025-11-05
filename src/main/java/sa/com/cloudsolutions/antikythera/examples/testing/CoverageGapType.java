package sa.com.cloudsolutions.antikythera.examples.testing;

/**
 * Types of test coverage gaps that can be identified.
 */
public enum CoverageGapType {
    NO_TEST_FILE("No Test File", "Class has no corresponding test file"),
    UNTESTED_METHOD("Untested Method", "Method has no corresponding test method"),
    UNTESTED_ERROR_HANDLING("Untested Error Handling", "Error handling paths are not tested"),
    UNTESTED_BRANCHES("Untested Branches", "Branch conditions are not fully tested"),
    LOW_LINE_COVERAGE("Low Line Coverage", "Line coverage is below threshold"),
    LOW_BRANCH_COVERAGE("Low Branch Coverage", "Branch coverage is below threshold");
    
    private final String displayName;
    private final String description;
    
    CoverageGapType(String displayName, String description) {
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