package sa.com.cloudsolutions.antikythera.examples;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.generator.QueryMethodParameter;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Proper expression condition extractor that properly extends JSQLParser's ExpressionVisitorAdapter.
 * This implementation follows the visitor pattern correctly by overriding visit methods for each
 * expression type, allowing JSQLParser to dispatch to the appropriate handler.
 *
 * This class improves upon OptimizationAnalysisVisitor by:
 * - Properly using the visitor pattern (not instanceof checks)
 * - Better separation of concerns
 * - More comprehensive operator support
 * - Cleaner method organization
 * - Enhanced documentation
 */
public class ExpressionConditionExtractor extends ExpressionVisitorAdapter<Void> {

    private static final Logger logger = LoggerFactory.getLogger(ExpressionConditionExtractor.class);

    private final List<WhereCondition> conditions;
    private final RepositoryQuery repositoryQuery;
    private int positionCounter;

    public ExpressionConditionExtractor(RepositoryQuery repositoryQuery) {
        this.repositoryQuery = repositoryQuery;
        this.conditions = new ArrayList<>();
        this.positionCounter = 0;
    }

    /**
     * Extracts WHERE conditions from the given expression.
     * This is the main entry point that triggers the visitor pattern.
     */
    public List<WhereCondition> extractConditions(Expression whereExpression) {
        conditions.clear();
        positionCounter = 0;

        if (whereExpression != null) {
            logger.debug("Extracting conditions from expression type: {}", whereExpression.getClass().getSimpleName());
            whereExpression.accept(this, null);
        }

        logger.debug("Extracted {} WHERE conditions", conditions.size());
        return new ArrayList<>(conditions);
    }

    // Visitor pattern implementations for logical operators

    @Override
    public <S> Void visit(AndExpression andExpr, S context) {
        // Process left side first (maintains order for optimization analysis)
        andExpr.getLeftExpression().accept(this, context);
        andExpr.getRightExpression().accept(this, context);
        return null;
    }

    @Override
    public <S> Void visit(OrExpression orExpr, S context) {
        // For OR expressions, both sides are equally important for optimization
        orExpr.getLeftExpression().accept(this, context);
        orExpr.getRightExpression().accept(this, context);
        return null;
    }

    // Visitor pattern implementations for comparison operators

    @Override
    public <S> Void visit(EqualsTo equalsTo, S context) {
        extractConditionFromComparison(equalsTo);
        return null;
    }

    @Override
    public <S> Void visit(NotEqualsTo notEqualsTo, S context) {
        extractConditionFromComparison(notEqualsTo);
        return null;
    }

    @Override
    public <S> Void visit(GreaterThan greaterThan, S context) {
        extractConditionFromComparison(greaterThan);
        return null;
    }

    @Override
    public <S> Void visit(GreaterThanEquals greaterThanEquals, S context) {
        extractConditionFromComparison(greaterThanEquals);
        return null;
    }

    @Override
    public <S> Void visit(MinorThan minorThan, S context) {
        extractConditionFromComparison(minorThan);
        return null;
    }

    @Override
    public <S> Void visit(MinorThanEquals minorThanEquals, S context) {
        extractConditionFromComparison(minorThanEquals);
        return null;
    }

    // Visitor pattern implementations for special operators

    @Override
    public <S> Void visit(Between between, S context) {
        extractConditionFromBetween(between);
        return null;
    }

    @Override
    public <S> Void visit(InExpression inExpr, S context) {
        extractConditionFromIn(inExpr);
        return null;
    }

    @Override
    public <S> Void visit(IsNullExpression isNull, S context) {
        extractConditionFromIsNull(isNull);
        return null;
    }

    @Override
    public <S> Void visit(LikeExpression likeExpr, S context) {
        extractConditionFromLike(likeExpr);
        return null;
    }

    // Extraction methods for different expression types

