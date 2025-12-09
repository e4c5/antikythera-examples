# Phase 1 Design Enhancements for Refactoring Support

## Executive Summary

The current Phase 1 design focuses on **detection and reporting** but doesn't capture sufficient information to enable Phase 2's **automated refactoring**. This document identifies critical data that must be preserved during Phase 1 analysis.

---

## Critical Gaps in Current Design

### ❌ What's Missing

| Missing Data | Why We Need It | Current State |
|-------------|----------------|---------------|
| **Original AST Nodes** | To perform actual code transformations | Only storing normalized tokens |
| **Variation Mappings** | To extract method parameters | Not tracking what differs between duplicates |
| **Type Information** | To generate correct method signatures | Types discarded during normalization |
| **Scope Analysis** | To determine if variables are in scope for extraction | Not analyzed |
| **Parent Context** | To know where to insert extracted methods | Only storing method name as string |
| **Source Text** | For showing diffs and preserving formatting | Not captured |
| **Import Statements** | To add missing imports after refactoring | Not tracked |
| **Compilation Unit Reference** | To locate and modify the file | Not retained |

---

## Enhanced Data Structures

### 1. Enhanced StatementSequence

**Current (Insufficient):**
```java
record StatementSequence(
    String methodName,
    int startOffset,
    List<Statement> statements,
    Range range
) {}
```

**Enhanced (Required for Refactoring):**
```java
record StatementSequence(
    // Original data
    String methodName,
    int startOffset,
    List<Statement> statements,
    Range range,
    
    // NEW: Refactoring-critical data
    MethodDeclaration containingMethod,      // Parent method AST node
    ClassOrInterfaceDeclaration containingClass,  // Parent class AST node
    CompilationUnit compilationUnit,         // For file-level modifications
    Path sourceFilePath,                     // Absolute path to source file
    String originalSourceText,               // Preserve original formatting
    List<String> currentImports,             // Existing imports in file
    ScopeContext scopeContext                // Variables available in scope
) {}

record ScopeContext(
    List<VariableInfo> availableVariables,   // Variables in scope
    List<FieldDeclaration> classFields,      // Class-level fields
    List<MethodDeclaration> classMethods,    // Other methods in class
    boolean isInTestClass,                   // Is this a test class?
    List<String> annotations                 // Method/class annotations
) {}

record VariableInfo(
    String name,
    String type,
    boolean isParameter,
    boolean isField,
    boolean isFinal
) {}
```

### 2. Enhanced SimilarityResult

**Current:**
```java
record SimilarityResult(
    double overallScore,
    double lcsScore,
    double levenshteinScore,
    double structuralScore,
    int tokens1Count,
    int tokens2Count
) {}
```

**Enhanced:**
```java
record SimilarityResult(
    // Similarity metrics
    double overallScore,
    double lcsScore,
    double levenshteinScore,
    double structuralScore,
    int tokens1Count,
    int tokens2Count,
    
    // NEW: Variation analysis for refactoring
    VariationAnalysis variations,            // What differs between blocks
    TypeCompatibility typeCompatibility,     // Are types compatible for extraction?
    RefactoringFeasibility feasibility       // Can this be auto-refactored?
) {}

record VariationAnalysis(
    List<Variation> literalVariations,       // Different string/int literals
    List<Variation> variableVariations,      // Different variable names
    List<Variation> methodCallVariations,    // Different method calls
    List<Variation> typeVariations,          // Different types used
    boolean hasControlFlowDifferences        // Different if/for/while logic
) {}

record Variation(
    VariationType type,                      // LITERAL, VARIABLE, METHOD_CALL, TYPE
    int position,                            // Token position in sequence
    String value1,                           // Value in first sequence
    String value2,                           // Value in second sequence
    String inferredType,                     // Type of this variation
    boolean canParameterize                  // Can be extracted as parameter?
) {}

record TypeCompatibility(
    boolean allVariationsTypeSafe,           // All variations have same type
    Map<String, String> parameterTypes,      // Inferred parameter types
    String inferredReturnType,               // Inferred return type
    List<String> warnings                    // Type safety warnings
) {}

record RefactoringFeasibility(
    boolean canExtractMethod,                // Feasible to extract method
    boolean canExtractToBeforeEach,          // For test setup
    boolean requiresManualReview,            // Too complex for auto-refactor
    List<String> blockers,                   // Why auto-refactor won't work
    RefactoringStrategy suggestedStrategy    // Recommended approach
) {}

enum RefactoringStrategy {
    EXTRACT_HELPER_METHOD,
    EXTRACT_TO_BEFORE_EACH,
    EXTRACT_TO_UTILITY_CLASS,
    EXTRACT_TO_BASE_CLASS,
    MANUAL_REVIEW_REQUIRED
}
```

