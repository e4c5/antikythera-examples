package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for QueryOptimizer class.
 * Tests both annotation value updates and text block rendering.
 */
class QueryOptimizerTest {

    private QueryOptimizer optimizer;

    @BeforeAll
    static void setUpClass() throws Exception {
        Settings.loadConfigMap();
    }

    @BeforeEach
    void setUp() throws Exception {
        // Create a minimal QueryOptimizer instance for testing
        File liquibaseFile = new File("src/test/resources/liquibase-test.xml");
        optimizer = new QueryOptimizer(liquibaseFile);
    }

    @Test
    void testUpdateAnnotationValue_withSingleLineQuery() {
        // Test case 1: Single-line query should use StringLiteralExpr
        String singleLineQuery = "SELECT * FROM users WHERE id = ?";

        // Create a method with a Query annotation
        MethodDeclaration method = StaticJavaParser.parseMethodDeclaration(
            "@Query(\"old query\") List<User> findUsers();"
        );

        optimizer.updateAnnotationValue(method, "Query", singleLineQuery, false);

        // Verify the annotation was updated with StringLiteralExpr
        AnnotationExpr annotation = method.getAnnotationByName("Query").orElseThrow();
        assertTrue(annotation.isSingleMemberAnnotationExpr());

        var memberValue = annotation.asSingleMemberAnnotationExpr().getMemberValue();
        assertTrue(memberValue instanceof StringLiteralExpr,
            "Single-line query should use StringLiteralExpr");
        assertEquals(singleLineQuery, ((StringLiteralExpr) memberValue).getValue());
    }

    @Test
    void testUpdateAnnotationValue_withMultiLineQuery() {
        // Test case 2: Multi-line query should use TextBlockLiteralExpr
        String multiLineQuery = "SELECT *\nFROM users\nWHERE id = ?";

        // Create a method with a Query annotation
        MethodDeclaration method = StaticJavaParser.parseMethodDeclaration(
            "@Query(\"old query\") List<User> findUsers();"
        );

        optimizer.updateAnnotationValue(method, "Query", multiLineQuery, true);

        // Verify the annotation was updated with TextBlockLiteralExpr
        AnnotationExpr annotation = method.getAnnotationByName("Query").orElseThrow();
        assertTrue(annotation.isSingleMemberAnnotationExpr());

        var memberValue = annotation.asSingleMemberAnnotationExpr().getMemberValue();
        assertTrue(memberValue instanceof TextBlockLiteralExpr,
            "Multi-line query should use TextBlockLiteralExpr");
        assertEquals(multiLineQuery, ((TextBlockLiteralExpr) memberValue).getValue());
    }

    @Test
    void testUpdateAnnotationValue_withLiteralBackslashN() {
        // Test case 3: Query with literal \n characters (as string)
        String queryWithLiteralBackslashN = "SELECT * FROM users\\nWHERE id = ?";
        String expectedProcessed = "SELECT * FROM users\nWHERE id = ?";

        // Create a method with a Query annotation
        MethodDeclaration method = StaticJavaParser.parseMethodDeclaration(
            "@Query(\"old query\") List<User> findUsers();"
        );

        // The updateAnnotationValueWithTextBlockSupport should handle the conversion
        // But we're testing updateAnnotationValue directly, so we convert manually
        String processed = queryWithLiteralBackslashN.replace("\\n", "\n");
        optimizer.updateAnnotationValue(method, "Query", processed, true);

        // Verify the annotation was updated with TextBlockLiteralExpr
        AnnotationExpr annotation = method.getAnnotationByName("Query").orElseThrow();
        var memberValue = annotation.asSingleMemberAnnotationExpr().getMemberValue();

        assertTrue(memberValue instanceof TextBlockLiteralExpr,
            "Query with converted newlines should use TextBlockLiteralExpr");
        assertEquals(expectedProcessed, ((TextBlockLiteralExpr) memberValue).getValue());
    }

    @Test
    void testUpdateAnnotationValue_withNormalAnnotation() {
        // Test case 4: Normal annotation style @Query(value = "...")
        String multiLineQuery = "SELECT u.id, u.name\nFROM users u\nWHERE u.status = ?";

        // Create a method with a normal annotation
        MethodDeclaration method = StaticJavaParser.parseMethodDeclaration(
            "@Query(value = \"old query\", nativeQuery = true) List<User> findUsers();"
        );

        optimizer.updateAnnotationValue(method, "Query", multiLineQuery, true);

        // Verify the annotation was updated
        AnnotationExpr annotation = method.getAnnotationByName("Query").orElseThrow();
        assertTrue(annotation.isNormalAnnotationExpr());

        var pairs = annotation.asNormalAnnotationExpr().getPairs();
        var valuePair = pairs.stream()
            .filter(p -> p.getName().asString().equals("value"))
            .findFirst()
            .orElseThrow();

        assertTrue(valuePair.getValue() instanceof TextBlockLiteralExpr,
            "Multi-line query should use TextBlockLiteralExpr in normal annotations");
        assertEquals(multiLineQuery, ((TextBlockLiteralExpr) valuePair.getValue()).getValue());
    }

