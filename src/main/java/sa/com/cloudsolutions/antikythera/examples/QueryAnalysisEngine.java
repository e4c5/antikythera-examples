package sa.com.cloudsolutions.antikythera.examples;

import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.ComparisonOperator;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.generator.QueryMethodParameter;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Enhanced engine that analyzes repository queries using RepositoryQuery's parsing capabilities
 * to identify WHERE clause optimization opportunities based on column cardinality.
 * Updated to support AI-powered query optimization with enhanced analysis capabilities.
 */
public class QueryAnalysisEngine {

    private static final Logger logger = LoggerFactory.getLogger(QueryAnalysisEngine.class);
    public static final String UNKNOWN = "Unknown";

    private final CardinalityAnalyzer cardinalityAnalyzer;
    
    /**
     * Creates a new QueryAnalysisEngine with the provided cardinality analyzer.
     * 
     * @param cardinalityAnalyzer the analyzer for determining column cardinality
     */
    public QueryAnalysisEngine(CardinalityAnalyzer cardinalityAnalyzer) {
        this.cardinalityAnalyzer = cardinalityAnalyzer;
    }
    
    /**
     * Analyzes a RepositoryQuery using its parsed Statement to identify optimization opportunities.
     * Enhanced to handle both HQL and native SQL queries through RepositoryQuery conversion.
     * 
     * @param repositoryQuery the repository query to analyze
     * @return the analysis results including WHERE conditions and optimization issues
     */
    public QueryOptimizationResult analyzeQuery(RepositoryQuery repositoryQuery) {
        return analyzeQueryWithCallable(repositoryQuery);
    }
    
    /**
     * Analyzes a RepositoryQuery with enhanced Callable information for better reporting.
     * Enhanced to include repository class name and method name from Callable objects.
     * This method maintains compatibility with existing code while supporting AI analysis.
     * 
     * @param repositoryQuery the repository query to analyze
     * @return the analysis results including WHERE conditions and optimization issues
     */
    public QueryOptimizationResult analyzeQueryWithCallable(RepositoryQuery repositoryQuery) {
        String queryText = getQueryText(repositoryQuery);

        Statement statement = repositoryQuery.getStatement();
        if (statement == null) {
            return handleDerivedQuery(repositoryQuery, queryText);
        }

        // Extract table name for cardinality analysis
        String tableName = repositoryQuery.getTable();
        if (tableName == null || tableName.isEmpty()) {
            return createEmptyResult(repositoryQuery, queryText);
        }

        // Extract WHERE clause conditions using RepositoryQuery's parsing capabilities
        List<WhereCondition> whereConditions = extractWhereConditions(repositoryQuery);

        // Analyze condition ordering for optimization opportunities using existing CardinalityAnalyzer
        List<OptimizationIssue> optimizationIssues = analyzeConditionOrdering(
            whereConditions, repositoryQuery, queryText);

        return new QueryOptimizationResult(repositoryQuery, whereConditions, optimizationIssues);
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
            String tableName = repositoryQuery.getTable();
            
            // Create conditions based on method parameters
            for (int i = 0; i < methodParameters.size(); i++) {
                QueryMethodParameter param = methodParameters.get(i);
                String columnName = param.getColumnName();
                if (columnName != null && !columnName.isEmpty()) {
                    CardinalityLevel cardinality = cardinalityAnalyzer.analyzeColumnCardinality(tableName, columnName);
                    WhereCondition condition = new WhereCondition(columnName, "=", cardinality, i, param);
                    whereConditions.add(condition);
                }
            }
            
            // Analyze the inferred conditions
            optimizationIssues = analyzeConditionOrdering(whereConditions, repositoryQuery, queryText);
        }
        
