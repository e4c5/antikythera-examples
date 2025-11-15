package sa.com.cloudsolutions.antikythera.examples;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.examples.util.LiquibaseGenerator;
import sa.com.cloudsolutions.antikythera.examples.util.RepositoryAnalyzer;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;
import sa.com.cloudsolutions.antikythera.generator.TypeWrapper;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;
import sa.com.cloudsolutions.antikythera.parser.RepositoryParser;
import sa.com.cloudsolutions.liquibase.Indexes;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

@SuppressWarnings({"java:S3457", "java:S106"})
public class QueryOptimizationChecker {

    protected static final Logger logger = LoggerFactory.getLogger(QueryOptimizationChecker.class);

    protected final RepositoryParser repositoryParser;
    protected final QueryAnalysisEngine analysisEngine;
    protected final File liquibaseXmlPath;
    protected final GeminiAIService aiService;
    protected final LiquibaseGenerator liquibaseGenerator;

    // Aggregated counters for summary and exit code logic
    protected int totalQueriesAnalyzed = 0;
    protected int totalHighPriorityRecommendations = 0;
    protected int totalMediumPriorityRecommendations = 0;

    // Aggregated, de-duplicated suggestions for new indexes (key format: table|column)
    protected final LinkedHashSet<String> suggestedNewIndexes = new LinkedHashSet<>();
    // Multi-column index suggestions (key format: table|column1,column2,...)
    protected final LinkedHashSet<String> suggestedMultiColumnIndexes = new LinkedHashSet<>();
    // Counters for consolidated index actions
    protected int totalIndexCreateRecommendations = 0;
    protected int totalIndexDropRecommendations = 0;

    protected final List<QueryOptimizationResult> results = new ArrayList<>();

    // Token usage tracking for AI service
    protected final TokenUsage cumulativeTokenUsage = new TokenUsage();

    // Quiet mode flag - suppresses detailed output, shows only changes
    protected static boolean quietMode = false;

    /**
     * Creates a new QueryOptimizationChecker that uses RepositoryParser for comprehensive query analysis.
     *
     * @param liquibaseXmlPath path to the Liquibase XML file for database metadata
     * @throws Exception if initialization fails
     */
    public QueryOptimizationChecker(File liquibaseXmlPath) throws Exception {
        this.liquibaseXmlPath = liquibaseXmlPath;
        // Load database metadata for cardinality analysis
        Map<String, Set<Indexes.IndexInfo>> indexMap = Indexes.load(liquibaseXmlPath);

        // Initialize components
        CardinalityAnalyzer.setIndexMap(indexMap);
        this.analysisEngine = new QueryAnalysisEngine();
        this.repositoryParser = new RepositoryParser();

        // Initialize AI service components
        this.aiService = new GeminiAIService();
        Map<String, Object> aiConfig = (Map<String, Object>) Settings.getProperty("ai_service");
        if (aiConfig == null) {
            aiConfig = new HashMap<>();
        }
        this.aiService.configure(aiConfig);
        // Initialize Liquibase generator with default configuration
        this.liquibaseGenerator = new LiquibaseGenerator();
    }

    /**
     * Analyzes all JPA repositories using RepositoryParser to extract and analyze queries.
     *
     */
    public void analyze() throws IOException, ReflectiveOperationException, InterruptedException {
        Map<String, TypeWrapper> resolvedTypes = AntikytheraRunTime.getResolvedTypes();
        int i = 0;
        int repositoriesProcessed = 0;
        for (Map.Entry<String, TypeWrapper> entry : resolvedTypes.entrySet()) {
            String fullyQualifiedName = entry.getKey();
            TypeWrapper typeWrapper = entry.getValue();

            if (RepositoryAnalyzer.isJpaRepository(typeWrapper)) {
                results.clear(); // Clear results for each repository

                System.out.println("\n" + "=".repeat(80));
                System.out.printf("Analyzing Repository: %s%n", fullyQualifiedName);
                System.out.println("=".repeat(80));

                analyzeRepository(fullyQualifiedName, typeWrapper);
                repositoriesProcessed++;
                i++;
                if (i == 1) {
                    break;
                }
            }
        }

        System.out.printf("\n✅ Successfully analyzed %d out of %d repositories%n", repositoriesProcessed, i);
    }

