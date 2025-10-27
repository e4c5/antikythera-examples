package sa.com.cloudsolutions.antikythera.examples;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.generator.QueryMethodParameter;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;
import sa.com.cloudsolutions.antikythera.parser.converter.DatabaseDialect;
import sa.com.cloudsolutions.antikythera.parser.converter.EntityMetadata;
import sa.com.cloudsolutions.antikythera.parser.converter.EntityMappingResolver;
import sa.com.cloudsolutions.antikythera.parser.converter.SqlConversionContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Factory class that integrates optimization analysis with the existing parser infrastructure.
 * This replaces the custom parsing logic in QueryAnalysisEngine by leveraging the
 * well-tested parser package capabilities.
 */
public class QueryOptimizationExtractor {
    
    private static final Logger logger = LoggerFactory.getLogger(QueryOptimizationExtractor.class);
    
    private final CardinalityAnalyzer cardinalityAnalyzer;
    private final EntityMappingResolver entityMappingResolver;
    
    public QueryOptimizationExtractor(CardinalityAnalyzer cardinalityAnalyzer) {
        this.cardinalityAnalyzer = cardinalityAnalyzer;
        this.entityMappingResolver = new EntityMappingResolver();
    }
    
    /**
     * Extracts WHERE conditions from a RepositoryQuery using the existing parser infrastructure.
     * This is the main method that replaces QueryAnalysisEngine.extractWhereConditions().
     */
    public List<WhereCondition> extractWhereConditions(RepositoryQuery repositoryQuery) {
        Statement statement = repositoryQuery.getStatement();
        
        if (statement instanceof Select select && select.getSelectBody() instanceof PlainSelect plainSelect) {
            Expression whereClause = plainSelect.getWhere();
            
            if (whereClause != null) {
                return extractConditionsFromExpression(whereClause, repositoryQuery);
            }
        }
        
        // For queries without parsed statements (e.g., derived queries), 
        // extract conditions from method parameters
        logger.debug("No WHERE clause found in parsed statement, extracting from method parameters");
        return extractConditionsFromMethodParameters(repositoryQuery);
    }
    
    /**
     * Extracts conditions from a WHERE expression using the parser infrastructure.
     * This replaces QueryAnalysisEngine.extractConditionsFromExpression().
     */
    public List<WhereCondition> extractConditionsFromExpression(Expression whereExpression, 
                                                               RepositoryQuery repositoryQuery) {
        // Build entity metadata for the query
        EntityMetadata entityMetadata = buildEntityMetadata(repositoryQuery);
        
        // Create conversion context
        SqlConversionContext context = new SqlConversionContext(entityMetadata, DatabaseDialect.POSTGRESQL);
        
        // Use the optimization analysis visitor
        OptimizationAnalysisVisitor visitor = new OptimizationAnalysisVisitor(
            cardinalityAnalyzer, repositoryQuery, context);
        
        List<WhereCondition> conditions = visitor.extractConditions(whereExpression);
        
        logger.debug("Extracted {} conditions from WHERE expression using parser infrastructure", 
                    conditions.size());
        
        return conditions;
    }
    
    /**
     * Extracts WHERE conditions from method parameters for derived queries.
     * This handles cases where queries are inferred from method names (findBy*, etc.).
     */
    public List<WhereCondition> extractConditionsFromMethodParameters(RepositoryQuery repositoryQuery) {
        List<WhereCondition> conditions = new ArrayList<>();
        List<QueryMethodParameter> methodParameters = repositoryQuery.getMethodParameters();
        
        if (methodParameters == null || methodParameters.isEmpty()) {
            return conditions;
        }
        
        String tableName = repositoryQuery.getTable();
        if (tableName == null || tableName.isEmpty()) {
            logger.debug("Cannot extract conditions from method parameters without table name");
            return conditions;
        }
        
        for (int i = 0; i < methodParameters.size(); i++) {
            QueryMethodParameter param = methodParameters.get(i);
            String columnName = param.getColumnName();
            
            if (columnName != null && !columnName.isEmpty()) {
                // Use existing cardinality analysis infrastructure
                CardinalityLevel cardinality = 
                    cardinalityAnalyzer.analyzeColumnCardinality(tableName, columnName);
                
                WhereCondition condition = new WhereCondition(columnName, "=", cardinality, i, param);
                conditions.add(condition);
                
                logger.debug("Extracted condition from method parameter: {}", condition);
            }
        }
        
        return conditions;
    }
    
    /**
     * Builds entity metadata for the given repository query.
     * This leverages the existing EntityMappingResolver from the parser package.
     */
    private EntityMetadata buildEntityMetadata(RepositoryQuery repositoryQuery) {
        try {
            // Try to resolve entity metadata from the repository query's entity type
            if (repositoryQuery.getEntityType() != null) {
                // Find the entity class from the type
                Class<?> entityClass = resolveEntityClass(repositoryQuery);
                if (entityClass != null) {
                    return entityMappingResolver.resolveEntityMetadata(entityClass);
                }
            }
        } catch (Exception e) {
            logger.debug("Could not build entity metadata: {}", e.getMessage());
        }
        
        // Return empty metadata if resolution fails
        return EntityMetadata.empty();
    }
    
    /**
     * Resolves the entity class from the repository query.
     * This uses the existing type resolution logic from the parser package.
     */
    private Class<?> resolveEntityClass(RepositoryQuery repositoryQuery) {
        try {
            // Try to load the class from the entity type name
            String entityTypeName = repositoryQuery.getEntityType().toString();
            
            // Remove generic type parameters if present
            if (entityTypeName.contains("<")) {
                entityTypeName = entityTypeName.substring(0, entityTypeName.indexOf("<"));
            }
            
            // Try to load the class
            return Class.forName(entityTypeName);
        } catch (ClassNotFoundException e) {
            logger.debug("Could not resolve entity class: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Gets the cardinality analyzer used by this extractor.
     */
    public CardinalityAnalyzer getCardinalityAnalyzer() {
        return cardinalityAnalyzer;
    }
}