### 3. Enhanced Token with Original Context

**Current:**
```java
record Token(TokenType type, String value) {}
```

**Enhanced:**
```java
record Token(
    TokenType type, 
    String normalizedValue,
    
    // NEW: Original context
    String originalValue,                    // Before normalization
    String inferredType,                     // Type information
    Node originalASTNode,                    // Link back to AST
    int lineNumber,                          // Source line
    int columnNumber                         // Source column
) {}
```

---

## Enhanced Collectors and Analyzers

### 1. Variation Tracking During Normalization

```java
public class EnhancedTokenNormalizer {
    
    public NormalizationResult normalize(Statement stmt1, Statement stmt2) {
        List<Token> tokens1 = new ArrayList<>();
        List<Token> tokens2 = new ArrayList<>();
        List<Variation> variations = new ArrayList<>();
        
        // Parallel traversal to track differences
        ParallelVisitor visitor = new ParallelVisitor(tokens1, tokens2, variations);
        stmt1.accept(visitor, stmt2);
        
        return new NormalizationResult(tokens1, tokens2, variations);
    }
    
    private class ParallelVisitor extends VoidVisitorAdapter<Statement> {
        private List<Token> tokens1;
        private List<Token> tokens2;
        private List<Variation> variations;
        
        @Override
        public void visit(VariableDeclarationExpr n1, Statement stmt2) {
            if (stmt2 instanceof ExpressionStmt) {
                Expression expr2 = ((ExpressionStmt) stmt2).getExpression();
                if (expr2 instanceof VariableDeclarationExpr) {
                    VariableDeclarationExpr n2 = (VariableDeclarationExpr) expr2;
                    
                    // Compare types
                    String type1 = n1.getCommonType().asString();
                    String type2 = n2.getCommonType().asString();
                    
                    if (!type1.equals(type2)) {
                        variations.add(new Variation(
                            VariationType.TYPE,
                            tokens1.size(),
                            type1,
                            type2,
                            "Type",
                            false  // Can't parameterize type differences easily
                        ));
                    }
                    
                    // Compare variable names
                    for (int i = 0; i < n1.getVariables().size(); i++) {
                        String var1 = n1.getVariables().get(i).getNameAsString();
                        String var2 = n2.getVariables().get(i).getNameAsString();
                        
                        if (!var1.equals(var2)) {
                            variations.add(new Variation(
                                VariationType.VARIABLE,
                                tokens1.size(),
                                var1,
                                var2,
                                type1,
                                false  // Variable names don't become parameters
                            ));
                        }
                    }
                    
                    // Compare initializers
                    if (n1.getVariables().get(0).getInitializer().isPresent() &&
                        n2.getVariables().get(0).getInitializer().isPresent()) {
                        
                        Expression init1 = n1.getVariables().get(0).getInitializer().get();
                        Expression init2 = n2.getVariables().get(0).getInitializer().get();
                        
                        compareExpressions(init1, init2, type1);
                    }
                }
            }
            super.visit(n1, stmt2);
        }
        
        private void compareExpressions(Expression e1, Expression e2, String expectedType) {
            if (e1 instanceof StringLiteralExpr && e2 instanceof StringLiteralExpr) {
                String val1 = ((StringLiteralExpr) e1).getValue();
                String val2 = ((StringLiteralExpr) e2).getValue();
                
                if (!val1.equals(val2)) {
                    variations.add(new Variation(
                        VariationType.LITERAL,
                        tokens1.size(),
                        val1,
                        val2,
                        "String",
                        true  // CAN be parameterized!
                    ));
                }
            } else if (e1 instanceof IntegerLiteralExpr && e2 instanceof IntegerLiteralExpr) {
                String val1 = ((IntegerLiteralExpr) e1).getValue();
                String val2 = ((IntegerLiteralExpr) e2).getValue();
                
                if (!val1.equals(val2)) {
                    variations.add(new Variation(
                        VariationType.LITERAL,
                        tokens1.size(),
                        val1,
                        val2,
                        "int",
                        true  // CAN be parameterized!
                    ));
                }
            }
            // ... handle other expression types
        }
    }
}

record NormalizationResult(
    List<Token> tokens1,
    List<Token> tokens2,
    List<Variation> variations
) {}
```

### 2. Scope Analysis Collector

