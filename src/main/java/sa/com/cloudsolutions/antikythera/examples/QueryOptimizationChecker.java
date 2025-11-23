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
        // Initialize Liquibase generator with default configuration
        this.liquibaseGenerator = new LiquibaseGenerator();
    }

    /**
     * Analyzes all JPA repositories using RepositoryParser to extract and analyze
     * queries.
     *
     */
    public void analyze() throws IOException, ReflectiveOperationException, InterruptedException {
        Map<String, TypeWrapper> resolvedTypes = AntikytheraRunTime.getResolvedTypes();
        int i = 0;
        int repositoriesProcessed = 0;
        for (Map.Entry<String, TypeWrapper> entry : resolvedTypes.entrySet()) {
            String fullyQualifiedName = entry.getKey();
            TypeWrapper typeWrapper = entry.getValue();

            if (BaseRepositoryParser.isJpaRepository(typeWrapper)) {
                results.clear(); // Clear results for each repository

                System.out.println("\n" + "=".repeat(80));
                System.out.printf("Analyzing Repository: %s%n", fullyQualifiedName);
                System.out.println("=".repeat(80));
                try {
                    analyzeRepository(typeWrapper);
                    repositoriesProcessed++;
                } catch (AntikytheraException ae) {
                    logger.error("Error analyzing repository {}: {}", fullyQualifiedName, ae.getMessage());
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
     * @param typeWrapper        the TypeWrapper representing the repository
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
        // Create a batch with raw queries and basic cardinality information
        QueryBatch batch = createQueryBatch(repositoryName, rawQueries);

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
     * Creates a QueryBatch with raw queries and actual WHERE clause column
     * cardinality information.
     * Uses QueryAnalysisEngine to extract actual columns from WHERE clauses and
     * method parameters.
     */
    QueryBatch createQueryBatch(String repositoryName, Collection<RepositoryQuery> rawQueries) {
        QueryBatch batch = new QueryBatch(repositoryName);

        // Add all raw queries to the batch
        for (RepositoryQuery query : rawQueries) {

            batch.addQuery(query);
            addWhereClauseColumnCardinality(batch, query);
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
        // Use QueryAnalysisEngine to extract WHERE conditions from the actual query
        // This is independent of LLM recommendations
        QueryAnalysisResult engineResult = analysisEngine.analyzeQuery(rawQuery);
        List<WhereCondition> whereConditions = engineResult.getWhereConditions();

        // Analyze indexes based on actual WHERE conditions, not LLM recommendations
        // Each condition now includes its own table name, which is critical for JOIN
        // queries
        List<String> requiredIndexes = new ArrayList<>();

        for (WhereCondition condition : whereConditions) {
            String tableName = condition.tableName() == null ? rawQuery.getPrimaryTable() : condition.getTableName();

            String columnName = condition.columnName();
            CardinalityLevel cardinality = condition.cardinality();

            if (cardinality != CardinalityLevel.LOW && !hasOptimalIndexForColumn(tableName, columnName)) {
                String indexRecommendation = String.format("%s.%s", tableName, columnName);
                requiredIndexes.add(indexRecommendation);
            }
        }

        // Create enhanced optimization issue with index analysis
        OptimizationIssue enhancedRecommendation = new OptimizationIssue(
                llmRecommendation.query(),
                llmRecommendation.currentColumnOrder(),
                llmRecommendation.recommendedColumnOrder(),
                llmRecommendation.description(),
                llmRecommendation.aiExplanation(),
                llmRecommendation.optimizedQuery());

        QueryAnalysisResult result = new QueryAnalysisResult(rawQuery, whereConditions);
        result.setIndexSuggestions(requiredIndexes);
        result.setOptimizationIssue(enhancedRecommendation);
        return result;
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

        // Collect any index creation suggestions for consolidation at the end
        collectIndexSuggestions(result);

        System.out.println(); // Add blank line for readability
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

    String inferTableNameFromRepositoryClassName(String repositoryClass) {
        String simple = repositoryClass;
        int dot = simple.lastIndexOf('.');
        if (dot >= 0)
            simple = simple.substring(dot + 1);
        if (simple.endsWith("Repository"))
            simple = simple.substring(0, simple.length() - "Repository".length());
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

        for (WhereCondition condition : result.getWhereConditions()) {
            if (condition.cardinality() != CardinalityLevel.LOW) {
                String tableName = condition.tableName() == null ? result.getQuery().getPrimaryTable()
                        : condition.getTableName();
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
        int totalIndexCreateRecommendations = OptimizationStatsLogger.updateIndexesGenerated(result.size());

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

    public static void main(String[] args) throws Exception {
        Settings.loadConfigMap();
        AbstractCompiler.preProcess();

        // Parse optional CLI parameters for cardinality overrides
        Set<String> lowOverride = parseListArg(args, "--low-cardinality=");
        Set<String> highOverride = parseListArg(args, "--high-cardinality=");

        CardinalityAnalyzer.configureUserDefinedCardinality(lowOverride, highOverride);

        QueryOptimizationChecker checker = new QueryOptimizationChecker(getLiquibasePath());
        checker.analyze();

        // Generate Liquibase file with suggested changes and include in master
        checker.generateLiquibaseChangesFile();

        TokenUsage totalTokenUsage = checker.getCumulativeTokenUsage();
        OptimizationStatsLogger.printSummary(System.out);

        if (totalTokenUsage.getTotalTokens() > 0) {
            System.out.printf("🤖 AI Service Usage: %s%n", totalTokenUsage.getFormattedReport());
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
}
