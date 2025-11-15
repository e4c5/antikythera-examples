package sa.com.cloudsolutions.antikythera.examples;

import net.sf.jsqlparser.statement.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.exception.AntikytheraException;
import sa.com.cloudsolutions.antikythera.generator.QueryMethodParameter;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;
import sa.com.cloudsolutions.antikythera.parser.converter.ConversionResult;
import sa.com.cloudsolutions.antikythera.parser.converter.EntityMappingResolver;


import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Enhanced engine that analyzes repository queries using RepositoryQuery's parsing capabilities
 * to identify WHERE clause optimization opportunities based on column cardinality.
 * Updated to support AI-powered query optimization with enhanced analysis capabilities.
 */
public class QueryAnalysisEngine {
    private static final Logger logger = LoggerFactory.getLogger(QueryAnalysisEngine.class);

    /**
     * Analyzes a RepositoryQuery using its parsed Statement to identify optimization opportunities.
     * Enhanced to handle both HQL and native SQL queries through RepositoryQuery conversion.
     * 
     * @param repositoryQuery the repository query to analyze
     * @return the analysis results including WHERE conditions and optimization issues
     */
    public QueryOptimizationResult analyzeQuery(RepositoryQuery repositoryQuery) {
        Statement statement = repositoryQuery.getStatement();
        if (statement == null) {
            return handleDerivedQuery(repositoryQuery);
        }
        List<WhereCondition> whereConditions = QueryOptimizationExtractor.extractWhereConditions(repositoryQuery);
        ConversionResult conversionResult = repositoryQuery.getConversionResult();

        for (WhereCondition condition : whereConditions) {
            if (conversionResult != null) {
                String entity = conversionResult.getMetaData().getEntityForAlias(condition.tableName());
                String tableName = EntityMappingResolver.getTableNameForEntity(entity == null ? condition.tableName() : entity);

                CardinalityLevel cardinalityLevel = CardinalityAnalyzer.analyzeColumnCardinality(tableName, condition.columnName());
                condition.setTableName(tableName);
                condition.setCardinality(cardinalityLevel);
            }
            else {
                condition.setCardinality(CardinalityAnalyzer.analyzeColumnCardinality(
                        condition.tableName(), condition.columnName()
                ));
            }
        }
        return new QueryOptimizationResult(repositoryQuery, whereConditions);
    }

    /**
     * Handles analysis of derived query methods (findBy*, countBy*, etc.).
     */
    private QueryOptimizationResult handleDerivedQuery(RepositoryQuery repositoryQuery) {
        // For derived queries, we can still analyze the method parameters to infer WHERE conditions
        List<WhereCondition> whereConditions = new ArrayList<>();

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
                    WhereCondition condition = new WhereCondition(tableName, columnName, "=", i, param);
                    condition.setCardinality(cardinality);
                    whereConditions.add(condition);
                }
            }
        }
        
        return new QueryOptimizationResult(repositoryQuery, whereConditions);
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
}