    /**
     * Analyzes a repository using a new LLM-first approach.
     * 1. Collect raw methods and send to LLM first (no programmatic analysis)
     * 2. Get LLM recommendations
     * 3. Do index analysis based on LLM recommendations
     * 4. Generate final output
     *
     * @param fullyQualifiedName the fully qualified class name of the repository
     * @param typeWrapper the TypeWrapper representing the repository
     */
    protected void analyzeRepository(String fullyQualifiedName, TypeWrapper typeWrapper) throws IOException, ReflectiveOperationException, InterruptedException {
        repositoryParser.compile(AbstractCompiler.classToPath(fullyQualifiedName));
        repositoryParser.processTypes();
        repositoryParser.buildQueries();

        // Step 1: Collect raw methods for LLM analysis (no programmatic analysis yet)
        Collection<RepositoryQuery> rawQueries = repositoryParser.getAllQueries();

        // Step 2: Send raw methods to LLM first
        List<OptimizationIssue> llmRecommendations = sendRawQueriesToLLM(fullyQualifiedName, rawQueries);

        // Step 3: Analyze LLM recommendations and check indexes
        List<QueryOptimizationResult> finalResults = analyzeLLMRecommendations(llmRecommendations, rawQueries.stream().toList());

        // Step 4: Report final results
        for (QueryOptimizationResult result : finalResults) {
            results.add(result);
            reportOptimizationResults(result);
        }
    }


    /**
     * Sends raw queries to LLM for optimization recommendations.
     * No programmatic analysis is done beforehand - LLM gets the raw methods.
     */
    private List<OptimizationIssue> sendRawQueriesToLLM(String repositoryName, Collection<RepositoryQuery> rawQueries) throws IOException, InterruptedException {
        // Create a batch with raw queries and basic cardinality information
        QueryBatch batch = createRawQueryBatch(repositoryName, rawQueries);

        // Send batch to AI service for analysis
        List<OptimizationIssue> llmRecommendations = aiService.analyzeQueryBatch(batch);

        // Track and report token usage
        TokenUsage tokenUsage = aiService.getLastTokenUsage();
        cumulativeTokenUsage.add(tokenUsage);
        if (!quietMode) {
            System.out.printf("🤖 AI Analysis for %s: %s%n", repositoryName, tokenUsage.getFormattedReport());
        }

        return llmRecommendations;
    }

    /**
     * Creates a QueryBatch with raw queries and actual WHERE clause column cardinality information.
     * Uses QueryAnalysisEngine to extract actual columns from WHERE clauses and method parameters.
     */
    QueryBatch createRawQueryBatch(String repositoryName, Collection<RepositoryQuery> rawQueries) {
        QueryBatch batch = new QueryBatch(repositoryName);

        // Add all raw queries to the batch
        for (RepositoryQuery query : rawQueries) {
            batch.addQuery(query);
            addWhereClauseColumnCardinality(batch, query);
        }

        return batch;
    }

    /**
     * Adds actual WHERE clause column cardinality information using QueryAnalysisEngine.
     * Extracts columns from WHERE clauses and method parameters to get real query column usage.
     */
    void addWhereClauseColumnCardinality(QueryBatch batch, RepositoryQuery query) {
        // Use existing QueryAnalysisEngine to extract WHERE conditions from the actual query
        QueryOptimizationResult tempResult = analysisEngine.analyzeQuery(query);
        List<WhereCondition> whereConditions = tempResult.getWhereConditions();

        // Add cardinality information for each WHERE clause column
        for (WhereCondition condition : whereConditions) {
            String columnName = condition.columnName();
            CardinalityLevel cardinality = condition.cardinality();

            if (columnName != null && cardinality != null) {
                batch.addColumnCardinality(columnName, cardinality);
            }
        }
    }

    /**
     * Analyzes LLM recommendations and checks for required indexes.
     * This is where we do our programmatic analysis AFTER getting LLM recommendations.
     */
    List<QueryOptimizationResult> analyzeLLMRecommendations(List<OptimizationIssue> llmRecommendations, List<RepositoryQuery> rawQueries) {
        List<QueryOptimizationResult> finalResults = new ArrayList<>();

        for (int i = 0; i < llmRecommendations.size() && i < rawQueries.size(); i++) {
            OptimizationIssue llmRecommendation = llmRecommendations.get(i);
            RepositoryQuery rawQuery = rawQueries.get(i);

            QueryOptimizationResult result = createResultWithIndexAnalysis(llmRecommendation, rawQuery);
            finalResults.add(result);
        }

        return finalResults;
    }

