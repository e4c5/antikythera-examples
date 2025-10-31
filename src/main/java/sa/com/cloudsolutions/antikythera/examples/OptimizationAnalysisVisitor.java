package sa.com.cloudsolutions.antikythera.examples;

import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.ComparisonOperator;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.schema.Column;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.generator.QueryMethodParameter;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;
import sa.com.cloudsolutions.antikythera.parser.converter.ColumnMapping;
import sa.com.cloudsolutions.antikythera.parser.converter.EntityMetadata;
import sa.com.cloudsolutions.antikythera.parser.converter.SqlConversionContext;
import sa.com.cloudsolutions.antikythera.parser.converter.TableMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Specialized visitor that extends the existing parser infrastructure to extract
 * WHERE conditions and optimization information from SQL expressions.
 * 
 * This replaces the custom parsing logic in QueryAnalysisEngine.extractConditionsFromExpression()
 * by leveraging the well-tested SqlGenerationVisitor infrastructure.
 */
public class OptimizationAnalysisVisitor {
    
    private static final Logger logger = LoggerFactory.getLogger(OptimizationAnalysisVisitor.class);

    private final List<WhereCondition> conditions;
    private final RepositoryQuery repositoryQuery;
    private final SqlConversionContext context;
    private int positionCounter;
    
    public OptimizationAnalysisVisitor(RepositoryQuery repositoryQuery, SqlConversionContext context) {
        this.repositoryQuery = repositoryQuery;
        this.context = context;
        this.conditions = new ArrayList<>();
        this.positionCounter = 0;
    }
    
    /**
     * Extracts WHERE conditions from the given expression using the parser infrastructure.
     * This is the main entry point that replaces QueryAnalysisEngine.extractConditionsFromExpression().
     */
    public List<WhereCondition> extractConditions(Expression whereExpression) {
        conditions.clear();
        positionCounter = 0;
        
        if (whereExpression != null) {
            visitExpression(whereExpression);
        }
        
        logger.debug("Extracted {} WHERE conditions using parser infrastructure", conditions.size());
        return new ArrayList<>(conditions);
    }
    
    /**
     * Visits an expression and extracts optimization-relevant information.
     * Uses the same pattern as SqlGenerationVisitor for consistency.
     */
    private void visitExpression(Expression expression) {
        if (expression instanceof AndExpression andExpr) {
            // Process left side first (maintains order for optimization analysis)
            visitExpression(andExpr.getLeftExpression());
            visitExpression(andExpr.getRightExpression());
        } else if (expression instanceof OrExpression orExpr) {
            // For OR expressions, both sides are equally important for optimization
            visitExpression(orExpr.getLeftExpression());
            visitExpression(orExpr.getRightExpression());
        } else if (expression instanceof ComparisonOperator comparison) {
            extractConditionFromComparison(comparison);
        } else if (expression instanceof Between between) {
            extractConditionFromBetween(between);
        } else if (expression instanceof InExpression inExpr) {
            extractConditionFromIn(inExpr);
        } else if (expression instanceof IsNullExpression isNull) {
            extractConditionFromIsNull(isNull);
        } else if (expression instanceof BinaryExpression binaryExpr) {
            // Handle other binary expressions that might contain conditions
            visitExpression(binaryExpr.getLeftExpression());
            visitExpression(binaryExpr.getRightExpression());
        } else {
            logger.debug("Unhandled expression type in WHERE clause: {}", expression.getClass().getSimpleName());
        }
    }
    
    /**
     * Extracts a WhereCondition from a comparison operator expression.
     * Uses the existing column mapping infrastructure from the parser package.
     */
    private void extractConditionFromComparison(ComparisonOperator comparison) {
        Expression leftExpr = comparison.getLeftExpression();
        if (leftExpr instanceof Column column) {
            String columnName = extractColumnName(column);
            String operator = comparison.getStringExpression();
            
            // Use existing parameter mapping logic
            QueryMethodParameter parameter = findMatchingParameter(columnName, comparison.getRightExpression());
            
            // Use existing cardinality analysis
            CardinalityLevel cardinality = CardinalityAnalyzer.analyzeColumnCardinality(
                repositoryQuery.getPrimaryTable(), columnName);
            
            WhereCondition condition = new WhereCondition(columnName, operator, cardinality, 
                                                        positionCounter++, parameter);
            conditions.add(condition);
            
            logger.debug("Extracted comparison condition: {}", condition);
        }
    }
    
    /**
     * Extracts a WhereCondition from a BETWEEN expression.
     */
    private void extractConditionFromBetween(Between between) {
        Expression leftExpr = between.getLeftExpression();
        if (leftExpr instanceof Column column) {
            String columnName = extractColumnName(column);
            
            // For BETWEEN, try to find parameter from start or end expression
            QueryMethodParameter parameter = findMatchingParameter(columnName, between.getBetweenExpressionStart());
            if (parameter == null) {
                parameter = findMatchingParameter(columnName, between.getBetweenExpressionEnd());
            }
            
            CardinalityLevel cardinality = CardinalityAnalyzer.analyzeColumnCardinality(
                repositoryQuery.getPrimaryTable(), columnName);
            
            WhereCondition condition = new WhereCondition(columnName, "BETWEEN", cardinality, 
                                                        positionCounter++, parameter);
            conditions.add(condition);
            
            logger.debug("Extracted BETWEEN condition: {}", condition);
        }
    }
    
