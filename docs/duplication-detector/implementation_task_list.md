# Duplication Detector - Implementation Task List

**Version**: 1.0  
**Date**: December 10, 2025  
**Purpose**: Hierarchical task breakdown for Phase 1 MVP implementation  
**Scope**: Enhanced Detection with Refactoring Metadata

---

## Overview

This task list builds the duplication detector in dependency order, starting with foundational data structures and building up to the complete detection pipeline.

**Target**: Working CLI tool that detects duplicates and generates refactoring recommendations

---

## Phase 1: Foundation - Data Structures & Models

### 1.1 Core Model Records
- [ ] Create `model/` package under `com.raditha.dedup`
- [ ] Implement `TokenType` enum (VAR, METHOD_CALL, LITERAL, TYPE, CONTROL_FLOW, etc.)
- [ ] Implement `Token` record (type, normalizedValue, originalValue, inferredType, line, column)
- [ ] Implement `Range` record (startLine, endLine, startColumn, endColumn)
- [ ] Implement `StatementSequence` record with JavaParser references
  - Direct references: CompilationUnit, MethodDeclaration, Path
  - Helper methods: getContainingClass(), getImports(), isInTestClass()
- [ ] Write unit tests for StatementSequence helper methods

### 1.2 Analysis Result Records
- [ ] Implement `VariationType` enum (LITERAL, VARIABLE, METHOD_CALL, TYPE)
- [ ] Implement `Variation` record (type, alignedIndex1/2, value1/2, inferredType)
- [ ] Implement `VariationAnalysis` record (literalVariations, variableVariations, etc.)
- [ ] Implement `TypeCompatibility` record (allTypeSafe, parameterTypes, warnings)
- [ ] Implement `SimilarityResult` record (scores, variations, typeCompatibility)
- [ ] Implement `RefactoringStrategy` enum (EXTRACT_METHOD, BEFORE_EACH, PARAMETERIZED_TEST, etc.)
- [ ] Implement `ParameterSpec` record (name, type, exampleValues)
- [ ] Implement `RefactoringRecommendation` record (strategy, methodName, parameters, etc.)
- [ ] Implement `SimilarityPair` record (seq1, seq2, similarityResult)
- [ ] Implement `DuplicateCluster` record (primary, duplicates, recommendation, LOC reduction)

### 1.3 Configuration
- [ ] Create `config/` package
- [ ] Implement `SimilarityWeights` record (lcsWeight, levenshteinWeight, structuralWeight)
  - Add `balanced()` factory method (0.40, 0.40, 0.20)
- [ ] Implement `DuplicationConfig` record (minLines, threshold, weights, includeTests, excludePatterns)
  - Add preset factory methods: `strict()`, `moderate()`, `aggressive()`, `testSetup()`
- [ ] Write unit tests for config presets

---

## Phase 2: Token Normalization & Variation Tracking

### 2.1 Token Normalizer
- [ ] Create `detection/` package under `com.raditha.dedup`
- [ ] Implement `TokenNormalizer` class
  - [ ] `normalizeStatement(Statement)` → List\<Token\>
  - [ ] Preserve method names in METHOD_CALL tokens
  - [ ] Preserve type names in TYPE tokens
  - [ ] Normalize variables to VAR
  - [ ] Normalize literals to STRING_LIT, INT_LIT, etc.
  - [ ] Preserve operators and control flow keywords
- [ ] Write comprehensive unit tests
  - [ ] Test method name preservation (setActive vs setDeleted)
  - [ ] Test variable normalization (userId, customerId → VAR)
  - [ ] Test literal normalization
  - [ ] Test type preservation

### 2.2 Variation Tracker
- [ ] Create `analysis/` package under `com.raditha.dedup`
- [ ] Implement `VariationTracker` class
  - [ ] `trackVariations(tokens1, tokens2)` → VariationAnalysis
  - [ ] Use LCS alignment to map token positions
  - [ ] Categorize differences (literal, variable, method call, type)
  - [ ] Track control flow differences
- [ ] Write unit tests
  - [ ] Test variation detection with aligned sequences
  - [ ] Test handling of gaps/insertions
  - [ ] Test categorization accuracy

---

## Phase 3: Similarity Algorithms

### 3.1 LCS Similarity
- [ ] Implement `LCSSimilarity` class in `detection/` package
  - [ ] `calculate(tokens1, tokens2)` → double (0.0-1.0)
  - [ ] Implement space-optimized DP algorithm
  - [ ] Return LCS length / max(len1, len2)
- [ ] Write unit tests
  - [ ] Test exact matches (score = 1.0)
  - [ ] Test with gaps/insertions
  - [ ] Test empty sequences

### 3.2 Levenshtein Similarity
- [ ] Implement `LevenshteinSimilarity` class
  - [ ] `calculate(tokens1, tokens2)` → double (0.0-1.0)
  - [ ] Implement space-optimized DP algorithm
  - [ ] Return 1.0 - (distance / max(len1, len2))
