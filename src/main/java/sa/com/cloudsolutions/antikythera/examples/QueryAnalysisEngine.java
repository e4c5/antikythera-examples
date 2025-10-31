package sa.com.cloudsolutions.antikythera.examples;

import net.sf.jsqlparser.statement.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.generator.QueryMethodParameter;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;

import java.util.Optional;

import java.util.ArrayList;
import java.util.List;

/**
 * Enhanced engine that analyzes repository queries using RepositoryQuery's parsing capabilities
 * to identify WHERE clause optimization opportunities based on column cardinality.
 * Updated to support AI-powered query optimization with enhanced analysis capabilities.
 */
public class QueryAnalysisEngine {

    private static final Logger logger = LoggerFactory.getLogger(QueryAnalysisEngine.class);

    private final QueryOptimizationExtractor optimizationExtractor;
    
    /**
     * Creates a new QueryAnalysisEngine with the provided cardinality analyzer.
     * 
     */
    public QueryAnalysisEngine() {
        this.optimizationExtractor = new QueryOptimizationExtractor();
    }
    
    /**
     * Analyzes a RepositoryQuery using its parsed Statement to identify optimization opportunities.
     * Enhanced to handle both HQL and native SQL queries through RepositoryQuery conversion.
     * 
     * @param repositoryQuery the repository query to analyze
     * @return the analysis results including WHERE conditions and optimization issues
     */
    public QueryOptimizationResult analyzeQuery(RepositoryQuery repositoryQuery) {

        String queryText = getQueryText(repositoryQuery);

        Statement statement = repositoryQuery.getStatement();
        if (statement == null) {
            return handleDerivedQuery(repositoryQuery, queryText);
        }

        // Extract table name for cardinality analysis
        String tableName = repositoryQuery.getPrimaryTable();
        if (tableName == null || tableName.isEmpty()) {
            return createEmptyResult(repositoryQuery);
        }

        // Extract WHERE clause conditions using the parser infrastructure
        List<WhereCondition> whereConditions = optimizationExtractor.extractWhereConditions(repositoryQuery);

        // Analyze condition ordering for optimization opportunities using existing CardinalityAnalyzer
        List<OptimizationIssue> optimizationIssues = analyzeConditionOrdering(
            whereConditions, repositoryQuery, queryText);

        return new QueryOptimizationResult(repositoryQuery, whereConditions, optimizationIssues, List.of());
    }

    /**
     * Handles analysis of derived query methods (findBy*, countBy*, etc.).
     */
    private QueryOptimizationResult handleDerivedQuery(RepositoryQuery repositoryQuery, String queryText) {
        // For derived queries, we can still analyze the method parameters to infer WHERE conditions
        List<WhereCondition> whereConditions = new ArrayList<>();
        List<OptimizationIssue> optimizationIssues = new ArrayList<>();
        
        List<QueryMethodParameter> methodParameters = repositoryQuery.getMethodParameters();
        if (methodParameters != null && !methodParameters.isEmpty()) {
            // Infer table name from repository class
            String tableName = repositoryQuery.getPrimaryTable();
            
            // Create conditions based on method parameters
            for (int i = 0; i < methodParameters.size(); i++) {
                QueryMethodParameter param = methodParameters.get(i);
                String columnName = param.getColumnName();
                if (columnName != null && !columnName.isEmpty()) {
                    CardinalityLevel cardinality = CardinalityAnalyzer.analyzeColumnCardinality(tableName, columnName);
                    WhereCondition condition = new WhereCondition(columnName, "=", cardinality, i, param);
                    whereConditions.add(condition);
                }
            }
            
            // Analyze the inferred conditions
            optimizationIssues = analyzeConditionOrdering(whereConditions, repositoryQuery, queryText);
        }
        
        return new QueryOptimizationResult(repositoryQuery, whereConditions, optimizationIssues, List.of());
    }
    
