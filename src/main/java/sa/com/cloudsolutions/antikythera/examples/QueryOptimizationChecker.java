package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;
import sa.com.cloudsolutions.antikythera.generator.TypeWrapper;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;
import sa.com.cloudsolutions.antikythera.parser.Callable;
import sa.com.cloudsolutions.antikythera.parser.RepositoryParser;
import sa.com.cloudsolutions.liquibase.Indexes;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

@SuppressWarnings("java:S3457")
public class QueryOptimizationChecker {
    
    private static final Logger logger = LoggerFactory.getLogger(QueryOptimizationChecker.class);
    
    private final RepositoryParser repositoryParser;
    private final CardinalityAnalyzer cardinalityAnalyzer;
    private final QueryAnalysisEngine analysisEngine;

    /**
     * Creates a new QueryOptimizationChecker that uses RepositoryParser for comprehensive query analysis.
     * 
     * @param liquibaseXmlPath path to the Liquibase XML file for database metadata
     * @throws Exception if initialization fails
     */
    public QueryOptimizationChecker(String liquibaseXmlPath) throws Exception {
        // Load database metadata for cardinality analysis
        Map<String, List<Indexes.IndexInfo>> indexMap = Indexes.load(new File(liquibaseXmlPath));
        
        // Initialize components
        this.cardinalityAnalyzer = new CardinalityAnalyzer(indexMap);
        this.analysisEngine = new QueryAnalysisEngine(cardinalityAnalyzer);
        this.repositoryParser = new RepositoryParser();
    }

    /**
     * Analyzes all JPA repositories using RepositoryParser to extract and analyze queries.
     * 
     */
    public void analyze() throws FileNotFoundException {
        Map<String, TypeWrapper> resolvedTypes = AntikytheraRunTime.getResolvedTypes();

        for (Map.Entry<String, TypeWrapper> entry : resolvedTypes.entrySet()) {
            String fullyQualifiedName = entry.getKey();
            TypeWrapper typeWrapper = entry.getValue();

            if (isJpaRepository(typeWrapper)) {
                analyzeRepository(fullyQualifiedName, typeWrapper);
            }
        }
    }