- [ ] Write unit tests
  - [ ] Test exact matches (score = 1.0)
  - [ ] Test single edits
  - [ ] Test completely different sequences

### 3.3 Structural Similarity
- [ ] Implement `StructuralSignature` record (controlFlowPatterns, annotations, maxNesting, methodCallCount, hasTryCatch)
- [ ] Implement `StructuralSimilarity` class
  - [ ] `extractSignature(StatementSequence)` → StructuralSignature
  - [ ] `calculate(seq1, seq2)` → double
  - [ ] Compute Jaccard similarity for patterns
  - [ ] Compare numeric metrics (depth, call count)
  - [ ] Use formula: 0.5×Jaccard + 0.3×depth + 0.2×calls
- [ ] Write unit tests
  - [ ] Test signature extraction
  - [ ] Test Jaccard calculation
  - [ ] Test structurally incompatible code

### 3.4 Combined Similarity Calculator
- [ ] Implement `SimilarityCalculator` class
  - [ ] Constructor: inject LCS, Levenshtein, Structural, VariationTracker
  - [ ] `compare(seq1, seq2)` → SimilarityResult
  - [ ] Normalize both sequences
  - [ ] Track variations
  - [ ] Calculate all three scores (parallel if possible)
  - [ ] Combine with configurable weights
- [ ] Write integration tests
  - [ ] Test real duplicate code examples
  - [ ] Test near-duplicates with variations
  - [ ] Test non-duplicates

---

## Phase 4: Statement Extraction

### 4.1 Statement Extractor
- [ ] Implement `StatementExtractor` class in `detection/`
  - [ ] Constructor: accept minLines parameter
  - [ ] `extract(CompilationUnit, Path)` → List\<StatementSequence\>
  - [ ] Use JavaParser VoidVisitorAdapter to visit methods
  - [ ] Implement sliding window over statements
  - [ ] Create StatementSequence with direct JavaParser references
- [ ] Write unit tests
  - [ ] Test with simple method (verify sequence count)
  - [ ] Test minimum line filtering
  - [ ] Test multiple methods in same class
  - [ ] Test empty methods

### 4.2 Generated Code Detection
- [ ] Add `isGeneratedFile(Path)` utility method
  - [ ] Check @Generated annotation
  - [ ] Check file header comments
  - [ ] Check path patterns (target/, generated/)
- [ ] Write unit tests for generated file detection

---

## Phase 5: Pre-Filtering for Performance

### 5.1 Size Filter
- [ ] Implement `SizeFilter` class in `detection/`
  - [ ] `filter(seq1, seq2)` → boolean (keep/skip)
  - [ ] Skip if size difference > 30%
- [ ] Write unit tests

### 5.2 Structural Pre-Filter
- [ ] Implement `StructuralPreFilter` class
  - [ ] `filter(sig1, sig2)` → boolean
  - [ ] Skip if Jaccard(patterns) < 0.5
- [ ] Write unit tests

### 5.3 Pre-Filter Chain
- [ ] Implement `PreFilterChain` class
  - [ ] Compose Size + Structural filters
  - [ ] `generateCandidates(sequences)` → List\<SequencePair\>
  - [ ] Apply filters in order
  - [ ] Track filtering statistics
- [ ] Write integration tests
  - [ ] Measure reduction percentage
  - [ ] Verify no false negatives

---

## Phase 6: Scope & Type Analysis

### 6.1 Scope Analyzer
- [ ] Implement `VariableInfo` record (name, type, isParameter, isField, isFinal)
- [ ] Implement `ScopeAnalyzer` class in `analysis/`
  - [ ] `getAvailableVariables(StatementSequence)` → List\<VariableInfo\>
  - [ ] Extract method parameters from MethodDeclaration
  - [ ] Extract local variables before sequence start
  - [ ] Extract class fields from containing class
  - [ ] Handle static imports
- [ ] Write unit tests
  - [ ] Test parameter extraction
  - [ ] Test local variable extraction
  - [ ] Test field extraction

### 6.2 Type Analyzer
- [ ] Implement `TypeAnalyzer` class
  - [ ] `analyzeTypeCompatibility(VariationAnalysis)` → TypeCompatibility
  - [ ] Infer types for each variation
  - [ ] Check type consistency across variations
  - [ ] Identify type conflicts
  - [ ] Generate warnings for unsafe extractions
- [ ] Write unit tests
  - [ ] Test compatible variations (String, String, String)
  - [ ] Test incompatible variations (String, int, boolean)

---

## Phase 7: Refactoring Feasibility & Recommendations

