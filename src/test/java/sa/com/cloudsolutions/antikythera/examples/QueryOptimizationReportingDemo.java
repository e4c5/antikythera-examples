package sa.com.cloudsolutions.antikythera.examples;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Demonstration class to showcase the enhanced reporting functionality
 * of the QueryOptimizationChecker and QueryAnalysisEngine.
 */
public class QueryOptimizationReportingDemo {
    
    public static void main(String[] args) {
        System.out.println("=== Query Optimization Enhanced Reporting Demo ===\n");
        
        // Create a mock CardinalityAnalyzer for demonstration
        CardinalityAnalyzer mockAnalyzer = createMockCardinalityAnalyzer();
        QueryAnalysisEngine engine = new QueryAnalysisEngine(mockAnalyzer);
        
        // Demo 1: High severity issue (low cardinality first)
        System.out.println("Demo 1: High Severity Issue - Low Cardinality Column First");
        System.out.println("--------------------------------------------------------");
        demonstrateHighSeverityIssue(engine);
        
        System.out.println("\n" + "=".repeat(80) + "\n");
        
        // Demo 2: Medium severity issue (suboptimal high cardinality ordering)
        System.out.println("Demo 2: Medium Severity Issue - Suboptimal High Cardinality Ordering");
        System.out.println("--------------------------------------------------------------------");
        demonstrateMediumSeverityIssue(engine);
        
        System.out.println("\n" + "=".repeat(80) + "\n");
        
        // Demo 3: Optimized query confirmation
        System.out.println("Demo 3: Optimized Query Confirmation");
        System.out.println("------------------------------------");
        demonstrateOptimizedQuery(engine);
    }
    
    /**
     * Demonstrates high severity issue reporting with enhanced cardinality information.
     */
    private static void demonstrateHighSeverityIssue(QueryAnalysisEngine engine) {
        // Create mock WHERE conditions: boolean column first, primary key second
        List<WhereCondition> conditions = Arrays.asList(
            new WhereCondition("is_active", "=", CardinalityLevel.LOW, 0, null),
            new WhereCondition("user_id", "=", CardinalityLevel.HIGH, 1, null)
        );
        
        // Create mock optimization issues
        List<OptimizationIssue> issues = Arrays.asList(
            new OptimizationIssue(
                "UserRepository", 
                "findByIsActiveAndUserId",
                "is_active",
                "user_id",
                "Query starts with low cardinality column 'is_active' but high cardinality column 'user_id' is available. Boolean columns filter roughly 50% of rows. Primary keys provide unique row identification for optimal filtering.",
                OptimizationIssue.Severity.HIGH,
                "SELECT * FROM users WHERE is_active = ? AND user_id = ?"
            )
        );
        
        QueryOptimizationResult result = new QueryOptimizationResult(
            "UserRepository", 
            "findByIsActiveAndUserId",
            "SELECT * FROM users WHERE is_active = ? AND user_id = ?",
            conditions, 
            issues
        );
        
        // Create a demo checker to show the enhanced reporting
        DemoQueryOptimizationChecker checker = new DemoQueryOptimizationChecker();
        checker.demonstrateReporting(result);
    }
    
    /**
     * Demonstrates medium severity issue reporting.
     */
    private static void demonstrateMediumSeverityIssue(QueryAnalysisEngine engine) {
        // Create mock WHERE conditions: regular high cardinality first, primary key second
        List<WhereCondition> conditions = Arrays.asList(
            new WhereCondition("email", "=", CardinalityLevel.HIGH, 0, null),
            new WhereCondition("user_id", "=", CardinalityLevel.HIGH, 1, null)
        );
        
        List<OptimizationIssue> issues = Arrays.asList(
            new OptimizationIssue(
                "UserRepository", 
                "findByEmailAndUserId",
                "email",
                "user_id",
                "Primary key column 'user_id' should appear first in WHERE clause for optimal performance. Currently starts with 'email' (high cardinality). Primary keys provide maximum selectivity (1 row per value). Reordering can improve query execution plan efficiency.",
                OptimizationIssue.Severity.MEDIUM,
                "SELECT * FROM users WHERE email = ? AND user_id = ?"
            )
        );
        
        QueryOptimizationResult result = new QueryOptimizationResult(
            "UserRepository", 
            "findByEmailAndUserId",
            "SELECT * FROM users WHERE email = ? AND user_id = ?",
            conditions, 
            issues
        );
        
        DemoQueryOptimizationChecker checker = new DemoQueryOptimizationChecker();
        checker.demonstrateReporting(result);
    }
    
