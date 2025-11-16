package sa.com.cloudsolutions.antikythera.examples;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;

import java.util.List;

/**
 * Example demonstrating how to use the WHERE and JOIN condition extraction facilities.
 * 
 * This example shows:
 * 1. How to extract WHERE conditions separately from JOIN conditions
 * 2. How to extract JOIN conditions separately from WHERE conditions
 * 3. How to extract both types together
 */
public class ConditionExtractionExample {

    public static void main(String[] args) throws JSQLParserException {
        // Example SQL query with both WHERE and JOIN clauses
        String sql = "SELECT o.*, c.name " +
                     "FROM orders o " +
                     "JOIN customers c ON o.customer_id = c.id " +
                     "JOIN products p ON o.product_id = p.id " +
                     "WHERE o.status = 'active' AND o.total_amount > 100";

        System.out.println("Original SQL Query:");
        System.out.println(sql);
        System.out.println("\n" + "=".repeat(80) + "\n");

        // Parse the SQL statement
        Statement statement = CCJSqlParserUtil.parse(sql);

        // Example 1: Extract WHERE conditions only
        demonstrateWhereConditionExtraction(statement);

        // Example 2: Extract JOIN conditions only
        demonstrateJoinConditionExtraction(statement);

        // Example 3: Extract both types together
        demonstrateBothConditionExtraction(statement);
    }

    /**
     * Demonstrates extracting WHERE conditions only (excluding JOIN ON conditions).
     */
    private static void demonstrateWhereConditionExtraction(Statement statement) {
        System.out.println("1. Extracting WHERE Conditions Only:");
        System.out.println("-".repeat(40));

        List<WhereCondition> whereConditions = QueryOptimizationExtractor.extractWhereConditions(statement);

        System.out.println("Found " + whereConditions.size() + " WHERE conditions:");
        for (WhereCondition condition : whereConditions) {
            System.out.println("  - Table: " + condition.tableName() +
                             ", Column: " + condition.columnName() +
                             ", Operator: " + condition.operator() +
                             ", Position: " + condition.position());
        }
        System.out.println("\nNote: JOIN ON conditions are NOT included in this list.\n");
    }

    /**
     * Demonstrates extracting JOIN conditions only (excluding WHERE conditions).
     */
    private static void demonstrateJoinConditionExtraction(Statement statement) {
        System.out.println("2. Extracting JOIN ON Conditions Only:");
        System.out.println("-".repeat(40));

        List<JoinCondition> joinConditions = QueryOptimizationExtractor.extractJoinConditions(statement);

        System.out.println("Found " + joinConditions.size() + " JOIN conditions:");
        for (JoinCondition condition : joinConditions) {
            System.out.println("  - Left: " + condition.leftTable() + "." + condition.leftColumn() +
                             " " + condition.operator() +
                             " Right: " + condition.rightTable() + "." + condition.rightColumn() +
                             ", Position: " + condition.position());
        }
        System.out.println("\nNote: WHERE conditions are NOT included in this list.\n");
    }

    /**
     * Demonstrates extracting both WHERE and JOIN conditions in a single call.
     */
    private static void demonstrateBothConditionExtraction(Statement statement) {
        System.out.println("3. Extracting Both WHERE and JOIN Conditions:");
        System.out.println("-".repeat(40));

        QueryOptimizationExtractor.ConditionExtractionResult result =
            QueryOptimizationExtractor.extractAllConditions(statement);

        System.out.println("WHERE Conditions (" + result.getWhereConditions().size() + "):");
        for (WhereCondition condition : result.getWhereConditions()) {
            System.out.println("  - " + condition.tableName() + "." + condition.columnName() +
                             " " + condition.operator());
        }

        System.out.println("\nJOIN Conditions (" + result.getJoinConditions().size() + "):");
        for (JoinCondition condition : result.getJoinConditions()) {
            System.out.println("  - " + condition.leftTable() + "." + condition.leftColumn() +
                             " " + condition.operator() +
                             " " + condition.rightTable() + "." + condition.rightColumn());
        }

        System.out.println("\nThis convenience method returns both types properly separated.\n");
    }
}
