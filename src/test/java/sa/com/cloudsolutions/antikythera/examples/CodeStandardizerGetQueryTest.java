package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.ast.expr.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CodeStandardizerGetQueryTest {

    @Test
    void returnsQueryFromSingleMemberAnnotation() {
        CodeStandardizer cs = new CodeStandardizer();
        String sql = "SELECT * FROM users WHERE email = :email";
        AnnotationExpr ann = new SingleMemberAnnotationExpr(new Name("Query"), new StringLiteralExpr(sql));

        String result = cs.getQuery(ann);
        assertEquals(sql, result);
    }

    @Test
    void returnsQueryFromNormalAnnotationValuePair() {
        CodeStandardizer cs = new CodeStandardizer();
        String sql = "SELECT * FROM orders WHERE id = :id";
        NormalAnnotationExpr ann = new NormalAnnotationExpr();
        ann.setName(new Name("Query"));
        ann.addPair("timeout", new IntegerLiteralExpr(5));
        ann.addPair("value", new StringLiteralExpr(sql));
        ann.addPair("nativeQuery", new BooleanLiteralExpr(true));

        String result = cs.getQuery(ann);
        assertEquals(sql, result);
    }

    @Test
    void returnsNullWhenNoStringValuePresent() {
        CodeStandardizer cs = new CodeStandardizer();
        // Marker annotation: @Query
        AnnotationExpr marker = new MarkerAnnotationExpr(new Name("Query"));
        assertNull(cs.getQuery(marker));

        // Normal annotation without a string value for "value"
        NormalAnnotationExpr ann = new NormalAnnotationExpr();
        ann.setName(new Name("Query"));
        ann.addPair("value", new NameExpr("SOME_CONST")); // not a string literal
        assertNull(cs.getQuery(ann));
    }
}
