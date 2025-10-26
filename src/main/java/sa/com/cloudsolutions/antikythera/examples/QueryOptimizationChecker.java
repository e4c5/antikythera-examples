package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.generator.QueryMethodParameter;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;
import sa.com.cloudsolutions.antikythera.generator.TypeWrapper;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;
import sa.com.cloudsolutions.antikythera.parser.Callable;
import sa.com.cloudsolutions.antikythera.parser.RepositoryParser;
import sa.com.cloudsolutions.liquibase.Indexes;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

@SuppressWarnings({"java:S3457", "java:S106"})
public class QueryOptimizationChecker {

    protected static final Logger logger = LoggerFactory.getLogger(QueryOptimizationChecker.class);
    public static final String TABLE_NAME_TAG = "<TABLE_NAME>";

    protected final RepositoryParser repositoryParser;
    protected final CardinalityAnalyzer cardinalityAnalyzer;
    protected final QueryAnalysisEngine analysisEngine;
    protected final File liquibaseXmlPath;
    protected final GeminiAIService aiService;
    protected final QueryBatchProcessor batchProcessor;

    // Aggregated counters for summary and exit code logic
    protected int totalQueriesAnalyzed = 0;
    protected int totalHighPriorityRecommendations = 0;
    protected int totalMediumPriorityRecommendations = 0;

    // Aggregated, de-duplicated suggestions for new indexes (key format: table|column)
    protected final java.util.LinkedHashSet<String> suggestedNewIndexes = new java.util.LinkedHashSet<>();
    // Counters for consolidated index actions
    protected int totalIndexCreateRecommendations = 0;
    protected int totalIndexDropRecommendations = 0;

    protected final List<CodeStandardizer.SignatureUpdate> signatureUpdates = new ArrayList<>();
    protected final List<QueryOptimizationResult> results = new ArrayList<>();
    
    // Token usage tracking for AI service
    protected final TokenUsage cumulativeTokenUsage = new TokenUsage();

    /**
     * Creates a new QueryOptimizationChecker that uses RepositoryParser for comprehensive query analysis.
     * 
     * @param liquibaseXmlPath path to the Liquibase XML file for database metadata
     * @throws Exception if initialization fails
     */
    public QueryOptimizationChecker(File liquibaseXmlPath) throws Exception {
        this.liquibaseXmlPath = liquibaseXmlPath;
        // Load database metadata for cardinality analysis
        Map<String, List<Indexes.IndexInfo>> indexMap = Indexes.load(liquibaseXmlPath);
        
        // Initialize components
        this.cardinalityAnalyzer = new CardinalityAnalyzer(indexMap);
        this.analysisEngine = new QueryAnalysisEngine(cardinalityAnalyzer);
        this.repositoryParser = new RepositoryParser();
        
        // Initialize AI service components
        this.aiService = new GeminiAIService();
        AIServiceConfig config = AIServiceConfigLoader.loadConfig();
        this.aiService.configure(config);
        
        // Initialize batch processor for repository-level query collection
        this.batchProcessor = new QueryBatchProcessor(cardinalityAnalyzer);
    }

    /**
     * Analyzes all JPA repositories using RepositoryParser to extract and analyze queries.
     * 
     */
    public void analyze() throws IOException, ReflectiveOperationException, InterruptedException {
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
        logger.debug("Analyzing repository: {}", fullyQualifiedName);

        // Use RepositoryParser to process the repository type
        repositoryParser.compile(AbstractCompiler.classToPath(fullyQualifiedName));
        repositoryParser.processTypes();

        // Build all queries using RepositoryParser
        repositoryParser.buildQueries();
        
        // Step 1: Collect raw methods for LLM analysis (no programmatic analysis yet)
        List<RepositoryQuery> rawQueries = collectRawQueries(typeWrapper);
        
        // Step 2: Send raw methods to LLM first
        List<OptimizationIssue> llmRecommendations = sendRawQueriesToLLM(fullyQualifiedName, rawQueries);
        
        // Step 3: Analyze LLM recommendations and check indexes
        List<QueryOptimizationResult> finalResults = analyzeLLMRecommendations(llmRecommendations, rawQueries);
        
        // Step 4: Report final results
        for (QueryOptimizationResult result : finalResults) {
            results.add(result);
            reportOptimizationResults(result);
        }
    }

