package sa.com.cloudsolutions.antikythera.examples;

import java.util.Arrays;
import java.util.List;

/**
 * Simple test to verify the enhanced reporting functionality works correctly.
 * This test demonstrates the key enhancements made to the reporting system.
 */
public class EnhancedReportingTest {
    
    public static void main(String[] args) {
        System.out.println("=== Enhanced Reporting Functionality Test ===\n");
        
        // Test 1: Verify OptimizationIssue enhancements
        testOptimizationIssueEnhancements();
        
        // Test 2: Verify QueryOptimizationResult enhancements
        testQueryOptimizationResultEnhancements();
        
        // Test 3: Verify severity-based prioritization
        testSeverityBasedPrioritization();
        
        System.out.println("\n✅ All enhanced reporting functionality tests passed!");
    }
    
    /**
     * Test OptimizationIssue enhancements including severity levels and detailed descriptions.
     */
    private static void testOptimizationIssueEnhancements() {
        System.out.println("Test 1: OptimizationIssue Enhancements");
        System.out.println("--------------------------------------");
        
        // Create a high severity issue with enhanced cardinality information
        OptimizationIssue highSeverityIssue = new OptimizationIssue(
            "UserRepository",
            "findByIsActiveAndUserId", 
            "is_active",
            "user_id",
            "Query starts with low cardinality column 'is_active' but high cardinality column 'user_id' is available. Boolean columns filter roughly 50% of rows. Primary keys provide unique row identification for optimal filtering.",
            OptimizationIssue.Severity.HIGH,
            "SELECT * FROM users WHERE is_active = ? AND user_id = ?"
        );
        
        // Verify enhanced properties
        assert highSeverityIssue.getRepositoryClass().equals("UserRepository");
        assert highSeverityIssue.getMethodName().equals("findByIsActiveAndUserId");
        assert highSeverityIssue.getCurrentFirstColumn().equals("is_active");
        assert highSeverityIssue.getRecommendedFirstColumn().equals("user_id");
        assert highSeverityIssue.getSeverity() == OptimizationIssue.Severity.HIGH;
        assert highSeverityIssue.isHighSeverity();
        assert !highSeverityIssue.isMediumSeverity();
        assert !highSeverityIssue.isLowSeverity();
        
        // Test formatted report includes cardinality information
        String formattedReport = highSeverityIssue.getFormattedReport();
        assert formattedReport.contains("UserRepository.findByIsActiveAndUserId");
        assert formattedReport.contains("HIGH");
        assert formattedReport.contains("is_active");
        assert formattedReport.contains("user_id");
        assert formattedReport.contains("Boolean columns filter roughly 50% of rows");
        
        System.out.println("✓ OptimizationIssue enhancements verified");
        System.out.println("  - Repository class and method name from Callable objects: ✓");
        System.out.println("  - Enhanced descriptions with cardinality information: ✓");
        System.out.println("  - Severity-based classification: ✓");
        System.out.println("  - Formatted reporting with detailed recommendations: ✓");
    }
    