    /**
     * Creates a complete QueryOptimizationResult from an LLM recommendation with index analysis.
     * This merged method combines the functionality of creating the result and analyzing indexes.
     * Uses QueryAnalysisEngine to extract WHERE conditions and the Indexes class to determine missing indexes.
     */
    QueryOptimizationResult createResultWithIndexAnalysis(OptimizationIssue llmRecommendation, RepositoryQuery rawQuery) {
        // Use QueryAnalysisEngine to extract WHERE conditions from the actual query
        // This is independent of LLM recommendations
        QueryOptimizationResult engineResult = analysisEngine.analyzeQuery(rawQuery);
        List<WhereCondition> whereConditions = engineResult.getWhereConditions();

        // Analyze indexes based on actual WHERE conditions, not LLM recommendations
        // Each condition now includes its own table name, which is critical for JOIN queries
        List<String> requiredIndexes = new ArrayList<>();

        for (WhereCondition condition : whereConditions) {
            String tableName = condition.tableName(); // Use table from condition (supports JOINs)
            String columnName = condition.columnName();
            CardinalityLevel cardinality = condition.cardinality();

            // Check if index is needed for this column
            if (cardinality != null && cardinality != CardinalityLevel.LOW) {
                if (!hasOptimalIndexForColumn(tableName, columnName)) {
                    // Index is missing or not optimal - add to required indexes
                    String indexRecommendation = String.format("%s.%s", tableName, columnName);
                    requiredIndexes.add(indexRecommendation);
                }
            }
        }

        // Create enhanced optimization issue with index analysis
        OptimizationIssue enhancedRecommendation = new OptimizationIssue(
                llmRecommendation.query(),
                llmRecommendation.currentColumnOrder(),
                llmRecommendation.recommendedColumnOrder(),
                llmRecommendation.description(),
                llmRecommendation.severity(),
                llmRecommendation.aiExplanation(),
                llmRecommendation.optimizedQuery()
        );

        QueryOptimizationResult result = new QueryOptimizationResult(rawQuery, whereConditions);
        result.setIndexSuggestions(requiredIndexes);
        result.setOptimizationIssue(enhancedRecommendation);
        return result;
    }

    /**
     * Checks if there's an optimal index for the given column using the Indexes class.
     * This method provides more comprehensive index analysis than just checking for leading columns.
     *
     * @param tableName the table name
     * @param columnName the column name
     * @return true if an optimal index exists, false otherwise
     */
    boolean hasOptimalIndexForColumn(String tableName, String columnName) {
        if (CardinalityAnalyzer.hasIndexWithLeadingColumn(tableName, columnName)) {
            return true;
        }

        // Get the index map from cardinality analyzer to do more detailed analysis
        Map<String, Set<Indexes.IndexInfo>> indexMap = CardinalityAnalyzer.getIndexMap();
        indexMap.get(tableName);

        return false;
    }


    /**
     * Reports the optimization analysis results with enhanced formatting and severity-based prioritization.
     * Enhanced to include repository class name and method name from Callable objects,
     * specific recommendations for column reordering, and confirmation reporting for optimized queries.
     *
     * @param result the analysis results to report
     */
    void reportOptimizationResults(QueryOptimizationResult result) {
        OptimizationIssue issue = result.getOptimizationIssue();

        if (issue == null) {
            // Enhanced confirmation reporting for already optimized queries
            if (!quietMode) {
                reportOptimizedQuery(result);
            }
        } else {
            // Report optimization issues with severity-based prioritization
            if (quietMode) {
                reportOptimizationIssuesQuiet(result);
            } else {
                reportOptimizationIssues(result);
            }
        }
    }

    /**
     * Reports confirmation for already optimized queries with cardinality information.
     *
     * @param result the analysis results for an optimized query
     */
    void reportOptimizedQuery(QueryOptimizationResult result) {
        if (!result.getWhereConditions().isEmpty()) {
            WhereCondition firstCondition = result.getFirstCondition();
            String cardinalityInfo = firstCondition != null ?
                    String.format(" (First condition uses %s cardinality column: %s)",
                            firstCondition.cardinality().toString().toLowerCase(),
                            firstCondition.columnName()) : "";

            System.out.printf("✓ OPTIMIZED: %s.%s - Query is already optimized%s%n",
                    result.getQuery().getClassname(),
                    result.getMethodName(),
                    cardinalityInfo);

            // Print the full WHERE clause and query information
            printQueryDetails(result);
        }
    }

    /**
     * Reports optimization issues with severity-based prioritization and enhanced recommendations.
     *
     * @param result the analysis results
     */
    void reportOptimizationIssues(QueryOptimizationResult result) {
        // Sort issues by severity (HIGH -> MEDIUM -> LOW) for prioritized reporting
        OptimizationIssue issue = result.getOptimizationIssue();

        if (issue == null) {
            return;
        }
        // Update global recommendation counters
        int highCount = issue.isHighSeverity() ? 1 : 0;
        int mediumCount = issue.isMediumSeverity() ? 1 : 0;
        this.totalHighPriorityRecommendations += highCount;
        this.totalMediumPriorityRecommendations += mediumCount;

        // Report header with summary
        System.out.printf("\n⚠ OPTIMIZATION NEEDED: %s.%s%n",
                result.getQuery().getClassname(),
                result.getMethodName());

        // Print the full WHERE clause and query information
        printQueryDetails(result);

        System.out.println(formatOptimizationIssueEnhanced(issue, result));


        if (!result.getIndexSuggestions().isEmpty()) {
            System.out.println("    📋 Required Indexes:");
            for (String indexRecommendation : result.getIndexSuggestions()) {
                String[] parts = indexRecommendation.split("\\.", 2);
                if (parts.length == 2) {
                    String table = parts[0];
                    String column = parts[1];
                    boolean hasExistingIndex = CardinalityAnalyzer.hasIndexWithLeadingColumn(table, column);
                    String status = hasExistingIndex ? "✓ EXISTS" : "⚠ MISSING";
                    System.out.printf("      • %s.%s [%s]\n", table, column, status);
                }
            }
        }

        // Add specific recommendations for column reordering
        addColumnReorderingRecommendations(issue);

        // Collect any index creation suggestions for consolidation at the end
        collectIndexSuggestions(result);

        System.out.println(); // Add blank line for readability
    }

