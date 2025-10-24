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
        return analyzeQueryWithCallable(repositoryQuery, null, null);
    }
    
    /**
     * Analyzes a RepositoryQuery with enhanced Callable information for better reporting.
     * Enhanced to include repository class name and method name from Callable objects.
     * 
     * @param repositoryQuery the repository query to analyze
     * @param callable the callable representing the repository method (may be null)
     * @param repositoryClassName the repository class name (may be null)
     * @return the analysis results including WHERE conditions and optimization issues
     */
    public QueryOptimizationResult analyzeQueryWithCallable(RepositoryQuery repositoryQuery, 
                                                           sa.com.cloudsolutions.antikythera.parser.Callable callable,
                                                           String repositoryClassName) {
        if (repositoryQuery == null) {
            logger.warn("Cannot analyze null repository query");
            return createEmptyResult(UNKNOWN, UNKNOWN, "");
        }
        
        // Enhanced repository class and method name extraction using Callable information
        String repositoryClass = getRepositoryClassNameEnhanced(repositoryQuery, callable, repositoryClassName);
        String methodName = getMethodNameEnhanced(repositoryQuery, callable);
        String queryText = getQueryText(repositoryQuery);
        
        try {
            // Check if this is a native query or HQL
            boolean isNativeQuery = repositoryQuery.isNative();
            logger.debug("Analyzing {} query for {}.{}", isNativeQuery ? "native SQL" : "HQL", repositoryClass, methodName);
            
            Statement statement = repositoryQuery.getStatement();
            if (statement == null) {
                logger.debug("No parsed statement available for {}.{}", repositoryClass, methodName);
                return handleDerivedQuery(repositoryQuery, repositoryClass, methodName, queryText);
            }
            
            // Extract table name for cardinality analysis
            String tableName = extractTableName(statement);
            if (tableName == null) {
                logger.debug("Could not extract table name from query: {}", queryText);
                return createEmptyResult(repositoryClass, methodName, queryText);
            }
            
            // Extract WHERE clause conditions using RepositoryQuery's parsing capabilities
            List<WhereCondition> whereConditions = extractWhereConditions(repositoryQuery, tableName);
            
            // Analyze condition ordering for optimization opportunities
            List<OptimizationIssue> optimizationIssues = analyzeConditionOrdering(
                whereConditions, tableName, repositoryClass, methodName, queryText);
            
            return new QueryOptimizationResult(repositoryClass, methodName, queryText, 
                                             whereConditions, optimizationIssues);
            
        } catch (Exception e) {
            logger.error("Error analyzing query for {}.{}: {}", repositoryClass, methodName, e.getMessage());
            return createEmptyResult(repositoryClass, methodName, queryText);
        }
    }

    /**
     * Handles analysis of derived query methods (findBy*, countBy*, etc.).
     */
    private QueryOptimizationResult handleDerivedQuery(RepositoryQuery repositoryQuery, String repositoryClass, 
                                                      String methodName, String queryText) {
        // For derived queries, we can still analyze the method parameters to infer WHERE conditions
        List<WhereCondition> whereConditions = new ArrayList<>();
        List<OptimizationIssue> optimizationIssues = new ArrayList<>();
        
        if (repositoryQuery.getMethodParameters() != null && !repositoryQuery.getMethodParameters().isEmpty()) {
            // Infer table name from repository class
            String tableName = inferTableNameFromRepositoryClass(repositoryClass);
            
            // Create conditions based on method parameters
            for (int i = 0; i < repositoryQuery.getMethodParameters().size(); i++) {
                QueryMethodParameter param = repositoryQuery.getMethodParameters().get(i);
                if (param.getColumnName() != null) {
                    CardinalityLevel cardinality = cardinalityAnalyzer.analyzeColumnCardinality(tableName, param.getColumnName());
                    WhereCondition condition = new WhereCondition(param.getColumnName(), "=", cardinality, i, param);
                    whereConditions.add(condition);
                }
            }
            
            // Analyze the inferred conditions
            optimizationIssues = analyzeConditionOrdering(whereConditions, tableName, repositoryClass, methodName, queryText);
        }
        
        return new QueryOptimizationResult(repositoryClass, methodName, queryText, whereConditions, optimizationIssues);
    }
    
    /**
     * Infers table name from repository class name.
     */
    private String inferTableNameFromRepositoryClass(String repositoryClass) {
        if (repositoryClass == null || repositoryClass.equals(UNKNOWN)) {
            return "unknown_table";
        }
        
        // Remove "Repository" suffix and convert to snake_case
        String entityName = repositoryClass.replace("Repository", "");
        return convertCamelToSnakeCase(entityName);
    }
    
    /**
     * Extracts WHERE clause conditions using RepositoryQuery's expression parsing capabilities.
     * 
     * @param repositoryQuery the repository query containing the parsed statement
     * @param tableName the name of the table being queried
     * @return list of WHERE conditions found in the query
     */
    private List<WhereCondition> extractWhereConditions(RepositoryQuery repositoryQuery, String tableName) {
        List<WhereCondition> conditions = new ArrayList<>();
        
        try {
            Statement statement = repositoryQuery.getStatement();
            if (!(statement instanceof Select)) {
                return conditions;
            }
            
            Select select = (Select) statement;
            if (!(select.getSelectBody() instanceof PlainSelect)) {
                return conditions;
            }
            
            PlainSelect plainSelect = (PlainSelect) select.getSelectBody();
            Expression whereClause = plainSelect.getWhere();
            
            if (whereClause != null) {
                extractConditionsFromExpression(whereClause, conditions, tableName, repositoryQuery, 0);
            }
            
        } catch (Exception e) {
            logger.error("Error extracting WHERE conditions: {}", e.getMessage());
        }
        
        return conditions;
    }
    
    /**
     * Recursively extracts conditions from a WHERE clause expression.
     * Enhanced to handle complex expressions and improve parameter mapping.
     * 
     * @param expression the expression to analyze
     * @param conditions the list to add found conditions to
     * @param tableName the table name for cardinality analysis
     * @param repositoryQuery the repository query for parameter mapping
     * @param position the current position counter for conditions
     */
    private void extractConditionsFromExpression(Expression expression, List<WhereCondition> conditions, 
                                               String tableName, RepositoryQuery repositoryQuery, int position) {
        if (expression instanceof AndExpression andExpr) {
            // Process left side first (maintains order for optimization analysis)
            extractConditionsFromExpression(andExpr.getLeftExpression(), conditions, tableName, repositoryQuery, position);
            extractConditionsFromExpression(andExpr.getRightExpression(), conditions, tableName, repositoryQuery, 
                                          position + getConditionCount(andExpr.getLeftExpression()));
        } else if (expression instanceof OrExpression orExpr) {
            // For OR expressions, both sides are equally important for optimization
            extractConditionsFromExpression(orExpr.getLeftExpression(), conditions, tableName, repositoryQuery, position);
            extractConditionsFromExpression(orExpr.getRightExpression(), conditions, tableName, repositoryQuery, 
                                          position + getConditionCount(orExpr.getLeftExpression()));
        } else if (expression instanceof ComparisonOperator comparison) {
            WhereCondition condition = createConditionFromComparison(comparison, tableName, repositoryQuery, position);
            if (condition != null) {
                conditions.add(condition);
            }
        } else if (expression instanceof Between between) {
            WhereCondition condition = createConditionFromBetween(between, tableName, repositoryQuery, position);
            if (condition != null) {
                conditions.add(condition);
            }
        } else if (expression instanceof InExpression inExpr) {
            WhereCondition condition = createConditionFromIn(inExpr, tableName, repositoryQuery, position);
            if (condition != null) {
                conditions.add(condition);
            }
        } else if (expression instanceof IsNullExpression isNull) {
            WhereCondition condition = createConditionFromIsNull(isNull, tableName, repositoryQuery, position);
            if (condition != null) {
                conditions.add(condition);
            }
        } else {
            // Handle other expression types that might contain column references
            logger.debug("Unhandled expression type in WHERE clause: {}", expression.getClass().getSimpleName());
        }
    }
    
    /**
     * Creates a WhereCondition from a comparison operator expression.
     * Enhanced to better handle parameter mapping and HQL/SQL differences.
     */
    private WhereCondition createConditionFromComparison(ComparisonOperator comparison, String tableName, 
                                                        RepositoryQuery repositoryQuery, int position) {
        Expression leftExpr = comparison.getLeftExpression();
        if (leftExpr instanceof Column column) {
            String columnName = extractColumnName(column);
            String operator = comparison.getStringExpression();

            // Enhanced parameter mapping using RepositoryQuery's parameter mapping capabilities
            QueryMethodParameter parameter = findMatchingParameterEnhanced(repositoryQuery, columnName, comparison.getRightExpression());

            CardinalityLevel cardinality = cardinalityAnalyzer.analyzeColumnCardinality(tableName, columnName);

            return new WhereCondition(columnName, operator, cardinality, position, parameter);
        }
        return null;
    }
    
    /**
     * Enhanced parameter matching that uses RepositoryQuery's parameter mapping logic.
     */
    private QueryMethodParameter findMatchingParameterEnhanced(RepositoryQuery repositoryQuery, String columnName, Expression rightExpression) {
        // First try the existing matching logic
        QueryMethodParameter parameter = findMatchingParameter(repositoryQuery, columnName);
        if (parameter != null) {
            return parameter;
        }
        
        // Try to use RepositoryQuery's parameter mapping logic
        try {
            // Check if the right expression is a parameter placeholder
            if (rightExpression instanceof net.sf.jsqlparser.expression.JdbcParameter) {
                net.sf.jsqlparser.expression.JdbcParameter jdbcParam = 
                    (net.sf.jsqlparser.expression.JdbcParameter) rightExpression;
                int paramIndex = jdbcParam.getIndex() - 1; // JDBC parameters are 1-based
                
                if (paramIndex >= 0 && paramIndex < repositoryQuery.getMethodParameters().size()) {
                    QueryMethodParameter methodParam = repositoryQuery.getMethodParameters().get(paramIndex);
                    // Update the parameter's column mapping if not already set
                    if (methodParam.getColumnName() == null) {
                        methodParam.setColumnName(columnName);
                    }
                    return methodParam;
                }
            } else if (rightExpression instanceof net.sf.jsqlparser.expression.JdbcNamedParameter) {
                net.sf.jsqlparser.expression.JdbcNamedParameter namedParam = 
                    (net.sf.jsqlparser.expression.JdbcNamedParameter) rightExpression;
                String paramName = namedParam.getName();
                
                return repositoryQuery.getMethodParameters().stream()
                    .filter(param -> paramName.equals(param.getPlaceHolderName()))
                    .findFirst()
                    .orElse(null);
            }
        } catch (Exception e) {
            logger.debug("Error in enhanced parameter matching: {}", e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Creates a WhereCondition from a BETWEEN expression.
     * Enhanced to handle parameter mapping for BETWEEN expressions.
     */
    private WhereCondition createConditionFromBetween(Between between, String tableName, 
                                                     RepositoryQuery repositoryQuery, int position) {
        Expression leftExpr = between.getLeftExpression();
        if (!(leftExpr instanceof Column)) {
            return null;
        }
        
        Column column = (Column) leftExpr;
        String columnName = extractColumnName(column);
        
        // For BETWEEN, try to find parameter from start expression
        QueryMethodParameter parameter = findMatchingParameterEnhanced(repositoryQuery, columnName, between.getBetweenExpressionStart());
        if (parameter == null) {
            // Try end expression if start didn't match
            parameter = findMatchingParameterEnhanced(repositoryQuery, columnName, between.getBetweenExpressionEnd());
        }
        
        CardinalityLevel cardinality = cardinalityAnalyzer.analyzeColumnCardinality(tableName, columnName);
        
        return new WhereCondition(columnName, "BETWEEN", cardinality, position, parameter);
    }
    
    /**
     * Creates a WhereCondition from an IN expression.
     * Enhanced to handle parameter mapping for IN expressions.
     */
    private WhereCondition createConditionFromIn(InExpression inExpr, String tableName, 
                                                RepositoryQuery repositoryQuery, int position) {
        Expression leftExpr = inExpr.getLeftExpression();
        if (!(leftExpr instanceof Column)) {
            return null;
        }
        
        Column column = (Column) leftExpr;
        String columnName = extractColumnName(column);
        
        // For IN expressions, try to find parameter from the right expression
        QueryMethodParameter parameter = findMatchingParameterEnhanced(repositoryQuery, columnName, inExpr.getRightExpression());
        
        CardinalityLevel cardinality = cardinalityAnalyzer.analyzeColumnCardinality(tableName, columnName);
        
        return new WhereCondition(columnName, "IN", cardinality, position, parameter);
    }
    
    /**
     * Creates a WhereCondition from an IS NULL expression.
     * Enhanced parameter mapping (though IS NULL typically doesn't have parameters).
     */
    private WhereCondition createConditionFromIsNull(IsNullExpression isNull, String tableName, 
                                                    RepositoryQuery repositoryQuery, int position) {
        Expression leftExpr = isNull.getLeftExpression();
        if (!(leftExpr instanceof Column)) {
            return null;
        }
        
        Column column = (Column) leftExpr;
        String columnName = extractColumnName(column);
        
        // IS NULL expressions typically don't have parameters, but check anyway
        QueryMethodParameter parameter = findMatchingParameter(repositoryQuery, columnName);
        
        CardinalityLevel cardinality = cardinalityAnalyzer.analyzeColumnCardinality(tableName, columnName);
        
        String operator = isNull.isNot() ? "IS NOT NULL" : "IS NULL";
        return new WhereCondition(columnName, operator, cardinality, position, parameter);
    }
    
    /**
     * Analyzes condition ordering to identify optimization opportunities.
     * 
     * @param conditions the list of WHERE conditions to analyze
     * @param tableName the table name for context
     * @param repositoryClass the repository class name for reporting
     * @param methodName the method name for reporting
     * @param queryText the full query text for reporting
     * @return list of optimization issues found
     */
    private List<OptimizationIssue> analyzeConditionOrdering(List<WhereCondition> conditions, String tableName,
                                                            String repositoryClass, String methodName, String queryText) {
        List<OptimizationIssue> issues = new ArrayList<>();
        
        if (conditions.isEmpty()) {
            return issues;
        }
        
        // Find the first condition (position 0)
        Optional<WhereCondition> firstConditionOpt = conditions.stream()
            .filter(condition -> condition.getPosition() == 0)
            .findFirst();
            
        if (!firstConditionOpt.isPresent()) {
            return issues;
        }
        
        WhereCondition firstCondition = firstConditionOpt.get();
        
        // Check if first condition is low cardinality while high cardinality alternatives exist
        if (firstCondition.isLowCardinality()) {
            Optional<WhereCondition> highCardinalityAlternative = conditions.stream()
                .filter(WhereCondition::isHighCardinality)
                .findFirst();
                
            if (highCardinalityAlternative.isPresent()) {
                WhereCondition recommended = highCardinalityAlternative.get();
                
                // Enhanced description with cardinality information
                String cardinalityDetails = getCardinalityDetails(tableName, firstCondition, recommended);
                String description = String.format(
                    "Query starts with %s cardinality column '%s' but %s cardinality column '%s' is available. %s",
                    firstCondition.getCardinality().toString().toLowerCase(),
                    firstCondition.getColumnName(),
                    recommended.getCardinality().toString().toLowerCase(),
                    recommended.getColumnName(),
                    cardinalityDetails);
                    
                OptimizationIssue issue = new OptimizationIssue(
                    repositoryClass, methodName, firstCondition.getColumnName(),
                    recommended.getColumnName(), description, OptimizationIssue.Severity.HIGH, queryText);
                issues.add(issue);
            }
        }
        
        // Check for suboptimal ordering of high cardinality columns
        List<WhereCondition> highCardinalityConditions = conditions.stream()
            .filter(WhereCondition::isHighCardinality)
            .toList();
            
        if (highCardinalityConditions.size() > 1) {
            // Find the most selective high cardinality column (primary keys are most selective)
            Optional<WhereCondition> primaryKeyCondition = highCardinalityConditions.stream()
                .filter(condition -> cardinalityAnalyzer.isPrimaryKey(tableName, condition.getColumnName()))
                .findFirst();
                
            if (primaryKeyCondition.isPresent() && !firstCondition.equals(primaryKeyCondition.get())) {
                WhereCondition recommended = primaryKeyCondition.get();
                
                // Enhanced description with cardinality and selectivity information
                String selectivityDetails = getSelectivityDetails(tableName, firstCondition, recommended);
                String description = String.format(
                    "Primary key column '%s' should appear first in WHERE clause for optimal performance. " +
                    "Currently starts with '%s' (%s cardinality). %s",
                    recommended.getColumnName(),
                    firstCondition.getColumnName(),
                    firstCondition.getCardinality().toString().toLowerCase(),
                    selectivityDetails);
                    
                OptimizationIssue issue = new OptimizationIssue(
                    repositoryClass, methodName, firstCondition.getColumnName(),
                    recommended.getColumnName(), description, OptimizationIssue.Severity.MEDIUM, queryText);
                issues.add(issue);
            }
        }
        
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
    private QueryMethodParameter findMatchingParameter(RepositoryQuery repositoryQuery, String columnName) {
        if (repositoryQuery.getMethodParameters() == null) {
            return null;
        }
        
        // First try exact column name match
        QueryMethodParameter exactMatch = repositoryQuery.getMethodParameters().stream()
            .filter(param -> columnName.equals(param.getColumnName()))
            .findFirst()
            .orElse(null);
            
        if (exactMatch != null) {
            return exactMatch;
        }
        
        // Try placeholder name match (for named parameters)
        QueryMethodParameter placeholderMatch = repositoryQuery.getMethodParameters().stream()
            .filter(param -> param.getPlaceHolderName() != null && 
                           columnName.equals(param.getPlaceHolderName()))
            .findFirst()
            .orElse(null);
            
        if (placeholderMatch != null) {
            return placeholderMatch;
        }
        
        // Try parameter name match (convert camelCase to snake_case)
        return repositoryQuery.getMethodParameters().stream()
            .filter(param -> {
                if (param.getParameter() != null) {
                    String paramName = param.getParameter().getNameAsString();
                    String snakeCaseParamName = convertCamelToSnakeCase(paramName);
                    return columnName.equals(snakeCaseParamName);
                }
                return false;
            })
            .findFirst()
            .orElse(null);
    }
    
    /**
     * Converts camelCase to snake_case for parameter name matching.
     */
    private String convertCamelToSnakeCase(String camelCase) {
        if (camelCase == null || camelCase.isEmpty()) {
            return camelCase;
        }
        
        return camelCase.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }
    
    /**
     * Extracts the table name from a SQL statement.
     */
    private String extractTableName(Statement statement) {
        try {
            if (statement instanceof Select select) {
                if (select.getSelectBody() instanceof PlainSelect plainSelect) {
                    if (plainSelect.getFromItem() != null) {
                        String fromItem = plainSelect.getFromItem().toString();
                        // Handle table aliases (e.g., "table_name t" -> "table_name")
                        String[] parts = fromItem.split("\\s+");
                        return parts[0];
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Error extracting table name: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Gets the method name from the RepositoryQuery.
     * Enhanced to use RepositoryQuery's methodDeclaration when available.
     */
    private String getMethodName(RepositoryQuery repositoryQuery) {
        return getMethodNameEnhanced(repositoryQuery, null);
    }
    
    /**
     * Enhanced method to get repository class name using Callable information.
     * Prioritizes Callable information over reflection-based extraction.
     * 
     * @param repositoryQuery the repository query
     * @param callable the callable object (may be null)
     * @param repositoryClassName the repository class name (may be null)
     * @return the repository class name
     */
    private String getRepositoryClassNameEnhanced(RepositoryQuery repositoryQuery, 
                                                 sa.com.cloudsolutions.antikythera.parser.Callable callable,
                                                 String repositoryClassName) {
        // First priority: use provided repository class name
        if (repositoryClassName != null && !repositoryClassName.isEmpty()) {
            // Extract simple class name from fully qualified name
            String[] parts = repositoryClassName.split("\\.");
            return parts[parts.length - 1];
        }
        
        // Second priority: use Callable information
        if (callable != null && callable.isMethodDeclaration()) {
            try {
                return callable.asMethodDeclaration().findAncestor(
                    com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                    .map(cls -> cls.getNameAsString())
                    .orElse(UNKNOWN);
            } catch (Exception e) {
                logger.debug("Error getting class name from Callable: {}", e.getMessage());
            }
        }

        sa.com.cloudsolutions.antikythera.parser.Callable reflectedCallable = repositoryQuery.getMethodDeclaration();

        if (reflectedCallable != null && reflectedCallable.isMethodDeclaration()) {
            return reflectedCallable.asMethodDeclaration().findAncestor(
                com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                .map(cls -> cls.getNameAsString())
                .orElse(UNKNOWN);
        }

        
        // Fallback: convert table name to repository class
        try {
            String tableName = extractTableName(repositoryQuery.getStatement());
            if (tableName != null) {
                return convertTableNameToRepositoryClass(tableName);
            }
        } catch (Exception e) {
            logger.debug("Error converting table name to repository class: {}", e.getMessage());
        }
        
        return UNKNOWN;
    }
    
    /**
     * Enhanced method to get method name using Callable information.
     * Prioritizes Callable information over reflection-based extraction.
     * 
     * @param repositoryQuery the repository query
     * @param callable the callable object (may be null)
     * @return the method name
     */
    private String getMethodNameEnhanced(RepositoryQuery repositoryQuery, 
                                        sa.com.cloudsolutions.antikythera.parser.Callable callable) {
        // First priority: use provided Callable information
        if (callable != null && callable.isMethodDeclaration()) {
            try {
                return callable.asMethodDeclaration().getNameAsString();
            } catch (Exception e) {
                logger.debug("Error getting method name from Callable: {}", e.getMessage());
            }
        }

        sa.com.cloudsolutions.antikythera.parser.Callable reflectedCallable = repositoryQuery.getMethodDeclaration();
        if (reflectedCallable != null && reflectedCallable.isMethodDeclaration()) {
            return reflectedCallable.asMethodDeclaration().getNameAsString();
        }

        // Fallback: infer from parameters
        List<QueryMethodParameter> parameters = repositoryQuery.getMethodParameters();
        if (parameters != null && !parameters.isEmpty()) {
            return inferMethodNameFromParameters(parameters);
        }
        
        return UNKNOWN;
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
            if (repositoryQuery.getOriginalQuery() != null && !repositoryQuery.getOriginalQuery().isEmpty()) {
                return repositoryQuery.getOriginalQuery();
            }
            
            // Fall back to parsed statement if original is not available
            if (repositoryQuery.getStatement() != null) {
                return repositoryQuery.getStatement().toString();
            }
            
            // Check if this is a derived query method (no explicit query)
            String methodName = getMethodName(repositoryQuery);
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
            if (cardinalityAnalyzer.isBooleanColumn(tableName, currentCondition.getColumnName())) {
                details.append("Boolean columns filter roughly 50% of rows. ");
            } else {
                details.append("Low cardinality columns filter fewer rows, requiring more processing. ");
            }
        }
        
        // Explain why the recommended condition is better
        if (recommendedCondition.isHighCardinality()) {
            if (cardinalityAnalyzer.isPrimaryKey(tableName, recommendedCondition.getColumnName())) {
                details.append("Primary keys provide unique row identification for optimal filtering.");
            } else if (cardinalityAnalyzer.hasUniqueConstraint(tableName, recommendedCondition.getColumnName())) {
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
        
        if (cardinalityAnalyzer.isPrimaryKey(tableName, recommendedCondition.getColumnName())) {
            details.append("Primary keys provide maximum selectivity (1 row per value). ");
        } else if (cardinalityAnalyzer.hasUniqueConstraint(tableName, recommendedCondition.getColumnName())) {
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
    private QueryOptimizationResult createEmptyResult(String repositoryClass, String methodName, String queryText) {
        return new QueryOptimizationResult(repositoryClass, methodName, queryText, 
                                         new ArrayList<>(), new ArrayList<>());
    }
}