    @Test
    void testUpdateAnnotationValue_preservesOtherAnnotationProperties() {
        // Test case 5: Updating value should preserve other annotation properties
        String newQuery = "SELECT * FROM users";

        MethodDeclaration method = StaticJavaParser.parseMethodDeclaration(
            "@Query(value = \"old query\", nativeQuery = true) List<User> findUsers();"
        );

        optimizer.updateAnnotationValue(method, "Query", newQuery, false);

        // Verify nativeQuery property is still present
        AnnotationExpr annotation = method.getAnnotationByName("Query").orElseThrow();
        var pairs = annotation.asNormalAnnotationExpr().getPairs();

        assertEquals(2, pairs.size(), "Should still have 2 pairs (value and nativeQuery)");

        var nativeQueryPair = pairs.stream()
            .filter(p -> p.getName().asString().equals("nativeQuery"))
            .findFirst();

        assertTrue(nativeQueryPair.isPresent(), "nativeQuery property should be preserved");
    }

    @Test
    void testUpdateAnnotationValue_withNonExistentAnnotation() {
        // Test case 6: Attempting to update non-existent annotation should not crash
        MethodDeclaration method = StaticJavaParser.parseMethodDeclaration(
            "List<User> findUsers();"
        );

        // Should not throw exception
        assertDoesNotThrow(() ->
            optimizer.updateAnnotationValue(method, "Query", "SELECT * FROM users", false)
        );

        // Annotation should still not exist (we don't create it, just update if present)
        assertFalse(method.getAnnotationByName("Query").isPresent());
    }

    @Test
    void testUpdateAnnotationValue_withComplexMultiLineQuery() {
        // Test case 7: Complex multi-line query with various SQL clauses
        String complexQuery = "SELECT u.id, u.name, u.email\n" +
                            "FROM users u\n" +
                            "JOIN orders o ON u.id = o.user_id\n" +
                            "WHERE u.status = ? AND o.total > ?\n" +
                            "ORDER BY u.name";

        MethodDeclaration method = StaticJavaParser.parseMethodDeclaration(
            "@Query(\"old query\") List<User> findUsers();"
        );

        optimizer.updateAnnotationValue(method, "Query", complexQuery, true);

        // Verify the annotation was updated
        AnnotationExpr annotation = method.getAnnotationByName("Query").orElseThrow();
        var memberValue = annotation.asSingleMemberAnnotationExpr().getMemberValue();

        assertTrue(memberValue instanceof TextBlockLiteralExpr);
        String retrievedValue = ((TextBlockLiteralExpr) memberValue).getValue();

        // Verify all parts of the query are preserved
        assertTrue(retrievedValue.contains("SELECT u.id, u.name, u.email"));
        assertTrue(retrievedValue.contains("FROM users u"));
        assertTrue(retrievedValue.contains("JOIN orders o ON u.id = o.user_id"));
        assertTrue(retrievedValue.contains("WHERE u.status = ? AND o.total > ?"));
        assertTrue(retrievedValue.contains("ORDER BY u.name"));
    }

    // ==================== Integration Tests for Text Block Rendering ====================

    @Test
    void testTextBlockRendering_withTripleQuotes() {
        // Integration test: Verify text blocks render with triple quotes
        MethodDeclaration method = StaticJavaParser.parseMethodDeclaration(
            "@Query(\"old query\") List<User> findUsers();"
        );

        String multilineQuery = "SELECT u.id, u.name\nFROM users u\nWHERE u.status = ?";
        optimizer.updateAnnotationValue(method, "Query", multilineQuery, true);

        // Verify the rendered output
        String rendered = method.toString();

        assertTrue(rendered.contains("\"\"\""),
            "Rendered output should contain triple quotes for text block");
        assertFalse(rendered.contains("\\n"),
            "Rendered output should NOT contain escaped \\n characters");
        assertTrue(rendered.contains("SELECT u.id, u.name"),
            "Rendered output should contain the query text");
        assertTrue(rendered.contains("FROM users u"),
            "Rendered output should preserve line structure");

        // Verify it actually renders across multiple lines
        String[] lines = rendered.split("\n");
        assertTrue(lines.length > 1,
            "Rendered output should span multiple lines");
    }