    /**
     * Gets the priority value for severity-based sorting (lower number = higher priority).
     *
     * @param severity the severity level
     * @return priority value for sorting
     */
    int getSeverityPriority(OptimizationIssue.Severity severity) {
        return switch (severity) {
            case HIGH -> 0;
            case MEDIUM -> 1;
            case LOW -> 2;
            default -> 3;
        };
    }

    /**
     * Formats an optimization issue with enhanced display including cardinality information,
     * AI explanations, and required indexes.
     *
     * @param issue the optimization issue to format
     * @param result the full analysis result for additional context
     * @return formatted string representation of the issue
     */
    String formatOptimizationIssueEnhanced(OptimizationIssue issue, QueryOptimizationResult result) {
        StringBuilder formatted = new StringBuilder();

        // Issue header with severity indicator
        String severityIcon = getSeverityIcon(issue.severity());
        formatted.append(String.format("  %s [%s PRIORITY]: %s",
                severityIcon, issue.severity(),
                issue.description()));

        // Show WHERE clause conditions if available
        if (!result.getWhereConditions().isEmpty()) {
            formatted.append("\n    🔍 WHERE Clause Conditions:");
            for (WhereCondition condition : result.getWhereConditions()) {
                formatted.append(String.format("\n      • %s %s ? (%s cardinality, position %d)",
                        condition.columnName(),
                        condition.operator(),
                        condition.cardinality().toString().toLowerCase(),
                        condition.position() + 1));
            }
        }

        // Current vs recommended with cardinality information
        WhereCondition currentCondition = findConditionByColumn(result, issue.currentFirstColumn());
        WhereCondition recommendedCondition = findConditionByColumn(result, issue.recommendedFirstColumn());

        formatted.append(String.format("\n    Current first condition: %s",
                formatConditionWithCardinality(issue.currentFirstColumn(), currentCondition)));
        formatted.append(String.format("\n    Recommended first condition: %s",
                formatConditionWithCardinality(issue.recommendedFirstColumn(), recommendedCondition)));

        // AI explanation
        if (issue.hasAIRecommendation()) {
            formatted.append(String.format("\n    🤖 AI Explanation: %s", issue.aiExplanation()));
        }

        return formatted.toString();
    }

    /**
     * Gets the appropriate icon for the severity level.
     *
     * @param severity the severity level
     * @return icon string for the severity
     */
    String getSeverityIcon(OptimizationIssue.Severity severity) {
        return switch (severity) {
            case HIGH -> "🔴";
            case MEDIUM -> "🟡";
            case LOW -> "🟢";
            default -> "⚪";
        };
    }

    /**
     * Reports optimization issues in quiet mode - only shows method signatures and queries changed.
     *
     * @param result the analysis results
     */
    void reportOptimizationIssuesQuiet(QueryOptimizationResult result) {
        // Update global recommendation counters
        OptimizationIssue issue = result.getOptimizationIssue();
        if (issue == null) {
            return ;
        }

        int highCount = issue.isHighSeverity() ? 1 : 0;
        int mediumCount = issue.isMediumSeverity() ? 1 : 0;
        this.totalHighPriorityRecommendations += highCount;
        this.totalMediumPriorityRecommendations += mediumCount;

        // Collect any index creation suggestions for consolidation at the end
        collectIndexSuggestions(result);

        if (issue.optimizedQuery() != null) {
            System.out.printf("Repository: %s%n", result.getQuery().getClassname());
            System.out.printf("Method:     %s → %s%n",
                    issue.query().getMethodName(),
                    issue.optimizedQuery().getMethodName());
            System.out.println("\nOriginal Query:");
            System.out.println("  " + issue.query().getStatement().toString());
            System.out.println("\nOptimized Query:");
            System.out.println("  " + issue.optimizedQuery().getStatement().toString());

            // Show parameter order change if applicable
            if (issue.currentColumnOrder() != null && issue.recommendedColumnOrder() != null &&
                    !issue.currentColumnOrder().equals(issue.recommendedColumnOrder())) {
                System.out.printf("\nParameter Order Change:%n");
                System.out.printf("  Before: %s%n", String.join(", ", issue.currentColumnOrder()));
                System.out.printf("  After:  %s%n", String.join(", ", issue.recommendedColumnOrder()));
            }
            System.out.println("=".repeat(80));
        }
    }