### 7.1 Parameter Extractor
- [ ] Implement `ParameterExtractor` class in `analysis/`
  - [ ] `extractParameters(VariationAnalysis)` → List\<ParameterSpec\>
  - [ ] Infer parameter names from variations
  - [ ] Collect example values
  - [ ] Order parameters (required before optional, primitives before objects)
  - [ ] Limit to 5 parameters max
- [ ] Write unit tests
  - [ ] Test parameter inference
  - [ ] Test parameter ordering
  - [ ] Test max parameter limit

### 7.2 Refactoring Feasibility Analyzer
- [ ] Implement `RefactoringFeasibility` record (canExtractMethod, requiresManualReview, blockers, strategy)
- [ ] Implement `RefactoringFeasibilityAnalyzer` class
  - [ ] `analyze(seq1, seq2, variations, typeCompatibility)` → RefactoringFeasibility
  - [ ] Check scope compatibility
  - [ ] Detect lambdas/anonymous classes → manual review
  - [ ] Validate type safety
  - [ ] Select refactoring strategy
- [ ] Implement `StrategySelector` inner class/method
  - [ ] Decision tree: test class? → setup code? → parameterizable?
  - [ ] Select: PARAMETERIZED_TEST, BEFORE_EACH, EXTRACT_METHOD, UTILITY_CLASS
- [ ] Write unit tests
  - [ ] Test strategy selection for test setup code
  - [ ] Test strategy selection for cross-class duplicates
  - [ ] Test manual review triggers

### 7.3 Recommendation Generator
- [ ] Extend RefactoringFeasibilityAnalyzer
  - [ ] `generateRecommendation(cluster)` → RefactoringRecommendation
  - [ ] Suggest method name based on context
  - [ ] Calculate confidence score
  - [ ] Estimate LOC reduction
- [ ] Write unit tests

---

## Phase 8: Clustering

### 8.1 Duplicate Clusterer
- [ ] Implement `DuplicateClusterer` class in `analysis/`
  - [ ] Constructor: accept threshold
  - [ ] `cluster(results)` → List\<DuplicateCluster\>
  - [ ] Group SimilarityResults by similar sequences
  - [ ] Select primary sequence (earliest in file)
  - [ ] Attach refactoring recommendation
  - [ ] Calculate LOC reduction
- [ ] Implement `clusterAll(clusters)` for cross-file clustering
- [ ] Write unit tests
  - [ ] Test cluster formation
  - [ ] Test primary selection
  - [ ] Test LOC calculation

---

## Phase 9: Core Orchestration

### 9.1 Duplication Report
- [ ] Implement `DuplicationReport` record in `core/`
  - [ ] Fields: clusters, totalDuplicateLines, estimatedReduction, config
  - [ ] Add summary statistics methods
- [ ] Write unit tests

### 9.2 Duplication Analyzer
- [ ] Create `core/` package under `com.raditha.dedup`
- [ ] Implement `DuplicationAnalyzer` class (main orchestrator)
  - [ ] Constructor: inject config, create all components
  - [ ] `analyzeFile(Path)` → DuplicationReport
    - [ ] Use AbstractCompiler to parse file
    - [ ] Extract sequences
    - [ ] Generate candidates (with pre-filtering)
    - [ ] Calculate similarities (parallel)
    - [ ] Cluster results
  - [ ] `analyzeProject()` → DuplicationReport
    - [ ] Use Settings for project root
    - [ ] Find all Java files (exclude target/, generated/)
    - [ ] Analyze each file
    - [ ] Merge clusters across files
- [ ] Write integration tests
  - [ ] Test single file analysis
  - [ ] Test project-wide analysis with sample files

---

## Phase 10: Reporting

### 10.1 Text Report Generator
- [ ] Create `report/` package under `com.raditha.dedup`
- [ ] Implement `TextReportFormatter` class
  - [ ] `format(DuplicationReport)` → String
  - [ ] Summary section (clusters, duplicate lines, reduction)
  - [ ] Cluster details (similarity %, file:lines, recommendation)
  - [ ] Make output readable and actionable
- [ ] Write unit tests with sample reports

### 10.2 JSON Report Generator
- [ ] Implement `JsonReportFormatter` class
  - [ ] `format(DuplicationReport)` → String (JSON)
  - [ ] Use Jackson or Gson for serialization
  - [ ] Include all metadata for tooling integration
- [ ] Write unit tests

### 10.3 Report Generator (Unified)
- [ ] Implement `ReportGenerator` class
  - [ ] `generate(report, format)` → String
  - [ ] Support TEXT and JSON formats
  - [ ] Delegate to appropriate formatter
- [ ] Write unit tests

---

## Phase 11: CLI Interface

