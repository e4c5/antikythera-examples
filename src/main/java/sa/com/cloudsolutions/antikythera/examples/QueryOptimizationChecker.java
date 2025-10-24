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

    // Aggregated counters for summary and exit code logic
    private int totalQueriesAnalyzed = 0;
    private int totalHighPriorityRecommendations = 0;
    private int totalMediumPriorityRecommendations = 0;

    // Aggregated, de-duplicated suggestions for new indexes (key format: table|column)
    private final java.util.LinkedHashSet<String> suggestedNewIndexes = new java.util.LinkedHashSet<>();
    // Counters for consolidated index actions
    private int totalIndexCreateRecommendations = 0;
    private int totalIndexDropRecommendations = 0;

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
        // Count every repository method query analyzed
        totalQueriesAnalyzed++;

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

        // Update global recommendation counters
        int highCount = (int) issues.stream().filter(OptimizationIssue::isHighSeverity).count();
        int mediumCount = (int) issues.stream().filter(OptimizationIssue::isMediumSeverity).count();
        this.totalHighPriorityRecommendations += highCount;
        this.totalMediumPriorityRecommendations += mediumCount;
        
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

        // Collect any index creation suggestions for consolidation at the end
        collectIndexSuggestions(result, sortedIssues);
        
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
        if (!isIndexCreationForLeadingMedium(issue)) {
            formatted.append(String.format("\n    Performance Impact: %s", 
                                          getPerformanceImpactExplanation(issue.severity())));
        }
        
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
                // Skip reordering recommendation when the recommended column is already first
                if (issue.recommendedFirstColumn() != null && issue.recommendedFirstColumn().equals(issue.currentFirstColumn())) {
                    continue;
                }
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
                // Skip reordering recommendation when the recommended column is already first
                if (issue.recommendedFirstColumn() != null && issue.recommendedFirstColumn().equals(issue.currentFirstColumn())) {
                    continue;
                }
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

    // Helpers to tailor output for index recommendations
    private boolean isIndexCreationForLeadingMedium(OptimizationIssue issue) {
        if (issue == null) return false;
        boolean same = issue.recommendedFirstColumn() != null && issue.recommendedFirstColumn().equals(issue.currentFirstColumn());
        boolean mentionsCreate = issue.description() != null && issue.description().toLowerCase().contains("create an index");
        return issue.isHighSeverity() && same && mentionsCreate;
    }

    private String inferTableNameFromQuerySafe(String queryText, String repositoryClass) {
        String t = inferTableNameFromQuery(queryText);
        if (t != null) return t;
        return inferTableNameFromRepositoryClassName(repositoryClass);
    }

    private String inferTableNameFromQuery(String queryText) {
        if (queryText == null) return null;
        String lower = queryText;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("(?i)\\bfrom\\s+([\\\"`'\\w\\.]+)");
        java.util.regex.Matcher m = p.matcher(lower);
        if (m.find()) {
            String raw = m.group(1);
            // remove schema and quotes
            String last = raw;
            int dot = raw.lastIndexOf('.');
            if (dot >= 0 && dot < raw.length() - 1) last = raw.substring(dot + 1);
            last = last.replace("`", "").replace("\"", "").replace("'", "");
            return last;
        }
        return null;
    }

    private String inferTableNameFromRepositoryClassName(String repositoryClass) {
        if (repositoryClass == null) return "<TABLE_NAME>";
        String simple = repositoryClass;
        int dot = simple.lastIndexOf('.');
        if (dot >= 0) simple = simple.substring(dot + 1);
        if (simple.endsWith("Repository")) simple = simple.substring(0, simple.length() - "Repository".length());
        // convert CamelCase to snake_case
        String snake = simple.replaceAll("(?<!^)([A-Z])", "_$1").toLowerCase();
        return snake.isEmpty() ? "<TABLE_NAME>" : snake;
    }

    private String buildLiquibaseNonLockingIndexChangeSet(String tableName, String columnName) {
        if (columnName == null || columnName.isEmpty()) columnName = "<COLUMN_NAME>";
        if (tableName == null || tableName.isEmpty()) tableName = "<TABLE_NAME>";
        String idxName = ("idx_" + sanitize(tableName) + "_" + sanitize(columnName)).toLowerCase();
        String id = idxName + "_" + System.currentTimeMillis();
        return "<changeSet id=\"" + id + "\" author=\"antikythera\">\n" +
               "  <preConditions onFail=\"MARK_RAN\">\n" +
               "    <not>\n" +
               "      <indexExists tableName=\"" + tableName + "\" indexName=\"" + idxName + "\"/>\n" +
               "    </not>\n" +
               "  </preConditions>\n" +
               "  <sql dbms=\"postgresql\">CREATE INDEX CONCURRENTLY " + idxName + " ON " + tableName + " (" + columnName + ");</sql>\n" +
               "  <sql dbms=\"oracle\">CREATE INDEX " + idxName + " ON " + tableName + " (" + columnName + ") ONLINE</sql>\n" +
               "  <rollback>\n" +
               "    <sql dbms=\"postgresql\">DROP INDEX CONCURRENTLY IF EXISTS " + idxName + ";</sql>\n" +
               "    <sql dbms=\"oracle\">DROP INDEX " + idxName + "</sql>\n" +
               "  </rollback>\n" +
               "</changeSet>";
    }

    private String buildLiquibaseDropIndexChangeSet(String indexName) {
        if (indexName == null || indexName.isEmpty()) indexName = "<INDEX_NAME>";
        String id = ("drop_" + sanitize(indexName) + "_" + System.currentTimeMillis()).toLowerCase();
        return "<changeSet id=\"" + id + "\" author=\"antikythera\">\n" +
               "  <preConditions onFail=\"MARK_RAN\">\n" +
               "    <indexExists indexName=\"" + indexName + "\"/>\n" +
               "  </preConditions>\n" +
               "  <sql dbms=\"postgresql\">DROP INDEX CONCURRENTLY IF EXISTS " + indexName + ";</sql>\n" +
               "  <sql dbms=\"oracle\">DROP INDEX " + indexName + "</sql>\n" +
               "</changeSet>";
    }

    private void collectIndexSuggestions(QueryOptimizationResult result, List<OptimizationIssue> issues) {
        if (issues == null || issues.isEmpty()) return;
        List<OptimizationIssue> idxIssues = issues.stream().filter(this::isIndexCreationForLeadingMedium).toList();
        if (idxIssues.isEmpty()) return;
        for (OptimizationIssue idxIssue : idxIssues) {
            String table = inferTableNameFromQuerySafe(result.getQueryText(), result.getRepositoryClass());
            String col = idxIssue.recommendedFirstColumn() != null ? idxIssue.recommendedFirstColumn() : idxIssue.currentFirstColumn();
            if (table == null || col == null) continue;
            String key = (table + "|" + col).toLowerCase();
            suggestedNewIndexes.add(key);
        }
    }

    public void printConsolidatedIndexActions() {
        // Print consolidated suggested new indexes as raw changeSets only
        if (!suggestedNewIndexes.isEmpty()) {
            System.out.println("\n📦 SUGGESTED NEW INDEXES (consolidated):");
            int count = 0;
            for (String key : suggestedNewIndexes) {
                String[] parts = key.split("\\|", 2);
                String table = parts.length > 0 ? parts[0] : "<TABLE_NAME>";
                String column = parts.length > 1 ? parts[1] : "<COLUMN_NAME>";
                String snippet = buildLiquibaseNonLockingIndexChangeSet(table, column);
                // print only the changeSet block, no leading names or bullets
                System.out.println("\n" + indent(snippet, 0));
                count++;
            }
            totalIndexCreateRecommendations = count;
        } else {
            totalIndexCreateRecommendations = 0;
        }

        // Analyze existing indexes to suggest drops for low-cardinality leading columns
        java.util.LinkedHashSet<String> dropCandidates = new java.util.LinkedHashSet<>();
        try {
            java.util.Map<String, java.util.List<sa.com.cloudsolutions.liquibase.Indexes.IndexInfo>> map = cardinalityAnalyzer.snapshotIndexMap();
            for (var entry : map.entrySet()) {
                String table = entry.getKey();
                for (var idx : entry.getValue()) {
                    if (!"INDEX".equals(idx.type)) continue; // avoid PKs and unique constraints
                    if (idx.columns == null || idx.columns.isEmpty()) continue;
                    String first = idx.columns.get(0);
                    if (first == null) continue;
                    CardinalityLevel card = cardinalityAnalyzer.analyzeColumnCardinality(table, first);
                    if (card == CardinalityLevel.LOW && idx.name != null && !idx.name.isEmpty()) {
                        dropCandidates.add(idx.name);
                    }
                }
            }
        } catch (Exception ignore) {
            // snapshotIndexMap does not throw, this is just a safety net
        }
        if (!dropCandidates.isEmpty()) {
            System.out.println("\n🗑 SUGGESTED INDEX DROPS (leading low-cardinality columns):");
            int dcount = 0;
            for (String idxName : dropCandidates) {
                String snippet = buildLiquibaseDropIndexChangeSet(idxName);
                // print only the changeSet block, no leading names or bullets
                System.out.println("\n" + indent(snippet, 0));
                dcount++;
            }
            totalIndexDropRecommendations = dcount;
        } else {
            totalIndexDropRecommendations = 0;
        }
    }

    private String sanitize(String s) {
        return s == null ? "" : s.replaceAll("[^A-Za-z0-9_]+", "_");
    }

    private String indent(String s, int spaces) {
        String pad = " ".repeat(Math.max(0, spaces));
        return java.util.Arrays.stream(s.split("\n"))
                .map(line -> pad + line)
                .reduce((a,b) -> a + "\n" + b)
                .orElse(pad + s);
    }

    // Summary getters
    public int getTotalQueriesAnalyzed() { return totalQueriesAnalyzed; }
    public int getTotalHighPriorityRecommendations() { return totalHighPriorityRecommendations; }
    public int getTotalMediumPriorityRecommendations() { return totalMediumPriorityRecommendations; }
    public int getTotalIndexCreateRecommendations() { return totalIndexCreateRecommendations; }
    public int getTotalIndexDropRecommendations() { return totalIndexDropRecommendations; }

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

        CardinalityAnalyzer.configureUserDefinedCardinality(lowOverride, highOverride);

        QueryOptimizationChecker checker = new QueryOptimizationChecker(liquibaseXml);
        checker.analyze();

        // Print consolidated index actions at end of analysis
        checker.printConsolidatedIndexActions();

        // Print execution summary
        int queries = checker.getTotalQueriesAnalyzed();
        int high = checker.getTotalHighPriorityRecommendations();
        int medium = checker.getTotalMediumPriorityRecommendations();
        int createCount = checker.getTotalIndexCreateRecommendations();
        int dropCount = checker.getTotalIndexDropRecommendations();
        System.out.println(String.format("\nSUMMARY: Analyzed %d quer%s. Recommendations given: %d high priorit%s, %d medium priorit%s. Index actions: %d creation%s, %d drop%s.",
                queries,
                queries == 1 ? "y" : "ies",
                high,
                high == 1 ? "y" : "ies",
                medium,
                medium == 1 ? "y" : "ies",
                createCount,
                createCount == 1 ? "" : "s",
                dropCount,
                dropCount == 1 ? "" : "s"));

        // Exit with non-zero if at least 1 high and at least 10 medium priority recommendations
        if (high >= 1 && medium >= 10) {
            System.exit(1);
        } else {
            System.exit(0);
        }
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