    /**
     * Collects raw queries from a repository without any programmatic analysis.
     * These will be sent directly to the LLM for optimization recommendations.
     */
    private List<RepositoryQuery> collectRawQueries(TypeWrapper typeWrapper) {
        List<RepositoryQuery> rawQueries = new ArrayList<>();
        
        var declaration = typeWrapper.getType().asClassOrInterfaceDeclaration();

        for (var method : declaration.getMethods()) {
            Callable callable = new Callable(method, null);
            RepositoryQuery repositoryQuery = repositoryParser.getQueryFromRepositoryMethod(callable);
            totalQueriesAnalyzed++;
            rawQueries.add(repositoryQuery);
        }
        
        return rawQueries;
    }
    
    /**
     * Sends raw queries to LLM for optimization recommendations.
     * No programmatic analysis is done beforehand - LLM gets the raw methods.
     */
    private List<OptimizationIssue> sendRawQueriesToLLM(String repositoryName, List<RepositoryQuery> rawQueries) throws IOException, InterruptedException {
        // Create a batch with raw queries and basic cardinality information
        QueryBatch batch = createRawQueryBatch(repositoryName, rawQueries);
        
        // Send batch to AI service for analysis
        List<OptimizationIssue> llmRecommendations = aiService.analyzeQueryBatch(batch);
        
        // Track and report token usage
        TokenUsage tokenUsage = aiService.getLastTokenUsage();
        cumulativeTokenUsage.add(tokenUsage);
        System.out.printf("🤖 AI Analysis for %s: %s%n", repositoryName, tokenUsage.getFormattedReport());
        logger.info("AI analysis completed for {}: {}", repositoryName, tokenUsage.getFormattedReport());
        
        return llmRecommendations;
    }

    /**
     * Creates a QueryBatch with raw queries and actual WHERE clause column cardinality information.
     * Uses QueryAnalysisEngine to extract actual columns from WHERE clauses and method parameters.
     */
    private QueryBatch createRawQueryBatch(String repositoryName, List<RepositoryQuery> rawQueries) {
        QueryBatch batch = new QueryBatch(repositoryName);
        
        // Add all raw queries to the batch
        for (RepositoryQuery query : rawQueries) {
            batch.addQuery(query);
        }
        
        // Extract actual WHERE clause columns and their cardinality information
        // Use QueryAnalysisEngine to get real columns from the actual queries
        for (RepositoryQuery query : rawQueries) {
            addWhereClauseColumnCardinality(batch, query);
        }
        
        return batch;
    }

