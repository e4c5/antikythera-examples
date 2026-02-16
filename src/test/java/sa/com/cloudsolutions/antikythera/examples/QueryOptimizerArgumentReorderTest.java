package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueryOptimizerArgumentReorderTest {

    private QueryAnalysisResult mockResult;
    private OptimizationIssue mockIssue;
    private RepositoryQuery mockOriginalQuery;
    private RepositoryQuery mockOptimizedQuery;
    private sa.com.cloudsolutions.antikythera.parser.Callable mockOldCallable;
    private sa.com.cloudsolutions.antikythera.parser.Callable mockNewCallable;

    @BeforeEach
    void setUp() {
        mockResult = mock(QueryAnalysisResult.class);
        mockIssue = mock(OptimizationIssue.class);
        mockOriginalQuery = mock(RepositoryQuery.class);
        mockOptimizedQuery = mock(RepositoryQuery.class);
        mockOldCallable = mock(sa.com.cloudsolutions.antikythera.parser.Callable.class);
        mockNewCallable = mock(sa.com.cloudsolutions.antikythera.parser.Callable.class);

        when(mockResult.getOptimizationIssue()).thenReturn(mockIssue);
        when(mockIssue.query()).thenReturn(mockOriginalQuery);
        when(mockIssue.optimizedQuery()).thenReturn(mockOptimizedQuery);
        when(mockOriginalQuery.getMethodDeclaration()).thenReturn(mockOldCallable);
        when(mockOptimizedQuery.getMethodDeclaration()).thenReturn(mockNewCallable);
    }

    @Test
    void testNameChangeVisitor_ReordersArguments() {
        // Setup original method: findByAAndB(String a, int b)
        MethodDeclaration oldMethod = new MethodDeclaration();
        oldMethod.addParameter(new Parameter(new ClassOrInterfaceType(null, "String"), "a"));
        oldMethod.addParameter(new Parameter(new ClassOrInterfaceType(null, "int"), "b"));
        when(mockOldCallable.asMethodDeclaration()).thenReturn(oldMethod);

        // Setup optimized method: findByBAndA(int b, String a)
        MethodDeclaration newMethod = new MethodDeclaration();
        newMethod.addParameter(new Parameter(new ClassOrInterfaceType(null, "int"), "b"));
        newMethod.addParameter(new Parameter(new ClassOrInterfaceType(null, "String"), "a"));
        when(mockNewCallable.asMethodDeclaration()).thenReturn(newMethod);

        when(mockResult.getMethodName()).thenReturn("findByAAndB");
        when(mockOptimizedQuery.getMethodName()).thenReturn("findByBAndA");
        
        // Setup column orders: a and b -> b and a (reordered)
        when(mockIssue.currentColumnOrder()).thenReturn(List.of("a", "b"));
        when(mockIssue.recommendedColumnOrder()).thenReturn(List.of("b", "a"));

        // Setup method call: repo.findByAAndB("valA", 123)
        MethodCallExpr call = new MethodCallExpr(new NameExpr("repo"), "findByAAndB");
        call.addArgument(new NameExpr("\"valA\""));
        call.addArgument(new NameExpr("123"));

        QueryOptimizer.NameChangeVisitor visitor = new QueryOptimizer.NameChangeVisitor("repo", "com.example.Repo");
        visitor.visit(call, mockResult);

        // Verify: repo.findByBAndA(123, "valA")
        assertEquals("findByBAndA", call.getNameAsString());
        assertEquals(2, call.getArguments().size());
        assertEquals("123", call.getArgument(0).toString());
        assertEquals("\"valA\"", call.getArgument(1).toString());
    }

    @Test
    void testReordersArguments_WithFinalModifierMismatch() {
        // Reproduces the real-world bug: old method has 'final' on some params,
        // but the optimized method drops 'final' during reorderMethodParameters cloning.
        // e.g., existsBy...(..., final String facilityType) -> existsBy...(..., String facilityType, ...)

        // Old method: findByAAndB(final Long a, boolean b)
        MethodDeclaration oldMethod = new MethodDeclaration();
        Parameter paramA = new Parameter(new ClassOrInterfaceType(null, "Long"), "a");
        paramA.addModifier(Modifier.Keyword.FINAL);
        oldMethod.addParameter(paramA);
        oldMethod.addParameter(new Parameter(new ClassOrInterfaceType(null, "boolean"), "b"));
        when(mockOldCallable.asMethodDeclaration()).thenReturn(oldMethod);

        // Optimized method: findByBAndA(boolean b, Long a) — note: no 'final' on a
        MethodDeclaration newMethod = new MethodDeclaration();
        newMethod.addParameter(new Parameter(new ClassOrInterfaceType(null, "boolean"), "b"));
        newMethod.addParameter(new Parameter(new ClassOrInterfaceType(null, "Long"), "a"));
        when(mockNewCallable.asMethodDeclaration()).thenReturn(newMethod);

        when(mockResult.getMethodName()).thenReturn("findByAAndB");
        when(mockOptimizedQuery.getMethodName()).thenReturn("findByBAndA");

        // Setup method call: repo.findByAAndB(tenantId, true)
        MethodCallExpr call = new MethodCallExpr(new NameExpr("repo"), "findByAAndB");
        call.addArgument(new NameExpr("tenantId"));
        call.addArgument(new NameExpr("true"));

        QueryOptimizer.NameChangeVisitor visitor = new QueryOptimizer.NameChangeVisitor("repo", "com.example.Repo");
        visitor.visit(call, mockResult);

        // Verify: arguments are reordered despite final modifier mismatch
        assertEquals("findByBAndA", call.getNameAsString());
        assertEquals(2, call.getArguments().size());
        assertEquals("true", call.getArgument(0).toString());
        assertEquals("tenantId", call.getArgument(1).toString());
    }

    @Test
    void testHasEarlyParameterChange_swapInFirstFour() {
        // Old: (String a, String b, Long c, boolean d, String e)
        // New: (String b, String a, Long c, boolean d, String e)  — swap at positions 0,1
        MethodDeclaration oldMethod = buildMethod("String", "a", "String", "b", "Long", "c", "boolean", "d", "String", "e");
        MethodDeclaration newMethod = buildMethod("String", "b", "String", "a", "Long", "c", "boolean", "d", "String", "e");

        assertTrue(QueryOptimizer.hasEarlyParameterChange(oldMethod, newMethod, 4));
    }

    @Test
    void testHasEarlyParameterChange_swapOnlyAfterFourth() {
        // Old: (String a, String b, Long c, boolean d, String e, Long f)
        // New: (String a, String b, Long c, boolean d, Long f, String e)  — swap at 4,5 only
        MethodDeclaration oldMethod = buildMethod("String", "a", "String", "b", "Long", "c", "boolean", "d", "String", "e", "Long", "f");
        MethodDeclaration newMethod = buildMethod("String", "a", "String", "b", "Long", "c", "boolean", "d", "Long", "f", "String", "e");

        assertFalse(QueryOptimizer.hasEarlyParameterChange(oldMethod, newMethod, 4));
    }

    @Test
    void testHasEarlyParameterChange_identicalParams() {
        MethodDeclaration oldMethod = buildMethod("String", "a", "Long", "b");
        MethodDeclaration newMethod = buildMethod("String", "a", "Long", "b");

        assertFalse(QueryOptimizer.hasEarlyParameterChange(oldMethod, newMethod, 4));
    }

    @Test
    void testHasEarlyParameterChange_fewerThanThreshold() {
        // Only 2 params, threshold is 4 — checks the 2 that exist
        MethodDeclaration oldMethod = buildMethod("String", "a", "Long", "b");
        MethodDeclaration newMethod = buildMethod("Long", "b", "String", "a");

        assertTrue(QueryOptimizer.hasEarlyParameterChange(oldMethod, newMethod, 4));
    }

    @Test
    void testHasEarlyParameterChange_swapAtExactlyFourthPosition() {
        // Old: (String a, String b, Long c, boolean d, ...)
        // New: (String a, String b, Long c, String e, ...)  — change at position 3 (the fourth param)
        MethodDeclaration oldMethod = buildMethod("String", "a", "String", "b", "Long", "c", "boolean", "d", "String", "e");
        MethodDeclaration newMethod = buildMethod("String", "a", "String", "b", "Long", "c", "String", "e", "boolean", "d");

        assertTrue(QueryOptimizer.hasEarlyParameterChange(oldMethod, newMethod, 4));
    }

    /** Helper to build a MethodDeclaration from alternating type/name pairs. */
    private static MethodDeclaration buildMethod(String... typeNamePairs) {
        MethodDeclaration md = new MethodDeclaration();
        for (int i = 0; i < typeNamePairs.length; i += 2) {
            md.addParameter(new Parameter(new ClassOrInterfaceType(null, typeNamePairs[i]), typeNamePairs[i + 1]));
        }
        return md;
    }

    @Test
    void testBatchedNameChangeVisitor_ReordersArguments() {
        // Setup original method: findByXAndY(String x, String y)
        MethodDeclaration oldMethod = new MethodDeclaration();
        oldMethod.setName("findByXAndY");
        oldMethod.addParameter(new Parameter(new ClassOrInterfaceType(null, "String"), "x"));
        oldMethod.addParameter(new Parameter(new ClassOrInterfaceType(null, "String"), "y"));
        when(mockOldCallable.asMethodDeclaration()).thenReturn(oldMethod);

        // Setup optimized method: findByYAndX(String y, String x)
        MethodDeclaration newMethod = new MethodDeclaration();
        newMethod.setName("findByYAndX");
        newMethod.addParameter(new Parameter(new ClassOrInterfaceType(null, "String"), "y"));
        newMethod.addParameter(new Parameter(new ClassOrInterfaceType(null, "String"), "x"));
        when(mockNewCallable.asMethodDeclaration()).thenReturn(newMethod);
        
        // Setup column orders: x and y -> y and x (reordered)
        when(mockIssue.currentColumnOrder()).thenReturn(List.of("x", "y"));
        when(mockIssue.recommendedColumnOrder()).thenReturn(List.of("y", "x"));
        
        // Setup MethodRename
        QueryOptimizer.MethodRename rename = new QueryOptimizer.MethodRename(
            "findByXAndY", 
            "findByYAndX", 
            mockResult, 
            mockIssue
        );
        List<QueryOptimizer.MethodRename> renames = Collections.singletonList(rename);
        Set<String> fields = new HashSet<>();
        fields.add("repo");

        // Setup method call: repo.findByXAndY("valX", "valY")
        MethodCallExpr call = new MethodCallExpr(new NameExpr("repo"), "findByXAndY");
        call.addArgument(new NameExpr("\"valX\""));
        call.addArgument(new NameExpr("\"valY\""));

        QueryOptimizer.BatchedNameChangeProcessor processor = new QueryOptimizer.BatchedNameChangeProcessor(fields, renames);
        processor.processMethodCall(call);

        // Verify: repo.findByYAndX("valY", "valX")
        assertEquals("findByYAndX", call.getNameAsString());
        assertEquals(2, call.getArguments().size());
        assertEquals("\"valY\"", call.getArgument(0).toString());
        assertEquals("\"valX\"", call.getArgument(1).toString());
    }

    @Test
    void testNameChangeVisitor_DoReturnWhenPattern() {
        // Setup original method: findByAAndB(String a, int b)
        MethodDeclaration oldMethod = new MethodDeclaration();
        oldMethod.addParameter(new Parameter(new ClassOrInterfaceType(null, "String"), "a"));
        oldMethod.addParameter(new Parameter(new ClassOrInterfaceType(null, "int"), "b"));
        when(mockOldCallable.asMethodDeclaration()).thenReturn(oldMethod);

        // Setup optimized method: findByBAndA(int b, String a)
        MethodDeclaration newMethod = new MethodDeclaration();
        newMethod.addParameter(new Parameter(new ClassOrInterfaceType(null, "int"), "b"));
        newMethod.addParameter(new Parameter(new ClassOrInterfaceType(null, "String"), "a"));
        when(mockNewCallable.asMethodDeclaration()).thenReturn(newMethod);

        when(mockResult.getMethodName()).thenReturn("findByAAndB");
        when(mockOptimizedQuery.getMethodName()).thenReturn("findByBAndA");

        when(mockIssue.currentColumnOrder()).thenReturn(List.of("a", "b"));
        when(mockIssue.recommendedColumnOrder()).thenReturn(List.of("b", "a"));

        // Simulate doReturn(val).when(repo).findByAAndB("valA", 123)
        // AST: findByAAndB has scope = when(repo)
        MethodCallExpr whenCall = new MethodCallExpr(null, "when");
        whenCall.addArgument(new NameExpr("repo"));
        MethodCallExpr call = new MethodCallExpr(whenCall, "findByAAndB");
        call.addArgument(new NameExpr("\"valA\""));
        call.addArgument(new NameExpr("123"));

        QueryOptimizer.NameChangeVisitor visitor = new QueryOptimizer.NameChangeVisitor("repo", "com.example.Repo");
        visitor.visit(call, mockResult);

        // Verify: name changed and args reordered
        assertEquals("findByBAndA", call.getNameAsString());
        assertEquals(2, call.getArguments().size());
        assertEquals("123", call.getArgument(0).toString());
        assertEquals("\"valA\"", call.getArgument(1).toString());
    }

    @Test
    void testBatchedNameChangeProcessor_DoReturnWhenPattern() {
        // Setup original method: findByXAndY(String x, String y)
        MethodDeclaration oldMethod = new MethodDeclaration();
        oldMethod.setName("findByXAndY");
        oldMethod.addParameter(new Parameter(new ClassOrInterfaceType(null, "String"), "x"));
        oldMethod.addParameter(new Parameter(new ClassOrInterfaceType(null, "String"), "y"));
        when(mockOldCallable.asMethodDeclaration()).thenReturn(oldMethod);

        // Setup optimized method: findByYAndX(String y, String x)
        MethodDeclaration newMethod = new MethodDeclaration();
        newMethod.setName("findByYAndX");
        newMethod.addParameter(new Parameter(new ClassOrInterfaceType(null, "String"), "y"));
        newMethod.addParameter(new Parameter(new ClassOrInterfaceType(null, "String"), "x"));
        when(mockNewCallable.asMethodDeclaration()).thenReturn(newMethod);

        when(mockIssue.currentColumnOrder()).thenReturn(List.of("x", "y"));
        when(mockIssue.recommendedColumnOrder()).thenReturn(List.of("y", "x"));

        QueryOptimizer.MethodRename rename = new QueryOptimizer.MethodRename(
            "findByXAndY",
            "findByYAndX",
            mockResult,
            mockIssue
        );
        List<QueryOptimizer.MethodRename> renames = Collections.singletonList(rename);
        Set<String> fields = new HashSet<>();
        fields.add("repo");

        // Simulate doReturn(val).when(repo).findByXAndY("valX", "valY")
        // AST: findByXAndY has scope = when(repo)
        MethodCallExpr whenCall = new MethodCallExpr(null, "when");
        whenCall.addArgument(new NameExpr("repo"));
        MethodCallExpr call = new MethodCallExpr(whenCall, "findByXAndY");
        call.addArgument(new NameExpr("\"valX\""));
        call.addArgument(new NameExpr("\"valY\""));

        QueryOptimizer.BatchedNameChangeProcessor processor = new QueryOptimizer.BatchedNameChangeProcessor(fields, renames);
        processor.processMethodCall(call);

        // Verify: name changed and args reordered
        assertEquals("findByYAndX", call.getNameAsString());
        assertEquals(2, call.getArguments().size());
        assertEquals("\"valY\"", call.getArgument(0).toString());
        assertEquals("\"valX\"", call.getArgument(1).toString());
    }

    @Test
    void testReorderArguments_FallsBackToColumnOrderWhenNamesDiffer() {
        // Reproduces a bug where old params have abbreviated names like "wallet"/"chain"
        // but the AI-generated new params have "walletId"/"blockchainId".
        // Type+name matching fails, so column order mapping must be used.

        // Old: existsByTxHashAndIsConfirmedAndWalletIdAndBlockchainIdAndUtxoCodeIn(
        //          Long txHash, boolean isConfirmed, Long wallet, Long chain, List<String> utxoCodes)
        MethodDeclaration oldMethod = new MethodDeclaration();
        oldMethod.addParameter(new Parameter(new ClassOrInterfaceType(null, "Long"), "txHash"));
        oldMethod.addParameter(new Parameter(new ClassOrInterfaceType(null, "boolean"), "isConfirmed"));
        oldMethod.addParameter(new Parameter(new ClassOrInterfaceType(null, "Long"), "wallet"));
        oldMethod.addParameter(new Parameter(new ClassOrInterfaceType(null, "Long"), "chain"));
        oldMethod.addParameter(new Parameter(new ClassOrInterfaceType(null, "List"), "utxoCodes"));
        when(mockOldCallable.asMethodDeclaration()).thenReturn(oldMethod);

        // New: existsByTxHashAndBlockchainIdAndUtxoCodeInAndIsConfirmedAndWalletId(
        //          Long txHash, Long blockchainId, List<String> utxoCodes, boolean isConfirmed, Long walletId)
        MethodDeclaration newMethod = new MethodDeclaration();
        newMethod.addParameter(new Parameter(new ClassOrInterfaceType(null, "Long"), "txHash"));
        newMethod.addParameter(new Parameter(new ClassOrInterfaceType(null, "Long"), "blockchainId"));
        newMethod.addParameter(new Parameter(new ClassOrInterfaceType(null, "List"), "utxoCodes"));
        newMethod.addParameter(new Parameter(new ClassOrInterfaceType(null, "boolean"), "isConfirmed"));
        newMethod.addParameter(new Parameter(new ClassOrInterfaceType(null, "Long"), "walletId"));
        when(mockNewCallable.asMethodDeclaration()).thenReturn(newMethod);

        // Column orders encode the positional permutation
        when(mockIssue.currentColumnOrder()).thenReturn(
                List.of("tx_hash", "is_confirmed", "wallet_id", "blockchain_id", "utxo_code"));
        when(mockIssue.recommendedColumnOrder()).thenReturn(
                List.of("tx_hash", "blockchain_id", "utxo_code", "is_confirmed", "wallet_id"));

        // Call: existsBy...(txHash, false, walletId, chainId, spentUtxoCodes)
        MethodCallExpr call = new MethodCallExpr();
        call.addArgument(new NameExpr("txHash"));
        call.addArgument(new NameExpr("false"));
        call.addArgument(new NameExpr("walletId"));
        call.addArgument(new NameExpr("chainId"));
        call.addArgument(new NameExpr("spentUtxoCodes"));

        boolean result = QueryOptimizer.reorderMethodArguments(call, mockIssue);

        assertTrue(result, "Should succeed using column order fallback");
        assertEquals(5, call.getArguments().size());
        // Expected: txHash, chainId, spentUtxoCodes, false, walletId
        assertEquals("txHash", call.getArgument(0).toString());
        assertEquals("chainId", call.getArgument(1).toString());
        assertEquals("spentUtxoCodes", call.getArgument(2).toString());
        assertEquals("false", call.getArgument(3).toString());
        assertEquals("walletId", call.getArgument(4).toString());
    }

    @Test
    void testReorderArguments_ReturnsFalseWhenNoMappingPossible() {
        // When both type+name and column order mapping fail, should return false
        MethodDeclaration oldMethod = new MethodDeclaration();
        oldMethod.addParameter(new Parameter(new ClassOrInterfaceType(null, "Long"), "a"));
        oldMethod.addParameter(new Parameter(new ClassOrInterfaceType(null, "Long"), "b"));
        when(mockOldCallable.asMethodDeclaration()).thenReturn(oldMethod);

        MethodDeclaration newMethod = new MethodDeclaration();
        newMethod.addParameter(new Parameter(new ClassOrInterfaceType(null, "Long"), "x"));
        newMethod.addParameter(new Parameter(new ClassOrInterfaceType(null, "Long"), "y"));
        when(mockNewCallable.asMethodDeclaration()).thenReturn(newMethod);

        // No column orders available
        when(mockIssue.currentColumnOrder()).thenReturn(null);
        when(mockIssue.recommendedColumnOrder()).thenReturn(null);

        MethodCallExpr call = new MethodCallExpr();
        call.addArgument(new NameExpr("val1"));
        call.addArgument(new NameExpr("val2"));

        boolean result = QueryOptimizer.reorderMethodArguments(call, mockIssue);
        assertFalse(result, "Should fail when no mapping can be built");
        // Arguments should remain unchanged
        assertEquals("val1", call.getArgument(0).toString());
        assertEquals("val2", call.getArgument(1).toString());
    }
}