```java
public class ScopeAnalyzer {
    
    public ScopeContext analyzeScopeAt(Statement stmt, MethodDeclaration method) {
        List<VariableInfo> availableVariables = new ArrayList<>();
        
        // 1. Collect method parameters
        for (Parameter param : method.getParameters()) {
            availableVariables.add(new VariableInfo(
                param.getNameAsString(),
                param.getType().asString(),
                true,   // isParameter
                false,  // isField
                param.isFinal()
            ));
        }
        
        // 2. Collect local variables declared before this statement
        BlockStmt methodBody = method.getBody().orElse(null);
        if (methodBody != null) {
            List<Statement> stmts = methodBody.getStatements();
            int stmtIndex = stmts.indexOf(stmt);
            
            for (int i = 0; i < stmtIndex; i++) {
                Statement s = stmts.get(i);
                s.accept(new VoidVisitorAdapter<Void>() {
                    @Override
                    public void visit(VariableDeclarationExpr n, Void arg) {
                        for (VariableDeclarator var : n.getVariables()) {
                            availableVariables.add(new VariableInfo(
                                var.getNameAsString(),
                                var.getType().asString(),
                                false,  // isParameter
                                false,  // isField
                                n.getParentNode().map(p -> {
                                    return p.toString().contains("final");
                                }).orElse(false)
                            ));
                        }
                        super.visit(n, arg);
                    }
                }, null);
            }
        }
        
        // 3. Collect class fields
        ClassOrInterfaceDeclaration clazz = 
            method.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
        
        List<FieldDeclaration> fields = new ArrayList<>();
        List<MethodDeclaration> methods = new ArrayList<>();
        boolean isTestClass = false;
        
        if (clazz != null) {
            fields = clazz.getFields();
            methods = clazz.getMethods();
            isTestClass = clazz.getAnnotationByName("Test").isPresent() ||
                         clazz.getNameAsString().endsWith("Test");
            
            for (FieldDeclaration field : fields) {
                for (VariableDeclarator var : field.getVariables()) {
                    availableVariables.add(new VariableInfo(
                        var.getNameAsString(),
                        var.getType().asString(),
                        false,  // isParameter
                        true,   // isField
                        field.isFinal()
                    ));
                }
            }
        }
        
        List<String> annotations = method.getAnnotations().stream()
            .map(a -> a.getNameAsString())
            .toList();
        
        return new ScopeContext(
            availableVariables,
            fields,
            methods,
            isTestClass,
            annotations
        );
    }
}
```

### 3. Type Compatibility Analyzer

```java
public class TypeCompatibilityAnalyzer {
    
    public TypeCompatibility analyze(VariationAnalysis variations) {
        Map<String, String> parameterTypes = new HashMap<>();
        List<String> warnings = new ArrayList<>();
        boolean allTypeSafe = true;
        
        // Group variations by position to find consistent types
        Map<Integer, List<Variation>> byPosition = variations.literalVariations().stream()
            .collect(Collectors.groupingBy(Variation::position));
        
        for (Map.Entry<Integer, List<Variation>> entry : byPosition.entrySet()) {
            List<Variation> variationsAtPos = entry.getValue();
            
            // Check if all variations at this position have the same type
            Set<String> types = variationsAtPos.stream()
                .map(Variation::inferredType)
                .collect(Collectors.toSet());
            
            if (types.size() > 1) {
                warnings.add("Inconsistent types at position " + entry.getKey() + ": " + types);
                allTypeSafe = false;
            } else if (types.size() == 1) {
                parameterTypes.put("param" + entry.getKey(), types.iterator().next());
            }
        }
        
        // Infer return type from the sequence
        String returnType = inferReturnType(variations);
        
        return new TypeCompatibility(allTypeSafe, parameterTypes, returnType, warnings);
    }
    
    private String inferReturnType(VariationAnalysis variations) {
        // Check if sequences create and return a variable
        // Look for pattern: Type var = ...; return var;
        // This is a simplified heuristic
        return "void";  // Default
    }
}
```

---

## Updated Detection Flow

### Before (Current - Insufficient):

```
Parse → Extract Statements → Normalize → Calculate Similarity → Report
```

### After (Enhanced - Refactoring-Ready):

```
Parse → Extract Statements → 
  ↓
Normalize WITH Variation Tracking →
  ↓
Analyze Scope Context →
  ↓
Analyze Type Compatibility →
  ↓
Calculate Similarity + Feasibility →
  ↓
Enrich Results with Refactoring Metadata →
  ↓
Report
```

### Updated HybridDuplicationDetector