    /**
     * Extracts a WhereCondition from an IN expression.
     */
    private void extractConditionFromIn(InExpression inExpr) {
        Expression leftExpr = inExpr.getLeftExpression();
        if (leftExpr instanceof Column column) {
            String tableName = extractTableName(column);
            String columnName = extractColumnName(column);

            QueryMethodParameter parameter = findMatchingParameter(columnName, inExpr.getRightExpression());

            CardinalityLevel cardinality = CardinalityAnalyzer.analyzeColumnCardinality(tableName, columnName);

            WhereCondition condition = new WhereCondition(tableName, columnName, "IN", cardinality,
                                                        positionCounter++, parameter);
            conditions.add(condition);

            logger.debug("Extracted IN condition: {} from table {}", columnName, tableName);
        }
    }

    /**
     * Extracts a WhereCondition from a comparison operator expression.
     */
    private void extractConditionFromComparison(ComparisonOperator comparison) {
        Expression leftExpr = comparison.getLeftExpression();
        if (leftExpr instanceof Column column) {
            String tableName = extractTableName(column);
            String columnName = extractColumnName(column);
            String operator = comparison.getStringExpression();

            QueryMethodParameter parameter = findMatchingParameter(columnName, comparison.getRightExpression());

            CardinalityLevel cardinality = CardinalityAnalyzer.analyzeColumnCardinality(tableName, columnName);

            WhereCondition condition = new WhereCondition(tableName, columnName, operator, cardinality,
                                                        positionCounter++, parameter);
            conditions.add(condition);

            logger.debug("Extracted comparison condition: {} {} from table {}", columnName, operator, tableName);
        }
    }

    /**
     * Extracts a WhereCondition from a BETWEEN expression.
     */
    private void extractConditionFromBetween(Between between) {
        Expression leftExpr = between.getLeftExpression();
        if (leftExpr instanceof Column column) {
            String tableName = extractTableName(column);
            String columnName = extractColumnName(column);

            // For BETWEEN, try to find parameter from start or end expression
            QueryMethodParameter parameter = findMatchingParameter(columnName, between.getBetweenExpressionStart());
            if (parameter == null) {
                parameter = findMatchingParameter(columnName, between.getBetweenExpressionEnd());
            }

            CardinalityLevel cardinality = CardinalityAnalyzer.analyzeColumnCardinality(tableName, columnName);

            WhereCondition condition = new WhereCondition(tableName, columnName, "BETWEEN", cardinality,
                                                        positionCounter++, parameter);
            conditions.add(condition);

            logger.debug("Extracted BETWEEN condition: {} from table {}", columnName, tableName);
        }
    }

    /**
     * Extracts a WhereCondition from an IS NULL expression.
     */
    private void extractConditionFromIsNull(IsNullExpression isNull) {
        Expression leftExpr = isNull.getLeftExpression();
        if (leftExpr instanceof Column column) {
            String tableName = extractTableName(column);
            String columnName = extractColumnName(column);

            // IS NULL expressions typically don't have parameters
            QueryMethodParameter parameter = findMatchingParameter(columnName, null);

            CardinalityLevel cardinality = CardinalityAnalyzer.analyzeColumnCardinality(tableName, columnName);

            String operator = isNull.isNot() ? "IS NOT NULL" : "IS NULL";
            WhereCondition condition = new WhereCondition(tableName, columnName, operator, cardinality,
                                                        positionCounter++, parameter);
            conditions.add(condition);

            logger.debug("Extracted IS NULL condition: {} from table {}", columnName, tableName);
        }
    }

    /**
     * Extracts a WhereCondition from a LIKE expression.
     */
    private void extractConditionFromLike(LikeExpression likeExpr) {
        Expression leftExpr = likeExpr.getLeftExpression();
        if (leftExpr instanceof Column column) {
            String tableName = extractTableName(column);
            String columnName = extractColumnName(column);
            String operator = likeExpr.isNot() ? "NOT LIKE" : "LIKE";

            QueryMethodParameter parameter = findMatchingParameter(columnName, likeExpr.getRightExpression());

            CardinalityLevel cardinality = CardinalityAnalyzer.analyzeColumnCardinality(tableName, columnName);

            WhereCondition condition = new WhereCondition(tableName, columnName, operator, cardinality,
                                                        positionCounter++, parameter);
            conditions.add(condition);

            logger.debug("Extracted LIKE condition: {} from table {}", columnName, tableName);
        }
    }

    // Table and column name extraction

