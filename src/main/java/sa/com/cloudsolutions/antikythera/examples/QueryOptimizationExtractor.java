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

    public QueryOptimizationExtractor() {

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
        // Use the optimization analysis visitor
        OptimizationAnalysisVisitor visitor = new OptimizationAnalysisVisitor(repositoryQuery);
        
        return  visitor.extractConditions(whereExpression);
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
        
        String tableName = repositoryQuery.getPrimaryTable();
        if (tableName == null || tableName.isEmpty()) {
            logger.debug("Cannot extract conditions from method parameters without table name");
            return conditions;
        }
        
        for (int i = 0; i < methodParameters.size(); i++) {
            QueryMethodParameter param = methodParameters.get(i);
            String columnName = param.getColumnName();
            
            if (columnName != null && !columnName.isEmpty()) {
                // Use existing cardinality analysis infrastructure
                CardinalityLevel cardinality = CardinalityAnalyzer.analyzeColumnCardinality(tableName, columnName);
                
                WhereCondition condition = new WhereCondition(tableName, columnName, "=", cardinality, i, param);
                conditions.add(condition);
                
                logger.debug("Extracted condition from method parameter: {}", condition);
            }
        }
        
        return conditions;
    }


}
