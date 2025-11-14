package sa.com.cloudsolutions.antikythera.examples;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;

public class VisitorDebug {
    public static void main(String[] args) throws Exception {
        String sql = "SELECT * FROM orders o " +
                     "WHERE o.customer_id IN (SELECT c.id FROM customers c WHERE c.country = ?) " +
                     "AND o.product_id IN (SELECT p.id FROM products p WHERE p.category = ?)";
        Statement statement = CCJSqlParserUtil.parse(sql);
        Select select = (Select) statement;
        PlainSelect plainSelect = select.getPlainSelect();

        Expression where = plainSelect.getWhere();
        System.out.println("WHERE expression type: " + where.getClass().getName());
        System.out.println("WHERE expression: " + where);
        System.out.println("Is AndExpression? " + (where instanceof AndExpression));
        System.out.println("Is InExpression? " + (where instanceof InExpression));

        // Try to cast and check
        if (where instanceof InExpression) {
            InExpression in = (InExpression) where;
            System.out.println("InExpression left: " + in.getLeftExpression());
            System.out.println("InExpression right: " + in.getRightExpression());
            System.out.println("InExpression right type: " + in.getRightExpression().getClass().getName());

            // Check if there's an AND after the first IN
            Object rightItem = in.getRightExpression();
            System.out.println("Right item string: " + rightItem);
        }
    }
}

