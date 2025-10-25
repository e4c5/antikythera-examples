package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.generator.QueryMethodParameter;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;
import sa.com.cloudsolutions.antikythera.parser.Callable;
import sa.com.cloudsolutions.antikythera.parser.MCEWrapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CodeStandardizerTest {

    private static Callable wrap(MethodDeclaration md) {
        // Use a dummy method call expr to satisfy MCEWrapper constructor
        MethodCallExpr dummyCall = new MethodCallExpr("dummy");
        return new Callable(md, new MCEWrapper(dummyCall));
    }

    private static ClassOrInterfaceDeclaration newRepoClass(CompilationUnit cu, String name) {
        cu.setPackageDeclaration("sa.com.cloudsolutions.antikythera.examples");
        return cu.addClass(name);
    }

    private static OptimizationIssue newIssue(Callable callable, String currentFirst, String recommended, String queryText) {
        RepositoryQuery rq = new RepositoryQuery();
        rq.setMethodDeclaration(callable);
        // For this test, we don't need RepositoryQuery to parse the SQL; avoid calling setQuery which expects entity context
        return new OptimizationIssue(rq, currentFirst, recommended, "reorder for performance", OptimizationIssue.Severity.HIGH, queryText);
    }

    @Test
    void reorderQueryAnnotation_AND_only_high_to_low() {
        // Build method with @Query annotation
        CompilationUnit cu = new CompilationUnit();
        ClassOrInterfaceDeclaration clazz = newRepoClass(cu, "UserRepository");

        MethodDeclaration md = clazz.addMethod("findActiveByEmailAndAge", com.github.javaparser.ast.Modifier.Keyword.PUBLIC);
        String originalQuery = "SELECT * FROM users WHERE is_active = true AND age > :age AND email = :email";
        SingleMemberAnnotationExpr queryAnn = new SingleMemberAnnotationExpr(new Name("Query"), new StringLiteralExpr(originalQuery));
        md.addAnnotation(queryAnn);
        md.setType(new ClassOrInterfaceType(null, "List<User>"));
        md.setParameters(new NodeList<>());

        // Analysis result: email (HIGH), age (MEDIUM), is_active (LOW)
        List<WhereCondition> conditions = new ArrayList<>();
        conditions.add(new WhereCondition("email", "=", CardinalityLevel.HIGH, 2, null));
        conditions.add(new WhereCondition("age", ">", CardinalityLevel.MEDIUM, 1, null));
        conditions.add(new WhereCondition("is_active", "=", CardinalityLevel.LOW, 0, null));

        Callable callable = wrap(md);
        List<OptimizationIssue> issues = List.of(newIssue(callable, "is_active", "email", originalQuery));
        QueryOptimizationResult qres = new QueryOptimizationResult(callable, originalQuery, conditions, issues);

        CodeStandardizer cs = new CodeStandardizer();
        Optional<CodeStandardizer.SignatureUpdate> update = cs.standardize(qres);
        assertTrue(update.isEmpty(), "Annotated methods should not produce a signature update");

        // Verify the annotation has been rewritten with HIGH -> MEDIUM -> LOW order
        Optional<AnnotationExpr> ann = md.getAnnotationByName("Query");
        assertTrue(ann.isPresent());
        String newQuery;
        if (ann.get().isSingleMemberAnnotationExpr()) {
            newQuery = ann.get().asSingleMemberAnnotationExpr().getMemberValue().asStringLiteralExpr().getValue();
        } else if (ann.get().isNormalAnnotationExpr()) {
            newQuery = ann.get().asNormalAnnotationExpr().getPairs().stream()
                    .filter(p -> p.getNameAsString().equals("value"))
                    .findFirst().orElseThrow()
                    .getValue().asStringLiteralExpr().getValue();
        } else {
            newQuery = null;
        }
        assertNotNull(newQuery);
        // Expected order: email (HIGH) AND age (MEDIUM) AND is_active (LOW)
        assertTrue(newQuery.matches("(?is).*WHERE\\s+email\\s*=\\s*:email\\s+AND\\s+age\\s*>\\s*:age\\s+AND\\s+is_active\\s*=\\s*true.*"),
                () -> "Unexpected query after rewrite: " + newQuery);
    }

    @Test
    void annotatedQuery_with_OR_is_left_unchanged() {
        CompilationUnit cu = new CompilationUnit();
        ClassOrInterfaceDeclaration clazz = newRepoClass(cu, "OrderRepository");
        MethodDeclaration md = clazz.addMethod("findByStatusOrUser", com.github.javaparser.ast.Modifier.Keyword.PUBLIC);
        String originalQuery = "SELECT * FROM orders WHERE status = :status OR user_id = :userId AND created_at > :created";
        md.addAnnotation(new SingleMemberAnnotationExpr(new Name("Query"), new StringLiteralExpr(originalQuery)));
        Callable callable = wrap(md);

        List<WhereCondition> conditions = List.of(
                new WhereCondition("user_id", "=", CardinalityLevel.HIGH, 1, null),
                new WhereCondition("status", "=", CardinalityLevel.LOW, 0, null));
        List<OptimizationIssue> issues = List.of(newIssue(callable, "status", "user_id", originalQuery));
        QueryOptimizationResult qres = new QueryOptimizationResult(callable, originalQuery, conditions, issues);

        CodeStandardizer cs = new CodeStandardizer();
        cs.standardize(qres);

        String after = md.getAnnotationByName("Query").get().asSingleMemberAnnotationExpr().getMemberValue().asStringLiteralExpr().getValue();
        assertEquals(originalQuery, after, "Query containing OR should not be rewritten");
    }

    @Test
    void reorderParameters_for_derived_method_returns_update_and_mutates_signature() {
        // Build a derived method (no @Query) with three parameters
        CompilationUnit cu = new CompilationUnit();
        ClassOrInterfaceDeclaration clazz = newRepoClass(cu, "UserRepository2");
        MethodDeclaration md = clazz.addMethod("findByEmailAndAgeAndActive", com.github.javaparser.ast.Modifier.Keyword.PUBLIC);

        Parameter p1 = new Parameter(new ClassOrInterfaceType(null, "String"), "email");
        Parameter p2 = new Parameter(new ClassOrInterfaceType(null, "Integer"), "age");
        Parameter p3 = new Parameter(new ClassOrInterfaceType(null, "Boolean"), "active");
        md.addParameter(p1);
        md.addParameter(p2);
        md.addParameter(p3);

        // Where conditions mapped to parameters
        QueryMethodParameter qmp1 = new QueryMethodParameter(p1, 0); // email HIGH
        QueryMethodParameter qmp2 = new QueryMethodParameter(p2, 1); // age MEDIUM
        QueryMethodParameter qmp3 = new QueryMethodParameter(p3, 2); // active LOW

        List<WhereCondition> conditions = List.of(
                new WhereCondition("email", "=", CardinalityLevel.HIGH, 0, qmp1),
                new WhereCondition("age", ">", CardinalityLevel.MEDIUM, 1, qmp2),
                new WhereCondition("active", "=", CardinalityLevel.LOW, 2, qmp3)
        );

        Callable callable = wrap(md);
        // Optimization issue to trigger reordering (current first not equal to recommended)
        List<OptimizationIssue> issues = List.of(newIssue(callable, "active", "email", null));
        QueryOptimizationResult qres = new QueryOptimizationResult(callable, null, conditions, issues);

        CodeStandardizer cs = new CodeStandardizer();
        Optional<CodeStandardizer.SignatureUpdate> sig = cs.standardize(qres);
        assertTrue(sig.isEmpty(), "Already optimal order should not produce a SignatureUpdate");

        // Now shuffle initial order and expect reorder
        md.getParameters().clear();
        md.addParameter(p3);
        md.addParameter(p1);
        md.addParameter(p2);
        QueryOptimizationResult qres2 = new QueryOptimizationResult(callable, null, conditions, issues);
        Optional<CodeStandardizer.SignatureUpdate> sig2 = cs.standardize(qres2);
        assertTrue(sig2.isPresent());
        assertEquals(List.of("active", "email", "age"), sig2.get().oldParamNames);
        assertEquals(List.of("email", "age", "active"), sig2.get().newParamNames);

        // The method declaration must also be mutated to new order
        assertEquals("email", md.getParameter(0).getNameAsString());
        assertEquals("age", md.getParameter(1).getNameAsString());
        assertEquals("active", md.getParameter(2).getNameAsString());
    }

    @Test
    void annotatedQuery_already_ordered_results_in_no_change() {
        CompilationUnit cu = new CompilationUnit();
        ClassOrInterfaceDeclaration clazz = newRepoClass(cu, "UserRepository3");
        MethodDeclaration md = clazz.addMethod("findByEmailThenAge", com.github.javaparser.ast.Modifier.Keyword.PUBLIC);
        String originalQuery = "SELECT * FROM users WHERE email = :email AND age > :age";
        md.addAnnotation(new SingleMemberAnnotationExpr(new Name("Query"), new StringLiteralExpr(originalQuery)));

        Callable callable = wrap(md);
        List<WhereCondition> conditions = List.of(
                new WhereCondition("email", "=", CardinalityLevel.HIGH, 0, null),
                new WhereCondition("age", ">", CardinalityLevel.MEDIUM, 1, null)
        );
        // Since currentFirst == recommendedFirst, standardize() should do nothing
        List<OptimizationIssue> issues = List.of(newIssue(callable, "email", "email", originalQuery));
        QueryOptimizationResult qres = new QueryOptimizationResult(callable, originalQuery, conditions, issues);

        CodeStandardizer cs = new CodeStandardizer();
        cs.standardize(qres);

        String after = md.getAnnotationByName("Query").get().asSingleMemberAnnotationExpr().getMemberValue().asStringLiteralExpr().getValue();
        assertEquals(originalQuery, after);
    }
}
