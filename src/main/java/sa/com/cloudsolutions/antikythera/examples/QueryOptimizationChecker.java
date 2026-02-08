package sa.com.cloudsolutions.antikythera.examples;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.examples.util.LiquibaseGenerator;
import sa.com.cloudsolutions.antikythera.exception.AntikytheraException;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;
import sa.com.cloudsolutions.antikythera.generator.TypeWrapper;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;
import sa.com.cloudsolutions.antikythera.parser.BaseRepositoryParser;
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

@SuppressWarnings({ "java:S3457", "java:S106" })
public class QueryOptimizationChecker {

    protected static final Logger logger = LoggerFactory.getLogger(QueryOptimizationChecker.class);

    protected RepositoryParser repositoryParser;
    protected QueryAnalysisEngine analysisEngine;
    protected final File liquibaseXmlPath;
    protected GeminiAIService aiService;
    protected LiquibaseGenerator liquibaseGenerator;

    protected int totalRecommendations = 0;

    // Aggregated, de-duplicated suggestions for new indexes (key format:
    // table|column)
    protected final LinkedHashSet<String> suggestedNewIndexes = new LinkedHashSet<>();
    // Multi-column index suggestions (key format: table|column1,column2,...)
    protected final LinkedHashSet<String> suggestedMultiColumnIndexes = new LinkedHashSet<>();

    protected final List<QueryAnalysisResult> results = new ArrayList<>();

    // Token usage tracking for AI service
    protected final TokenUsage cumulativeTokenUsage = new TokenUsage();

    // Quiet mode flag - suppresses detailed output, shows only changes
    protected static boolean quietMode = false;

    // Target class - if set, only analyze this specific repository
    protected static String targetClass = null;

    // Maximum number of columns allowed in a multi-column index (configurable)
    protected final int maxIndexColumns;

    // Checkpoint manager for resume capability
    protected CheckpointManager checkpointManager;

    /**
     * Creates a new QueryOptimizationChecker that uses RepositoryParser for
     * comprehensive query analysis.
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
        this.liquibaseGenerator = new LiquibaseGenerator(LiquibaseGenerator.ChangesetConfig.fromConfiguration());

        // Read max_index_columns from configuration (default: 4)
        this.maxIndexColumns = getMaxIndexColumnsFromConfig();

        // Initialize checkpoint manager
        this.checkpointManager = new CheckpointManager();
    }

    /**
     * Analyzes all JPA repositories using RepositoryParser to extract and analyze
     * queries.
     *
     * @return the number of repositories that were actually analyzed (not skipped)
     */
    public int analyze() throws IOException, ReflectiveOperationException, InterruptedException {
        // Load checkpoint for resume capability
        boolean resumed = checkpointManager.load();
        if (resumed) {
            // Restore accumulated state from checkpoint
            suggestedNewIndexes.addAll(checkpointManager.getSuggestedNewIndexes());
            suggestedMultiColumnIndexes.addAll(checkpointManager.getSuggestedMultiColumnIndexes());
            System.out.printf("🔄 Resuming from checkpoint: %d repositories already processed%n",
                    checkpointManager.getProcessedCount());
        }

        Map<String, TypeWrapper> resolvedTypes = AntikytheraRunTime.getResolvedTypes();
        int totalRepositories = 0;
        int repositoriesProcessed = 0;
        int repositoriesResumed = 0;
        int repositoriesSkippedByFilter = 0;

        logger.debug("targetClass filter value: {}", targetClass);

        for (Map.Entry<String, TypeWrapper> entry : resolvedTypes.entrySet()) {
            String fullyQualifiedName = entry.getKey();
            TypeWrapper typeWrapper = entry.getValue();

            if (BaseRepositoryParser.isJpaRepository(typeWrapper)) {
                totalRepositories++;

                // Filter by target_class if specified
                if (targetClass != null && !targetClass.equals(fullyQualifiedName)) {
                    repositoriesSkippedByFilter++;
                    logger.debug("Skipping repository (target_class filter): {}", fullyQualifiedName);
                    continue;
                }

                // Check checkpoint for resume after crash/interruption
                if (checkpointManager.isProcessed(fullyQualifiedName)) {
                    if (!quietMode) {
                        System.out.printf("⏭️ Skipping (checkpoint): %s%n", fullyQualifiedName);
                    }
                    repositoriesResumed++;
                    continue;
                }

                results.clear(); // Clear results for each repository

                System.out.println("\n" + "=".repeat(80));
                System.out.printf("Analyzing Repository: %s%n", fullyQualifiedName);
                System.out.println("=".repeat(80));
                try {
                    analyzeRepository(typeWrapper);
                    repositoriesProcessed++;

                    // Save checkpoint after successful repository analysis
                    checkpointManager.markProcessed(fullyQualifiedName);
                    checkpointManager.setIndexSuggestions(suggestedNewIndexes, suggestedMultiColumnIndexes);
                    checkpointManager.save();
                } catch (AntikytheraException ae) {
                    logger.error("Error analyzing repository {}: {}", fullyQualifiedName, ae.getMessage());
                    // Save checkpoint even on error so we don't lose progress
                    checkpointManager.setIndexSuggestions(suggestedNewIndexes, suggestedMultiColumnIndexes);
                    checkpointManager.save();
                }
            }
        }
        OptimizationStatsLogger.flush();

        // Print summary
        if (repositoriesSkippedByFilter > 0) {
            System.out.printf("\n✅ Analyzed %d repositories, skipped %d by target_class filter (total: %d)%n",
                    repositoriesProcessed, repositoriesSkippedByFilter, totalRepositories);
        } else if (repositoriesResumed > 0) {
            System.out.printf("\n✅ Analyzed %d repositories, skipped %d from checkpoint (total: %d)%n",
                    repositoriesProcessed, repositoriesResumed, totalRepositories);
        } else {
            System.out.printf("\n✅ Successfully analyzed %d repositories%n", repositoriesProcessed);
        }

        // Clear checkpoint on successful completion (all repos processed without error)
        checkpointManager.clear();

        return repositoriesProcessed;
    }