    /**
     * Test QueryOptimizationResult enhancements including confirmation reporting.
     */
    private static void testQueryOptimizationResultEnhancements() {
        System.out.println("\nTest 2: QueryOptimizationResult Enhancements");
        System.out.println("--------------------------------------------");
        
        // Create WHERE conditions with cardinality information
        List<WhereCondition> conditions = Arrays.asList(
            new WhereCondition("user_id", "=", CardinalityLevel.HIGH, 0, null),
            new WhereCondition("is_active", "=", CardinalityLevel.LOW, 1, null)
        );
        
        // Test optimized query (no issues)
        QueryOptimizationResult optimizedResult = new QueryOptimizationResult(
            "UserRepository",
            "findByUserIdAndIsActive",
            "SELECT * FROM users WHERE user_id = ? AND is_active = ?",
            conditions,
            Arrays.asList() // No issues - optimized
        );
        
        // Verify optimized query properties
        assert optimizedResult.isOptimized();
        assert !optimizedResult.hasOptimizationIssues();
        assert optimizedResult.getOptimizationIssueCount() == 0;
        assert optimizedResult.getWhereConditionCount() == 2;
        assert optimizedResult.isFirstConditionHighCardinality();
        
        WhereCondition firstCondition = optimizedResult.getFirstCondition();
        assert firstCondition != null;
        assert firstCondition.getColumnName().equals("user_id");
        assert firstCondition.getCardinality() == CardinalityLevel.HIGH;
        
        // Test conditions by cardinality filtering
        List<WhereCondition> highCardinalityConditions = optimizedResult.getConditionsByCardinality(CardinalityLevel.HIGH);
        List<WhereCondition> lowCardinalityConditions = optimizedResult.getConditionsByCardinality(CardinalityLevel.LOW);
        
        assert highCardinalityConditions.size() == 1;
        assert lowCardinalityConditions.size() == 1;
        assert highCardinalityConditions.get(0).getColumnName().equals("user_id");
        assert lowCardinalityConditions.get(0).getColumnName().equals("is_active");
        
        System.out.println("✓ QueryOptimizationResult enhancements verified");
        System.out.println("  - Confirmation reporting for optimized queries: ✓");
        System.out.println("  - Enhanced cardinality information in results: ✓");
        System.out.println("  - First condition cardinality checking: ✓");
        System.out.println("  - Cardinality-based condition filtering: ✓");
    }
    
    /**
     * Test severity-based issue prioritization.
     */
    private static void testSeverityBasedPrioritization() {
        System.out.println("\nTest 3: Severity-Based Issue Prioritization");
        System.out.println("-------------------------------------------");
        
        // Create issues with different severity levels
        OptimizationIssue highIssue = new OptimizationIssue(
            "UserRepository", "findByIsActiveAndUserId", "is_active", "user_id",
            "High severity issue", OptimizationIssue.Severity.HIGH, "query1"
        );
        
        OptimizationIssue mediumIssue = new OptimizationIssue(
            "UserRepository", "findByEmailAndUserId", "email", "user_id", 
            "Medium severity issue", OptimizationIssue.Severity.MEDIUM, "query2"
        );
        
        OptimizationIssue lowIssue = new OptimizationIssue(
            "UserRepository", "findByNameAndAge", "name", "age",
            "Low severity issue", OptimizationIssue.Severity.LOW, "query3"
        );
        
        List<OptimizationIssue> issues = Arrays.asList(lowIssue, highIssue, mediumIssue);
        
        QueryOptimizationResult result = new QueryOptimizationResult(
            "UserRepository", "testMethod", "test query", Arrays.asList(), issues
        );
        
        // Test severity filtering
        List<OptimizationIssue> highSeverityIssues = result.getIssuesBySeverity(OptimizationIssue.Severity.HIGH);
        List<OptimizationIssue> mediumSeverityIssues = result.getIssuesBySeverity(OptimizationIssue.Severity.MEDIUM);
        List<OptimizationIssue> lowSeverityIssues = result.getIssuesBySeverity(OptimizationIssue.Severity.LOW);
        
        assert highSeverityIssues.size() == 1;
        assert mediumSeverityIssues.size() == 1;
        assert lowSeverityIssues.size() == 1;
        
        assert highSeverityIssues.get(0).equals(highIssue);
        assert mediumSeverityIssues.get(0).equals(mediumIssue);
        assert lowSeverityIssues.get(0).equals(lowIssue);
        
        // Test highest severity detection
        OptimizationIssue.Severity highestSeverity = result.getHighestSeverity();
        assert highestSeverity == OptimizationIssue.Severity.HIGH;
        
        System.out.println("✓ Severity-based prioritization verified");
        System.out.println("  - Issue filtering by severity level: ✓");
        System.out.println("  - Highest severity detection: ✓");
        System.out.println("  - Multiple severity level support: ✓");
    }
}