    /**
     * Checks if a TypeWrapper represents a JPA repository interface.
     * 
     * @param typeWrapper the type to check
     * @return true if it's a JPA repository, false otherwise
     */
    private boolean isJpaRepository(TypeWrapper typeWrapper) {
        if (typeWrapper.getType() instanceof ClassOrInterfaceDeclaration classOrInterface && classOrInterface.isInterface()) {
            for (var extendedType : classOrInterface.getExtendedTypes()) {
                if (extendedType.getNameAsString().contains("JpaRepository") ||
                    extendedType.toString().contains("JpaRepository")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Analyzes a repository using RepositoryParser to extract and analyze all queries.
     * 
     * @param fullyQualifiedName the fully qualified class name of the repository
     * @param typeWrapper the TypeWrapper representing the repository
     */
    private void analyzeRepository(String fullyQualifiedName, TypeWrapper typeWrapper) throws FileNotFoundException {
        logger.debug("Analyzing repository: {}", fullyQualifiedName);
        
        // Use RepositoryParser to process the repository type
        repositoryParser.compile(AbstractCompiler.classToPath(fullyQualifiedName));
        repositoryParser.processTypes();

        // Build all queries using RepositoryParser
        repositoryParser.buildQueries();

        // Analyze each method in the repository to get its queries
        if (typeWrapper.getType() != null) {
            var declaration = typeWrapper.getType().asClassOrInterfaceDeclaration();

            for (var method : declaration.getMethods()) {
                Callable callable = new Callable(method, null);
                RepositoryQuery repositoryQuery = repositoryParser.get(callable);

                if (repositoryQuery != null) {
                    analyzeRepositoryQuery(fullyQualifiedName, callable, repositoryQuery);
                }
            }
        }
    }

    /**
     * Analyzes a single repository query using QueryAnalysisEngine.
     * Enhanced to pass Callable information for better repository class and method name reporting.
     * 
     * @param repositoryClassName the repository class name
     * @param callable the callable representing the repository method
     * @param repositoryQuery the RepositoryQuery object containing parsed query information
     */
    private void analyzeRepositoryQuery(String repositoryClassName, Callable callable, RepositoryQuery repositoryQuery) {
        // Use QueryAnalysisEngine to analyze the query with enhanced Callable information
        QueryOptimizationResult result = analysisEngine.analyzeQueryWithCallable(repositoryQuery, callable, repositoryClassName);

        // Report optimization issues with enhanced reporting
        reportOptimizationResults(result);
    }
    
    /**
     * Reports the optimization analysis results with enhanced formatting and severity-based prioritization.
     * Enhanced to include repository class name and method name from Callable objects,
     * specific recommendations for column reordering, and confirmation reporting for optimized queries.
     * 
     * @param result the analysis results to report
     */
    private void reportOptimizationResults(QueryOptimizationResult result) {
        if (result == null) {
            return;
        }
        
        List<OptimizationIssue> issues = result.getOptimizationIssues();
        
        if (issues.isEmpty()) {
            // Enhanced confirmation reporting for already optimized queries
            reportOptimizedQuery(result);
        } else {
            // Report optimization issues with severity-based prioritization
            reportOptimizationIssues(result, issues);
        }
    }
    
    /**
     * Reports confirmation for already optimized queries with cardinality information.
     * 
     * @param result the analysis results for an optimized query
     */
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
            
            if (logger.isDebugEnabled()) {
                logger.debug("Query details: {}", result.getQueryText());
            }
        }
    }
    
    /**
     * Reports optimization issues with severity-based prioritization and enhanced recommendations.
     * 
     * @param result the analysis results
     * @param issues the list of optimization issues to report
     */
    private void reportOptimizationIssues(QueryOptimizationResult result, List<OptimizationIssue> issues) {
        // Sort issues by severity (HIGH -> MEDIUM -> LOW) for prioritized reporting
        List<OptimizationIssue> sortedIssues = issues.stream()
            .sorted((issue1, issue2) -> {
                // Sort by severity priority: HIGH (0) -> MEDIUM (1) -> LOW (2)
                int priority1 = getSeverityPriority(issue1.severity());
                int priority2 = getSeverityPriority(issue2.severity());
                return Integer.compare(priority1, priority2);
            })
            .toList();
        
        // Report header with summary
        System.out.println(String.format("\n⚠ OPTIMIZATION NEEDED: %s.%s (%d issue%s found)",
                                        result.getRepositoryClass(),
                                        result.getMethodName(),
                                        issues.size(),
                                        issues.size() == 1 ? "" : "s"));
        
        // Report each issue with enhanced formatting
        for (int i = 0; i < sortedIssues.size(); i++) {
            OptimizationIssue issue = sortedIssues.get(i);
            System.out.println(formatOptimizationIssueEnhanced(issue, i + 1, result));
        }
        
        // Add specific recommendations for column reordering
        addColumnReorderingRecommendations(result, sortedIssues);
        
        System.out.println(); // Add blank line for readability
    }
    
    /**
     * Gets the priority value for severity-based sorting (lower number = higher priority).
     * 
     * @param severity the severity level
     * @return priority value for sorting
     */
    private int getSeverityPriority(OptimizationIssue.Severity severity) {
        return switch (severity) {
            case HIGH -> 0;
            case MEDIUM -> 1;
            case LOW -> 2;
            default -> 3;
        };
    }
    
    /**
     * Formats an optimization issue with enhanced display including cardinality information
     * and specific recommendations.
     * 
     * @param issue the optimization issue to format
     * @param issueNumber the issue number for display
     * @param result the full analysis result for additional context
     * @return formatted string representation of the issue
     */
    private String formatOptimizationIssueEnhanced(OptimizationIssue issue, int issueNumber, 
                                                  QueryOptimizationResult result) {
        StringBuilder formatted = new StringBuilder();
        
        // Issue header with severity indicator
        String severityIcon = getSeverityIcon(issue.severity());
        formatted.append(String.format("  %s Issue #%d [%s PRIORITY]: %s", 
                                      severityIcon, issueNumber, 
                                      issue.severity().toString(),
                                      issue.description()));
        
        // Current vs recommended with cardinality information
        WhereCondition currentCondition = findConditionByColumn(result, issue.currentFirstColumn());
        WhereCondition recommendedCondition = findConditionByColumn(result, issue.recommendedFirstColumn());
        
        formatted.append(String.format("\n    Current first condition: %s", 
                                      formatConditionWithCardinality(issue.currentFirstColumn(), currentCondition)));
        formatted.append(String.format("\n    Recommended first condition: %s", 
                                      formatConditionWithCardinality(issue.recommendedFirstColumn(), recommendedCondition)));
        
        // Performance impact explanation
        formatted.append(String.format("\n    Performance Impact: %s", 
                                      getPerformanceImpactExplanation(issue.severity())));
        
        return formatted.toString();
    }
    
    /**
     * Gets the appropriate icon for the severity level.
     * 
     * @param severity the severity level
     * @return icon string for the severity
     */
    private String getSeverityIcon(OptimizationIssue.Severity severity) {
        return switch (severity) {
            case HIGH -> "🔴";
            case MEDIUM -> "🟡";
            case LOW -> "🟢";
            default -> "⚪";
        };
    }
    
    /**
     * Finds a WHERE condition by column name from the analysis result.
     * 
     * @param result the analysis result
     * @param columnName the column name to find
     * @return the matching WhereCondition or null if not found
     */
    private WhereCondition findConditionByColumn(QueryOptimizationResult result, String columnName) {
        return result.getWhereConditions().stream()
            .filter(condition -> columnName.equals(condition.columnName()))
            .findFirst()
            .orElse(null);
    }
    
    /**
     * Formats a condition with cardinality information for enhanced reporting.
     * 
     * @param columnName the column name
     * @param condition the WhereCondition object (may be null)
     * @return formatted string with cardinality information
     */
    private String formatConditionWithCardinality(String columnName, WhereCondition condition) {
        if (condition != null) {
            return String.format("%s (%s cardinality)", columnName, 
                               condition.cardinality().toString().toLowerCase());
        } else {
            return columnName + " (cardinality unknown)";
        }
    }
    
    /**
     * Gets a performance impact explanation based on severity level.
     * 
     * @param severity the severity level
     * @return explanation of the performance impact
     */
    private String getPerformanceImpactExplanation(OptimizationIssue.Severity severity) {
        return switch (severity) {
            case HIGH -> "Significant performance degradation likely - low cardinality column filters fewer rows";
            case MEDIUM -> "Moderate performance improvement possible - better column ordering can reduce query time";
            case LOW -> "Minor performance optimization opportunity - small potential gains";
            default -> "Performance impact unknown";
        };
    }
    
    /**
     * Adds specific recommendations for column reordering in WHERE clauses.
     * 
     * @param result the analysis result
     * @param issues the list of optimization issues
     */
    private void addColumnReorderingRecommendations(QueryOptimizationResult result, List<OptimizationIssue> issues) {
        if (issues.isEmpty()) {
            return;
        }
        
        System.out.println("  📋 RECOMMENDED ACTIONS:");
        
        // Group recommendations by priority
        List<OptimizationIssue> highPriorityIssues = issues.stream()
            .filter(OptimizationIssue::isHighSeverity)
            .toList();
        
        List<OptimizationIssue> mediumPriorityIssues = issues.stream()
            .filter(OptimizationIssue::isMediumSeverity)
            .toList();
        
        // High priority recommendations
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
        
        // Medium priority recommendations
        if (!mediumPriorityIssues.isEmpty()) {
            System.out.println("    🟡 MEDIUM PRIORITY:");
            for (OptimizationIssue issue : mediumPriorityIssues) {
                System.out.println(String.format("      • Consider reordering: place '%s' before '%s' in WHERE clause", 
                                                issue.recommendedFirstColumn(), issue.currentFirstColumn()));
            }
        }
        
        // General optimization tips
        System.out.println("    💡 OPTIMIZATION TIPS:");
        System.out.println("      • Primary key columns should appear first when possible");
        System.out.println("      • Unique indexed columns are more selective than non-unique columns");
        System.out.println("      • Avoid leading with boolean or low-cardinality columns");
        System.out.println("      • Consider adding indexes for frequently queried columns");
    }

    public static void main(String[] args) throws Exception {
        Settings.loadConfigMap();
        AbstractCompiler.preProcess();
        
        String basePath = (String) Settings.getProperty("base_path");
        if (basePath == null) {
            System.err.println("base_path not found in generator.yml");
            System.exit(1);
        }

        String liquibaseXml = Paths.get(basePath, "src/main/resources/db/changelog/db.changelog-master.xml").toString();
        File liquibaseFile = new File(liquibaseXml);
        if (!liquibaseFile.exists()) {
            System.err.println("Liquibase file not found: " + liquibaseXml);
            System.exit(1);
        }

        // Parse optional CLI parameters for cardinality overrides
        Set<String> lowOverride = parseListArg(args, "--low-cardinality=");
        Set<String> highOverride = parseListArg(args, "--high-cardinality=");
        if ((lowOverride != null && !lowOverride.isEmpty()) || (highOverride != null && !highOverride.isEmpty())) {
            CardinalityAnalyzer.configureUserDefinedCardinality(
                lowOverride != null ? lowOverride : new HashSet<>(),
                highOverride != null ? highOverride : new HashSet<>()
            );
        }

        QueryOptimizationChecker checker = new QueryOptimizationChecker(liquibaseXml);
        checker.analyze();
    }

    private static Set<String> parseListArg(String[] args, String prefix) {
        if (args == null || args.length == 0) return new HashSet<>();
        for (String arg : args) {
            if (arg != null && arg.startsWith(prefix)) {
                String value = arg.substring(prefix.length());
                HashSet<String> set = new HashSet<>();
                if (!value.isEmpty()) {
                    for (String token : value.split(",")) {
                        if (token != null) {
                            String t = token.trim().toLowerCase();
                            if (!t.isEmpty()) set.add(t);
                        }
                    }
                }
                return set;
            }
        }
        return new HashSet<>();
    }
}