    /**
     * Analyzes a repository using a new LLM-first approach.
     * 1. Collect raw methods and send to LLM first (no programmatic analysis)
     * 2. Get LLM recommendations
     * 3. Do index analysis based on LLM recommendations
     * 4. Generate final output
     *
     * @param typeWrapper the TypeWrapper representing the repository
     */
    void analyzeRepository(TypeWrapper typeWrapper)
            throws IOException, ReflectiveOperationException, InterruptedException {
        String fullyQualifiedName = typeWrapper.getFullyQualifiedName();
        OptimizationStatsLogger.initialize(fullyQualifiedName);
        repositoryParser.compile(AbstractCompiler.classToPath(fullyQualifiedName));
        repositoryParser.processTypes();
        if (repositoryParser.getEntity() != null && repositoryParser.getEntity().getFullyQualifiedName() != null) {

            repositoryParser.buildQueries();

            // Step 1: Collect raw methods for LLM analysis (no programmatic analysis yet)
            Collection<RepositoryQuery> rawQueries = repositoryParser.getAllQueries();

            // Step 2: Send raw methods to LLM first
            List<OptimizationIssue> llmRecommendations = sendRawQueriesToLLM(fullyQualifiedName, rawQueries);

            // Step 3: Analyze LLM recommendations and check indexes
            List<QueryAnalysisResult> finalResults = analyzeLLMRecommendations(llmRecommendations,
                    rawQueries.stream().toList());

            // Step 4: Report final results
            for (QueryAnalysisResult result : finalResults) {
                results.add(result);
                reportOptimizationResults(result);
            }
        } else {
            logger.warn("Repository Entity could not be identified for {}", fullyQualifiedName);
        }
    }

    /**
     * Sends raw queries to LLM for optimization recommendations.
     * No programmatic analysis is done beforehand - LLM gets the raw methods.
     */
    private List<OptimizationIssue> sendRawQueriesToLLM(String repositoryName, Collection<RepositoryQuery> rawQueries)
            throws IOException, InterruptedException {
        List<OptimizationIssue> allRecommendations = new ArrayList<>();
        List<RepositoryQuery> queryList = new ArrayList<>(rawQueries);
        int batchSize = 5; // Process queries in small batches to avoid token limits

        for (int i = 0; i < queryList.size(); i += batchSize) {
            int end = Math.min(queryList.size(), i + batchSize);
            List<RepositoryQuery> batchQueries = queryList.subList(i, end);

            // Create a batch with raw queries and basic cardinality information
            QueryBatch batch = createQueryBatch(repositoryName, batchQueries);

            // Send batch to AI service for analysis
            List<OptimizationIssue> batchRecommendations = aiService.analyzeQueryBatch(batch);
            allRecommendations.addAll(batchRecommendations);

            // Track and report token usage
            TokenUsage tokenUsage = aiService.getLastTokenUsage();
            cumulativeTokenUsage.add(tokenUsage);
            if (!quietMode) {
                System.out.printf("🤖 AI Analysis for %s (Batch %d/%d): %s%n",
                        repositoryName,
                        (i / batchSize) + 1,
                        (int) Math.ceil((double) queryList.size() / batchSize),
                        tokenUsage.getFormattedReport());
            }
        }

        return allRecommendations;
    }

    /**
     * Creates a QueryBatch with raw queries and actual WHERE clause column
     * cardinality information.
     * Uses QueryAnalysisEngine to extract actual columns from WHERE clauses and
     * method parameters.
     */
    QueryBatch createQueryBatch(String repositoryName, Collection<RepositoryQuery> rawQueries) {
        QueryBatch batch = new QueryBatch(repositoryName);

        // Add all raw queries to the batch
        for (RepositoryQuery query : rawQueries) {
            if (!"save".equals(query.getMethodDeclaration().getNameAsString())) {
                String methodName = query.getMethodDeclaration().getNameAsString();
                if (!quietMode) {
                    System.out.printf("  📝 Processing method: %s.%s%n", repositoryName, methodName);
                }
                batch.addQuery(query);
                addWhereClauseColumnCardinality(batch, query);
            }
        }

        return batch;
    }