```java
public class EnhancedHybridDetector {
    
    private final EnhancedTokenNormalizer normalizer;
    private final ScopeAnalyzer scopeAnalyzer;
    private final TypeCompatibilityAnalyzer typeAnalyzer;
    private final RefactoringFeasibilityAnalyzer feasibilityAnalyzer;
    // ... existing similarity calculators
    
    public EnhancedSimilarityResult compare(
        StatementSequence seq1, 
        StatementSequence seq2
    ) {
        // 1. Normalize with variation tracking
        NormalizationResult normalization = normalizer.normalize(
            seq1.statements().get(0),
            seq2.statements().get(0)
        );
        
        // 2. Calculate similarity metrics (unchanged)
        double lcsScore = lcs.calculate(
            normalization.tokens1(), 
            normalization.tokens2()
        );
        double levScore = levenshtein.calculate(
            normalization.tokens1(), 
            normalization.tokens2()
        );
        double structScore = structural.calculate(
            seq1.statements().get(0),
            seq2.statements().get(0)
        );
        
        double combinedScore = 
            weights.lcsWeight() * lcsScore +
            weights.levenshteinWeight() * levScore +
            weights.structuralWeight() * structScore;
        
        // 3. NEW: Analyze variations for refactoring
        VariationAnalysis variations = new VariationAnalysis(
            normalization.variations().stream()
                .filter(v -> v.type() == VariationType.LITERAL)
                .toList(),
            normalization.variations().stream()
                .filter(v -> v.type() == VariationType.VARIABLE)
                .toList(),
            normalization.variations().stream()
                .filter(v -> v.type() == VariationType.METHOD_CALL)
                .toList(),
            normalization.variations().stream()
                .filter(v -> v.type() == VariationType.TYPE)
                .toList(),
            hasControlFlowDiff(seq1, seq2)
        );
        
        // 4. NEW: Check type compatibility
        TypeCompatibility compatibility = typeAnalyzer.analyze(variations);
        
        // 5. NEW: Determine refactoring feasibility
        RefactoringFeasibility feasibility = feasibilityAnalyzer.analyze(
            seq1, seq2, variations, compatibility
        );
        
        // 6. Return enriched result
        return new EnhancedSimilarityResult(
            combinedScore,
            lcsScore,
            levScore,
            structScore,
            normalization.tokens1().size(),
            normalization.tokens2().size(),
            variations,
            compatibility,
            feasibility
        );
    }
}
```

---

## Updated DuplicateCluster

```java
record EnhancedDuplicateCluster(
    StatementSequence primary,
    List<SimilarityPair> duplicates,
    
    // NEW: Aggregated refactoring metadata
    RefactoringRecommendation recommendation,
    int estimatedLOCReduction,
    RefactoringComplexity complexity
) {}

record RefactoringRecommendation(
    RefactoringStrategy strategy,
    String suggestedMethodName,
    List<ParameterSpec> suggestedParameters,
    String suggestedReturnType,
    String targetLocation,                    // Where to place extracted method
    double confidenceScore                    // 0.0 - 1.0
) {}

record ParameterSpec(
    String name,
    String type,
    String defaultValue,                      // For optional params
    List<String> exampleValues                // From different duplicates
) {}

enum RefactoringComplexity {
    SIMPLE,           // High confidence auto-refactor
    MODERATE,         // Needs user review
    COMPLEX           // Manual intervention required
}
```

---

## Implementation Checklist

### Phase 1 Updates Required:

- [ ] Update `StatementSequence` to capture parent AST nodes, compilation unit, source path
- [ ] Update `Token` to preserve original values and AST node references
- [ ] Implement `EnhancedTokenNormalizer` with parallel variation tracking
- [ ] Implement `ScopeAnalyzer` to capture available variables/fields
- [ ] Implement `TypeCompatibilityAnalyzer` to validate parameter types
- [ ] Implement `RefactoringFeasibilityAnalyzer` to determine auto-refactor viability
- [ ] Update `SimilarityResult` to include variation analysis and feasibility
- [ ] Update `DuplicateCluster` to include refactoring recommendations
- [ ] Preserve original source text for each sequence
- [ ] Track import statements in compilation unit

---

## Benefits of Enhanced Data Collection

✅ **Enables automated refactoring** without re-parsing files  
✅ **Type-safe parameter extraction** with validated signatures  
✅ **Scope-aware refactoring** (knows what variables are available)  
✅ **Preserves formatting** through original source text  
✅ **Detailed feasibility analysis** (auto vs. manual refactoring)  
✅ **Better error prevention** through pre-validation  
✅ **Richer reporting** with concrete refactoring suggestions  

---

## Performance Impact

**Additional overhead:**
- Variation tracking: +10-15% during normalization
- Scope analysis: +5-10% per sequence
- Type analysis: +5% per cluster

**Total impact:** ~20-30% slower Phase 1, but **essential** for Phase 2

**Mitigation:**
- Lazy evaluation (only analyze clusters above threshold)
- Caching of scope contexts per method
- Parallel processing of independent analyses

---

## Conclusion

The current Phase 1 design is **optimized for reporting** but **insufficient for refactoring**. These enhancements are **mandatory** to enable Phase 2's automated code transformation capabilities. The additional data collection overhead (~25%) is justified by enabling fully automated refactoring in Phase 2.