    /**
     * Extracts a WhereCondition from an IN expression.
     */
    private void extractConditionFromIn(InExpression inExpr) {
        Expression leftExpr = inExpr.getLeftExpression();
        if (leftExpr instanceof Column column) {
            String columnName = extractColumnName(column);
            
            QueryMethodParameter parameter = findMatchingParameter(columnName, inExpr.getRightExpression());
            
            CardinalityLevel cardinality = CardinalityAnalyzer.analyzeColumnCardinality(
                repositoryQuery.getPrimaryTable(), columnName);
            
            WhereCondition condition = new WhereCondition(columnName, "IN", cardinality, 
                                                        positionCounter++, parameter);
            conditions.add(condition);
            
            logger.debug("Extracted IN condition: {}", condition);
        }
    }
    
    /**
     * Extracts a WhereCondition from an IS NULL expression.
     */
    private void extractConditionFromIsNull(IsNullExpression isNull) {
        Expression leftExpr = isNull.getLeftExpression();
        if (leftExpr instanceof Column column) {
            String columnName = extractColumnName(column);
            
            // IS NULL expressions typically don't have parameters
            QueryMethodParameter parameter = findMatchingParameter(columnName, null);
            
            CardinalityLevel cardinality = CardinalityAnalyzer.analyzeColumnCardinality(
                repositoryQuery.getPrimaryTable(), columnName);
            
            String operator = isNull.isNot() ? "IS NOT NULL" : "IS NULL";
            WhereCondition condition = new WhereCondition(columnName, operator, cardinality, 
                                                        positionCounter++, parameter);
            conditions.add(condition);
            
            logger.debug("Extracted IS NULL condition: {}", condition);
        }
    }
    
    /**
     * Extracts column name using the existing parser infrastructure.
     * Leverages the column mapping capabilities from SqlGenerationVisitor.
     */
    private String extractColumnName(Column column) {
        String columnName = column.getColumnName();
        
        // Remove table alias prefix if present (e.g., "t.column_name" -> "column_name")
        if (columnName.contains(".")) {
            String[] parts = columnName.split("\\.");
            columnName = parts[parts.length - 1];
        }
        
        // Use existing column mapping if available
        if (context != null && context.entityMetadata() != null) {
            String mappedColumn = findColumnMapping(columnName);
            if (mappedColumn != null) {
                columnName = mappedColumn;
            }
        }
        
        return columnName;
    }
    
    /**
     * Finds column mapping using the existing EntityMetadata infrastructure.
     * This leverages the same logic used in SqlGenerationVisitor.
     */
    private String findColumnMapping(String propertyName) {
        EntityMetadata metadata = context.entityMetadata();
        
        // Try to find the property in any of the table mappings
        for (TableMapping tableMapping : metadata.getAllTableMappings()) {
            ColumnMapping columnMapping = tableMapping.getColumnMapping(propertyName);
            if (columnMapping != null) {
                return columnMapping.getColumnName();
            }
        }
        
        return null; // No mapping found, use original name
    }
    
    /**
     * Finds matching parameter using enhanced logic that leverages the parser infrastructure.
     * This replaces the custom parameter matching in QueryAnalysisEngine.
     */
    private QueryMethodParameter findMatchingParameter(String columnName, Expression rightExpression) {
        List<QueryMethodParameter> methodParameters = repositoryQuery.getMethodParameters();
        if (methodParameters == null || methodParameters.isEmpty()) {
            return null;
        }
        
        // First try exact column name match
        Optional<QueryMethodParameter> exactMatch = methodParameters.stream()
            .filter(param -> columnName.equals(param.getColumnName()))
            .findFirst();
        
        if (exactMatch.isPresent()) {
            return exactMatch.get();
        }
        
        // Try parameter mapping using JSQLParser parameter types
        if (rightExpression instanceof JdbcParameter jdbcParam) {
            int paramIndex = jdbcParam.getIndex() - 1; // JDBC parameters are 1-based
            
            if (paramIndex >= 0 && paramIndex < methodParameters.size()) {
                QueryMethodParameter methodParam = methodParameters.get(paramIndex);
                // Update the parameter's column mapping if not already set
                if (methodParam.getColumnName() == null || methodParam.getColumnName().isEmpty()) {
                    methodParam.setColumnName(columnName);
                }
                return methodParam;
            }
        } else if (rightExpression instanceof JdbcNamedParameter namedParam) {
            String paramName = namedParam.getName();
            
            Optional<QueryMethodParameter> namedMatch = methodParameters.stream()
                .filter(param -> paramName.equals(param.getPlaceHolderName()))
                .findFirst();
            
            if (namedMatch.isPresent()) {
                return namedMatch.get();
            }
        }
        
        // Try placeholder name match (for named parameters)
        Optional<QueryMethodParameter> placeholderMatch = methodParameters.stream()
            .filter(param -> {
                String placeHolderName = param.getPlaceHolderName();
                return placeHolderName != null && columnName.equals(placeHolderName);
            })
            .findFirst();
        
        if (placeholderMatch.isPresent()) {
            return placeholderMatch.get();
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
            .findFirst()
            .orElse(null);
    }
    
    /**
     * Converts camelCase to snake_case for parameter name matching.
     * Uses the same logic as the existing parser infrastructure.
     */
    private String convertCamelToSnakeCase(String camelCase) {
        if (camelCase == null || camelCase.isEmpty()) {
            return "";
        }
        
        return camelCase.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }
}