    @Test
    void testStringLiteralRendering_withEscapedNewlines() {
        // Integration test: Verify string literals escape newlines
        MethodDeclaration method = StaticJavaParser.parseMethodDeclaration(
            "@Query(\"old query\") List<User> findUsers();"
        );

        String queryWithNewlines = "SELECT *\nFROM users\nWHERE id = ?";
        optimizer.updateAnnotationValue(method, "Query", queryWithNewlines, false);

        String rendered = method.toString();

        assertFalse(rendered.contains("\"\"\""),
            "Rendered output should NOT contain triple quotes when using StringLiteralExpr");
        assertTrue(rendered.contains("\\n"),
            "Rendered output SHOULD contain escaped \\n characters when using StringLiteralExpr");
    }

    @Test
    void testLiteralBackslashN_detectionAndConversion() {
        // Integration test: Verify literal \n detection and conversion
        MethodDeclaration method = StaticJavaParser.parseMethodDeclaration(
            "@Query(\"old query\") List<User> findUsers();"
        );

        String queryWithLiteralBackslashN = "SELECT * FROM users\\nWHERE id = ?";

        // Simulate what updateAnnotationValueWithTextBlockSupport does
        boolean isMultiline = queryWithLiteralBackslashN.contains("\\n") ||
                             queryWithLiteralBackslashN.contains("\n");
        assertTrue(isMultiline, "Should detect literal \\n as multiline");

        // Convert and update
        String processed = queryWithLiteralBackslashN.replace("\\n", "\n");
        optimizer.updateAnnotationValue(method, "Query", processed, true);

        // Verify the rendered output
        String rendered = method.toString();
        assertTrue(rendered.contains("\"\"\""),
            "Should render as text block");
        assertFalse(rendered.contains("\\n"),
            "Should not have escaped newlines in text block");
    }

    @Test
    void testCompleteWorkflow_fromLiteralBackslashNToTextBlock() {
        // Integration test: Complete workflow from literal \n to text block
        String originalQuery = "SELECT u.id, u.name, u.email\\nFROM users u\\nWHERE u.status = ?";

        // Detect multiline
        boolean hasLiteralNewline = originalQuery.contains("\\n");
        boolean hasActualNewline = originalQuery.contains("\n");
        boolean isMultiline = hasLiteralNewline || hasActualNewline;

        assertTrue(isMultiline, "Should detect query as multiline");

        // Convert and update
        String processedQuery = originalQuery.replace("\\n", "\n");

        MethodDeclaration method = StaticJavaParser.parseMethodDeclaration(
            "@Query(\"old\") List<User> findUsers();"
        );
        optimizer.updateAnnotationValue(method, "Query", processedQuery, true);

        // Verify final output
        String rendered = method.toString();

        assertTrue(rendered.contains("\"\"\""),
            "Final output should use text block delimiters");
        assertFalse(rendered.contains("\\n"),
            "Final output should not have escaped \\n");
        assertTrue(rendered.split("\n").length > 1,
            "Final output should span multiple lines");

        // Verify content preservation
        assertTrue(rendered.contains("SELECT u.id, u.name, u.email"));
        assertTrue(rendered.contains("FROM users u"));
        assertTrue(rendered.contains("WHERE u.status = ?"));
    }

    @Test
    void testVerifyExpressionType_textBlock() {
        // Integration test: Verify AST contains TextBlockLiteralExpr when using text blocks
        MethodDeclaration method = StaticJavaParser.parseMethodDeclaration(
            "@Query(\"old\") List<User> findUsers();"
        );

        String multilineQuery = "SELECT *\nFROM users";
        optimizer.updateAnnotationValue(method, "Query", multilineQuery, true);

        AnnotationExpr annotation = method.getAnnotationByName("Query").orElseThrow();
        var memberValue = annotation.asSingleMemberAnnotationExpr().getMemberValue();

        assertTrue(memberValue instanceof TextBlockLiteralExpr,
            "When useTextBlock=true, should create TextBlockLiteralExpr in AST");

        String value = ((TextBlockLiteralExpr) memberValue).getValue();
        assertEquals(multilineQuery, value,
            "TextBlockLiteralExpr should preserve the exact query content");
    }

    @Test
    void testVerifyExpressionType_stringLiteral() {
        // Integration test: Verify AST contains StringLiteralExpr when not using text blocks
        MethodDeclaration method = StaticJavaParser.parseMethodDeclaration(
            "@Query(\"old\") List<User> findUsers();"
        );

        String singleLineQuery = "SELECT * FROM users";
        optimizer.updateAnnotationValue(method, "Query", singleLineQuery, false);

        AnnotationExpr annotation = method.getAnnotationByName("Query").orElseThrow();
        var memberValue = annotation.asSingleMemberAnnotationExpr().getMemberValue();

        assertTrue(memberValue instanceof StringLiteralExpr,
            "When useTextBlock=false, should create StringLiteralExpr in AST");

        String value = ((StringLiteralExpr) memberValue).getValue();
        assertEquals(singleLineQuery, value,
            "StringLiteralExpr should preserve the exact query content");
    }
}