    /**
     * Analyzes condition ordering to identify optimization opportunities based on cardinality analysis.
     * Enhanced to provide comprehensive optimization recommendations using existing CardinalityAnalyzer.
     * 
     * @param conditions the list of WHERE conditions to analyze
     * @param query the repository query
     * @param queryText the full query text for reporting
     * @return list of optimization issues found
     */
    private List<OptimizationIssue> analyzeConditionOrdering(List<WhereCondition> conditions, RepositoryQuery query, String queryText) {
        List<OptimizationIssue> issues = new ArrayList<>();
        
        if (conditions.isEmpty()) {
            logger.debug("No WHERE conditions found for analysis");
            return issues;
        }
        
        WhereCondition firstCondition = conditions.getFirst();
        String tableName = query.getPrimaryTable();
        
        logger.debug("Analyzing condition ordering for {} conditions, first condition: {}", 
                    conditions.size(), firstCondition);

        // Rule 1: If the first condition is MEDIUM cardinality, ensure there is a supporting index
        if (firstCondition.cardinality() == CardinalityLevel.MEDIUM) {
            boolean hasLeadingIndex = CardinalityAnalyzer.hasIndexWithLeadingColumn(tableName, firstCondition.columnName());
            if (!hasLeadingIndex) {
                String description = String.format(
                        "First WHERE condition uses medium cardinality column '%s' but no index starts with this column. " +
                        "Create an index with leading column '%s' to improve query performance.",
                        firstCondition.columnName(), firstCondition.columnName());
                OptimizationIssue indexIssue = new OptimizationIssue(query, firstCondition.columnName(),
                        firstCondition.columnName(), description, OptimizationIssue.Severity.HIGH, queryText);
                issues.add(indexIssue);
                logger.debug("Added index recommendation issue for medium cardinality column: {}", firstCondition.columnName());
            }
        }
        
        // Rule 2: Check if first condition is low cardinality while higher-cardinality alternatives exist
        if (firstCondition.isLowCardinality()) {
            // Prefer HIGH over MEDIUM if both exist
            Optional<WhereCondition> highCardinalityAlternative = conditions.stream()
                .filter(WhereCondition::isHighCardinality)
                .findFirst();

            if (highCardinalityAlternative.isPresent()) {
                WhereCondition recommended = highCardinalityAlternative.get();

                // Enhanced description with cardinality information
                String cardinalityDetails = getCardinalityDetails(tableName, firstCondition, recommended);
                String description = String.format(
                    "Query starts with %s cardinality column '%s' but %s cardinality column '%s' is available. %s",
                    firstCondition.cardinality().toString().toLowerCase(),
                    firstCondition.columnName(),
                    recommended.cardinality().toString().toLowerCase(),
                    recommended.columnName(),
                    cardinalityDetails);

                OptimizationIssue issue = new OptimizationIssue(query, firstCondition.columnName(),
                    recommended.columnName(), description, OptimizationIssue.Severity.HIGH, queryText);
                issues.add(issue);
                logger.debug("Added high severity issue: low cardinality first with high cardinality alternative");
            } else {
                // Rule 3: LOW followed by any MEDIUM should also be flagged
                Optional<WhereCondition> mediumCardinalityAlternative = conditions.stream()
                    .filter(cond -> cond.cardinality() == CardinalityLevel.MEDIUM)
                    .findFirst();

                if (mediumCardinalityAlternative.isPresent()) {
                    WhereCondition recommended = mediumCardinalityAlternative.get();
                    String description = String.format(
                        "Query starts with low cardinality column '%s' but a medium cardinality column '%s' occurs later in the WHERE clause. Consider reordering for better selectivity.",
                        firstCondition.columnName(),
                        recommended.columnName());

                    OptimizationIssue issue = new OptimizationIssue(query, firstCondition.columnName(),
                        recommended.columnName(), description, OptimizationIssue.Severity.MEDIUM, queryText);
                    issues.add(issue);
                    logger.debug("Added medium severity issue: low cardinality first with medium cardinality alternative");
                }
            }
        }
        
        // Rule 4: Check for suboptimal ordering of high cardinality columns
        List<WhereCondition> highCardinalityConditions = conditions.stream()
            .filter(WhereCondition::isHighCardinality)
            .toList();
            
        if (highCardinalityConditions.size() > 1) {
            // Find the most selective high cardinality column (primary keys are most selective)
            Optional<WhereCondition> primaryKeyCondition = highCardinalityConditions.stream()
                .filter(condition -> CardinalityAnalyzer.isPrimaryKey(tableName, condition.columnName()))
                .findFirst();
                
            if (primaryKeyCondition.isPresent() && !firstCondition.equals(primaryKeyCondition.get())) {
                WhereCondition recommended = primaryKeyCondition.get();
                
                // Enhanced description with cardinality and selectivity information
                String selectivityDetails = getSelectivityDetails(tableName, firstCondition, recommended);
                String description = String.format(
                    "Primary key column '%s' should appear first in WHERE clause for optimal performance. " +
                    "Currently starts with '%s' (%s cardinality). %s",
                    recommended.columnName(),
                    firstCondition.columnName(),
                    firstCondition.cardinality().toString().toLowerCase(),
                    selectivityDetails);
                    
                OptimizationIssue issue = new OptimizationIssue(query, firstCondition.columnName(),
                    recommended.columnName(), description, OptimizationIssue.Severity.MEDIUM, queryText);
                issues.add(issue);
                logger.debug("Added primary key ordering issue");
            }
        }
        
        logger.debug("Analysis complete. Found {} optimization issues", issues.size());
        return issues;
    }