### 11.1 CLI Implementation
- [ ] Create `cli/` package under `com.raditha.dedup`
- [ ] Implement `DuplicationDetectorCLI` class with main() method
  - [ ] Parse command-line arguments
    - [ ] `--project <path>` - project root
    - [ ] `--file <path>` - single file analysis
    - [ ] `--config <preset>` - strict/moderate/aggressive
    - [ ] `--output <file>` - output file path
    - [ ] `--format <text|json>` - output format
    - [ ] `--help` - show usage
  - [ ] Load Settings if analyzing project
  - [ ] Create DuplicationAnalyzer with config
  - [ ] Run analysis
  - [ ] Generate report
  - [ ] Write to output or stdout
  - [ ] Return appropriate exit codes (0=success, 1=duplicates found, 2=error)
- [ ] Add proper error handling and logging
- [ ] Write integration tests

### 11.2 Build Configuration
- [ ] Add duplication-detector module to Antikythera build
- [ ] Configure dependencies (JavaParser, Antikythera core)
- [ ] Configure JAR packaging with main class
- [ ] Create shell script wrapper for easy execution

---

## Phase 12: Testing & Validation

### 12.1 Unit Test Coverage
- [ ] Review all components for test coverage
- [ ] Aim for >80% code coverage
- [ ] Add missing edge case tests

### 12.2 Integration Testing
- [ ] Create test fixtures in antikythera-test-helper
  - [ ] Perfect duplicates (100% similarity)
  - [ ] Near duplicates with variations (80-95%)
  - [ ] Structural duplicates (different variables/types)
  - [ ] Non-duplicates (similar but semantically different)
  - [ ] Test setup code duplicates
- [ ] Write integration tests using real code samples
- [ ] Validate detection accuracy
- [ ] Validate refactoring recommendations

### 12.3 Performance Testing
- [ ] Test with small project (100 files)
- [ ] Test with medium project (1000 files)
- [ ] Measure pre-filtering effectiveness
- [ ] Profile for bottlenecks
- [ ] Optimize if needed

### 12.4 False Positive Analysis
- [ ] Run on real Antikythera codebase
- [ ] Review false positives
- [ ] Tune thresholds and weights if needed
- [ ] Document known limitations

---

## Phase 13: Documentation

### 13.1 User Documentation
- [ ] Create README.md for duplication-detector
  - [ ] Quick start guide
  - [ ] CLI usage examples
  - [ ] Configuration presets explanation
  - [ ] Output format documentation
- [ ] Create USAGE.md with detailed examples
- [ ] Document interpretation of similarity scores

### 13.2 Developer Documentation
- [ ] Add JavaDoc to all public classes and methods
- [ ] Document algorithm choices and trade-offs
- [ ] Create architecture diagram
- [ ] Document extension points for Phase 2

---

## Phase 14: Final Integration & Delivery

### 14.1 End-to-End Testing
- [ ] Run on antikythera-sample-project
- [ ] Run on antikythera-test-helper
- [ ] Verify all features work together
- [ ] Test all CLI options

### 14.2 Walkthrough Creation
- [ ] Create walkthrough document showing:
  - [ ] Detection results on real code
  - [ ] Example refactoring recommendations
  - [ ] Performance metrics
  - [ ] Accuracy validation

### 14.3 Release Preparation
- [ ] Code review and cleanup
- [ ] Final testing pass
- [ ] Update main Antikythera README with duplication detector section
- [ ] Tag release

---

## Success Criteria

- ✅ Detects duplicates with >90% accuracy on test fixtures
- ✅ Generates actionable refactoring recommendations
- ✅ Processes 1000-file project in <5 minutes
- ✅ <5% false positive rate on real codebases
- ✅ CLI tool works standalone and integrated with Antikythera
- ✅ Comprehensive test coverage (>80%)
- ✅ Clear, usable documentation

---

## Dependencies Summary

**Critical Path** (must be done in order):
1. Data structures (Phase 1)
2. Token normalization (Phase 2)
3. Similarity algorithms (Phase 3)
4. Statement extraction (Phase 4)
5. Core orchestration (Phase 9)
6. Reporting & CLI (Phases 10-11)

**Can be parallelized**:
- Pre-filtering (Phase 5) - after Phase 4
- Scope/Type analysis (Phase 6) - after Phase 2
- Refactoring analysis (Phase 7) - after Phases 2, 6
- Clustering (Phase 8) - after Phase 3

---

## Estimated Effort

- **Phase 1-4** (Foundation): ~3-4 days
- **Phase 5-8** (Advanced Analysis): ~3-4 days
- **Phase 9-11** (Integration): ~2-3 days
- **Phase 12-14** (Testing & Docs): ~2-3 days

**Total**: ~10-14 days for Phase 1 MVP

---

## Notes

- Focus on **correctness first, optimization second**
- Write tests alongside implementation (TDD where appropriate)
- Use real code samples early to validate approach
- Be prepared to tune weights and thresholds based on empirical results
- Phase 2 (Automated Refactoring) will build on this foundation - keep extension points in mind