    /**
     * Adds actual WHERE clause column cardinality information using QueryAnalysisEngine.
     * Extracts columns from WHERE clauses and method parameters to get real query column usage.
     */
    private void addWhereClauseColumnCardinality(QueryBatch batch, RepositoryQuery query) {
        try {
            // Use existing QueryAnalysisEngine to extract WHERE conditions from the actual query
            QueryOptimizationResult tempResult = analysisEngine.analyzeQueryWithCallable(query);
            List<WhereCondition> whereConditions = tempResult.getWhereConditions();
            
            // Add cardinality information for each WHERE clause column
            for (WhereCondition condition : whereConditions) {
                String columnName = condition.columnName();
                CardinalityLevel cardinality = condition.cardinality();
                
                if (columnName != null && cardinality != null) {
                    batch.addColumnCardinality(columnName, cardinality);
                    logger.debug("Added WHERE clause cardinality info: {} -> {} for query {}", 
                            columnName, cardinality, query.getMethodName());
                }
            }
            
        } catch (Exception e) {
            logger.debug("Could not extract WHERE clause cardinality for query {}: {}", 
                    query.getMethodName(), e.getMessage());
        }
    }


    
    /**
     * Analyzes LLM recommendations and checks for required indexes.
     * This is where we do our programmatic analysis AFTER getting LLM recommendations.
     */
    private List<QueryOptimizationResult> analyzeLLMRecommendations(List<OptimizationIssue> llmRecommendations, List<RepositoryQuery> rawQueries) {
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
     * Uses the Indexes class to properly determine what indexes are available.
     */
    private QueryOptimizationResult createResultWithIndexAnalysis(OptimizationIssue llmRecommendation, RepositoryQuery rawQuery) {
        String tableName = rawQuery.getTable();
        if (tableName == null || tableName.isEmpty()) {
            tableName = inferTableNameFromQuerySafe(rawQuery.getQuery(), rawQuery.getClassname());
        }
        
        // Extract WHERE conditions and analyze indexes in a single pass
        List<WhereCondition> whereConditions = new ArrayList<>();
        List<String> requiredIndexes = new ArrayList<>();
        
        // Process each column in the LLM's recommended order
        for (int i = 0; i < llmRecommendation.recommendedColumnOrder().size(); i++) {
            String columnName = llmRecommendation.recommendedColumnOrder().get(i);
            // Analyze cardinality for WHERE conditions
            CardinalityLevel cardinality = cardinalityAnalyzer.analyzeColumnCardinality(tableName, columnName);
            if (cardinality != null) {
                // Create WhereCondition
                whereConditions.add(new WhereCondition(columnName, "=", cardinality, i, null));
            }

            // Use Indexes class to check for existing indexes more comprehensively
            if (!hasOptimalIndexForColumn(tableName, columnName)) {
                // Index is missing or not optimal - add to required indexes
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
            llmRecommendation.severity(),
            llmRecommendation.queryText(),
            llmRecommendation.aiExplanation(),
            requiredIndexes // Our analysis based on LLM recommendations and Indexes class
        );

        // Create result with WHERE conditions and the enhanced optimization issue
        List<OptimizationIssue> issues = new ArrayList<>();
        issues.add(enhancedRecommendation);

        return new QueryOptimizationResult(rawQuery, whereConditions, issues);
    }

    /**
     * Checks if there's an optimal index for the given column using the Indexes class.
     * This method provides more comprehensive index analysis than just checking for leading columns.
     * 
     * @param tableName the table name
     * @param columnName the column name
     * @return true if an optimal index exists, false otherwise
     */
    private boolean hasOptimalIndexForColumn(String tableName, String columnName) {
        try {
            // First check using the existing cardinality analyzer method
            if (cardinalityAnalyzer.hasIndexWithLeadingColumn(tableName, columnName)) {
                return true;
            }
            
            // Get the index map from cardinality analyzer to do more detailed analysis
            Map<String, List<Indexes.IndexInfo>> indexMap = cardinalityAnalyzer.snapshotIndexMap();
            List<Indexes.IndexInfo> tableIndexes = indexMap.get(tableName);
            
            if (tableIndexes == null || tableIndexes.isEmpty()) {
                return false;
            }
            
            // Check for any index that includes this column (not just as leading column)
            for (Indexes.IndexInfo indexInfo : tableIndexes) {
                if (indexInfo.columns != null && indexInfo.columns.contains(columnName)) {
                    // Found an index that includes this column
                    logger.debug("Found index {} that includes column {}.{}", indexInfo.name, tableName, columnName);
                    return true;
                }
            }
            
            return false;
            
        } catch (Exception e) {
            logger.debug("Error checking optimal index for {}.{}: {}", tableName, columnName, e.getMessage());
            // Fall back to the basic check
            return cardinalityAnalyzer.hasIndexWithLeadingColumn(tableName, columnName);
        }
    }

    /**
     * Analyzes JOIN queries to identify index requirements on both sides of the JOIN.
     * Uses improved index checking that leverages the Indexes class.
     * 
     * @param result the query optimization result
     * @return list of index recommendations for JOIN columns
     */
    private List<String> analyzeJoinIndexRequirements(QueryOptimizationResult result) {
        List<String> joinIndexes = new ArrayList<>();
        String queryText = result.getQuery().getQuery();
        
        if (queryText == null || !queryText.toLowerCase().contains("join")) {
            return joinIndexes;
        }
        
        // Extract JOIN conditions using regex pattern
        java.util.regex.Pattern joinPattern = java.util.regex.Pattern.compile(
            "(?i)\\bjoin\\s+([\\w\\.`\"']+)\\s+(?:\\w+\\s+)?on\\s+([\\w\\.`\"']+)\\s*=\\s*([\\w\\.`\"']+)",
            java.util.regex.Pattern.CASE_INSENSITIVE
        );
        
        java.util.regex.Matcher matcher = joinPattern.matcher(queryText);
        while (matcher.find()) {
            String joinTable = cleanTableOrColumnName(matcher.group(1));
            String leftColumn = extractColumnFromQualified(matcher.group(2));
            String rightColumn = extractColumnFromQualified(matcher.group(3));
            
            // Get the main table name
            String mainTable = result.getQuery().getTable();
            if (mainTable == null || mainTable.isEmpty()) {
                mainTable = inferTableNameFromQuerySafe(queryText, result.getRepositoryClass());
            }
            
            // Use improved index checking on both sides of the JOIN
            if (leftColumn != null && !hasOptimalIndexForColumn(mainTable, leftColumn)) {
                joinIndexes.add(String.format("%s.%s", mainTable, leftColumn));
                logger.debug("JOIN index recommendation: {}.{} (left side)", mainTable, leftColumn);
            }
            
            if (rightColumn != null && !hasOptimalIndexForColumn(joinTable, rightColumn)) {
                joinIndexes.add(String.format("%s.%s", joinTable, rightColumn));
                logger.debug("JOIN index recommendation: {}.{} (right side)", joinTable, rightColumn);
            }
        }
        
        return joinIndexes;
    }

    /**
     * Extracts column name from a qualified column reference (e.g., "table.column" -> "column").
     * 
     * @param qualifiedColumn the qualified column reference
     * @return the column name without table prefix
     */
    private String extractColumnFromQualified(String qualifiedColumn) {
        if (qualifiedColumn == null) return null;
        
        String cleaned = cleanTableOrColumnName(qualifiedColumn);
        int dotIndex = cleaned.lastIndexOf('.');
        return dotIndex >= 0 ? cleaned.substring(dotIndex + 1) : cleaned;
    }

    /**
     * Cleans table or column names by removing quotes and extra whitespace.
     * 
     * @param name the table or column name to clean
     * @return cleaned name
     */
    private String cleanTableOrColumnName(String name) {
        if (name == null) return null;
        return name.trim().replaceAll("[`\"'\\s]", "");
    }

    /**
     * Reports the optimization analysis results with enhanced formatting and severity-based prioritization.
     * Enhanced to include repository class name and method name from Callable objects,
     * specific recommendations for column reordering, and confirmation reporting for optimized queries.
     * 
     * @param result the analysis results to report
     */
    private void reportOptimizationResults(QueryOptimizationResult result) {
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
            
            System.out.printf("✓ OPTIMIZED: %s.%s - Query is already optimized%s%n",
                                            result.getRepositoryClass(),
                                            result.getMethodName(),
                                            cardinalityInfo);
            
            if (logger.isDebugEnabled()) {
                logger.debug("Query details: {}", result.getQuery());
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
        System.out.printf("\n⚠ OPTIMIZATION NEEDED: %s.%s (%d issue%s found)%n",
                                        result.getRepositoryClass(),
                                        result.getMethodName(),
                                        issues.size(),
                                        issues.size() == 1 ? "" : "s");
        
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
     * Formats an optimization issue with enhanced display including cardinality information,
     * AI explanations, and required indexes.
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
        
        // AI explanation
        if (issue.hasAIRecommendation()) {
            formatted.append(String.format("\n    🤖 AI Explanation: %s", issue.aiExplanation()));
        }
        
        // Required indexes if specified with enhanced formatting
        if (!issue.getRequiredIndexes().isEmpty()) {
            formatted.append("\n    📋 Required Indexes:");
            for (String indexRecommendation : issue.getRequiredIndexes()) {
                String[] parts = indexRecommendation.split("\\.", 2);
                if (parts.length == 2) {
                    String table = parts[0];
                    String column = parts[1];
                    boolean hasExistingIndex = cardinalityAnalyzer.hasIndexWithLeadingColumn(table, column);
                    String status = hasExistingIndex ? "✓ EXISTS" : "⚠ MISSING";
                    formatted.append(String.format("\n      • %s.%s [%s]", table, column, status));
                } else {
                    formatted.append(String.format("\n      • %s", indexRecommendation));
                }
            }
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
     * Adds specific recommendations for column reordering in WHERE clauses.
     * Enhanced to handle AI recommendations and required indexes.
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
                if (issue.recommendedFirstColumn().equals(issue.currentFirstColumn())) {
                    continue;
                }
                System.out.println(String.format("      • Move '%s' condition to the beginning of WHERE clause", 
                                                issue.recommendedFirstColumn()));
                System.out.println(String.format("        Replace: WHERE %s = ? AND %s = ?", 
                                                issue.currentFirstColumn(), issue.recommendedFirstColumn()));
                System.out.println(String.format("        With:    WHERE %s = ? AND %s = ?", 
                                                issue.recommendedFirstColumn(), issue.currentFirstColumn()));
                
                // Add required indexes if specified by AI with status check
                if (!issue.getRequiredIndexes().isEmpty()) {
                    System.out.println("        Required indexes:");
                    for (String indexRecommendation : issue.getRequiredIndexes()) {
                        String[] parts = indexRecommendation.split("\\.", 2);
                        if (parts.length == 2) {
                            String table = parts[0];
                            String column = parts[1];
                            boolean hasExistingIndex = cardinalityAnalyzer.hasIndexWithLeadingColumn(table, column);
                            String status = hasExistingIndex ? "✓ EXISTS" : "⚠ CREATE NEEDED";
                            System.out.println(String.format("          • %s.%s [%s]", table, column, status));
                        } else {
                            System.out.println(String.format("          • %s", indexRecommendation));
                        }
                    }
                }
            }
        }
        
        // Medium priority recommendations
        if (!mediumPriorityIssues.isEmpty()) {
            System.out.println("    🟡 MEDIUM PRIORITY:");
            for (OptimizationIssue issue : mediumPriorityIssues) {
                // Skip reordering recommendation when the recommended column is already first
                if (issue.recommendedFirstColumn().equals(issue.currentFirstColumn())) {
                    continue;
                }
                System.out.println(String.format("      • Consider reordering: place '%s' before '%s' in WHERE clause", 
                                                issue.recommendedFirstColumn(), issue.currentFirstColumn()));
                
                // Add required indexes if specified by AI with status check
                if (!issue.getRequiredIndexes().isEmpty()) {
                    System.out.println("        Suggested indexes:");
                    for (String indexRecommendation : issue.getRequiredIndexes()) {
                        String[] parts = indexRecommendation.split("\\.", 2);
                        if (parts.length == 2) {
                            String table = parts[0];
                            String column = parts[1];
                            boolean hasExistingIndex = cardinalityAnalyzer.hasIndexWithLeadingColumn(table, column);
                            String status = hasExistingIndex ? "✓ EXISTS" : "💡 CONSIDER CREATING";
                            System.out.printf("          • %s.%s [%s]%n", table, column, status);
                        } else {
                            System.out.printf("          • %s%n", indexRecommendation);
                        }
                    }
                }
            }
        }
    }

    // Helpers to tailor output for index recommendations
    private boolean isIndexCreationForLeadingMedium(OptimizationIssue issue) {
        boolean same = issue.recommendedFirstColumn().equals(issue.currentFirstColumn());
        boolean mentionsCreate = issue.description().toLowerCase().contains("create an index");
        return issue.isHighSeverity() && same && mentionsCreate;
    }

    private String inferTableNameFromQuerySafe(String queryText, String repositoryClass) {
        String t = inferTableNameFromQuery(queryText);
        if (t != null) return t;
        return inferTableNameFromRepositoryClassName(repositoryClass);
    }

    private String inferTableNameFromQuery(String queryText) {
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

    private String inferTableNameFromRepositoryClassName(String repositoryClass) {
        String simple = repositoryClass;
        int dot = simple.lastIndexOf('.');
        if (dot >= 0) simple = simple.substring(dot + 1);
        if (simple.endsWith("Repository")) simple = simple.substring(0, simple.length() - "Repository".length());
        // convert CamelCase to snake_case
        String snake = simple.replaceAll("(?<!^)([A-Z])", "_$1").toLowerCase();
        return snake.isEmpty() ? TABLE_NAME_TAG : snake;
    }

    private String buildLiquibaseNonLockingIndexChangeSet(String tableName, String columnName) {
        if (columnName.isEmpty()) columnName = "<COLUMN_NAME>";
        if (tableName.isEmpty()) tableName = TABLE_NAME_TAG;
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
        if (indexName.isEmpty()) indexName = "<INDEX_NAME>";
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
        if (issues.isEmpty()) return;
        
        // Collect traditional index suggestions based on cardinality analysis
        List<OptimizationIssue> idxIssues = issues.stream().filter(this::isIndexCreationForLeadingMedium).toList();
        for (OptimizationIssue idxIssue : idxIssues) {
            String table = result.getQuery().getTable();
            String col = !idxIssue.recommendedFirstColumn().isEmpty() ? idxIssue.recommendedFirstColumn() : idxIssue.currentFirstColumn();
            String key = (table + "|" + col).toLowerCase();
            suggestedNewIndexes.add(key);
        }
        
        // Collect AI-generated index recommendations from OptimizationIssue.requiredIndexes
        // Use improved index checking logic that leverages the Indexes class
        for (OptimizationIssue issue : issues) {
            if (!issue.getRequiredIndexes().isEmpty()) {
                for (String indexRecommendation : issue.getRequiredIndexes()) {
                    // Parse index recommendation format: "table.column" or just "column"
                    String[] parts = indexRecommendation.split("\\.", 2);
                    String table, column;
                    
                    if (parts.length == 2) {
                        table = parts[0];
                        column = parts[1];
                    } else {
                        // If no table specified, use the query's table
                        table = result.getQuery().getTable();
                        if (table == null || table.isEmpty()) {
                            table = inferTableNameFromQuerySafe(result.getQuery().getQuery(), result.getRepositoryClass());
                        }
                        column = parts[0];
                    }
                    
                    // Use improved index checking that leverages the Indexes class
                    if (!hasOptimalIndexForColumn(table, column)) {
                        String key = (table + "|" + column).toLowerCase();
                        suggestedNewIndexes.add(key);
                        logger.debug("Added index suggestion from AI recommendation: {}.{}", table, column);
                    } else {
                        logger.debug("Skipping AI index recommendation - optimal index already exists: {}.{}", table, column);
                    }
                }
            }
        }
    }

    public void printConsolidatedIndexActions() {
        // Print consolidated suggested new indexes using existing buildLiquibaseNonLockingIndexChangeSet logic
        if (!suggestedNewIndexes.isEmpty()) {
            System.out.println("\n📦 SUGGESTED NEW INDEXES (AI-Enhanced + Cardinality Analysis):");
            int count = 0;
            for (String key : suggestedNewIndexes) {
                String[] parts = key.split("\\|", 2);
                String table = parts.length > 0 ? parts[0] : TABLE_NAME_TAG;
                String column = parts.length > 1 ? parts[1] : "<COLUMN_NAME>";
                
                // Use existing buildLiquibaseNonLockingIndexChangeSet logic
                String snippet = buildLiquibaseNonLockingIndexChangeSet(table, column);
                
                // Add context about the recommendation source
                System.out.println(String.format("\n  Index for %s.%s:", table, column));
                System.out.println(indent(snippet, 2));
                count++;
            }
            totalIndexCreateRecommendations = count;
            
            System.out.println(String.format("\n  💡 Total index creation recommendations: %d", count));
            System.out.println("  🤖 Recommendations include AI-generated suggestions and cardinality analysis");
        } else {
            totalIndexCreateRecommendations = 0;
            System.out.println("\n📦 No new index recommendations found.");
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
                    CardinalityLevel card = cardinalityAnalyzer.analyzeColumnCardinality(table, first);
                    if (card == CardinalityLevel.LOW && !idx.name.isEmpty()) {
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
                System.out.println(String.format("\n  Drop index %s:", idxName));
                System.out.println(indent(snippet, 2));
                dcount++;
            }
            totalIndexDropRecommendations = dcount;
            
            System.out.println(String.format("\n  💡 Total index drop recommendations: %d", dcount));
        } else {
            totalIndexDropRecommendations = 0;
        }
    }

    private String sanitize(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("[^A-Za-z0-9_]+", "_");
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
    public TokenUsage getCumulativeTokenUsage() { return cumulativeTokenUsage; }

    /**
     * Generates a Liquibase changes file from consolidated suggestions and includes it in the master file.
     * Enhanced to integrate AI-generated index recommendations with existing Liquibase changeset generation.
     */
    public void generateLiquibaseChangesFile() {
        try {
            if (suggestedNewIndexes.isEmpty()) {
                logger.info("No index recommendations to generate Liquibase changes for");
                return;
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("<!-- AI-Enhanced Query Optimization Index Recommendations -->\n");
            sb.append("<!-- Generated by QueryOptimizationChecker with AI integration -->\n");
            
            int aiGeneratedCount = 0;
            int cardinalityBasedCount = 0;
            
            for (String key : suggestedNewIndexes) {
                String[] parts = key.split("\\|", 2);
                String table = parts.length > 0 ? parts[0] : TABLE_NAME_TAG;
                String column = parts.length > 1 ? parts[1] : "<COLUMN_NAME>";
                
                // Use existing buildLiquibaseNonLockingIndexChangeSet logic
                String changeSet = buildLiquibaseNonLockingIndexChangeSet(table, column);
                
                // Add comment indicating source of recommendation
                sb.append("\n<!-- Index recommendation for ").append(table).append(".").append(column).append(" -->\n");
                sb.append(changeSet).append("\n");
                
                // Track recommendation sources for logging
                if (isAIGeneratedRecommendation(table, column)) {
                    aiGeneratedCount++;
                } else {
                    cardinalityBasedCount++;
                }
            }
            
            // Add summary comment
            sb.append("\n<!-- Summary: ").append(suggestedNewIndexes.size()).append(" total recommendations ");
            sb.append("(").append(aiGeneratedCount).append(" AI-generated, ");
            sb.append(cardinalityBasedCount).append(" cardinality-based) -->\n");
            
            // Write to Liquibase changes file
            LiquibaseChangesWriter writer = new LiquibaseChangesWriter();
            writer.write(liquibaseXmlPath, sb.toString());
            
            logger.info("Generated Liquibase changes file with {} index recommendations ({} AI-generated, {} cardinality-based)", 
                       suggestedNewIndexes.size(), aiGeneratedCount, cardinalityBasedCount);
            
        } catch (Exception e) {
            logger.warn("Failed to generate Liquibase changes file: {}", e.getMessage());
        }
    }

    /**
     * Determines if an index recommendation was generated by AI analysis.
     * This is a simplified heuristic - in a more sophisticated implementation,
     * we would track the source of each recommendation explicitly.
     * 
     * @param table the table name
     * @param column the column name
     * @return true if likely AI-generated, false if likely cardinality-based
     */
    private boolean isAIGeneratedRecommendation(String table, String column) {
        // This is a simplified heuristic. In practice, we would track recommendation sources
        // For now, assume recommendations that don't follow the traditional cardinality pattern
        // are more likely to be AI-generated
        try {
            CardinalityLevel cardinality = cardinalityAnalyzer.analyzeColumnCardinality(table, column);
            // Traditional cardinality-based recommendations focus on MEDIUM cardinality columns
            // AI might recommend indexes for other cardinality levels based on query patterns
            return cardinality != CardinalityLevel.MEDIUM;
        } catch (Exception e) {
            // If we can't determine cardinality, assume it's AI-generated
            return true;
        }
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
}