    /**
     * Extracts table name from column reference using JSQLParser's Table metadata.
     * For columns with table qualifiers (e.g., "t.column" or "table.column"),
     * returns the resolved table name. Otherwise returns the primary table.
     */
    private String extractTableName(Column column) {
        Table table = column.getTable();

        if (table != null && table.getName() != null) {
            // Column has explicit table reference (e.g., "a.admission_id")
            String tableRef = table.getName();

            // Resolve alias to actual table name
            String resolved = resolveTableFromAlias(tableRef);
            return resolved != null ? resolved : tableRef;
        }

        // No explicit table reference - use primary table
        return repositoryQuery.getPrimaryTable();
    }

    /**
     * Resolves a table alias to the actual table name by parsing FROM and JOIN clauses.
     * Uses regex pattern matching on the query text as a pragmatic solution.
     *
     * TODO: Consider using JSQLParser's FromItem and Join traversal for more robust mapping
     */
    private String resolveTableFromAlias(String aliasOrTable) {
        // If it matches the primary table or looks like a table name (snake_case), use it
        if (aliasOrTable.equals(repositoryQuery.getPrimaryTable()) || aliasOrTable.contains("_")) {
            return aliasOrTable;
        }

        // Try to resolve alias from query text
        String queryText = repositoryQuery.getQuery();
        if (queryText != null) {
            // Pattern to match: FROM table_name alias or JOIN table_name alias
            // Examples: "FROM Approval a", "LEFT JOIN BLAPP_open_coverage oc"
            String pattern = "(?i)(?:FROM|JOIN)\\s+([\\w_]+)\\s+" + java.util.regex.Pattern.quote(aliasOrTable) + "\\b";
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
            java.util.regex.Matcher m = p.matcher(queryText);

            if (m.find()) {
                String tableName = m.group(1);
                // Convert to snake_case if it's in CamelCase (entity name)
                if (!tableName.contains("_") && tableName.matches(".*[A-Z].*")) {
                    tableName = tableName.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
                }
                logger.debug("Resolved alias '{}' to table '{}'", aliasOrTable, tableName);
                return tableName;
            }
        }

        // If we can't resolve the alias, use primary table as fallback
        logger.debug("Could not resolve alias '{}', using primary table: {}", aliasOrTable, repositoryQuery.getPrimaryTable());
        return repositoryQuery.getPrimaryTable();
    }

    /**
     * Extracts column name from Column object, removing any table prefix.
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

    // Parameter matching logic

    /**
     * Finds matching QueryMethodParameter for a column using multiple strategies.
     * Leverages existing parser infrastructure from RepositoryQuery.
     */
    private QueryMethodParameter findMatchingParameter(String columnName, net.sf.jsqlparser.expression.Expression rightExpression) {
        List<QueryMethodParameter> methodParameters = repositoryQuery.getMethodParameters();
        if (methodParameters == null || methodParameters.isEmpty()) {
            return null;
        }

        // Strategy 1: Exact column name match
        Optional<QueryMethodParameter> exactMatch = methodParameters.stream()
            .filter(param -> columnName.equals(param.getColumnName()))
            .findFirst();

        if (exactMatch.isPresent()) {
            return exactMatch.get();
        }

        // Strategy 2: JDBC parameter index mapping
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
        }

        // Strategy 3: Named parameter mapping
        if (rightExpression instanceof JdbcNamedParameter namedParam) {
            String paramName = namedParam.getName();

            return methodParameters.stream()
                .filter(param -> paramName.equals(param.getPlaceHolderName()))
                .findFirst()
                .orElse(null);
        }

        // Strategy 4: Placeholder name match
        Optional<QueryMethodParameter> placeholderMatch = methodParameters.stream()
            .filter(param -> {
                String placeHolderName = param.getPlaceHolderName();
                return columnName.equals(placeHolderName);
            })
            .findFirst();

        if (placeholderMatch.isPresent()) {
            return placeholderMatch.get();
        }

        // Strategy 5: Parameter name match (convert camelCase to snake_case)
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
     * Reuses logic from existing parser infrastructure.
     */
    private String convertCamelToSnakeCase(String camelCase) {
        if (camelCase == null || camelCase.isEmpty()) {
            return "";
        }

        return camelCase.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }
}

