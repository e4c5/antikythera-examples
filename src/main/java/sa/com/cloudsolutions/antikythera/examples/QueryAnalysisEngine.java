package sa.com.cloudsolutions.antikythera.examples;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import sa.com.cloudsolutions.antikythera.exception.AntikytheraException;
import sa.com.cloudsolutions.antikythera.generator.QueryMethodParameter;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;
import sa.com.cloudsolutions.antikythera.parser.converter.ConversionResult;
import sa.com.cloudsolutions.antikythera.parser.converter.EntityMappingResolver;


import java.util.ArrayList;
import java.util.List;

/**
 * Enhanced engine that analyzes repository queries using RepositoryQuery's parsing capabilities
 * to identify WHERE clause optimization opportunities based on column cardinality.
 * Updated to support AI-powered query optimization with enhanced analysis capabilities.
 */
public class QueryAnalysisEngine {

    /**
     * Analyzes a RepositoryQuery using its parsed Statement to identify optimization opportunities.
     * Enhanced to handle both HQL and native SQL queries through RepositoryQuery conversion.
     * 
     * @param repositoryQuery the repository query to analyze
     * @return the analysis results including WHERE conditions and optimization issues
     */
    public QueryAnalysisResult analyzeQuery(RepositoryQuery repositoryQuery) {
        Statement statement = repositoryQuery.getStatement();
        if (statement == null) {
            return handleDerivedQuery(repositoryQuery);
        }

        ConversionResult conversionResult = repositoryQuery.getConversionResult();
        List<WhereCondition> whereConditions;
        if (conversionResult != null && conversionResult.getNativeSql() != null) {
            try {
                Statement st = CCJSqlParserUtil.parse(conversionResult.getNativeSql());
                whereConditions = QueryOptimizationExtractor.extractWhereConditions(st);
            } catch (JSQLParserException jsqe) {
                throw new AntikytheraException(jsqe);
            }
        }
        else {
            whereConditions = QueryOptimizationExtractor.extractWhereConditions(repositoryQuery.getStatement());
        }
        updateWhereConditions(whereConditions, conversionResult);
        return new QueryAnalysisResult(repositoryQuery, whereConditions);
    }

    private static void updateWhereConditions(List<WhereCondition> whereConditions, ConversionResult conversionResult) {
        for (WhereCondition condition : whereConditions) {
            if (conversionResult != null) {
                String entity = conversionResult.getMetaData().getEntityForAlias(condition.tableName());
                String tableName = entity == null ? condition.tableName() : EntityMappingResolver.getTableNameForEntity(entity);

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
    }

    /**
     * Handles analysis of derived query methods (findBy*, countBy*, etc.).
     */
    private QueryAnalysisResult handleDerivedQuery(RepositoryQuery repositoryQuery) {
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
                    WhereCondition condition = new WhereCondition(tableName, columnName, "=", i);
                    condition.setCardinality(cardinality);
                    whereConditions.add(condition);
                }
            }
        }
        
        return new QueryAnalysisResult(repositoryQuery, whereConditions);
    }
}