    /**
     * Enables or disables quiet mode.
     * @param enabled true to enable quiet mode, false for normal output
     */
    public static void setQuietMode(boolean enabled) {
        quietMode = enabled;
    }

    /**
     * Checks if quiet mode is enabled.
     * @return true if quiet mode is enabled
     */
    public static boolean isQuietMode() {
        return quietMode;
    }

    /**
     * Finds a WHERE condition by column name from the analysis result.
     *
     * @param result the analysis result
     * @param columnName the column name to find
     * @return the matching WhereCondition or null if not found
     */
    WhereCondition findConditionByColumn(QueryOptimizationResult result, String columnName) {
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
    String formatConditionWithCardinality(String columnName, WhereCondition condition) {
        if (condition != null) {
            return String.format("%s (%s cardinality)", columnName,
                    condition.cardinality().toString().toLowerCase());
        } else {
            return columnName + " (cardinality unknown)";
        }
    }

    /**
     * Prints the WHERE clause details for a query optimization result.
     * Shows the original WHERE clause and optimized WHERE clause when available.
     * This method eliminates code duplication between reportOptimizedQuery and reportOptimizationIssues.
     *
     * @param result the query optimization result to print details for
     */
    void printQueryDetails(QueryOptimizationResult result) {
        // Print the original WHERE clause
        String originalWhereClause = result.getFullWhereClause();
        if (!originalWhereClause.isEmpty()) {
            System.out.printf("  🔍 Original WHERE: %s%n", originalWhereClause);
        }

        OptimizationIssue firstIssue = result.getOptimizationIssue();

        if (firstIssue != null && firstIssue.optimizedQuery() != null) {
            // Use the existing QueryAnalysisEngine to analyze the optimized query
            QueryOptimizationResult optimizedResult = analysisEngine.analyzeQuery(firstIssue.optimizedQuery());
            String optimizedWhereClause = optimizedResult.getFullWhereClause();
            if (!optimizedWhereClause.isEmpty() && !optimizedWhereClause.equals(originalWhereClause)) {
                System.out.printf("  ✨ Optimized WHERE: %s%n", optimizedWhereClause);
            }
        }
    }


    /**
     * Adds specific recommendations for column reordering in WHERE clauses.
     * Enhanced to handle AI recommendations and required indexes.
     *
     * @param issue the list of optimization issues
     */
    void addColumnReorderingRecommendations(OptimizationIssue issue) {
        if (issue == null) {
            return;
        }

        StringBuilder recommendations = new StringBuilder();

        if (issue.isHighSeverity()) {
            addPriorityRecommendations(recommendations, issue, "🔴 HIGH PRIORITY:",
                    "Move '%s' condition to the beginning of WHERE clause");
        }

        if (issue.isMediumSeverity()) {
            addPriorityRecommendations(recommendations, issue, "🟡 MEDIUM PRIORITY:",
                    "Consider reordering: place '%s' before '%s' in WHERE clause");
        }

        // Only print if we have recommendations
        if (!recommendations.isEmpty()) {
            System.out.println("  📋 RECOMMENDED ACTIONS:");
            System.out.print(recommendations);
        }
    }

    /**
     * Helper method to add priority-specific recommendations to the StringBuilder.
     * Eliminates code duplication between high and medium priority processing.
     */
    void addPriorityRecommendations(StringBuilder recommendations, OptimizationIssue issue,
                                    String priorityHeader, String reorderingTemplate) {
        recommendations.append("    ").append(priorityHeader).append("\n");

        // Skip reordering recommendation when the recommended column is already first
        if (issue.recommendedFirstColumn().equals(issue.currentFirstColumn())) {
            return;
        }

        // Add reordering recommendation
        if (reorderingTemplate.contains("%s") && reorderingTemplate.contains("before")) {
            // Medium priority template with two parameters
            recommendations.append(String.format("      • " + reorderingTemplate + "%n",
                    issue.recommendedFirstColumn(), issue.currentFirstColumn()));
        } else {
            // High priority template with one parameter
            recommendations.append(String.format("      • " + reorderingTemplate + "%n",
                    issue.recommendedFirstColumn()));
            recommendations.append(String.format("        Replace: WHERE %s = ? AND %s = ?%n",
                    issue.currentFirstColumn(), issue.recommendedFirstColumn()));
            recommendations.append(String.format("        With:    WHERE %s = ? AND %s = ?%n",
                    issue.recommendedFirstColumn(), issue.currentFirstColumn()));
        }
    }

    String inferTableNameFromQuery(String queryText) {
        if (queryText == null) {
            return null;
        }
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("(?i)\\bfrom\\s+([\\\"`'\\w\\.]+)");
        java.util.regex.Matcher m = p.matcher(queryText);
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

    String inferTableNameFromRepositoryClassName(String repositoryClass) {
        String simple = repositoryClass;
        int dot = simple.lastIndexOf('.');
        if (dot >= 0) simple = simple.substring(dot + 1);
        if (simple.endsWith("Repository")) simple = simple.substring(0, simple.length() - "Repository".length());
        // convert CamelCase to snake_case
        return simple.replaceAll("(?<!^)([A-Z])", "_$1").toLowerCase();
    }

    String buildLiquibaseNonLockingIndexChangeSet(String tableName, String columnName) {
        return liquibaseGenerator.createIndexChangeset(tableName, columnName);
    }

    /**
     * Builds a Liquibase changeset for creating a multi-column index.
     * Uses the consolidated LiquibaseGenerator utility.
     *
     * @param tableName the table name
     * @param columns list of column names in index order
     * @return XML changeset string
     */
    String buildLiquibaseMultiColumnIndexChangeSet(String tableName, LinkedHashSet<String> columns) {
        return liquibaseGenerator.createMultiColumnIndexChangeset(tableName, columns);
    }

    String buildLiquibaseDropIndexChangeSet(String indexName) {
        return liquibaseGenerator.createDropIndexChangeset(indexName);
    }

    void collectIndexSuggestions(QueryOptimizationResult result) {
        // Group columns by table - critical for JOIN queries where columns come from multiple tables
        Map<String, List<String>> columnsByTable = new HashMap<>();

        for (WhereCondition condition : result.getWhereConditions()) {
            if (condition.cardinality() != CardinalityLevel.LOW) {
                String tableName = condition.tableName();
                columnsByTable.computeIfAbsent(tableName, k -> new ArrayList<>()).add(condition.columnName());
            }
        }

        // Process each table's columns separately to create table-specific indexes
        for (Map.Entry<String, List<String>> entry : columnsByTable.entrySet()) {
            String table = entry.getKey();
            List<String> columnsForTable = entry.getValue();

            // Filter out columns that already have indexes and low-cardinality columns
            List<String> filteredColumns = new ArrayList<>();
            for (String column : columnsForTable) {
                CardinalityLevel cardinality = CardinalityAnalyzer.analyzeColumnCardinality(table, column);
                if (cardinality != CardinalityLevel.LOW) {
                    filteredColumns.add(column);
                }
            }

            if (filteredColumns.size() > 1) {
                // Create multi-column index for this specific table
                String key = (table + "|" + String.join(",", filteredColumns)).toLowerCase();
                suggestedMultiColumnIndexes.add(key);
            } else if (filteredColumns.size() == 1) {
                // Only one column needs indexing - create single-column index
                String column = filteredColumns.get(0);
                boolean hasExisting = CardinalityAnalyzer.hasIndexWithLeadingColumn(table, column);
                if (!hasExisting) {
                    String key = (table + "|" + column).toLowerCase();
                    suggestedNewIndexes.add(key);
                }
            }
        }
    }

    public void printConsolidatedIndexActions() {
        GeneratedChangesets generated = generateAllChangesets();
        totalIndexCreateRecommendations = generated.totalCreateCount;
        totalIndexDropRecommendations = generated.dropCount;

        // Report create recommendations
        if (generated.totalCreateCount > 0) {
            if (generated.multiColumnCount > 0) {
                System.out.println("\n📦 SUGGESTED MULTI-COLUMN INDEXES (Optimized for Query Performance):");
                for (String key : suggestedMultiColumnIndexes) {
                    String[] parts = key.split("\\|", 2);
                    String table = parts[0];
                    String columnsStr = parts[1];
                    LinkedHashSet<String> columns = new LinkedHashSet<>(List.of(columnsStr.split(",")));
                    String snippet = buildLiquibaseMultiColumnIndexChangeSet(table, columns);
                    System.out.printf("\n  Multi-column index for %s (%s):%n", table, String.join(", ", columns));
                    System.out.println("    Note: First column is covered by this composite index - no separate index needed");
                    System.out.println(indent(snippet, 2));
                }
            }
            if (!suggestedNewIndexes.isEmpty()) {
                System.out.println("\n📦 SUGGESTED SINGLE-COLUMN INDEXES (AI-Enhanced + Cardinality Analysis):");
                for (String key : suggestedNewIndexes) {
                    String[] parts = key.split("\\|", 2);
                    String table = parts[0];
                    String column = parts[1];
                    boolean coveredByComposite = isCoveredByComposite(table, column);
                    if (coveredByComposite) {
                        System.out.printf("\n  ⏭️  Skipping %s.%s - already covered by multi-column index%n", table, column);
                        continue;
                    }
                    String snippet = buildLiquibaseNonLockingIndexChangeSet(table, column);
                    System.out.printf("\n  Index for %s.%s:%n", table, column);
                    System.out.println(indent(snippet, 2));
                }
            }
            System.out.printf("\n  💡 Total index creation recommendations: %d (%d multi-column, %d single-column)%n",
                    totalIndexCreateRecommendations, generated.multiColumnCount, generated.singleColumnCount);
        } else {
            System.out.println("\n📦 No index creation recommendations found.");
        }

        // Report drop recommendations
        if (totalIndexDropRecommendations > 0) {
            System.out.println("\n🗑 SUGGESTED INDEX DROPS (leading low-cardinality columns):");
            // Reconstruct drop candidates (for printing detail) – relies on same logic as generation
            java.util.LinkedHashSet<String> dropCandidates = new java.util.LinkedHashSet<>();
            java.util.Map<String, java.util.Set<Indexes.IndexInfo>> map = CardinalityAnalyzer.getIndexMap();
            if (map != null) {
                for (var entry : map.entrySet()) {
                    String table = entry.getKey();
                    for (var idx : entry.getValue()) {
                        if ("INDEX".equals(idx.type()) && idx.columns() != null && !idx.columns().isEmpty()) {
                            String first = idx.columns().getFirst();
                            CardinalityLevel card = CardinalityAnalyzer.analyzeColumnCardinality(table, first);
                            if (card == CardinalityLevel.LOW && !idx.name().isEmpty()) {
                                dropCandidates.add(idx.name());
                            }
                        }
                    }
                }
            }
            for (String idxName : dropCandidates) {
                String snippet = buildLiquibaseDropIndexChangeSet(idxName);
                System.out.printf("\n  Drop index %s:%n", idxName);
                System.out.println(indent(snippet, 2));
            }
            System.out.printf("\n  💡 Total index drop recommendations: %d%n", totalIndexDropRecommendations);
        } else {
            System.out.println("\n🗑 No index drop recommendations found.");
        }
    }



    String indent(String s, int spaces) {
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
    public TokenUsage getCumulativeTokenUsage() { return cumulativeTokenUsage; }

    /**
     * Generates a Liquibase changes file from consolidated suggestions and includes it in the master file.
     * Uses the consolidated LiquibaseGenerator utility for changeset creation and file operations.
     */
    public void generateLiquibaseChangesFile() throws IOException {
        // Generate changesets for both create and drop operations
        GeneratedChangesets generated = generateAllChangesets();

        if (generated.changesets.isEmpty()) {
            return;
        }

        // Use LiquibaseGenerator to write the changeset file
        String allChangesets = String.join("\n", generated.changesets);
        LiquibaseGenerator.WriteResult result = liquibaseGenerator.writeChangesetToFile(liquibaseXmlPath, allChangesets);

        if (result.wasWritten() && result.getChangesFile() != null) {
            logger.debug("Generated Liquibase changes file: {} with {} index create recommendations ({} multi-column, {} single-column) and {} drop recommendations",
                    result.getChangesFile().getName(), generated.totalCreateCount, generated.multiColumnCount,
                    generated.singleColumnCount, generated.dropCount);
        }
    }

    /**
     * Holds the results of generating all changesets.
     */
    static class GeneratedChangesets {
        List<String> changesets = new ArrayList<>();
        int multiColumnCount = 0;
        int singleColumnCount = 0;
        int totalCreateCount = 0;
        int dropCount = 0;
    }

    /**
     * Generates all Liquibase changesets (create and drop) as a consolidated list.
     * This method is used by both the file writer and the console printer to avoid duplication.
     *
     * @return GeneratedChangesets containing all changesets and counts
     */
    GeneratedChangesets generateAllChangesets() {
        GeneratedChangesets result = new GeneratedChangesets();

        for (String key : suggestedMultiColumnIndexes) {
            String[] parts = key.split("\\|", 2);
            String table = parts[0];
            String columnsStr = parts[1];
            LinkedHashSet<String> columns = new LinkedHashSet<>(List.of(columnsStr.split(",")));
            String changeSet = buildLiquibaseMultiColumnIndexChangeSet(table, columns);

            result.changesets.add(indentXml(changeSet, 4));
            result.multiColumnCount++;
        }

        for (String key : suggestedNewIndexes) {
            String[] parts = key.split("\\|", 2);
            String table = parts[0];
            String column = parts[1];

            if (!isCoveredByComposite(table, column)) {

                String changeSet = buildLiquibaseNonLockingIndexChangeSet(table, column);
                result.changesets.add(indentXml(changeSet, 4));
                result.singleColumnCount++;
            }
        }
        result.totalCreateCount = result.multiColumnCount + result.singleColumnCount;

        // Add create index summary comment (or note no create recommendations)
        if (result.totalCreateCount > 0) {
            result.changesets.add("\n    <!-- Summary: " + result.totalCreateCount + " total index create recommendations " +
                    "(" + result.multiColumnCount + " multi-column, " + result.singleColumnCount + " single-column) -->");
        } else {
            result.changesets.add("\n    <!-- Summary: No index create recommendations -->");
        }

        // Analyze existing indexes to suggest drops for low-cardinality leading columns (always perform)
        java.util.LinkedHashSet<String> dropCandidates = new java.util.LinkedHashSet<>();
        java.util.Map<String, java.util.Set<Indexes.IndexInfo>> map = CardinalityAnalyzer.getIndexMap();
        if (map != null) {
            for (var entry : map.entrySet()) {
                String table = entry.getKey();
                for (var idx : entry.getValue()) {
                    if ("INDEX".equals(idx.type()) && idx.columns() != null && !idx.columns().isEmpty()) {
                        String first = idx.columns().getFirst();
                        CardinalityLevel card = CardinalityAnalyzer.analyzeColumnCardinality(table, first);
                        if (card == CardinalityLevel.LOW && !idx.name().isEmpty()) {
                            dropCandidates.add(idx.name());
                        }
                    }
                }
            }
        }

        // Add drop index changesets even if there are no create suggestions
        if (!dropCandidates.isEmpty()) {
            result.changesets.add("\n    <!-- Index Drop Recommendations (leading low-cardinality columns) -->");
            for (String idxName : dropCandidates) {
                String changeSet = buildLiquibaseDropIndexChangeSet(idxName);
                result.changesets.add("\n    <!-- Drop index " + idxName + " -->");
                result.changesets.add(indentXml(changeSet, 4));
                result.dropCount++;
            }
            result.changesets.add("\n    <!-- Summary: " + result.dropCount + " total index drop recommendations -->");
        } else {
            result.changesets.add("\n    <!-- Summary: No index drop recommendations -->");
        }

        return result;
    }

    boolean isCoveredByComposite(String table, String column) {
        return suggestedMultiColumnIndexes.stream()
                .anyMatch(mcKey -> {
                    String[] mcParts = mcKey.split("\\|", 2);
                    if (mcParts.length == 2 && mcParts[0].equals(table)) {
                        String[] cols = mcParts[1].split(",");
                        return cols.length > 0 && cols[0].equals(column);
                    }
                    return false;
                });
    }

    /**
     * Indents XML content by the specified number of spaces.
     *
     * @param xml the XML content to indent
     * @param spaces number of spaces to indent
     * @return indented XML content
     */
    String indentXml(String xml, int spaces) {
        if (xml == null || xml.isEmpty()) {
            return xml;
        }
        String indent = " ".repeat(spaces);
        return xml.lines()
                .map(line -> line.isEmpty() ? line : indent + line)
                .reduce((a, b) -> a + "\n" + b)
                .orElse(xml);
    }

    protected static File getLiquibasePath() {
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
        return liquibaseFile;
    }


    public static void main(String[] args) throws Exception {
        long s = System.currentTimeMillis();
        Settings.loadConfigMap();
        AbstractCompiler.preProcess();

        // Parse optional CLI parameters for cardinality overrides
        Set<String> lowOverride = parseListArg(args, "--low-cardinality=");
        Set<String> highOverride = parseListArg(args, "--high-cardinality=");

        CardinalityAnalyzer.configureUserDefinedCardinality(lowOverride, highOverride);

        QueryOptimizationChecker checker = new QueryOptimizationChecker(getLiquibasePath());
        checker.analyze();

        // Print consolidated index actions at end of analysis
        checker.printConsolidatedIndexActions();
        // Generate Liquibase file with suggested changes and include in master
        checker.generateLiquibaseChangesFile();

        // Print execution summary
        int queries = checker.getTotalQueriesAnalyzed();
        int high = checker.getTotalHighPriorityRecommendations();
        int medium = checker.getTotalMediumPriorityRecommendations();
        int createCount = checker.getTotalIndexCreateRecommendations();
        int dropCount = checker.getTotalIndexDropRecommendations();
        TokenUsage totalTokenUsage = checker.getCumulativeTokenUsage();

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

        // Add token usage reporting to summary
        if (totalTokenUsage.getTotalTokens() > 0) {
            System.out.println(String.format("🤖 AI Service Usage: %s", totalTokenUsage.getFormattedReport()));
        }

        System.out.println("Time taken " + (System.currentTimeMillis() - s) + " ms.");
        // Exit with non-zero if at least 1 high and at least 10 medium priority recommendations
        if (high >= 1 && medium >= 10) {
            System.exit(1);
        } else {
            System.exit(0);
        }
    }

    protected static Set<String> parseListArg(String[] args, String prefix) {
        HashSet<String> set = new HashSet<>();

        for (String arg : args) {
            if (arg.startsWith(prefix)) {
                String value = arg.substring(prefix.length());
                for (String token : value.split(",")) {
                    String t = token.trim().toLowerCase();
                    if (!t.isEmpty()) set.add(t);
                }
            }
        }
        return set;
    }

    // Package-private getters for testing
    LinkedHashSet<String> getSuggestedNewIndexes() {
        return suggestedNewIndexes;
    }

    LinkedHashSet<String> getSuggestedMultiColumnIndexes() {
        return suggestedMultiColumnIndexes;
    }
}