        return new QueryOptimizationResult(repositoryQuery, whereConditions, optimizationIssues);
    }

    /**
     * Extracts WHERE clause conditions using RepositoryQuery's expression parsing capabilities.
     * Enhanced to handle both parsed statements and derived queries from method parameters.
     * 
     * @param repositoryQuery the repository query containing the parsed statement
     * @return list of WHERE conditions found in the query
     */
    private List<WhereCondition> extractWhereConditions(RepositoryQuery repositoryQuery) {
        List<WhereCondition> conditions = new ArrayList<>();
        
        Statement statement = repositoryQuery.getStatement();
        if (statement instanceof Select select && select.getSelectBody() instanceof PlainSelect plainSelect) {
            Expression whereClause = plainSelect.getWhere();

            if (whereClause != null) {
                extractConditionsFromExpression(whereClause, conditions, repositoryQuery, 0);
            }
        } else {
            // For queries without parsed statements (e.g., derived queries), 
            // extract conditions from method parameters
            logger.debug("No WHERE clause found in parsed statement, checking method parameters");
            extractConditionsFromMethodParameters(repositoryQuery, conditions);
        }
        
        return conditions;
    }
    
    /**
     * Extracts WHERE conditions from method parameters for derived queries.
     * This handles cases where queries are inferred from method names (findBy*, etc.).
     * 
     * @param repositoryQuery the repository query
     * @param conditions the list to add extracted conditions to
     */
    private void extractConditionsFromMethodParameters(RepositoryQuery repositoryQuery, List<WhereCondition> conditions) {
        List<QueryMethodParameter> methodParameters = repositoryQuery.getMethodParameters();
        if (methodParameters == null || methodParameters.isEmpty()) {
            return;
        }
        
        String tableName = repositoryQuery.getTable();
        if (tableName == null || tableName.isEmpty()) {
            logger.debug("Cannot extract conditions from method parameters without table name");
            return;
        }
        
        for (int i = 0; i < methodParameters.size(); i++) {
            QueryMethodParameter param = methodParameters.get(i);
            String columnName = param.getColumnName();
            if (columnName != null && !columnName.isEmpty()) {
                CardinalityLevel cardinality = cardinalityAnalyzer.analyzeColumnCardinality(tableName, columnName);
                WhereCondition condition = new WhereCondition(columnName, "=", cardinality, i, param);
                conditions.add(condition);
                logger.debug("Extracted condition from method parameter: {}", condition);
            }
        }
    }
    
    /**
     * Recursively extracts conditions from a WHERE clause expression.
     * Enhanced to handle complex expressions and improve parameter mapping.
     * 
     * @param expression the expression to analyze
     * @param conditions the list to add found conditions to
     * @param repositoryQuery the repository query for parameter mapping
     * @param position the current position counter for conditions
     */
    private void extractConditionsFromExpression(Expression expression, List<WhereCondition> conditions, 
                                               RepositoryQuery repositoryQuery, int position) {
        if (expression instanceof AndExpression andExpr) {
            // Process left side first (maintains order for optimization analysis)
            extractConditionsFromExpression(andExpr.getLeftExpression(), conditions, repositoryQuery, position);
            extractConditionsFromExpression(andExpr.getRightExpression(), conditions, repositoryQuery,
                                          position + getConditionCount(andExpr.getLeftExpression()));
        } else if (expression instanceof OrExpression orExpr) {
            // For OR expressions, both sides are equally important for optimization
            extractConditionsFromExpression(orExpr.getLeftExpression(), conditions, repositoryQuery, position);
            extractConditionsFromExpression(orExpr.getRightExpression(), conditions, repositoryQuery,
                                          position + getConditionCount(orExpr.getLeftExpression()));
        } else if (expression instanceof ComparisonOperator comparison) {
            createConditionFromComparison(comparison, repositoryQuery, position)
                .ifPresent(conditions::add);
        } else if (expression instanceof Between between) {
            createConditionFromBetween(between, repositoryQuery, position)
                .ifPresent(conditions::add);
        } else if (expression instanceof InExpression inExpr) {
            createConditionFromIn(inExpr, repositoryQuery, position)
                .ifPresent(conditions::add);
        } else if (expression instanceof IsNullExpression isNull) {
            createConditionFromIsNull(isNull, repositoryQuery, position)
                .ifPresent(conditions::add);
        } else {
            // Handle other expression types that might contain column references
            logger.debug("Unhandled expression type in WHERE clause: {}", expression.getClass().getSimpleName());
        }
    }
    
    /**
     * Creates a WhereCondition from a comparison operator expression.
     * Enhanced to better handle parameter mapping and HQL/SQL differences.
     */
    private Optional<WhereCondition> createConditionFromComparison(ComparisonOperator comparison,
                                                        RepositoryQuery repositoryQuery, int position) {
        Expression leftExpr = comparison.getLeftExpression();
        if (leftExpr instanceof Column column) {
            String columnName = extractColumnName(column);
            String operator = comparison.getStringExpression();

            // Enhanced parameter mapping using RepositoryQuery's parameter mapping capabilities
            QueryMethodParameter parameter = findMatchingParameterEnhanced(repositoryQuery, columnName, comparison.getRightExpression())
                .orElse(null);

            CardinalityLevel cardinality = cardinalityAnalyzer.analyzeColumnCardinality(repositoryQuery.getTable(), columnName);

            return Optional.of(new WhereCondition(columnName, operator, cardinality, position, parameter));
        }
        return Optional.empty();
    }
    
    /**
     * Enhanced parameter matching that uses RepositoryQuery's parameter mapping logic.
     */
    private Optional<QueryMethodParameter> findMatchingParameterEnhanced(RepositoryQuery repositoryQuery, String columnName, Expression rightExpression) {
        // First try the existing matching logic
        Optional<QueryMethodParameter> parameter = findMatchingParameter(repositoryQuery, columnName);
        if (parameter.isPresent()) {
            return parameter;
        }
        
        // Try to use RepositoryQuery's parameter mapping logic
        try {
            // Check if the right expression is a parameter placeholder
            if (rightExpression instanceof net.sf.jsqlparser.expression.JdbcParameter jdbcParam) {
                int paramIndex = jdbcParam.getIndex() - 1; // JDBC parameters are 1-based
                List<QueryMethodParameter> methodParameters = repositoryQuery.getMethodParameters();
                
                if (methodParameters != null && paramIndex >= 0 && paramIndex < methodParameters.size()) {
                    QueryMethodParameter methodParam = methodParameters.get(paramIndex);
                    // Update the parameter's column mapping if not already set
                    String existingColumnName = methodParam.getColumnName();
                    if (existingColumnName == null || existingColumnName.isEmpty()) {
                        methodParam.setColumnName(columnName);
                    }
                    return Optional.of(methodParam);
                }
            } else if (rightExpression instanceof net.sf.jsqlparser.expression.JdbcNamedParameter namedParam) {
                String paramName = namedParam.getName();
                List<QueryMethodParameter> methodParameters = repositoryQuery.getMethodParameters();
                
                if (methodParameters != null) {
                    return methodParameters.stream()
                        .filter(param -> paramName.equals(param.getPlaceHolderName()))
                        .findFirst();
                }
            }
        } catch (Exception e) {
            logger.debug("Error in enhanced parameter matching: {}", e.getMessage());
        }
        
        return Optional.empty();
    }
    
    /**
     * Creates a WhereCondition from a BETWEEN expression.
     * Enhanced to handle parameter mapping for BETWEEN expressions.
     */
    private Optional<WhereCondition> createConditionFromBetween(Between between, RepositoryQuery repositoryQuery, int position) {
        Expression leftExpr = between.getLeftExpression();
        if (!(leftExpr instanceof Column column)) {
            return Optional.empty();
        }
        
        String columnName = extractColumnName(column);
        
        // For BETWEEN, try to find parameter from start expression
        QueryMethodParameter parameter = findMatchingParameterEnhanced(repositoryQuery, columnName, between.getBetweenExpressionStart())
            .or(() -> findMatchingParameterEnhanced(repositoryQuery, columnName, between.getBetweenExpressionEnd()))
            .orElse(null);
        
        CardinalityLevel cardinality = cardinalityAnalyzer.analyzeColumnCardinality(repositoryQuery.getTable(), columnName);
        
        return Optional.of(new WhereCondition(columnName, "BETWEEN", cardinality, position, parameter));
    }
    
    /**
     * Creates a WhereCondition from an IN expression.
     * Enhanced to handle parameter mapping for IN expressions.
     */
    private Optional<WhereCondition> createConditionFromIn(InExpression inExpr, RepositoryQuery repositoryQuery, int position) {
        Expression leftExpr = inExpr.getLeftExpression();
        if (!(leftExpr instanceof Column column)) {
            return Optional.empty();
        }
        
        String columnName = extractColumnName(column);
        
        // For IN expressions, try to find parameter from the right expression
        QueryMethodParameter parameter = findMatchingParameterEnhanced(repositoryQuery, columnName, inExpr.getRightExpression())
            .orElse(null);
        
        CardinalityLevel cardinality = cardinalityAnalyzer.analyzeColumnCardinality(repositoryQuery.getTable(), columnName);
        
        return Optional.of(new WhereCondition(columnName, "IN", cardinality, position, parameter));
    }
    
    /**
     * Creates a WhereCondition from an IS NULL expression.
     * Enhanced parameter mapping (though IS NULL typically doesn't have parameters).
     */
    private Optional<WhereCondition> createConditionFromIsNull(IsNullExpression isNull, RepositoryQuery repositoryQuery, int position) {
        Expression leftExpr = isNull.getLeftExpression();
        if (leftExpr instanceof Column column) {
            String columnName = extractColumnName(column);

            // IS NULL expressions typically don't have parameters, but check anyway
            QueryMethodParameter parameter = findMatchingParameter(repositoryQuery, columnName).orElse(null);

            CardinalityLevel cardinality = cardinalityAnalyzer.analyzeColumnCardinality(repositoryQuery.getTable(), columnName);

            String operator = isNull.isNot() ? "IS NOT NULL" : "IS NULL";
            return Optional.of(new WhereCondition(columnName, operator, cardinality, position, parameter));
        }
        return Optional.empty();
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
        String tableName = query.getTable();
        
        logger.debug("Analyzing condition ordering for {} conditions, first condition: {}", 
                    conditions.size(), firstCondition);

        // Rule 1: If the first condition is MEDIUM cardinality, ensure there is a supporting index
        if (firstCondition.cardinality() == CardinalityLevel.MEDIUM) {
            boolean hasLeadingIndex = cardinalityAnalyzer.hasIndexWithLeadingColumn(tableName, firstCondition.columnName());
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
                .filter(condition -> cardinalityAnalyzer.isPrimaryKey(tableName, condition.columnName()))
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
     * Counts the number of individual conditions in an expression.
     */
    private int getConditionCount(Expression expression) {
        if (expression instanceof BinaryExpression binaryExpr) {
            return getConditionCount(binaryExpr.getLeftExpression()) + getConditionCount(binaryExpr.getRightExpression());
        }
        return 1;
    }
    
    /**
     * Extracts the column name from a Column object, handling table prefixes.
     */
    private String extractColumnName(Column column) {
        String columnName = column.getColumnName();
        // Remove table alias prefix if present (e.g., "t.column_name" -> "column_name")
        if (columnName.contains(".")) {
            String[] parts = columnName.split("\\.");
            columnName = parts[parts.length - 1];
        }
        return columnName;
    }
    
    /**
     * Finds a matching QueryMethodParameter for a given column name.
     * Enhanced to handle both direct column name matches and parameter name matches.
     */
    private Optional<QueryMethodParameter> findMatchingParameter(RepositoryQuery repositoryQuery, String columnName) {
        List<QueryMethodParameter> methodParameters = repositoryQuery.getMethodParameters();
        if (methodParameters == null || methodParameters.isEmpty()) {
            return Optional.empty();
        }
        
        // First try exact column name match
        Optional<QueryMethodParameter> exactMatch = methodParameters.stream()
            .filter(param -> columnName.equals(param.getColumnName()))
            .findFirst();
            
        if (exactMatch.isPresent()) {
            return exactMatch;
        }
        
        // Try placeholder name match (for named parameters)
        Optional<QueryMethodParameter> placeholderMatch = methodParameters.stream()
            .filter(param -> {
                String placeHolderName = param.getPlaceHolderName();
                return placeHolderName != null && columnName.equals(placeHolderName);
            })
            .findFirst();
            
        if (placeholderMatch.isPresent()) {
            return placeholderMatch;
        }
        
        // Try parameter name match (convert camelCase to snake_case)
        return methodParameters.stream()
            .filter(param -> {
                if (param.getParameter() != null) {
                    String paramName = param.getParameter().getNameAsString();
                    String snakeCaseParamName = convertCamelToSnakeCase(paramName);
                    return columnName.equals(snakeCaseParamName);
                }
                return false;
            })
            .findFirst();
    }
    
    /**
     * Converts camelCase to snake_case for parameter name matching.
     */
    private String convertCamelToSnakeCase(String camelCase) {
        if (camelCase == null || camelCase.isEmpty()) {
            return "";
        }
        
        return camelCase.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }

    /**
     * Converts a table name to a likely repository class name.
     */
    private String convertTableNameToRepositoryClass(String tableName) {
        if (tableName == null || tableName.isEmpty()) {
            return UNKNOWN;
        }
        
        // Convert snake_case to PascalCase and add Repository suffix
        String[] parts = tableName.toLowerCase().split("_");
        StringBuilder className = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                className.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) {
                    className.append(part.substring(1));
                }
            }
        }
        className.append("Repository");
        return className.toString();
    }
    
    /**
     * Infers method name from query parameters.
     */
    private String inferMethodNameFromParameters(List<QueryMethodParameter> parameters) {
        if (parameters.isEmpty()) {
            return "findAll";
        }
        
        // Build method name based on parameter column names
        StringBuilder methodName = new StringBuilder("findBy");
        for (int i = 0; i < parameters.size() && i < 3; i++) { // Limit to first 3 parameters
            QueryMethodParameter param = parameters.get(i);
            if (param.getColumnName() != null) {
                String columnName = param.getColumnName();
                // Convert snake_case to PascalCase
                String[] parts = columnName.split("_");
                for (String part : parts) {
                    if (!part.isEmpty()) {
                        methodName.append(Character.toUpperCase(part.charAt(0)));
                        if (part.length() > 1) {
                            methodName.append(part.substring(1));
                        }
                    }
                }
                if (i < parameters.size() - 1) {
                    methodName.append("And");
                }
            }
        }
        
        return methodName.toString();
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
            if (cardinalityAnalyzer.isBooleanColumn(tableName, currentCondition.columnName())) {
                details.append("Boolean columns filter roughly 50% of rows. ");
            } else {
                details.append("Low cardinality columns filter fewer rows, requiring more processing. ");
            }
        }
        
        // Explain why the recommended condition is better
        if (recommendedCondition.isHighCardinality()) {
            if (cardinalityAnalyzer.isPrimaryKey(tableName, recommendedCondition.columnName())) {
                details.append("Primary keys provide unique row identification for optimal filtering.");
            } else if (cardinalityAnalyzer.hasUniqueConstraint(tableName, recommendedCondition.columnName())) {
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
        
        if (cardinalityAnalyzer.isPrimaryKey(tableName, recommendedCondition.columnName())) {
            details.append("Primary keys provide maximum selectivity (1 row per value). ");
        } else if (cardinalityAnalyzer.hasUniqueConstraint(tableName, recommendedCondition.columnName())) {
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
    private QueryOptimizationResult createEmptyResult(RepositoryQuery query, String queryText) {
        return new QueryOptimizationResult(query, new ArrayList<>(), new ArrayList<>());
    }
    
    /**
     * Gets the CardinalityAnalyzer instance used by this engine.
     * Useful for testing and validation purposes.
     * 
     * @return the cardinality analyzer
     */
    public CardinalityAnalyzer getCardinalityAnalyzer() {
        return cardinalityAnalyzer;
    }
    
    /**
     * Validates that the engine is properly configured and ready for analysis.
     * 
     * @return true if the engine is ready, false otherwise
     */
    public boolean isReady() {
        return cardinalityAnalyzer != null;
    }
}