    /**
     * Demonstrates optimized query confirmation reporting.
     */
    private static void demonstrateOptimizedQuery(QueryAnalysisEngine engine) {
        // Create mock WHERE conditions: primary key first (optimal)
        List<WhereCondition> conditions = Arrays.asList(
            new WhereCondition("user_id", "=", CardinalityLevel.HIGH, 0, null),
            new WhereCondition("is_active", "=", CardinalityLevel.LOW, 1, null)
        );
        
        // No optimization issues - query is already optimized
        List<OptimizationIssue> issues = new ArrayList<>();
        
        QueryOptimizationResult result = new QueryOptimizationResult(
            "UserRepository", 
            "findByUserIdAndIsActive",
            "SELECT * FROM users WHERE user_id = ? AND is_active = ?",
            conditions, 
            issues
        );
        
        DemoQueryOptimizationChecker checker = new DemoQueryOptimizationChecker();
        checker.demonstrateReporting(result);
    }
    
    /**
     * Creates a mock CardinalityAnalyzer for demonstration purposes.
     */
    private static CardinalityAnalyzer createMockCardinalityAnalyzer() {
        // This would normally be initialized with real database metadata
        // For demo purposes, we'll create a simple mock
        return new CardinalityAnalyzer(new java.util.HashMap<>()) {
            @Override
            public boolean isPrimaryKey(String tableName, String columnName) {
                return "user_id".equals(columnName) || "id".equals(columnName);
            }
            
            @Override
            public boolean isBooleanColumn(String tableName, String columnName) {
                return "is_active".equals(columnName) || "is_deleted".equals(columnName);
            }
            
            @Override
            public boolean hasUniqueConstraint(String tableName, String columnName) {
                return "email".equals(columnName) || "username".equals(columnName);
            }
        };
    }
    
    /**
     * Demo wrapper class to access the enhanced reporting methods.
     */
    private static class DemoQueryOptimizationChecker {
        
        public void demonstrateReporting(QueryOptimizationResult result) {
            // Simulate the enhanced reporting functionality
            if (result.getOptimizationIssues().isEmpty()) {
                reportOptimizedQuery(result);
            } else {
                reportOptimizationIssues(result, result.getOptimizationIssues());
            }
        }
        
        private void reportOptimizedQuery(QueryOptimizationResult result) {
            if (!result.getWhereConditions().isEmpty()) {
                WhereCondition firstCondition = result.getFirstCondition();
                String cardinalityInfo = firstCondition != null ? 
                    String.format(" (First condition uses %s cardinality column: %s)", 
                                 firstCondition.cardinality().toString().toLowerCase(),
                                 firstCondition.columnName()) : "";
                
                System.out.println(String.format("✓ OPTIMIZED: %s.%s - Query is already optimized%s",
                                                result.getRepositoryClass(), 
                                                result.getMethodName(),
                                                cardinalityInfo));
                
                System.out.println("Query: " + result.getQueryText());
            }
        }
        
        private void reportOptimizationIssues(QueryOptimizationResult result, List<OptimizationIssue> issues) {
            // Sort issues by severity
            List<OptimizationIssue> sortedIssues = issues.stream()
                .sorted((issue1, issue2) -> {
                    int priority1 = getSeverityPriority(issue1.severity());
                    int priority2 = getSeverityPriority(issue2.severity());
                    return Integer.compare(priority1, priority2);
                })
                .toList();
            
            // Report header
            System.out.println(String.format("\n⚠ OPTIMIZATION NEEDED: %s.%s (%d issue%s found)",
                                            result.getRepositoryClass(),
                                            result.getMethodName(),
                                            issues.size(),
                                            issues.size() == 1 ? "" : "s"));
            
            // Report each issue
            for (int i = 0; i < sortedIssues.size(); i++) {
                OptimizationIssue issue = sortedIssues.get(i);
                System.out.println(formatOptimizationIssueEnhanced(issue, i + 1, result));
            }
            
            // Add recommendations
            addColumnReorderingRecommendations(result, sortedIssues);
        }
        
        private int getSeverityPriority(OptimizationIssue.Severity severity) {
            return switch (severity) {
                case HIGH -> 0;
                case MEDIUM -> 1;
                case LOW -> 2;
                default -> 3;
            };
        }
        