    /**
     * Gets the query text from the RepositoryQuery.
     * Enhanced to handle both HQL and native SQL queries properly.
     */
    private String getQueryText(RepositoryQuery repositoryQuery) {
        try {
            // Prefer original query for better readability in reports
            String originalQuery = repositoryQuery.getOriginalQuery();
            if (originalQuery != null && !originalQuery.isEmpty()) {
                return originalQuery;
            }
            
            // Fall back to parsed statement if original is not available
            if (repositoryQuery.getStatement() != null) {
                return repositoryQuery.getStatement().toString();
            }
            
            // Check if this is a derived query method (no explicit query)
            String methodName = repositoryQuery.getMethodDeclaration().getNameAsString();
            if (methodName.startsWith("findBy") || methodName.startsWith("countBy") || 
                methodName.startsWith("deleteBy") || methodName.startsWith("existsBy")) {
                return "Derived query method: " + methodName;
            }
        } catch (Exception e) {
            logger.debug("Error getting query text: {}", e.getMessage());
        }
        return "";
    }
    
    /**
     * Gets detailed cardinality information for optimization issue descriptions.
     * 
     * @param tableName the table name
     * @param currentCondition the current first condition
     * @param recommendedCondition the recommended first condition
     * @return detailed cardinality explanation
     */
    private String getCardinalityDetails(String tableName, WhereCondition currentCondition, WhereCondition recommendedCondition) {
        StringBuilder details = new StringBuilder();
        
        // Explain why the current condition is problematic
        if (currentCondition.isLowCardinality()) {
            if (CardinalityAnalyzer.isBooleanColumn(currentCondition.columnName())) {
                details.append("Boolean columns filter roughly 50% of rows. ");
            } else {
                details.append("Low cardinality columns filter fewer rows, requiring more processing. ");
            }
        }
        
        // Explain why the recommended condition is better
        if (recommendedCondition.isHighCardinality()) {
            if (CardinalityAnalyzer.isPrimaryKey(tableName, recommendedCondition.columnName())) {
                details.append("Primary keys provide unique row identification for optimal filtering.");
            } else if (CardinalityAnalyzer.hasUniqueConstraint(tableName, recommendedCondition.columnName())) {
                details.append("Unique columns provide highly selective filtering.");
            } else {
                details.append("High cardinality columns filter more rows earlier in query execution.");
            }
        }
        
        return details.toString();
    }
    
    /**
     * Gets detailed selectivity information for optimization issue descriptions.
     * 
     * @param tableName the table name
     * @param currentCondition the current first condition
     * @param recommendedCondition the recommended first condition
     * @return detailed selectivity explanation
     */
    private String getSelectivityDetails(String tableName, WhereCondition currentCondition, WhereCondition recommendedCondition) {
        StringBuilder details = new StringBuilder();
        
        if (CardinalityAnalyzer.isPrimaryKey(tableName, recommendedCondition.columnName())) {
            details.append("Primary keys provide maximum selectivity (1 row per value). ");
        } else if (CardinalityAnalyzer.hasUniqueConstraint(tableName, recommendedCondition.columnName())) {
            details.append("Unique constraints provide high selectivity. ");
        }
        
        if (currentCondition.isLowCardinality()) {
            details.append("Current first condition has low selectivity, causing unnecessary row processing.");
        } else {
            details.append("Reordering can improve query execution plan efficiency.");
        }
        
        return details.toString();
    }
    
    /**
     * Creates an empty result for cases where analysis cannot be performed.
     */
    private QueryOptimizationResult createEmptyResult(RepositoryQuery query) {
        return new QueryOptimizationResult(query, List.of(), List.of(), List.of());
    }

    /**
     * Validates that the engine is properly configured and ready for analysis.
     * 
     * @return true if the engine is ready, false otherwise
     */
    public boolean isReady() {
        return CardinalityAnalyzer.getIndexMap() != null;
    }
}
