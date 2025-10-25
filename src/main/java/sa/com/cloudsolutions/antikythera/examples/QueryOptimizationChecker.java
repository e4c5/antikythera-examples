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
    }

    /**
     * Analyzes all JPA repositories using RepositoryParser to extract and analyze queries.
     * 
     */
    public void analyze() throws IOException {
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
    protected void analyzeRepository(String fullyQualifiedName, TypeWrapper typeWrapper) throws IOException {
        logger.debug("Analyzing repository: {}", fullyQualifiedName);

        // Use RepositoryParser to process the repository type
        repositoryParser.compile(AbstractCompiler.classToPath(fullyQualifiedName));
        repositoryParser.processTypes();

        // Build all queries using RepositoryParser
        repositoryParser.buildQueries();
        results.clear();
        // Analyze each method in the repository to get its queries
        if (typeWrapper.getType() != null) {
            var declaration = typeWrapper.getType().asClassOrInterfaceDeclaration();

            for (var method : declaration.getMethods()) {
                Callable callable = new Callable(method, null);
                RepositoryQuery repositoryQuery = repositoryParser.get(callable);

                if (repositoryQuery != null) {
                    results.add(analyzeRepositoryQuery(repositoryQuery));
                }
            }
        }
    }

    /**
     * Analyzes a single repository query using QueryAnalysisEngine.
     * Enhanced to pass Callable information for better repository class and method name reporting.
     * @param repositoryQuery the RepositoryQuery object containing parsed query information
     */
    protected QueryOptimizationResult analyzeRepositoryQuery(RepositoryQuery repositoryQuery) {
        // Count every repository method query analyzed
        totalQueriesAnalyzed++;

        // Use QueryAnalysisEngine to analyze the query with enhanced Callable information
        QueryOptimizationResult result = analysisEngine.analyzeQueryWithCallable(repositoryQuery);

        // Report optimization issues with enhanced reporting
        reportOptimizationResults(result);
        return result;

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
        if (repositoryClass == null) return TABLE_NAME_TAG;
        String simple = repositoryClass;
        int dot = simple.lastIndexOf('.');
        if (dot >= 0) simple = simple.substring(dot + 1);
        if (simple.endsWith("Repository")) simple = simple.substring(0, simple.length() - "Repository".length());
        // convert CamelCase to snake_case
        String snake = simple.replaceAll("(?<!^)([A-Z])", "_$1").toLowerCase();
        return snake.isEmpty() ? TABLE_NAME_TAG : snake;
    }

    private String buildLiquibaseNonLockingIndexChangeSet(String tableName, String columnName) {
        if (columnName == null || columnName.isEmpty()) columnName = "<COLUMN_NAME>";
        if (tableName == null || tableName.isEmpty()) tableName = TABLE_NAME_TAG;
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
                String table = parts.length > 0 ? parts[0] : TABLE_NAME_TAG;
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

    /**
     * Generates a Liquibase changes file from consolidated suggestions and includes it in the master file.
     */
    public void generateLiquibaseChangesFile() {
        try {
            if (suggestedNewIndexes.isEmpty()) return;
            StringBuilder sb = new StringBuilder();
            for (String key : suggestedNewIndexes) {
                String[] parts = key.split("\\|", 2);
                String table = parts.length > 0 ? parts[0] : TABLE_NAME_TAG;
                String column = parts.length > 1 ? parts[1] : "<COLUMN_NAME>";
                sb.append("\n").append(buildLiquibaseNonLockingIndexChangeSet(table, column)).append("\n");
            }
            // For simplicity, only include create index changes here
            LiquibaseChangesWriter writer = new LiquibaseChangesWriter();
            writer.write(liquibaseXmlPath, sb.toString());
        } catch (Exception e) {
            logger.warn("Failed to generate Liquibase changes file: {}", e.getMessage());
        }
    }

    /**
     * Apply recorded signature updates to usage sites in classes that @Autowired the given repository.
     * This reorders call arguments to match the new parameter order. Only same-arity calls are modified.
     */
    public void applySignatureUpdatesToUsages() {
        if (signatureUpdates.isEmpty()) return;
        Map<String, java.util.List<CodeStandardizer.SignatureUpdate>> byRepo = new java.util.HashMap<>();
        for (CodeStandardizer.SignatureUpdate up : signatureUpdates) {
            byRepo.computeIfAbsent(up.repositoryClassFqn, k -> new java.util.ArrayList<>()).add(up);
        }
        Map<String, com.github.javaparser.ast.CompilationUnit> units = AntikytheraRunTime.getResolvedCompilationUnits();
        for (Map.Entry<String, com.github.javaparser.ast.CompilationUnit> e : units.entrySet()) {
            String fqn = e.getKey();
            com.github.javaparser.ast.CompilationUnit cu = e.getValue();
            boolean modified = false;
            // Find classes with @Autowired fields that match any repo
            java.util.List<com.github.javaparser.ast.body.ClassOrInterfaceDeclaration> classes = cu.findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class);
            for (com.github.javaparser.ast.body.ClassOrInterfaceDeclaration cls : classes) {
                java.util.Map<String, String> autowiredFields = new java.util.HashMap<>(); // fieldName -> typeSimple
                for (com.github.javaparser.ast.body.FieldDeclaration fd : cls.getFields()) {
                    if (fd.getAnnotationByName("Autowired").isPresent()) {
                        for (var var : fd.getVariables()) {
                            String typeName = var.getType().toString();
                            String simple = simpleName(typeName);
                            autowiredFields.put(var.getNameAsString(), simple);
                        }
                    }
                }
                if (autowiredFields.isEmpty()) continue;

                java.util.List<com.github.javaparser.ast.expr.MethodCallExpr> calls = cu.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class);
                for (com.github.javaparser.ast.expr.MethodCallExpr call : calls) {
                    String scopeName = getScopeName(call.getScope().orElse(null));
                    if (scopeName == null) continue;
                    String fieldTypeSimple = autowiredFields.get(scopeName);
                    if (fieldTypeSimple == null) continue;
                    // Try all updates whose repo simple name matches
                    for (Map.Entry<String, java.util.List<CodeStandardizer.SignatureUpdate>> upEntry : byRepo.entrySet()) {
                        String repoFqn = upEntry.getKey();
                        String repoSimple = simpleName(repoFqn);
                        if (!repoSimple.equals(fieldTypeSimple)) continue;
                        for (CodeStandardizer.SignatureUpdate up : upEntry.getValue()) {
                            if (!up.methodName.equals(call.getNameAsString())) continue;
                            java.util.List<String> oldNames = up.oldParamNames;
                            java.util.List<String> newNames = up.newParamNames;
                            com.github.javaparser.ast.NodeList<com.github.javaparser.ast.expr.Expression> args = call.getArguments();
                            if (args.size() != oldNames.size() || oldNames.size() != newNames.size()) continue;
                            // Map old param name to index
                            java.util.Map<String, Integer> oldIndex = new java.util.HashMap<>();
                            for (int i = 0; i < oldNames.size(); i++) oldIndex.put(oldNames.get(i), i);
                            com.github.javaparser.ast.NodeList<com.github.javaparser.ast.expr.Expression> reordered = new com.github.javaparser.ast.NodeList<>();
                            boolean ok = true;
                            for (String pname : newNames) {
                                Integer idx = oldIndex.get(pname);
                                if (idx == null || idx >= args.size()) { ok = false; break; }
                                reordered.add(args.get(idx));
                            }
                            if (!ok) continue;
                            call.setArguments(reordered);
                            modified = true;
                            logger.info("Reordered call args for {}.{} in {}", repoSimple, up.methodName, fqn);
                        }
                    }
                }
            }
            if (modified) {
                try {
                    java.nio.file.Path p = java.nio.file.Path.of(sa.com.cloudsolutions.antikythera.parser.AbstractCompiler.classToPath(fqn));
                    java.nio.file.Files.writeString(p, cu.toString());
                } catch (Exception ex) {
                    logger.warn("Failed writing updated usages in {}: {}", fqn, ex.getMessage());
                }
            }
        }
    }

    private String getScopeName(com.github.javaparser.ast.expr.Expression scope) {
        if (scope == null) return null;
        if (scope.isNameExpr()) return scope.asNameExpr().getNameAsString();
        if (scope.isFieldAccessExpr()) return scope.asFieldAccessExpr().getNameAsString();
        if (scope.isThisExpr()) return "this"; // unlikely useful
        return null;
    }

    private String simpleName(String typeName) {
        if (typeName == null) return null;
        int lt = typeName.lastIndexOf('<');
        if (lt > 0) typeName = typeName.substring(0, lt);
        int dot = typeName.lastIndexOf('.');
        return dot >= 0 ? typeName.substring(dot + 1) : typeName;
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
        // Apply any method signature updates to usages in @Autowired consumers
        checker.applySignatureUpdatesToUsages();

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
