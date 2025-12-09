# Duplication Detector - Class and Interface Design

**Version**: 1.0  
**Date**: December 9, 2025  
**Status**: Ready for Implementation

---

## Table of Contents

1. [Package Structure](#1-package-structure)
2. [Core Interfaces](#2-core-interfaces)
3. [Data Structures](#3-data-structures)
4. [Detection Components](#4-detection-components)
5. [Analysis Components](#5-analysis-components)
6. [Refactoring Components](#6-refactoring-components)
7. [Configuration](#7-configuration)
8. [Utilities](#8-utilities)
9. [Main Entry Points](#9-main-entry-points)

---

## 1. Package Structure (Consolidated)

```
sa.com.cloudsolutions.antikythera.duplication/
├── core/
│   ├── DuplicationAnalyzer.java          (Main orchestrator)
│   ├── DuplicationReport.java            (Results)
│   ├── ContextManager.java               (Manages shared FileContext/MethodContext)
│   └── PerformanceMetrics.java           (Optional: memory/time tracking)
│
├── model/
│   ├── Token.java
│   ├── StatementSequence.java
│   ├── FileContext.java
│   ├── MethodContext.java
│   ├── ScopeContext.java
│   ├── SimilarityResult.java
│   ├── VariationAnalysis.java
│   ├── DuplicateCluster.java
│   ├── RefactoringRecommendation.java
│   ├── NormalizationResult.java          (tokens + variations)
│   └── Enums.java                        (TokenType, VariationType, RefactoringStrategyType)
│
├── detection/
│   ├── StatementExtractor.java           (Extract sequences)
│   ├── TokenNormalizer.java              (Normalize + track variations)
│   ├── ScopeAnalyzer.java                (Analyze variable scope)
│   ├── SimilarityCalculator.java         (LCS, Levenshtein, Structural combined)
│   ├── PreFilterChain.java               (Size + Structural + optional LSH)
│   └── StructuralSignature.java          (Control flow patterns)
│
├── analysis/
│   ├── VariationTracker.java             (Track differences)
│   ├── TypeCompatibilityAnalyzer.java    (Type safety)
│   ├── RefactoringFeasibilityAnalyzer.java (Can refactor?)
│   ├── DuplicateClusterer.java           (Group similar sequences)
│   └── ParameterExtractor.java           (Infer method signatures from variations)
│
├── refactoring/                          (Phase 2)
│   ├── RefactoringEngine.java            (Orchestrator)
│   ├── ExtractMethodRefactorer.java      (Combine all extract strategies)
│   ├── RefactoringSafetyValidator.java   (Pre/post checks)
│   └── InteractiveReviewer.java          (CLI review workflow)
│
├── config/
│   ├── DuplicationConfig.java            (Main config + presets)
│   └── RefactoringConfig.java            (Phase 2 config)
│
├── report/
│   ├── ReportGenerator.java              (Text + JSON in one class)
│   └── ReportFormatter.java              (Shared utilities)
│
└── cli/
    ├── DuplicationDetectorCLI.java       (Main entry)
    └── CommandLineParser.java            (Arg parsing)
```

**Key Changes from Original**:
- **9 packages → 5 packages** (core, model, detection, analysis, refactoring)
- **Merged packages**: extraction + normalization + similarity + filtering = `detection/`
- **Removed excessive interfaces**: Keep code simple, add interfaces later if needed
- **Combined related classes**: SimilarityCalculator has all 3 metrics in one class
- **Simpler hierarchy**: Easier to navigate and understand

---

## 2. Core Interfaces (Minimal - Add More Later If Needed)

Most classes will be concrete implementations. Interfaces only where we genuinely need pluggability.

### 2.1 Key Design Decision

**Philosophy**: Start with concrete classes, extract interfaces later when you actually need them. Premature abstraction is a form of over-engineering.

**Where we might add interfaces later** (Phase 2+):
- Custom similarity metrics (if users want their own algorithms)
- Custom refactoring strategies (if patterns emerge beyond our 3 core strategies)
- Report formats beyond Text/JSON

### 2.2 The One Interface We Actually Need (Now)

```java
package sa.com.cloudsolutions.antikythera.duplication.detection;

/**
 * Optional interface if we want pluggable similarity strategies in future.
 * For MVP: SimilarityCalculator is just a concrete class with 3 methods.
 */
public interface ISimilarityCalculator {
    double calculateLCS(List<Token> t1, List<Token> t2);
    double calculateLevenshtein(List<Token> t1, List<Token> t2);
    double calculateStructural(List<Statement> s1, List<Statement> s2);
}
```

**Reality Check**: We probably don't even need this interface. Just make `SimilarityCalculator` a concrete class with these methods. Keep it simple.

---

## 3. Data Structures

### 3.1 Core Records

```java
package sa.com.cloudsolutions.antikythera.duplication.model;

// Lightweight token with references, not heavy objects
public record Token(
    TokenType type,
    String normalizedValue,
    String originalValue,
    String inferredType,
    int astNodeId,           // Lightweight ID
    int fileContextId,       // Reference to FileContext
    int lineNumber,
    int columnNumber
) {}

// Optimized sequence with context references
public record StatementSequence(
    List<Statement> statements,
    Range range,
    String methodName,
    int startOffset,
    int fileContextId,       // Resolve to FileContext
    int methodContextId      // Resolve to MethodContext
) {}

// Shared file context (ONE per file)
public record FileContext(
    int id,
    Path sourceFilePath,
    CompilationUnit compilationUnit,
    List<String> imports,
    String originalFileContent,
    Map<Integer, Node> astNodeCache  // Lazy-loaded
) {}

// Shared method context (ONE per method)
public record MethodContext(
    int id,
    int fileContextId,
    MethodDeclaration declaration,
    ClassOrInterfaceDeclaration containingClass,
    ScopeContext scopeContext
) {}

// Scope information
public record ScopeContext(
    List<VariableInfo> availableVariables,
    List<FieldDeclaration> classFields,
    List<String> staticImports,
    boolean isInTestClass,
    List<String> annotations
) {}

public record VariableInfo(
    String name,
    String type,
    boolean isParameter,
    boolean isField,
    boolean isFinal
) {}
```

### 3.2 Analysis Results

```java
// Similarity comparison result
public record SimilarityResult(
    double overallScore,
    double lcsScore,
    double levenshteinScore,
    double structuralScore,
    int tokens1Count,
    int tokens2Count,
    VariationAnalysis variations,
    TypeCompatibility typeCompatibility,
    RefactoringFeasibility feasibility
) {}

// Tracked differences (alignment-based)
public record VariationAnalysis(
    List<Variation> literalVariations,
    List<Variation> variableVariations,
    List<Variation> methodCallVariations,
    List<Variation> typeVariations,
    boolean hasControlFlowDifferences
) {}

public record Variation(
    VariationType type,
    int alignedIndex1,      // Aligned position in seq1
    int alignedIndex2,      // Aligned position in seq2
    String value1,
    String value2,
    String inferredType,
    boolean canParameterize
) {}

// Type compatibility assessment
public record TypeCompatibility(
    boolean allVariationsTypeSafe,
    Map<String, String> parameterTypes,
    String inferredReturnType,
    List<String> warnings
) {}

// Refactoring feasibility
public record RefactoringFeasibility(
    boolean canExtractMethod,
    boolean canExtractToBeforeEach,
    boolean canExtractToParameterizedTest,
    boolean requiresManualReview,
    List<String> blockers,
    RefactoringStrategy suggestedStrategy
) {}
```

### 3.3 Clustering and Recommendations

```java
// Group of similar sequences
public record DuplicateCluster(
    StatementSequence primary,
    List<SimilarityPair> duplicates,
    RefactoringRecommendation recommendation,
    int estimatedLOCReduction,
    double avgSimilarity
) {}

public record SimilarityPair(
    StatementSequence seq1,
    StatementSequence seq2,
    SimilarityResult similarity
) {}

// Refactoring suggestion
public record RefactoringRecommendation(
    RefactoringStrategyType strategy,
    String suggestedMethodName,
    List<ParameterSpec> suggestedParameters,
    String suggestedReturnType,
    String targetLocation,
    double confidenceScore
) {}

public record ParameterSpec(
    String name,
    String type,
    List<String> exampleValues
) {}
```

---

## 4. Detection Components

### 4.1 DuplicationAnalyzer (Main Orchestrator)

```java
package sa.com.cloudsolutions.antikythera.duplication.core;

/**
 * Main entry point for duplication detection analysis.
 * Orchestrates the entire detection pipeline.
 */
public class DuplicationAnalyzer {
    private final DuplicationConfig config;
    private final StatementExtractor extractor;
    private final EnhancedTokenNormalizer normalizer;
    private final FilterChain preFilters;
    private final HybridSimilarityDetector detector;
    private final DuplicateClusterer clusterer;
    private final ContextManager contextManager;
    
    public DuplicationAnalyzer(DuplicationConfig config) {
        this.config = config;
        this.extractor = new StatementExtractor(config.minLines());
        this.normalizer = new EnhancedTokenNormalizer();
        this.preFilters = FilterChain.builder()
            .add(new SizeFilter(0.30))
            .add(new StructuralFilter(0.50))
            .build();
        this.detector = new HybridSimilarityDetector(config.weights());
        this.clusterer = new DuplicateClusterer(config.threshold());
        this.contextManager = new ContextManager();
    }
    
    /**
     * Analyze a single file for duplicates.
     */
    public DuplicationReport analyzeFile(Path sourceFile) {
        // Implementation
    }
    
    /**
     * Analyze entire project for duplicates.
     */
    public DuplicationReport analyzeProject(Path projectRoot) {
        // Implementation
    }
    
    /**
     * Core detection pipeline for a single file.
     */
    private List<DuplicateCluster> detectDuplicates(CompilationUnit cu, Path filePath) {
        // 1. Extract sequences
        List<StatementSequence> sequences = extractor.extract(cu, filePath);
        
        // 2. Generate candidate pairs (with pre-filtering)
        List<SequencePair> candidates = generateCandidates(sequences);
        
        // 3. Calculate similarities (parallel)
        List<SimilarityResult> results = candidates.parallelStream()
            .map(pair -> detector.compare(pair.seq1(), pair.seq2()))
            .filter(result -> result.overallScore() > config.threshold())
            .toList();
        
        // 4. Cluster duplicates
        return clusterer.cluster(results);
    }
    
    private List<SequencePair> generateCandidates(List<StatementSequence> sequences) {
        List<SequencePair> candidates = new ArrayList<>();
        for (int i = 0; i < sequences.size(); i++) {
            for (int j = i + 1; j < sequences.size(); j++) {
                StatementSequence seq1 = sequences.get(i);
                StatementSequence seq2 = sequences.get(j);
                
                // Apply pre-filters
                if (preFilters.shouldCompare(seq1, seq2)) {
                    candidates.add(new SequencePair(seq1, seq2));
                }
            }
        }
        return candidates;
    }
}
```

### 4.2 StatementExtractor

```java
package sa.com.cloudsolutions.antikythera.duplication.extraction;

/**
 * Extracts statement sequences from compilation units using sliding window.
 */
public class StatementExtractor {
    private final int minLines;
    private final ScopeAnalyzer scopeAnalyzer;
    private final StructuralSignatureExtractor structureExtractor;
    
    public StatementExtractor(int minLines) {
        this.minLines = minLines;
        this.scopeAnalyzer = new ScopeAnalyzer();
        this.structureExtractor = new StructuralSignatureExtractor();
    }
    
    /**
     * Extract all statement sequences from a compilation unit.
     */
    public List<StatementSequence> extract(CompilationUnit cu, Path filePath) {
        List<StatementSequence> sequences = new ArrayList<>();
        
        // Create FileContext (shared)
        FileContext fileContext = createFileContext(cu, filePath);
        
        // Visit all methods
        cu.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(MethodDeclaration method, Void arg) {
                sequences.addAll(extractFrom Method(method, fileContext));
                super.visit(method, arg);
            }
        }, null);
        
        return sequences;
    }
    
    private List<StatementSequence> extractFromMethod(
        MethodDeclaration method, 
        FileContext fileContext
    ) {
        // Create MethodContext (shared)
        ScopeContext scope = scopeAnalyzer.analyze(method);
        MethodContext methodContext = createMethodContext(method, fileContext, scope);
        
        // Sliding window extraction
        BlockStmt body = method.getBody().orElse(null);
        if (body == null) return List.of();
        
        List<Statement> statements = body.getStatements();
        List<StatementSequence> sequences = new ArrayList<>();
        
        for (int i = 0; i <= statements.size() - minLines; i++) {
            for (int windowSize = minLines; i + windowSize <= statements.size(); windowSize++) {
                List<Statement> window = statements.subList(i, i + windowSize);
                
                StatementSequence seq = new StatementSequence(
                    window,
                    Range.range(window.get(0).getRange().get(), window.get(windowSize-1).getRange().get()),
                    method.getNameAsString(),
                    i,
                    fileContext.id(),
                    methodContext.id()
                );
                
                sequences.add(seq);
            }
        }
        
        return sequences;
    }
}
```

### 4.3 HybridSimilarityDetector

```java
package sa.com.cloudsolutions.antikythera.duplication.similarity;

/**
 * Combines LCS, Levenshtein, and Structural similarity metrics.
 */
public class HybridSimilarityDetector {
    private final SimilarityWeights weights;
    private final LCSSimilarity lcs;
    private final LevenshteinSimilarity levenshtein;
    private final StructuralSimilarity structural;
    private final EnhancedTokenNormalizer normalizer;
    private final VariationTracker variationTracker;
    private final TypeCompatibilityAnalyzer typeAnalyzer;
    private final RefactoringFeasibilityAnalyzer feasibilityAnalyzer;
    
    public HybridSimilarityDetector(SimilarityWeights weights) {
        this.weights = weights;
        this.lcs = new LCSSimilarity();
        this.levenshtein = new LevenshteinSimilarity();
        this.structural = new StructuralSimilarity();
        this.normalizer = new EnhancedTokenNormalizer();
        this.variationTracker = new VariationTracker();
        this.typeAnalyzer = new TypeCompatibilityAnalyzer();
        this.feasibilityAnalyzer = new RefactoringFeasibilityAnalyzer();
    }
    
    /**
     * Compare two statement sequences and return enriched similarity result.
     */
    public SimilarityResult compare(StatementSequence seq1, StatementSequence seq2) {
        // 1. Normalize with variation tracking
        NormalizationResult normalized = normalizer.normalizeWithVariations(
            seq1.statements(),
            seq2.statements(),
            seq1.fileContextId(),
            seq2.fileContextId()
        );
        
        // 2. Calculate similarity metrics
        double lcsScore = lcs.calculate(normalized.tokens1(), normalized.tokens2());
        double levScore = levenshtein.calculate(normalized.tokens1(), normalized.tokens2());
        double structScore = structural.calculate(seq1.statements(), seq2.statements());
        
        double overall = weights.lcsWeight() * lcsScore +
                        weights.levenshteinWeight() * levScore +
                        weights.structuralWeight() * structScore;
        
        // 3. Analyze variations
        VariationAnalysis variations = variationTracker.analyze(normalized.variations());
        
        // 4. Check type compatibility
        TypeCompatibility typeCompat = typeAnalyzer.analyze(variations);
        
        // 5. Determine refactoring feasibility
        RefactoringFeasibility feasibility = feasibilityAnalyzer.analyze(
            seq1, seq2, variations, typeCompat
        );
        
        return new SimilarityResult(
            overall, lcsScore, levScore, structScore,
            normalized.tokens1().size(), normalized.tokens2().size(),
            variations, typeCompat, feasibility
        );
    }
}
```

---

## 5. Analysis Components

### 5.1 ScopeAnalyzer

```java
package sa.com.cloudsolutions.antikythera.duplication.extraction;

/**
 * Analyzes variable scope for statement sequences.
 */
public class ScopeAnalyzer {
    /**
     * Analyze scope context for a method.
     */
    public ScopeContext analyze(MethodDeclaration method) {
        List<VariableInfo> variables = new ArrayList<>();
        
        // Collect method parameters
        variables.addAll(collectParameters(method));
        
        // Collect local variables
        variables.addAll(collectLocalVariables(method));
        
        // Collect class fields
        ClassOrInterfaceDeclaration clazz = method.findAncestor(
            ClassOrInterfaceDeclaration.class
        ).orElse(null);
        
        List<FieldDeclaration> fields = clazz != null ? clazz.getFields() : List.of();
        List<String> staticImports = collectStaticImports(clazz);
        boolean isTestClass = isTestClass(clazz);
        List<String> annotations = collectAnnotations(method);
        
        return new ScopeContext(variables, fields, staticImports, isTestClass, annotations);
    }
    
    private boolean isTestClass(ClassOrInterfaceDeclaration clazz) {
        if (clazz == null) return false;
        return clazz.getNameAsString().endsWith("Test") ||
               clazz.getAnnotationByName("TestInstance").isPresent();
    }
    
    // Additional helper methods...
}
```

### 5.2 TypeCompatibilityAnalyzer

```java
package sa.com.cloudsolutions.antikythera.duplication.analysis;

/**
 * Analyzes type compatibility of variations for parameter extraction.
 */
public class TypeCompatibilityAnalyzer {
    public TypeCompatibility analyze(VariationAnalysis variations) {
        Map<String, String> parameterTypes = new HashMap<>();
        List<String> warnings = new ArrayList<>();
        boolean allTypeSafe = true;
        
        // Group variations by aligned position
        Map<Integer, List<Variation>> byPosition = groupByPosition(variations);
        
        for (Map.Entry<Integer, List<Variation>> entry : byPosition.entrySet()) {
            // Check if all variations at this position have same type
            Set<String> types = entry.getValue().stream()
                .map(Variation::inferredType)
                .collect(Collectors.toSet());
            
            if (types.size() > 1) {
                warnings.add("Inconsistent types at position " + entry.getKey());
                allTypeSafe = false;
            } else if (types.size() == 1) {
                parameterTypes.put("param" + entry.getKey(), types.iterator().next());
            }
        }
        
        String returnType = inferReturnType(variations);
        
        return new TypeCompatibility(allTypeSafe, parameterTypes, returnType, warnings);
    }
}
```

### 5.3 DuplicateClusterer

```java
package sa.com.cloudsolutions.antikythera.duplication.analysis;

/**
 * Groups similar sequences into clusters.
 */
public class DuplicateClusterer {
    private final double threshold;
    
    public DuplicateClusterer(double threshold) {
        this.threshold = threshold;
    }
    
    /**
     * Cluster similarity results into duplicate groups.
     */
    public List<DuplicateCluster> cluster(List<SimilarityResult> results) {
        // Use union-find or graph-based clustering
        Map<StatementSequence, Set<SimilarityPair>> clusters = new HashMap<>();
        
        for (SimilarityResult result : results) {
            // Group sequences that are similar to each other
            // Handle transitivity: if A~B and B~C, then A,B,C in same cluster
        }
        
        // Convert to DuplicateCluster records with recommendations
        return clusters.entrySet().stream()
            .map(this::createCluster)
            .sorted(Comparator.comparingDouble(DuplicateCluster::avgSimilarity).reversed())
            .toList();
    }
    
    private DuplicateCluster createCluster(Map.Entry entry) {
        // Generate refactoring recommendation
        // Calculate LOC reduction
        // Determine strategy
    }
}
```

---

## 6. Refactoring Components (Phase 2)

### 6.1 RefactoringEngine

```java
package sa.com.cloudsolutions.antikythera.duplication.refactoring;

/**
 * Main orchestrator for Phase 2 automated refactoring.
 */
public class RefactoringEngine {
    private final Map<RefactoringStrategyType, RefactoringStrategy> strategies;
    private final RefactoringSafetyValidator validator;
    private final ContextManager contextManager;
    
    public RefactoringEngine(RefactoringConfig config, ContextManager contextManager) {
        this.contextManager = contextManager;
        this.validator = new RefactoringSafetyValidator();
        this.strategies = Map.of(
            RefactoringStrategyType.EXTRACT_METHOD, new ExtractMethodStrategy(),
            RefactoringStrategyType.EXTRACT_TO_BEFORE_EACH, new ExtractToBeforeEachStrategy(),
            RefactoringStrategyType.EXTRACT_TO_PARAMETERIZED_TEST, new ExtractToParameterizedTestStrategy()
        );
    }
    
    /**
     * Apply refactoring to a cluster.
     */
    public RefactoringResult applyRefactoring(
        DuplicateCluster cluster,
        RefactoringConfig config
    ) {
        // 1. Select strategy
        RefactoringStrategy strategy = selectStrategy(cluster);
        
        // 2. Pre-validation
        ValidationResult validation = validator.validate(cluster, strategy);
        if (!validation.isValid()) {
            return RefactoringResult.failed(validation.errors());
        }
        
        // 3. Create backup
        if (config.createBackup()) {
            createBackup(cluster);
        }
        
        // 4. Apply transformation
        try {
            RefactoringResult result = strategy.apply(cluster, config);
            
            // 5. Post-verification
            if (config.verifyCompilation()) {
                if (!compiles()) {
                    rollback(cluster);
                    return RefactoringResult.failed("Compilation failed");
                }
            }
            
            if (config.runTests()) {
                if (!testsPass()) {
                    rollback(cluster);
                    return RefactoringResult.failed("Tests failed");
                }
            }
            
            return result;
            
        } catch (Exception e) {
            rollback(cluster);
            return RefactoringResult.failed(e.getMessage());
        }
    }
}
```

### 6.2 ExtractMethodStrategy

```java
package sa.com.cloudsolutions.antikythera.duplication.refactoring;

/**
 * Extracts duplicate code into a helper method.
 */
public class ExtractMethodStrategy implements RefactoringStrategy {
    private final ParameterExtractor paramExtractor;
    
    public ExtractMethodStrategy() {
        this.paramExtractor = new ParameterExtractor();
    }
    
    @Override
    public boolean isApplicable(DuplicateCluster cluster) {
        return cluster.recommendation().strategy() == RefactoringStrategyType.EXTRACT_METHOD;
    }
    
    @Override
    public RefactoringResult apply(DuplicateCluster cluster, RefactoringConfig config) {
        // 1. Extract method signature from variations
        MethodSignature signature = paramExtractor.extract(cluster);
        
        // 2. Create extracted method
        MethodDeclaration extractedMethod = createMethod(cluster.primary(), signature);
        
        // 3. Insert method in target class
        ClassOrInterfaceDeclaration targetClass = findTargetClass(cluster);
        targetClass.addMember(extractedMethod);
        
        // 4. Replace all occurrences with method calls
        for (SimilarityPair pair : cluster.duplicates()) {
            replaceWithMethodCall(pair.seq1(), signature);
            replaceWithMethodCall(pair.seq2(), signature);
        }
        
        // 5. Add necessary imports
        addRequiredImports(targetClass, signature);
        
        return RefactoringResult.success();
    }
    
    @Override
    public RefactoringPreview preview(DuplicateCluster cluster) {
        // Generate before/after code for user review
    }
}
```

---

## 7. Configuration

### 7.1 Configuration Classes

```java
package sa.com.cloudsolutions.antikythera.duplication.config;

/**
 * Main configuration for duplication detection.
 */
public record DuplicationConfig(
    int minLines,
    double threshold,
    SimilarityWeights weights,
    boolean includeTests,
    boolean includeSources,
    List<String> excludePatterns
) {
    public static DuplicationConfig strict() {
        return new DuplicationConfig(5, 0.90, SimilarityWeights.balanced(), true, true, List.of());
    }
    
    public static DuplicationConfig moderate() {
        return new DuplicationConfig(4, 0.75, SimilarityWeights.balanced(), true, true,
            List.of("**/generated/**", "**/target/**"));
    }
    
    public static DuplicationConfig aggressive() {
        return new DuplicationConfig(3, 0.60, SimilarityWeights.structureFocused(), true, true,
            List.of("**/generated/**", "**/target/**"));
    }
}

/**
 * Weights for similarity metrics.
 */
public record SimilarityWeights(
    double lcsWeight,
    double levenshteinWeight,
    double structuralWeight
) {
    public static SimilarityWeights balanced() {
        return new SimilarityWeights(0.40, 0.40, 0.20);
    }
    
    public static SimilarityWeights structureFocused() {
        return new SimilarityWeights(0.35, 0.30, 0.35);
    }
}

/**
 * Configuration for Phase 2 refactoring.
 */
public record RefactoringConfig(
    boolean interactive,
    boolean runTests,
    boolean verifyCompilation,
    double autoApplyThreshold,
    boolean createBackup,
    boolean extractToBeforeEach,
    boolean extractToParameterizedTest,
    int maxParameters
) {
    public static RefactoringConfig safe() {
        return new RefactoringConfig(true, true, true, 0.98, true, true, true, 5);
    }
}
```

---

## 8. Utilities

### 8.1 ContextManager

```java
package sa.com.cloudsolutions.antikythera.duplication.util;

/**
 * Manages FileContext and MethodContext instances for memory efficiency.
 * Ensures one context per file/method regardless of how many sequences reference it.
 */
public class ContextManager {
    private final Map<Path, FileContext> fileContexts = new ConcurrentHashMap<>();
    private final Map<String, MethodContext> methodContexts = new ConcurrentHashMap<>();
    private final AtomicInteger fileIdCounter = new AtomicInteger(0);
    private final AtomicInteger methodIdCounter = new AtomicInteger(0);
    
    /**
     * Get or create FileContext for a file.
     */
    public FileContext getOrCreateFileContext(Path filePath, CompilationUnit cu) {
        return fileContexts.computeIfAbsent(filePath, path -> {
            int id = fileIdCounter.getAndIncrement();
            List<String> imports = extractImports(cu);
            String content = readFileContent(path);
            return new FileContext(id, path, cu, imports, content, new HashMap<>());
        });
    }
    
    /**
     * Get or create MethodContext for a method.
     */
    public MethodContext getOrCreateMethodContext(
        MethodDeclaration method,
        FileContext fileContext,
        ScopeContext scope
    ) {
        String key = fileContext.id() + ":" + method.getNameAsString() + ":" + method.getBegin();
        return methodContexts.computeIfAbsent(key, k -> {
            int id = methodIdCounter.getAndIncrement();
            ClassOrInterfaceDeclaration clazz = method.findAncestor(
                ClassOrInterfaceDeclaration.class
            ).orElse(null);
            return new MethodContext(id, fileContext.id(), method, clazz, scope);
        });
    }
    
    /**
     * Clear all cached contexts (e.g., after analysis complete).
     */
    public void clear() {
        fileContexts.clear();
        methodContexts.clear();
    }
}
```

---

## 9. Main Entry Points

### 9.1 CLI Entry Point

```java
package sa.com.cloudsolutions.antikythera.duplication.cli;

/**
 * Main CLI entry point.
 */
public class DuplicationDetectorCLI {
    public static void main(String[] args) {
        CommandLineParser parser = new CommandLineParser();
        CLIOptions options = parser.parse(args);
        
        if (options.command().equals("detect")) {
            runDetection(options);
        } else if (options.command().equals("refactor")) {
            runRefactoring(options);
        }
    }
    
    private static void runDetection(CLIOptions options) {
        DuplicationConfig config = loadConfig(options);
        DuplicationAnalyzer analyzer = new DuplicationAnalyzer(config);
        DuplicationReport report = analyzer.analyzeProject(options.projectPath());
        
        // Generate report
        ReportGenerator generator = options.format().equals("json") 
            ? new JsonReportGenerator()
            : new TextReportGenerator();
        
        String output = generator.generate(report);
        
        if (options.outputFile() != null) {
            Files.writeString(options.outputFile(), output);
        } else {
            System.out.println(output);
        }
        
        // Exit code for CI/CD
        System.exit(report.clusters().isEmpty() ? 0 : 1);
    }
    
    private static void runRefactoring(CLIOptions options) {
        // Phase 2 implementation
    }
}
```

---

## Summary

This class design provides:

✅ **Consolidated package structure** - 5 focused packages instead of 9 fragmented ones  
✅ **Pragmatic approach** - Concrete classes first, interfaces only when needed  
✅ **Lightweight data structures** - IDs and shared contexts (10-100x memory savings)  
✅ **Combined functionality** - Related components grouped together (detection, analysis)  
✅ **Memory-efficient design** - ContextManager prevents duplication across sequences  
✅ **Thread-safe parallelization** - Immutable records enable safe concurrent processing  
✅ **Phase 1 foundation** - Ready for Phase 2 extension without over-engineering  

**Philosophy**: Keep it simple. Add abstractions when you need them, not before.

**Next Steps**: Implement incrementally, starting with:
1. Core data structures (model package)
2. Detection pipeline (DuplicationAnalyzer → StatementExtractor → SimilarityCalculator)
3. Analysis components (VariationTracker → TypeCompatibilityAnalyzer)
4. Output generation (ReportGenerator)