    /**
     * Adds actual WHERE clause column cardinality information using
     * QueryAnalysisEngine.
     * Extracts columns from WHERE clauses and method parameters to get real query
     * column usage.
     */
    void addWhereClauseColumnCardinality(QueryBatch batch, RepositoryQuery query) {
        // Use existing QueryAnalysisEngine to extract WHERE conditions from the actual
        // query
        QueryAnalysisResult tempResult = analysisEngine.analyzeQuery(query);
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
     * This is where we do our programmatic analysis AFTER getting LLM
     * recommendations.
     */
    List<QueryAnalysisResult> analyzeLLMRecommendations(List<OptimizationIssue> llmRecommendations,
            List<RepositoryQuery> rawQueries) {
        List<QueryAnalysisResult> finalResults = new ArrayList<>();

        for (int i = 0; i < llmRecommendations.size() && i < rawQueries.size(); i++) {
            OptimizationIssue llmRecommendation = llmRecommendations.get(i);
            RepositoryQuery rawQuery = rawQueries.get(i);

            QueryAnalysisResult result = createResultWithIndexAnalysis(llmRecommendation, rawQuery);
            finalResults.add(result);
        }

        return finalResults;
    }

    /**
     * Creates a complete QueryOptimizationResult from an LLM recommendation with
     * index analysis.
     * This merged method combines the functionality of creating the result and
     * analyzing indexes.
     * Uses QueryAnalysisEngine to extract WHERE conditions and the Indexes class to
     * determine missing indexes.
     */
    QueryAnalysisResult createResultWithIndexAnalysis(OptimizationIssue llmRecommendation,
            RepositoryQuery rawQuery) {
        // Use QueryAnalysisEngine to extract WHERE conditions from the query
        // If the LLM provided an optimized query, analyze that instead of the raw query
        // to ensure index suggestions match the recommended code changes.
        RepositoryQuery queryToAnalyze = llmRecommendation.optimizedQuery() != null
                ? llmRecommendation.optimizedQuery()
                : rawQuery;

        QueryAnalysisResult engineResult = analysisEngine.analyzeQuery(queryToAnalyze);
        List<WhereCondition> whereConditions = engineResult.getWhereConditions();

        // Analyze indexes based on actual WHERE conditions
        // Each condition now includes its own table name, which is critical for JOIN
        // queries
        // Use LinkedHashSet to deduplicate (same column in multiple OR branches should only suggest one index)
        Set<String> requiredIndexes = new LinkedHashSet<>();

        for (WhereCondition condition : whereConditions) {
            String tableName = condition.tableName() == null ? rawQuery.getPrimaryTable() : condition.getTableName();

            String columnName = condition.columnName();
            CardinalityLevel cardinality = condition.cardinality();

            if (cardinality != CardinalityLevel.LOW && !hasOptimalIndexForColumn(tableName, columnName)) {
                String indexRecommendation = String.format("%s.%s", tableName, columnName);
                requiredIndexes.add(indexRecommendation);
            }
        }

        analyzeJoinRightSide(engineResult, requiredIndexes);

        // Create enhanced optimization issue with index analysis
        OptimizationIssue enhancedRecommendation = new OptimizationIssue(
                llmRecommendation.query(),
                llmRecommendation.currentColumnOrder(),
                llmRecommendation.recommendedColumnOrder(),
                llmRecommendation.description(),
                llmRecommendation.aiExplanation(),
                llmRecommendation.optimizedQuery());

        QueryAnalysisResult result = new QueryAnalysisResult(rawQuery, whereConditions);
        result.setIndexSuggestions(new ArrayList<>(requiredIndexes));
        result.setOptimizationIssue(enhancedRecommendation);
        return result;
    }

    private void analyzeJoinRightSide(QueryAnalysisResult engineResult, Set<String> requiredIndexes) {
        // Analyze right-side JOIN columns for missing indexes (critical for JOIN
        // performance)
        for (JoinCondition joinCondition : engineResult.getJoinConditions()) {
            String rightTable = joinCondition.getRightTable();
            String rightColumn = joinCondition.getRightColumn();

            if (rightTable != null && rightColumn != null) {
                CardinalityLevel cardinality = CardinalityAnalyzer.analyzeColumnCardinality(rightTable, rightColumn);

                // Only suggest indexes for non-low cardinality columns
                if (cardinality != CardinalityLevel.LOW && !hasOptimalIndexForColumn(rightTable, rightColumn)) {
                    String indexRecommendation = String.format("%s.%s (JOIN)", rightTable, rightColumn);

                    // Avoid duplication - check if already suggested from WHERE clause
                    String baseRecommendation = String.format("%s.%s", rightTable, rightColumn);
                    if (!requiredIndexes.contains(baseRecommendation)) {
                        requiredIndexes.add(indexRecommendation);
                    }
                }
            }
        }
    }

    /**
     * Checks if there's an optimal index for the given column using the Indexes
     * class.
     * This method provides more comprehensive index analysis than just checking for
     * leading columns.
     *
     * @param tableName  the table name
     * @param columnName the column name
     * @return true if an optimal index exists, false otherwise
     */
    boolean hasOptimalIndexForColumn(String tableName, String columnName) {
        return CardinalityAnalyzer.hasIndexWithLeadingColumn(tableName, columnName);
    }

    /**
     * Reports the optimization analysis results with enhanced formatting and
     * severity-based prioritization.
     * Enhanced to include repository class name and method name from Callable
     * objects,
     * specific recommendations for column reordering, and confirmation reporting
     * for optimized queries.
     *
     * @param result the analysis results to report
     */
    void reportOptimizationResults(QueryAnalysisResult result) {
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
     * Reports confirmation for already optimized queries with cardinality
     * information.
     *
     * @param result the analysis results for an optimized query
     */
    void reportOptimizedQuery(QueryAnalysisResult result) {
        if (!result.getWhereConditions().isEmpty()) {
            WhereCondition firstCondition = result.getFirstCondition();
            String cardinalityInfo = firstCondition != null
                    ? String.format(" (First condition uses %s cardinality column: %s)",
                            firstCondition.cardinality().toString().toLowerCase(),
                            firstCondition.columnName())
                    : "";

            System.out.printf("✓ OPTIMIZED: %s.%s - Query is already optimized%s%n",
                    result.getQuery().getClassname(),
                    result.getMethodName(),
                    cardinalityInfo);

            // Print the full WHERE clause and query information
            printQueryDetails(result);
        }
    }

    private OptimizationIssue reportNumbers(QueryAnalysisResult result) {
        OptimizationIssue issue = result.getOptimizationIssue();

        if (issue != null) {
            totalRecommendations++;
        }
        return issue;
    }

    /**
     * Reports optimization issues with severity-based prioritization and enhanced
     * recommendations.
     *
     * @param result the analysis results
     */
    void reportOptimizationIssues(QueryAnalysisResult result) {
        // Sort issues by severity (HIGH -> MEDIUM -> LOW) for prioritized reporting
        OptimizationIssue issue = reportNumbers(result);

        // Report header with summary
        System.out.printf("\n⚠ OPTIMIZATION NEEDED: %s.%s%n",
                result.getQuery().getClassname(),
                result.getMethodName());

        // Print the full WHERE clause and query information
        printQueryDetails(result);

        System.out.println(formatOptimizationIssueEnhanced(issue, result));

        if (!result.getIndexSuggestions().isEmpty()) {
            reportOptimizationIssuesHelper(result);
        }

        // Collect any index creation suggestions for consolidation at the end
        collectIndexSuggestions(result);

        System.out.println(); // Add blank line for readability
    }

    private static void reportOptimizationIssuesHelper(QueryAnalysisResult result) {
        // Separate WHERE and JOIN index suggestions
        List<String> whereIndexes = new ArrayList<>();
        List<String> joinIndexes = new ArrayList<>();

        for (String indexRecommendation : result.getIndexSuggestions()) {
            if (indexRecommendation.contains("(JOIN)")) {
                joinIndexes.add(indexRecommendation);
            } else {
                whereIndexes.add(indexRecommendation);
            }
        }

        // Report WHERE clause indexes
        if (!whereIndexes.isEmpty()) {
            reportWhereClauseIndexes(whereIndexes);
        }

        // Report JOIN indexes with critical marker
        if (!joinIndexes.isEmpty()) {
            reportJoinColumnIndexes(joinIndexes);
        }
    }

    private static void reportJoinColumnIndexes(List<String> joinIndexes) {
        System.out.println("    🔴 Critical JOIN Indexes (right-side probe table):");
        for (String indexRecommendation : joinIndexes) {
            String cleanRecommendation = indexRecommendation.replace(" (JOIN)", "");
            String[] parts = cleanRecommendation.split("\\.", 2);
            if (parts.length == 2) {
                String table = parts[0];
                String column = parts[1];
                boolean hasExistingIndex = CardinalityAnalyzer.hasIndexWithLeadingColumn(table, column);
                String status = hasExistingIndex ? "✓ EXISTS" : "⚠ MISSING";
                System.out.printf("      • %s.%s [%s] - JOIN performance critical\n", table, column, status);
            }
        }
    }

    private static void reportWhereClauseIndexes(List<String> whereIndexes) {
        System.out.println("    📋 Required Indexes (WHERE clause):");
        for (String indexRecommendation : whereIndexes) {
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

    /**
     * Formats an optimization issue with enhanced display including cardinality
     * information,
     * AI explanations, and required indexes.
     *
     * @param issue  the optimization issue to format
     * @param result the full analysis result for additional context
     * @return formatted string representation of the issue
     */
    String formatOptimizationIssueEnhanced(OptimizationIssue issue, QueryAnalysisResult result) {
        StringBuilder formatted = new StringBuilder();

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
     * Reports optimization issues in quiet mode - only shows method signatures and
     * queries changed.
     *
     * @param result the analysis results
     */
    void reportOptimizationIssuesQuiet(QueryAnalysisResult result) {
        // Update global recommendation counters
        OptimizationIssue issue = reportNumbers(result);
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
     *
     * @param enabled true to enable quiet mode, false for normal output
     */
    public static void setQuietMode(boolean enabled) {
        quietMode = enabled;
    }

    /**
     * Set the target class to analyze. If set, only this repository will be analyzed.
     *
     * @param className fully qualified class name, or null to analyze all repositories
     */
    public static void setTargetClass(String className) {
        targetClass = className;
    }

    /**
     * Get the current target class filter.
     *
     * @return the target class name, or null if not set
     */
    public static String getTargetClass() {
        return targetClass;
    }

    /**
     * Checks if quiet mode is enabled.
     * 
     * @return true if quiet mode is enabled
     */
    public static boolean isQuietMode() {
        return quietMode;
    }

    /**
     * Finds a WHERE condition by column name from the analysis result.
     *
     * @param result     the analysis result
     * @param columnName the column name to find
     * @return the matching WhereCondition or null if not found
     */
    WhereCondition findConditionByColumn(QueryAnalysisResult result, String columnName) {
        return result.getWhereConditions().stream()
                .filter(condition -> columnName.equals(condition.columnName()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Formats a condition with cardinality information for enhanced reporting.
     *
     * @param columnName the column name
     * @param condition  the WhereCondition object (may be null)
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
     * This method eliminates code duplication between reportOptimizedQuery and
     * reportOptimizationIssues.
     *
     * @param result the query optimization result to print details for
     */
    void printQueryDetails(QueryAnalysisResult result) {
        // Print the original WHERE clause
        String originalWhereClause = result.getFullWhereClause();
        if (!originalWhereClause.isEmpty()) {
            System.out.printf("  🔍 Original WHERE: %s%n", originalWhereClause);
        }

        OptimizationIssue firstIssue = result.getOptimizationIssue();

        if (firstIssue != null && firstIssue.optimizedQuery() != null) {
            // Use the existing QueryAnalysisEngine to analyze the optimized query
            QueryAnalysisResult optimizedResult = analysisEngine.analyzeQuery(firstIssue.optimizedQuery());
            String optimizedWhereClause = optimizedResult.getFullWhereClause();
            if (!optimizedWhereClause.isEmpty() && !optimizedWhereClause.equals(originalWhereClause)) {
                System.out.printf("  ✨ Optimized WHERE: %s%n", optimizedWhereClause);
            }
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
            if (dot >= 0 && dot < raw.length() - 1)
                last = raw.substring(dot + 1);
            last = last.replace("`", "").replace("\"", "").replace("'", "");
            return last;
        }
        return null;
    }

    String buildLiquibaseNonLockingIndexChangeSet(String tableName, String columnName) {
        return liquibaseGenerator.createIndexChangeset(tableName, columnName);
    }

    /**
     * Builds a Liquibase changeset for creating a multi-column index.
     * Uses the consolidated LiquibaseGenerator utility.
     *
     * @param tableName the table name
     * @param columns   list of column names in index order
     * @return XML changeset string
     */
    String buildLiquibaseMultiColumnIndexChangeSet(String tableName, LinkedHashSet<String> columns) {
        return liquibaseGenerator.createMultiColumnIndexChangeset(tableName, columns);
    }

    String buildLiquibaseDropIndexChangeSet(String indexName) {
        return liquibaseGenerator.createDropIndexChangeset(indexName);
    }

    void collectIndexSuggestions(QueryAnalysisResult result) {
        // Group columns by table - critical for JOIN queries where columns come from
        // multiple tables
        Map<String, List<String>> columnsByTable = new HashMap<>();

        // Process WHERE conditions
        for (WhereCondition condition : result.getWhereConditions()) {
            if (condition.cardinality() != CardinalityLevel.LOW) {
                String tableName = condition.tableName() == null ? result.getQuery().getPrimaryTable()
                        : condition.getTableName();
                // Normalize table and column names to lowercase to ensure consistent key handling
                String normalizedTableName = tableName != null ? tableName.toLowerCase() : null;
                String normalizedColumnName = condition.columnName() != null ? condition.columnName().toLowerCase() : null;
                if (normalizedTableName != null && normalizedColumnName != null) {
                    List<String> tableColumns = columnsByTable.computeIfAbsent(normalizedTableName, k -> new ArrayList<>());
                    // Avoid duplicate columns (case-insensitive)
                    boolean alreadyExists = tableColumns.stream().anyMatch(c -> c.equalsIgnoreCase(normalizedColumnName));
                    if (!alreadyExists) {
                        tableColumns.add(normalizedColumnName);
                    }
                }
            }
        }

        groupJoinColumnsByTable(result, columnsByTable);

        generatedRequiredIndexesList(columnsByTable);
    }

    private void generatedRequiredIndexesList(Map<String, List<String>> columnsByTable) {
        // Process each table's columns separately to create table-specific indexes
        for (Map.Entry<String, List<String>> entry : columnsByTable.entrySet()) {
            String table = entry.getKey();
            List<String> columnsForTable = entry.getValue();

            // Filter out ALL low-cardinality columns from the index
            // Low cardinality columns should never be included in indexes as they provide
            // minimal selectivity benefit and can actually hurt performance
            List<String> filteredColumns = new ArrayList<>();
            for (String column : columnsForTable) {
                CardinalityLevel cardinality = CardinalityAnalyzer.analyzeColumnCardinality(table, column);
                if (cardinality == CardinalityLevel.LOW) {
                    // Skip low cardinality column - don't include it in the index
                    if (!quietMode) {
                        logger.info("Excluding low-cardinality column '{}' from index on table '{}'.", column, table);
                    }
                    continue; // Skip this column but continue processing remaining columns
                }
                filteredColumns.add(column);
            }

            if (filteredColumns.size() > 1) {
                // Limit to maxIndexColumns (default 4)
                if (filteredColumns.size() > maxIndexColumns) {
                    if (!quietMode) {
                        logger.info("Limiting multi-column index on table {} from {} columns to {} columns (max_index_columns config)",
                                table, filteredColumns.size(), maxIndexColumns);
                    }
                    filteredColumns = filteredColumns.subList(0, maxIndexColumns);
                }

                // Check if an existing DATABASE index already covers these columns
                if (CardinalityAnalyzer.hasIndexCoveringColumns(table, filteredColumns)) {
                    if (!quietMode) {
                        logger.info("Skipping index on table {} for columns {} - already covered by existing index",
                                table, filteredColumns);
                    }
                    continue;
                }

                // Create the key for this proposed index
                String key = (table + "|" + String.join(",", filteredColumns)).toLowerCase();

                // Check if this proposed index is already covered by another PROPOSED index
                if (isIndexCoveredByProposedIndex(table.toLowerCase(), filteredColumns)) {
                    if (!quietMode) {
                        logger.info("Skipping index {} - already covered by a larger proposed index", key);
                    }
                    continue;
                }

                // Before adding, remove any smaller proposed indexes that this new index covers
                removeProposedIndexesCoveredBy(table.toLowerCase(), filteredColumns);

                // Create multi-column index for this specific table
                if (suggestedMultiColumnIndexes.add(key)) {
                    OptimizationStatsLogger.updateIndexesGenerated(1);
                }
            } else if (filteredColumns.size() == 1) {
                // Only one column needs indexing - create single-column index
                String column = filteredColumns.get(0);
                boolean hasExisting = CardinalityAnalyzer.hasIndexWithLeadingColumn(table, column);
                if (!hasExisting) {
                    String key = (table + "|" + column).toLowerCase();

                    // Check if this single-column is already covered by a proposed multi-column index
                    if (isSingleColumnCoveredByProposed(table.toLowerCase(), column.toLowerCase())) {
                        if (!quietMode) {
                            logger.info("Skipping single-column index {} - already covered by a proposed multi-column index", key);
                        }
                        continue;
                    }

                    if (suggestedNewIndexes.add(key)) {
                        OptimizationStatsLogger.updateIndexesGenerated(1);
                    }
                }
            }
        }
    }

    /**
     * Checks if a proposed index is already covered by another larger proposed index.
     * An index (A, B) is covered by (A, B, C) if the larger index starts with all columns
     * of the smaller index in the same order.
     *
     * @param table the table name (lowercase)
     * @param columns the columns of the proposed index
     * @return true if there's already a larger proposed index that covers this one
     */
    private boolean isIndexCoveredByProposedIndex(String table, List<String> columns) {
        for (String existingKey : suggestedMultiColumnIndexes) {
            String[] parts = existingKey.split("\\|", 2);
            if (parts.length != 2) continue;

            String existingTable = parts[0];
            if (!existingTable.equals(table)) continue;

            List<String> existingColumns = List.of(parts[1].split(","));

            // Check if existingColumns covers the new columns (existing is larger or equal and starts with same prefix)
            if (existingColumns.size() >= columns.size() && isPrefixIgnoreCase(columns, existingColumns)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a single-column index is already covered by a proposed multi-column index
     * that has this column as its leading column.
     *
     * @param table the table name (lowercase)
     * @param column the column name (lowercase)
     * @return true if there's a multi-column index with this as the leading column
     */
    private boolean isSingleColumnCoveredByProposed(String table, String column) {
        for (String mcKey : suggestedMultiColumnIndexes) {
            String[] mcParts = mcKey.split("\\|", 2);
            if (mcParts.length != 2) continue;

            String mcTable = mcParts[0];
            String[] mcColumns = mcParts[1].split(",");

            if (table.equals(mcTable) && mcColumns.length > 0 && mcColumns[0].equalsIgnoreCase(column)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes any proposed indexes that are covered by a new larger index being added.
     * For example, if adding (A, B, C), this will remove existing (A) and (A, B).
     *
     * @param table the table name (lowercase)
     * @param columns the columns of the new larger index
     */
    private void removeProposedIndexesCoveredBy(String table, List<String> columns) {
        Set<String> toRemove = new HashSet<>();

        // Check multi-column indexes
        for (String existingKey : suggestedMultiColumnIndexes) {
            String[] parts = existingKey.split("\\|", 2);
            if (parts.length != 2) continue;

            String existingTable = parts[0];
            if (!existingTable.equals(table)) continue;

            List<String> existingColumns = List.of(parts[1].split(","));

            // If existing is smaller and is a prefix of the new columns, remove it
            if (existingColumns.size() < columns.size() && isPrefixIgnoreCase(existingColumns, columns)) {
                toRemove.add(existingKey);
                if (!quietMode) {
                    logger.info("Removing smaller proposed index {} - will be covered by new index on columns {}",
                            existingKey, columns);
                }
            }
        }

        // Check single-column indexes
        if (!columns.isEmpty()) {
            String firstColumn = columns.get(0).toLowerCase();
            String singleKey = table + "|" + firstColumn;
            if (suggestedNewIndexes.contains(singleKey)) {
                toRemove.add(singleKey);
                if (!quietMode) {
                    logger.info("Removing single-column index {} - will be covered by new multi-column index on columns {}",
                            singleKey, columns);
                }
            }
        }

        // Remove the covered indexes and update stats
        suggestedMultiColumnIndexes.removeAll(toRemove);
        suggestedNewIndexes.removeAll(toRemove);
        if (!toRemove.isEmpty()) {
            OptimizationStatsLogger.updateIndexesGenerated(-toRemove.size());
        }
    }

    /**
     * Checks if list1 is a prefix of list2 (case-insensitive comparison).
     * For example, [A, B] is a prefix of [A, B, C, D].
     */
    private boolean isPrefixIgnoreCase(List<String> list1, List<String> list2) {
        if (list1.size() > list2.size()) return false;
        for (int i = 0; i < list1.size(); i++) {
            if (!list1.get(i).equalsIgnoreCase(list2.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Final cleanup pass to remove any remaining redundant proposed indexes.
     * This is called once before generating the Liquibase file to catch any edge cases
     * that might have slipped through the incremental checks.
     */
    private void finalRedundancyCleanup() {
        Set<String> toRemove = new HashSet<>();

        // Check each multi-column index against all others
        removeRedundantMultiColumnIndexes(toRemove);


        // Also check single-column indexes against multi-column indexes
        removeRedundantSingleColumnIndexes(toRemove);

        // Remove the redundant indexes
        suggestedMultiColumnIndexes.removeAll(toRemove);
        suggestedNewIndexes.removeAll(toRemove);

        // Update stats if we removed any
        if (!toRemove.isEmpty()) {
            OptimizationStatsLogger.updateIndexesGenerated(-toRemove.size());
        }
    }

    private void removeRedundantSingleColumnIndexes(Set<String> toRemove) {
        for (String singleKey : suggestedNewIndexes) {
            String[] parts = singleKey.split("\\|", 2);
            if (parts.length != 2) continue;
            String table = parts[0];
            String column = parts[1];

            // Check if any multi-column index on the same table starts with this column
            for (String mcKey : suggestedMultiColumnIndexes) {
                String[] mcParts = mcKey.split("\\|", 2);
                if (mcParts.length != 2) continue;
                String mcTable = mcParts[0];
                String[] mcColumns = mcParts[1].split(",");

                if (table.equals(mcTable) && mcColumns.length > 0 && mcColumns[0].equalsIgnoreCase(column)) {
                    toRemove.add(singleKey);
                    if (!quietMode) {
                        logger.info("Final cleanup: Removing redundant single-column index {} - covered by multi-column index {}",
                                singleKey, mcKey);
                    }
                    break;
                }
            }
        }
    }

    private void removeRedundantMultiColumnIndexes(Set<String> toRemove) {
        for (String key1 : suggestedMultiColumnIndexes) {
            String[] parts1 = key1.split("\\|", 2);
            if (parts1.length != 2) continue;
            String table1 = parts1[0];
            List<String> columns1 = List.of(parts1[1].split(","));

            for (String key2 : suggestedMultiColumnIndexes) {
                if (key1.equals(key2)) continue;

                String[] parts2 = key2.split("\\|", 2);
                if (parts2.length != 2) continue;
                String table2 = parts2[0];
                List<String> columns2 = List.of(parts2[1].split(","));

                // Only compare indexes on the same table
                if (!table1.equals(table2)) continue;

                // Check if columns1 is a prefix of columns2 (columns2 covers columns1)
                if (columns2.size() > columns1.size() && isPrefixIgnoreCase(columns1, columns2)) {
                    toRemove.add(key1);
                    if (!quietMode) {
                        logger.info("Final cleanup: Removing redundant index {} - covered by {}", key1, key2);
                    }
                    break;
                }
            }
        }
    }

    private static void groupJoinColumnsByTable(QueryAnalysisResult result, Map<String, List<String>> columnsByTable) {
        // Process right-side JOIN columns (critical for JOIN performance)
        for (JoinCondition joinCondition : result.getJoinConditions()) {
            String rightTable = joinCondition.getRightTable();
            String rightColumn = joinCondition.getRightColumn();

            if (rightTable != null && rightColumn != null) {
                // Normalize table name to lowercase for consistent key handling
                String normalizedTable = rightTable.toLowerCase();
                String normalizedColumn = rightColumn.toLowerCase();
                CardinalityLevel cardinality = CardinalityAnalyzer.analyzeColumnCardinality(normalizedTable, normalizedColumn);

                // Only add non-low cardinality columns that don't already have indexes
                if (cardinality != CardinalityLevel.LOW &&
                        !CardinalityAnalyzer.hasIndexWithLeadingColumn(normalizedTable, normalizedColumn)) {

                    // Add to columnsByTable for index generation, avoiding duplicates (case-insensitive)
                    List<String> tableColumns = columnsByTable.computeIfAbsent(normalizedTable, k -> new ArrayList<>());
                    boolean alreadyExists = tableColumns.stream().anyMatch(c -> c.equalsIgnoreCase(normalizedColumn));
                    if (!alreadyExists) {
                        tableColumns.add(normalizedColumn);
                    }
                }
            }
        }
    }

    String indent(String s, int spaces) {
        String pad = " ".repeat(Math.max(0, spaces));
        return java.util.Arrays.stream(s.split("\n"))
                .map(line -> pad + line)
                .reduce((a, b) -> a + "\n" + b)
                .orElse(pad + s);
    }

    public TokenUsage getCumulativeTokenUsage() {
        return cumulativeTokenUsage;
    }

    /**
     * Generates a Liquibase changes file from consolidated suggestions and includes
     * it in the master file.
     * Uses the consolidated LiquibaseGenerator utility for changeset creation and
     * file operations.
     */
    public void generateLiquibaseChangesFile() throws IOException {
        // Generate changesets for both create and drop operations
        List<String> generated = generateLiquibaseChangesets();

        if (generated.isEmpty()) {
            return;
        }

        // Use LiquibaseGenerator to write the changeset file
        String allChangesets = String.join("\n", generated);
        LiquibaseGenerator.WriteResult result = liquibaseGenerator.writeChangesetToFile(liquibaseXmlPath,
                allChangesets);

        if (result.wasWritten() && result.getChangesFile() != null) {
            logger.debug(
                    "Generated Liquibase changes file: {} with {} index create recommendations and {} drop recommendations",
                    result.getChangesFile().getName(), OptimizationStatsLogger.getTotalIndexesGenerated(),
                    OptimizationStatsLogger.getTotalIndexesDropped());
        }
    }

    /**
     * Generates all Liquibase changesets (create and drop) as a consolidated list.
     * This method is used by both the file writer and the console printer to avoid
     * duplication.
     *
     * @return GeneratedChangesets containing all changesets and counts
     */
    List<String> generateLiquibaseChangesets() {
        // Final cleanup pass to remove any remaining redundant indexes before generating
        finalRedundancyCleanup();

        List<String> result = new ArrayList<>();

        for (String key : suggestedMultiColumnIndexes) {
            String[] parts = key.split("\\|", 2);
            String table = parts[0];
            String columnsStr = parts[1];
            LinkedHashSet<String> columns = new LinkedHashSet<>(List.of(columnsStr.split(",")));
            String changeSet = buildLiquibaseMultiColumnIndexChangeSet(table, columns);
            result.add(indentXml(changeSet, 4));
        }

        for (String key : suggestedNewIndexes) {
            String[] parts = key.split("\\|", 2);
            String table = parts[0];
            String column = parts[1];

            if (!isCoveredByComposite(table, column)) {

                String changeSet = buildLiquibaseNonLockingIndexChangeSet(table, column);
                result.add(indentXml(changeSet, 4));
            }
        }
        int totalIndexCreateRecommendations = OptimizationStatsLogger.getTotalIndexesGenerated();

        // Add create index summary comment (or note no create recommendations)
        if (totalIndexCreateRecommendations > 0) {
            result.add("\n    <!-- Summary: " + totalIndexCreateRecommendations
                    + " total index create recommendations -->");
        }

        addIndexDropChanges(result);
        return result;
    }

    private void addIndexDropChanges(List<String> result) {
        // Analyze existing indexes to suggest drops for low-cardinality leading columns
        // (always perform)
        LinkedHashSet<String> dropCandidates = new LinkedHashSet<>();
        Map<String, Set<Indexes.IndexInfo>> map = CardinalityAnalyzer.getIndexMap();
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
            if (result.isEmpty()) {
                result.add("\n    <!-- Summary: No index create recommendations -->");
            }

            result.add("\n    <!-- Index Drop Recommendations (leading low-cardinality columns) -->");
            for (String idxName : dropCandidates) {
                String changeSet = buildLiquibaseDropIndexChangeSet(idxName);
                result.add("\n    <!-- Drop index " + idxName + " -->");
                result.add(indentXml(changeSet, 4));
            }
            OptimizationStatsLogger.updateIndexesDropped(dropCandidates.size());
            result.add("\n    <!-- Summary: " + dropCandidates.size() + " total index drop recommendations -->");
        }
    }

    boolean isCoveredByComposite(String table, String column) {
        return suggestedMultiColumnIndexes.stream()
                .anyMatch(mcKey -> {
                    String[] mcParts = mcKey.split("\\|", 2);
                    if (mcParts.length == 2 && mcParts[0].equalsIgnoreCase(table)) {
                        String[] cols = mcParts[1].split(",");
                        return cols.length > 0 && cols[0].equalsIgnoreCase(column);
                    }
                    return false;
                });
    }

    /**
     * Indents XML content by the specified number of spaces.
     *
     * @param xml    the XML content to indent
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

    public void setRepositoryParser(RepositoryParser repositoryParser) {
        this.repositoryParser = repositoryParser;
    }

    public void setAnalysisEngine(QueryAnalysisEngine analysisEngine) {
        this.analysisEngine = analysisEngine;
    }

    public void setAiService(GeminiAIService aiService) {
        this.aiService = aiService;
    }

    public void setLiquibaseGenerator(LiquibaseGenerator liquibaseGenerator) {
        this.liquibaseGenerator = liquibaseGenerator;
    }

    public RepositoryParser getRepositoryParser() {
        return repositoryParser;
    }

    public QueryAnalysisEngine getAnalysisEngine() {
        return analysisEngine;
    }

    public GeminiAIService getAiService() {
        return aiService;
    }

    public LiquibaseGenerator getLiquibaseGenerator() {
        return liquibaseGenerator;
    }

    public CheckpointManager getCheckpointManager() {
        return checkpointManager;
    }

    public void setCheckpointManager(CheckpointManager checkpointManager) {
        this.checkpointManager = checkpointManager;
    }

    /**
     * Clears any existing checkpoint, forcing a fresh start on the next run.
     * Use this when you want to reprocess all repositories from scratch.
     */
    public void clearCheckpoint() {
        if (checkpointManager != null) {
            checkpointManager.clear();
        }
    }

    public static void main(String[] args) throws Exception {
        Settings.loadConfigMap();
        AbstractCompiler.preProcess();

        // Parse optional CLI parameters for cardinality overrides
        Set<String> lowOverride = parseListArg(args, "--low-cardinality=");
        Set<String> highOverride = parseListArg(args, "--high-cardinality=");

        CardinalityAnalyzer.configureUserDefinedCardinality(lowOverride, highOverride);

        // Read configuration from generator.yml
        configureFromSettings();

        QueryOptimizationChecker checker = new QueryOptimizationChecker(getLiquibasePath());
        int repositoriesAnalyzed = checker.analyze();

        // Only generate Liquibase file if at least one repository was analyzed
        if (repositoriesAnalyzed > 0) {
            checker.generateLiquibaseChangesFile();
        } else {
            System.out.println("\n⏭️ Skipping Liquibase generation - no new repositories were analyzed");
        }

        TokenUsage totalTokenUsage = checker.getCumulativeTokenUsage();
        OptimizationStatsLogger.printSummary(System.out);

        if (totalTokenUsage.getTotalTokens() > 0) {
            System.out.printf("🤖 AI Service Usage: %s%n", totalTokenUsage.getFormattedReport());
        }
    }

    /**
     * Configures QueryOptimizationChecker from generator.yml settings.
     * Reads target_class configuration.
     * Call this from main() after Settings.loadConfigMap().
     */
    @SuppressWarnings("unchecked")
    public static void configureFromSettings() {
        // Read target_class from query_optimizer section
        Map<String, Object> queryOptimizer = (Map<String, Object>) Settings.getProperty("query_optimizer");
        if (queryOptimizer != null) {
            Object targetClassValue = queryOptimizer.get("target_class");
            if (targetClassValue instanceof String s && !s.isBlank()) {
                setTargetClass(s);
                System.out.printf("🎯 Target class filter: %s%n", s);
            } else {
                System.out.println("ℹ️ No target_class filter specified (processing all repositories)");
            }
        } else {
            System.out.println("ℹ️ No query_optimizer section in settings (processing all repositories)");
        }
    }

    protected static Set<String> parseListArg(String[] args, String prefix) {
        HashSet<String> set = new HashSet<>();

        for (String arg : args) {
            if (arg.startsWith(prefix)) {
                String value = arg.substring(prefix.length());
                for (String token : value.split(",")) {
                    String t = token.trim().toLowerCase();
                    if (!t.isEmpty())
                        set.add(t);
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

    int getMaxIndexColumns() {
        return maxIndexColumns;
    }

    /**
     * Reads the max_index_columns configuration from query_optimizer section.
     * Returns the default value of 4 if not configured.
     *
     * @return maximum number of columns allowed in a multi-column index
     */
    @SuppressWarnings("unchecked")
    private static int getMaxIndexColumnsFromConfig() {
        Map<String, Object> queryOptimizer = (Map<String, Object>) Settings.getProperty("query_optimizer");
        if (queryOptimizer != null) {
            Object maxColumns = queryOptimizer.get("max_index_columns");
            if (maxColumns instanceof Number n) {
                int value = n.intValue();
                // Validate the value is reasonable (at least 1, at most 16)
                if (value >= 1 && value <= 16) {
                    return value;
                }
                logger.warn("max_index_columns value {} is out of range (1-16), using default of 4", value);
            }
        }
        return 4; // Default value
    }
}