        private String formatOptimizationIssueEnhanced(OptimizationIssue issue, int issueNumber, 
                                                      QueryOptimizationResult result) {
            StringBuilder formatted = new StringBuilder();
            
            String severityIcon = getSeverityIcon(issue.severity());
            formatted.append(String.format("  %s Issue #%d [%s PRIORITY]: %s", 
                                          severityIcon, issueNumber, 
                                          issue.severity().toString(),
                                          issue.description()));
            
            WhereCondition currentCondition = findConditionByColumn(result, issue.currentFirstColumn());
            WhereCondition recommendedCondition = findConditionByColumn(result, issue.recommendedFirstColumn());
            
            formatted.append(String.format("\n    Current first condition: %s", 
                                          formatConditionWithCardinality(issue.currentFirstColumn(), currentCondition)));
            formatted.append(String.format("\n    Recommended first condition: %s", 
                                          formatConditionWithCardinality(issue.recommendedFirstColumn(), recommendedCondition)));
            
            formatted.append(String.format("\n    Performance Impact: %s", 
                                          getPerformanceImpactExplanation(issue.severity())));
            
            return formatted.toString();
        }
        
        private String getSeverityIcon(OptimizationIssue.Severity severity) {
            return switch (severity) {
                case HIGH -> "🔴";
                case MEDIUM -> "🟡";
                case LOW -> "🟢";
                default -> "⚪";
            };
        }
        
        private WhereCondition findConditionByColumn(QueryOptimizationResult result, String columnName) {
            return result.getWhereConditions().stream()
                .filter(condition -> columnName.equals(condition.columnName()))
                .findFirst()
                .orElse(null);
        }
        
        private String formatConditionWithCardinality(String columnName, WhereCondition condition) {
            if (condition != null) {
                return String.format("%s (%s cardinality)", columnName, 
                                   condition.cardinality().toString().toLowerCase());
            } else {
                return columnName + " (cardinality unknown)";
            }
        }
        
        private String getPerformanceImpactExplanation(OptimizationIssue.Severity severity) {
            return switch (severity) {
                case HIGH -> "Significant performance degradation likely - low cardinality column filters fewer rows";
                case MEDIUM -> "Moderate performance improvement possible - better column ordering can reduce query time";
                case LOW -> "Minor performance optimization opportunity - small potential gains";
                default -> "Performance impact unknown";
            };
        }
        
        private void addColumnReorderingRecommendations(QueryOptimizationResult result, List<OptimizationIssue> issues) {
            if (issues.isEmpty()) {
                return;
            }
            
            System.out.println("  📋 RECOMMENDED ACTIONS:");
            
            List<OptimizationIssue> highPriorityIssues = issues.stream()
                .filter(OptimizationIssue::isHighSeverity)
                .toList();
            
            List<OptimizationIssue> mediumPriorityIssues = issues.stream()
                .filter(OptimizationIssue::isMediumSeverity)
                .toList();
            
            if (!highPriorityIssues.isEmpty()) {
                System.out.println("    🔴 HIGH PRIORITY:");
                for (OptimizationIssue issue : highPriorityIssues) {
                    System.out.println(String.format("      • Move '%s' condition to the beginning of WHERE clause", 
                                                    issue.recommendedFirstColumn()));
                    System.out.println(String.format("        Replace: WHERE %s = ? AND %s = ?", 
                                                    issue.currentFirstColumn(), issue.recommendedFirstColumn()));
                    System.out.println(String.format("        With:    WHERE %s = ? AND %s = ?", 
                                                    issue.recommendedFirstColumn(), issue.currentFirstColumn()));
                }
            }
            
            if (!mediumPriorityIssues.isEmpty()) {
                System.out.println("    🟡 MEDIUM PRIORITY:");
                for (OptimizationIssue issue : mediumPriorityIssues) {
                    System.out.println(String.format("      • Consider reordering: place '%s' before '%s' in WHERE clause", 
                                                    issue.recommendedFirstColumn(), issue.currentFirstColumn()));
                }
            }
            
            System.out.println("    💡 OPTIMIZATION TIPS:");
            System.out.println("      • Primary key columns should appear first when possible");
            System.out.println("      • Unique indexed columns are more selective than non-unique columns");
            System.out.println("      • Avoid leading with boolean or low-cardinality columns");
            System.out.println("      • Consider adding indexes for frequently queried columns");
        }
    }
}
