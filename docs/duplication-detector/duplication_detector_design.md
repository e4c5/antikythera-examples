# Code Duplication Detector - Comprehensive Design Document

**Version**: 2.0  
**Last Updated**: December 9, 2025  
**Status**: Design Complete, Ready for Implementation

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Problem Statement](#2-problem-statement)
3. [Core Detection Approach](#3-core-detection-approach)
4. [Token Normalization Strategy](#4-token-normalization-strategy)
5. [Similarity Algorithms](#5-similarity-algorithms)
6. [Enhanced Data Structures](#6-enhanced-data-structures)
7. [Detection Flow and Architecture](#7-detection-flow-and-architecture)
8. [Configuration and Tuning](#8-configuration-and-tuning)
9. [Phase 1: Enhanced Detection (MVP)](#9-phase-1-enhanced-detection-mvp)
10. [Phase 2: Automated Refactoring](#10-phase-2-automated-refactoring)
11. [Integration with Antikythera](#11-integration-with-antikythera)
12. [Performance Considerations](#12-performance-considerations)
13. [Output Formats](#13-output-formats)
14. [Competitive Analysis](#14-competitive-analysis)
15. [Success Criteria](#15-success-criteria)
16. [Implementation Roadmap](#16-implementation-roadmap)

---

## 1. Executive Summary

### Goal

Detect **semantic code duplication** in Java codebases (tests and general code) and enable **automated refactoring** to eliminate duplicates.

### Approach

**Hybrid token sequence similarity** (LCS + Levenshtein) combined with AST structural analysis, with configurable thresholds and weights.

### Key Innovation

**Enhanced data capture during detection** (Phase 1) enables Phase 2 automated refactoring without re-parsing files.

### Unique Value

Only tool that not only **detects** duplicates but also **automatically refactors** them with:
- Test-specific intelligence (@BeforeEach extraction, @ParameterizedTest consolidation)
- Method-aware semantic analysis (preserves method names to avoid false positives)
- Interactive review workflow with side-by-side diffs

---

## 2. Problem Statement

### Requirements

| Aspect | Requirement |
|--------|-------------|
| **Granularity** | Collections of duplicate lines (multi-line blocks) |
| **Scope** | Test code and general source code |
| **Type** | Semantic duplicates (same logic, different variables/literals) |
| **Output** | Similarity scores (0-100%) + concrete refactoring recommendations |

### Why Hash-Based Approaches Fail

- Binary yes/no decisions (no gradation)
- Collision issues with minor variations
- No similarity scoring
- **User reported poor practical results**

### Why Existing Tools Fall Short

| Tool | Limitation |
|------|------------|
| **PMD CPD, Simian** | Text-based detection → many false positives, misses semantic duplicates |
| **SonarQube** | Better (AST-based) but binary detection, no refactoring support |
| **All Tools** | No automated refactoring or variation tracking for parameter extraction |

---

## 3. Core Detection Approach

### Three Complementary Metrics

**1. Longest Common Subsequence (LCS)**
- Finds longest shared token sequence
- Tolerant to insertions/deletions (e.g., extra logging)
- Order-preserving (critical for code)

**2. Levenshtein Edit Distance**
- Minimum edits to transform one sequence to another
- Sensitive to all changes
- Balances LCS's leniency

**3. Structural Similarity**
- Control flow patterns (if/for/while/try)
- Nesting depth
- Method call counts
- Method annotations (@Transactional, @Test, etc.)
- Fast pre-filter for incompatible code

### Combined Score Formula

```
Final Score = 0.40 × LCS + 0.40 × Levenshtein + 0.20 × Structural
```

> **Note**: These weights are **provisional starting points** chosen heuristically. They prioritize token sequence similarity (LCS + Levenshtein = 80%) over structural patterns (20%). Empirical tuning during Phase 1 implementation will refine these values based on real-world duplicate detection results.

Weights are **configurable** for different use cases (see [Configuration](#8-configuration-and-tuning)).

---

## 4. Token Normalization Strategy

### Core Principle

**Normalize away cosmetic differences while preserving semantic meaning.**

### What We Normalize (Cosmetic Differences)

| Original | Normalized | Rationale |
|----------|------------|-----------|
| `userId`, `customerId`, `accountId` | `VAR` | Variable names are implementation details |
| `"PENDING"`, `"APPROVED"`, `"ACTIVE"` | `STRING_LIT` | Literal values vary, pattern is same |
| `123`, `456`, `0` | `INT_LIT` | Numeric values vary, pattern is same |

### What We Preserve (Semantic Meaning)

| Code | Token | Rationale |
|------|-------|-----------|
| `userRepo.save(user)` | `METHOD_CALL(save)` | **Method name is semantic** |
| `user.setActive(true)` | `METHOD_CALL(setActive)` | Different from `setDeleted`! |
| `assertEquals(a, b)` | `ASSERT(assertEquals)` | Test framework method |
| `when(mock.foo())` | `MOCK(when)` | Mockito method |
| `if`, `for`, `while` | `CONTROL_FLOW(if/for/while)` | Control structures |
| `==`, `!=`, `&&` | `OPERATOR(==/!=/&&)` | Operators change logic |
| `User`, `Customer` | `TYPE(User/Customer)` | Types carry semantic meaning |

### Critical Design Decision

**❌ WRONG** (Over-normalization):
```java
user.setActive(true);   → [VAR, CALL, LITERAL]
user.setDeleted(true);  → [VAR, CALL, LITERAL]
// These would match 100% but are SEMANTICALLY DIFFERENT!
```

**✅ CORRECT** (Preserve method names):
```java
user.setActive(true);   → [VAR, METHOD_CALL(setActive), LITERAL]
user.setDeleted(true);  → [VAR, METHOD_CALL(setDeleted), LITERAL]
// These DON'T match - correctly identified as different!
```

### Real Duplicate Detection Example

```java
// Block A
User user = userRepository.findById(userId);
if (user != null) { user.setActive(true); }

// Block B
Customer c = customerDao.findById(customerId);
if (c != null) { c.setActive(true); }

// Normalized tokens:
// [TYPE(User), VAR, =, METHOD_CALL(findById), VAR, IF, VAR, !=, null, METHOD_CALL(setActive), LITERAL]
// [TYPE(Customer), VAR, =, METHOD_CALL(findById), VAR, IF, VAR, !=, null, METHOD_CALL(setActive), LITERAL]
// 
// High similarity - correctly flagged as duplicates!
// Variations tracked: User→Customer, userId→customerId, user→c
```

---

## 5. Similarity Algorithms

### 5.1 LCS Algorithm

**Concept**: Dynamic programming to find longest common subsequence

```java
public class LCSSimilarity {
    public double calculate(List<Token> t1, List<Token> t2) {
        int lcsLength = computeLCS(t1, t2);  // O(n × m) DP
        return (double) lcsLength / Math.max(t1.size(), t2.size());
    }
}
```

- **Complexity**: O(n × m) time, O(min(n,m)) space with optimization
- **Strength**: Tolerant to gaps and insertions
- **Use**: Find core shared logic

### 5.2 Levenshtein Algorithm

**Concept**: Minimum edit operations (insert/delete/substitute) to transform sequences

```java
public class LevenshteinSimilarity {
    public double calculate(List<Token> t1, List<Token> t2) {
        int distance = computeDistance(t1, t2);  // DP
        return 1.0 - ((double) distance / Math.max(t1.size(), t2.size()));
    }
}
```

- **Complexity**: O(n × m) time, O(min(n,m)) space with optimization
- **Strength**: Sensitive to every change
- **Use**: Penalize modifications

### 5.3 Structural Similarity

**Concept**: Compare high-level AST structural patterns

**Structural Signature**:
```java
record StructureSignature(
    List<String> controlFlowPatterns,  // ["IF", "FOR", "TRY"]
    List<String> annotations,           // ["@Transactional", "@Test"]
    int maxNestingDepth,                // 3
    int methodCallCount,                // 5
    int variableCount,                  // 2
    boolean hasTryCatch                 // true
) {}
```

**Comparison**: Jaccard similarity for patterns + weighted numeric differences

**Mathematical Formula**:
```
Structural Score = 0.5 × Jaccard(patterns1, patterns2) + 
                  0.3 × (1 - |depth1 - depth2| / max(depth1, depth2)) +
                  0.2 × (1 - |calls1 - calls2| / max(calls1, calls2))
```

- **Complexity**: O(n) time, O(1) space
- **Strength**: Fast pre-filter, catches architectural patterns
- **Use**: Reject structurally incompatible code early

---

## 6. Enhanced Data Structures

> **Critical for Phase 2 Refactoring**: Phase 1 must capture ALL data needed for automated refactoring to avoid re-parsing.

### 6.1 Enhanced Token

```java
record Token(
    TokenType type,
    String normalizedValue,  // e.g., "VAR", "METHOD_CALL(save)"
    
    // CRITICAL: Preserve original context
    String originalValue,    // e.g., "userId", "userRepo.save"
    String inferredType,     // e.g., "Long", "void"
    Node originalASTNode,    // Link to AST for refactoring
    int lineNumber,          // Source line
    int columnNumber         // Source column
) {}
```

This enables variation tracking: when we normalize `userId` and `customerId` both to `VAR`, we record the variation `(VARIABLE, position, "userId", "customerId")` which becomes a method parameter in Phase 2.

### 6.2 Enhanced StatementSequence

```java
record StatementSequence(
    // Detection (Phase 1)
    List<Statement> statements,
    Range range,
    String methodName,
    int startOffset,
    
    // CRITICAL for Refactoring (Phase 2)
    MethodDeclaration containingMethod,      // Parent method AST node
    ClassOrInterfaceDeclaration containingClass,  // Parent class AST node
    CompilationUnit compilationUnit,         // For file-level modifications
    Path sourceFilePath,                     // Absolute path to source file
    String originalSourceText,               // Preserve original formatting
    List<String> currentImports,             // Existing imports in file
    ScopeContext scopeContext                // Variables available in scope
) {}
```

**Lifecycle Management**: Records are created ONCE during detection. AST modifications in Phase 2 use fresh references from the compilation unit. Records are NOT updated after refactoring.

### 6.3 ScopeContext

```java
record ScopeContext(
    List<VariableInfo> availableVariables,  // In-scope vars
    List<FieldDeclaration> classFields,     // Class fields
    List<String> staticImports,             // e.g., "org.mockito.Mockito.*"
    boolean isInTestClass,                  // Test vs source
    List<String> annotations                // @Test, @BeforeEach, etc.
) {}

record VariableInfo(
    String name,
    String type,
    boolean isParameter,
    boolean isField,
    boolean isFinal
) {}
```

### 6.4 Enhanced SimilarityResult

```java
record SimilarityResult(
    // Similarity scores
    double overallScore,
    double lcsScore, 
    double levenshteinScore,
    double structuralScore,
    int tokens1Count,
    int tokens2Count,
    
    // CRITICAL for Refactoring
    VariationAnalysis variations,           // What differs
    TypeCompatibility typeCompatibility,    // Type-safe?
    RefactoringFeasibility feasibility      // Auto-refactor safe?
) {}
```

### 6.5 Variation Analysis

```java
record VariationAnalysis(
    List<Variation> literalVariations,      // "test" vs "prod"
    List<Variation> variableVariations,     // userId vs customerId
    List<Variation> methodCallVariations,   // getUser() vs getCustomer()
    List<Variation> typeVariations,         // User vs Customer
    boolean hasControlFlowDifferences       // Different if/for logic
) {}

record Variation(
    VariationType type,                     // LITERAL, VARIABLE, METHOD_CALL, TYPE
    int position,                           // Token position in sequence
    String value1, String value2,           // Values in each sequence
    String inferredType,                    // Type of this variation
    boolean canParameterize                 // Extract as param?
) {}
```

### 6.6 Type Compatibility and Feasibility

```java
record TypeCompatibility(
    boolean allVariationsTypeSafe,           // All variations have same type
    Map<String, String> parameterTypes,      // Inferred parameter types
    String inferredReturnType,               // Inferred return type
    List<String> warnings                    // Type safety warnings
) {}

record RefactoringFeasibility(
    boolean canExtractMethod,                // Feasible to extract method
    boolean canExtractToBeforeEach,          // For test setup
    boolean canExtractToParameterizedTest,   // Consolidate to @ParameterizedTest
    boolean requiresManualReview,            // Too complex for auto-refactor
    List<String> blockers,                   // Why auto-refactor won't work
    RefactoringStrategy suggestedStrategy    // Recommended approach
) {}

enum RefactoringStrategy {
    EXTRACT_HELPER_METHOD,
    EXTRACT_TO_BEFORE_EACH,
    EXTRACT_TO_PARAMETERIZED_TEST,
    EXTRACT_TO_UTILITY_CLASS,
    MANUAL_REVIEW_REQUIRED
}
```

### 6.7 Refactoring Recommendation

```java
record RefactoringRecommendation(
    RefactoringStrategy strategy,                // EXTRACT_METHOD, etc.
    String suggestedMethodName,                  // "createTestUser"
    List<ParameterSpec> suggestedParameters,     // (String id, String name)
    String suggestedReturnType,                  // "User"
    String targetLocation,                       // Where to insert
    double confidenceScore,                      // 0.0-1.0
    int estimatedLOCReduction                    // -24 lines
) {}

record ParameterSpec(
    String name,                                 // "patientId"
    String type,                                 // "String"
    List<String> exampleValues                   // ["P123", "P999", "P456"]
) {}
```

**Parameter Limits**: Maximum 5 parameters for auto-refactor. If more variations exist, mark for manual review and suggest grouping parameters into objects.

**Parameter Ordering**: Required before optional, primitives before objects, alphabetical within groups.

---

## 7. Detection Flow and Architecture

### 7.1 Enhanced Detection Flow

**Traditional Flow (Insufficient)**:
```
Parse → Extract → Normalize → Compare → Report
```

**Enhanced Flow (Refactoring-Ready)**:
```
Parse → Extract Statements → 
  ↓
Normalize WITH Variation Tracking →
  ↓
Analyze Scope (variables, fields, static imports) →
  ↓
Analyze Type Compatibility →
  ↓
Calculate Similarity + Feasibility →
  ↓
Generate Refactoring Recommendations →
  ↓
Report
```

### 7.2 Component Architecture

```
DuplicationAnalyzer
  ├─ StatementExtractor (sliding window)
  ├─ EnhancedTokenNormalizer (normalize + track variations)
  ├─ ScopeAnalyzer (variables, fields, static imports)
  ├─ HybridDuplicationDetector
  │   ├─ LCSSimilarity
  │   ├─ LevenshteinSimilarity
  │   └─ StructuralSimilarity
  ├─ TypeCompatibilityAnalyzer (validate parameter types)
  ├─ RefactoringFeasibilityAnalyzer (safe to auto-refactor?)
  └─ DuplicateClusterer (group similar sequences)
```

### 7.3 Key Class Signatures

```java
public class DuplicationAnalyzer {
    public DuplicationReport analyze(Path sourceFile);
    public DuplicationReport analyzeProject(Path projectRoot);
}

public class StatementExtractor {
    public List<StatementSequence> extractSequences(CompilationUnit cu, int windowSize);
}

public class EnhancedTokenNormalizer {
    public NormalizationResult normalize(Statement stmt1, Statement stmt2);
    // Returns: tokens1, tokens2, variations
}

public class ScopeAnalyzer {
    public ScopeContext analyzeScopeAt(Statement stmt, MethodDeclaration method);
}

public class HybridDuplication Detector {
    public SimilarityResult compare(StatementSequence seq1, StatementSequence seq2);
}

public class TypeCompatibilityAnalyzer {
    public TypeCompatibility analyze(VariationAnalysis variations);
}

public class RefactoringFeasibilityAnalyzer {
    public RefactoringFeasibility analyze(
        StatementSequence seq1, StatementSequence seq2,
        VariationAnalysis variations,
        TypeCompatibility compatibility
    );
}
```

---

## 8. Configuration and Tuning

### 8.1 Configuration Record

```java
public record DuplicationConfig(
    int minLines,
    double threshold,
    SimilarityWeights weights,
    boolean includeTests,
    boolean includeSources,
    List<String> excludePatterns
) {}

public record SimilarityWeights(
    double lcsWeight,
    double levenshteinWeight,
    double structuralWeight
) {
    public static SimilarityWeights balanced() {
        return new SimilarityWeights(0.40, 0.40, 0.20);
    }
}
```

### 8.2 Presets

| Preset | Min Lines | Threshold | Weights (LCS/Lev/Struct) | Use Case |
|--------|-----------|-----------|--------------------------|----------|
| **Strict** | 5+ | 0.90 | 0.40 / 0.40 / 0.20 | High-confidence refactoring candidates |
| **Moderate** | 4+ | 0.75 | 0.40 / 0.40 / 0.20 | General detection (default) |
| **Aggressive** | 3+ | 0.60 | 0.35 / 0.30 / 0.35 | Find all potential patterns |
| **Test Setup** | 3+ | 0.70 | 0.45 / 0.35 / 0.20 | Tolerant to gaps (extra logging) |

### 8.3 Generated Code Detection

**Automatically skip**:
- Files with `@Generated` annotation
- File header comments containing "auto-generated", "do not edit"
- Files matching exclusion patterns (e.g., `**/generated/**`, `**/target/**`)

---

## 9. Phase 1: Enhanced Detection (MVP)

**Goal**: Detect duplicates with complete refactoring metadata

### 9.1 Deliverables

- ✅ Enhanced token normalization with variation tracking
- ✅ LCS + Levenshtein + Structural similarity
- ✅ Scope analysis (variables, fields, static imports)
- ✅ Type compatibility checking
- ✅ Refactoring feasibility determination
- ✅ Text/JSON reports with refactoring recommendations
- ✅ CLI interface with CI/CD exit codes

### 9.2 Example Output

**Text Report**:
```
Code Duplication Analysis
=========================
Project: csi-bm-approval-java-service
Config: moderate (minLines=4, threshold=0.75)

Summary: 23 clusters, 347 duplicate lines, ~280 LOC reduction potential

Cluster #1 - 94.1% Similar
  Primary: AdmissionServiceImplTest.java:testCreateAdmission [L45-52]
  Duplicates: 2 occurrences in same file
  
  Recommendation: Extract helper method
    Signature: Admission createTestAdmission(String patientId, String hospitalId, String status)
    Impact: -16 lines
    Confidence: 0.95 (high - auto-refactor safe)
```

**JSON Report**:
```json
{
  "summary": {
    "clusters": 23,
    "duplicateLines": 347,
    "estimatedReduction": 280
  },
  "clusters": [{
    "id": 1,
    "similarity": 0.941,
    "primary": {
      "file": "AdmissionServiceImplTest.java",
      "method": "testCreateAdmission",
      "lines": "45-52"
    },
    "recommendation": {
      "strategy": "EXTRACT_METHOD",
      "methodName": "createTestAdmission",
      "parameters": [
        {"name": "patientId", "type": "String"},
        {"name": "hospitalId", "type": "String"},
        {"name": "status", "type": "String"}
      ],
      "returnType": "Admission",
      "confidence": 0.95
    }
  }]
}
```

### 9.3 CLI Interface

```bash
# Detection mode
java -jar duplication-detector.jar detect \
  --project /path/to/project \
  --config moderate \
  --output report.json

# Exit codes for CI/CD
# 0 = success, no duplicates above threshold
# 1 = duplicates found above threshold
# 2 = error
```

---

## 10. Phase 2: Automated Refactoring

**Goal**: Apply refactorings automatically or with user review

### 10.1 Refactoring Strategies

#### Strategy Selection Decision Tree

```
Is duplicate in test class?
  ├─ YES: Is setup code at method start?
  │   ├─ YES: Can differences be parameterized?
  │   │   ├─ YES: → EXTRACT_TO_PARAMETERIZED_TEST
  │   │   └─ NO:  → EXTRACT_TO_BEFORE_EACH
  │   └─ NO: → EXTRACT_HELPER_METHOD
  └─ NO: Is duplicate across multiple classes?
      ├─ YES: → EXTRACT_TO_UTILITY_CLASS
      └─ NO:  → EXTRACT_HELPER_METHOD
```

### 10.2 Extract Method Refactoring

**Example: Test Setup**

**Before**:
```java
@Test
void testCreateAdmission() {
    Admission admission = new Admission();
    admission.setPatientId("P123");
    admission.setHospitalId("H456");
    admission.setStatus("PENDING");
    // ... test logic
}

@Test
void testUpdateAdmission() {
    Admission admission = new Admission();
    admission.setPatientId("P999");
    admission.setHospitalId("H888");
    admission.setStatus("APPROVED");
    // ... test logic
}
```

**After (Automated Refactoring)**:
```java
private Admission createTestAdmission(String patientId, String hospitalId, String status) {
    Admission admission = new Admission();
    admission.setPatientId(patientId);
    admission.setHospitalId(hospitalId);
    admission.setStatus(status);
    return admission;
}

@Test
void testCreateAdmission() {
    Admission admission = createTestAdmission("P123", "H456", "PENDING");
    // ... test logic
}

@Test
void testUpdateAdmission() {
    Admission admission = createTestAdmission("P999", "H888", "APPROVED");
    // ... test logic
}
```

### 10.3 Extract to @BeforeEach

**Criteria**: Duplicates at start of multiple @Test methods with constant parts

**Before**:
```java
public class AdmissionServiceTest {
    @Test
    void testCreate() {
        Admission admission = new Admission();
        when(repository.save(any())).thenReturn(admission);
        // test logic
    }
    
    @Test
    void testUpdate() {
        Admission admission = new Admission();
        when(repository.save(any())).thenReturn(admission);
        // test logic
    }
}
```

**After**:
```java
public class AdmissionServiceTest {
    private Admission admission;
    
    @BeforeEach
    void setUp() {
        admission = new Admission();
        when(repository.save(any())).thenReturn(admission);
    }
    
    @Test
    void testCreate() {
        // test logic
    }
    
    @Test
    void testUpdate() {
        // test logic
    }
}
```

**⚠️ Test Isolation Warning**: When promoting mutable objects to `@BeforeEach`, the tool will warn:
> "WARNING: Promoted mutable object 'admission' to @BeforeEach. Ensure tests don't modify shared state or consider deep cloning."

### 10.4 Extract to @ParameterizedTest (JUnit 5 Only)

**Before (Multiple test methods with same logic)**:
```java
@Test
void testAdmissionPending() {
    Admission admission = createTestAdmission("P123", "H456", "PENDING");
    // same assertions
}

@Test
void testAdmissionApproved() {
    Admission admission = createTestAdmission("P999", "H888", "APPROVED");
    // same assertions
}
```

**After**:
```java
@ParameterizedTest
@CsvSource({
    "P123, H456, PENDING",
    "P999, H888, APPROVED"
})
void testAdmissionStates(String patientId, String hospitalId, String status) {
   Admission admission = createTestAdmission(patientId, hospitalId, status);
    // same assertions
}
```

### 10.5 Interactive Review Workflow

```
Code Duplication Refactoring Tool
==================================

Cluster #1: Test setup duplication (Similarity: 94.1%)
  Files affected: AdmissionServiceImplTest.java
  Methods: testCreateAdmission, testUpdateAdmission
  Duplicated lines: 8 lines × 2 occurrences = 16 total

  Proposed Refactoring: Extract helper method
  
  [BEFORE - testCreateAdmission L45-52]
  │ Admission admission = new Admission();
  │ admission.setPatientId("P123");
  │ admission.setHospitalId("H456");
  
  [AFTER]
  │ private Admission createTestAdmission(String patientId, String hospitalId, String status) {
  │     Admission admission = new Admission();
  │     admission.setPatientId(patientId);
  │     admission.setHospitalId(hospitalId);
  │     return admission;
  │ }
  │ // In testCreateAdmission:
  │ Admission admission = createTestAdmission("P123", "H456", "PENDING");
  
  Options:
    [a] Apply this refactoring
    [e] Edit method name/parameters
    [s] Skip this cluster
    [b] Apply all remaining (batch mode)
    [q] Quit and save session
  
  Your choice: _
```

**Session Management**: Review progress is saved to `.duplication-review-state.json`. If interrupted, resume with `--resume-session`.

### 10.6 Safety Validations

#### Pre-Refactoring Checks

```java
public class RefactoringSafetyValidator {
    public ValidationResult validate(DuplicateCluster cluster, MethodSignature signature) {
        List<ValidationIssue> issues = new ArrayList<>();
        
        // 1. No side effect differences
        if (hasDifferentSideEffects(cluster)) {
            issues.add(ValidationIssue.error("Sequences have different side effects"));
        }
        
        // 2. No naming conflicts
        if (methodNameConflict(signature.name())) {
            issues.add(ValidationIssue.error("Method name already exists"));
        }
        
        // 3. Annotation compatibility (e.g., @Transactional)
        if (hasIncompatibleAnnotations(cluster)) {
            issues.add(ValidationIssue.error("Incompatible annotations (@Transactional differs)"));
        }
        
        // 4. Scope compatibility
        if (!variablesInScope(cluster, signature)) {
            issues.add(ValidationIssue.error("Variables used out of scope"));
        }
        
        // 5. No complex control flow variations
        if (hasControlFlowDifferences(cluster)) {
            issues.add(ValidationIssue.error("Control flow differs between duplicates"));
        }
        
        return new ValidationResult(issues);
    }
}
```

**Side Effect Detection** (conservative approach):
- Flag I/O operations (file, network, database)
- Flag external API calls
- Flag non-idempotent operations
- Document limitations (cannot detect all side effects)

#### Post-Refactor Verification

```java
public class RefactoringVerifier {
    public VerificationResult verify(RefactoringResult refactoring) {
        // 1. Compilation check
        CompilationResult compilation = compileProject();
        if (!compilation.success()) {
            rollback();
            return VerificationResult.failed("Compilation failed");
        }
        
        // 2. Test execution (affected tests only or full suite)
        TestResult tests = runTests(refactoring.affectedClasses());
        if (!tests.allPassed()) {
            rollback();
            return VerificationResult.failed("Tests failed");
        }
        
        // 3. Import optimization
        optimizeImports();
        
        return VerificationResult.success();
    }
}
```

**Rollback Mechanism**: File-level backup before modification (copy to `.bak`), restore on failure. Git integration is optional.

### 10.7 Configuration

```java
public record RefactoringConfig(
    boolean interactive,           // Enable interactive review
    boolean runTests,             // Run tests after refactoring
    boolean verifyCompilation,    // Verify compilation
    double autoApplyThreshold,    // Auto-apply if similarity > threshold (0.95+)
    boolean createBackup,         // Create .bak files before refactoring
    boolean extractToBeforeEach,  // Prefer @BeforeEach for test setup
    boolean extractToParameterizedTest,  // Consider @ParameterizedTest
    int maxParameters             // Max parameters (default: 5)
) {
    public static RefactoringConfig safe() {
        return new RefactoringConfig(true, true, true, 0.98, true, true, true, 5);
    }
}
```

### 10.8 CLI Interface

```bash
# Interactive mode (recommended)
java -jar duplication-detector.jar refactor \
  --project /path/to/project \
  --interactive \
  --config moderate

# Auto-apply high-confidence refactorings
java -jar duplication-detector.jar refactor \
  --project /path/to/project \
  --auto-apply \
  --threshold 0.95

# Dry-run mode
java -jar duplication-detector.jar refactor \
  --project /path/to/project \
  --dry-run \
  --output refactoring-plan.json

# Resume interrupted session
java -jar duplication-detector.jar refactor \
  --project /path/to/project \
  --resume-session
```

---

## 11. Integration with Antikythera

### 11.1 Leverage Existing Infrastructure

```java
public class DuplicationAnalyzer {
    // Use AbstractCompiler for parsing
    private CompilationUnit parseFile(Path sourceFile) {
        AbstractCompiler compiler = new AbstractCompiler(sourceFile);
        return compiler.getCompilationUnit();
    }
    
    // Use Settings for configuration
    private void loadProjectSettings() {
        Settings settings = Settings.getInstance();
        // Access project root, source paths
    }
}
```

### 11.2 Integration with TestFixer

```java
public class TestFixerIntegration {
    public void runCompleteTestCleanup(Path projectRoot) {
        // 1. Existing TestFixer cleanup
        testFixer.migrateToJUnit5();            // JUnit 4→5
        testFixer.fixAnnotations();
        testFixer.removeTestsWithoutAssertions();
        
        // 2. Duplication detection + refactoring
        DuplicationReport report = analyzer.analyzeProject(projectRoot);
        refactorer.applyRecommendations(report);
        
        // 3. Verify tests pass
        runTests();
    }
}
```

**Workflow**: Run TestFixer migration before duplication detection. Assumes JUnit 5 only (no JUnit 4 support).

---

## 12. Performance Considerations

### 12.1 Complexity Analysis

| Component | Time | Space |
|-----------|------|-------|
| Token Normalization | O(n) | O(n) |
| LCS | O(n × m) | O(min(n,m)) with optimization |
| Levenshtein | O(n × m) | O(min(n,m)) with optimization |
| Structural | O(n) | O(1) |
| All-pairs | O(N²) | O(N²) |
| Variation tracking | +15% | +10% |
| Scope analysis | +10% per sequence | Cached |

### 12.2 Performance Estimates

**Without Opt imizations (pessimistic)**:
- 10K sequences = 100M comparisons
- At 0.1ms/comparison = 2.8 hours ❌

**With Optimizations (realistic)**:
- Early filtering (size, structural pre-check): -60% comparisons
- Parallelization (8 cores): 8x speedup
- Lazy evaluation (only feasibility for clusters above threshold): -30% work
- **Expected**: 10-20 minutes for 10K sequences ✅

### 12.3 Required Optimizations

1. **Early filtering** (size difference > 20% → skip)
2. **Structural pre-filter** (different control flow patterns → skip)
3. **Parallelization** (compare pairs in parallel)
4. **Lazy evaluation** (only analyze feasibility for clusters above threshold)
5. **Scope caching** (cache scope context per method)

### 12.4 Memory Considerations

**Phase 1 overhead**: ~20-30% additional memory for enhanced metadata

**Mitigation**:
- Records are short-lived (exist during analysis, discarded after report)
- JVM garbage collection handles circular references
- If needed: implement explicit cleanup or weak references

---

## 13. Output Formats

### 13.1 Text Report

See [Section 9.2](#92-example-output) for example.

### 13.2 JSON Report

See [Section 9.2](#92-example-output) for example.

**Additional fields** in JSON:
- Full variations list
- Type compatibility warnings
- Feasibility blockers
- Confidence scores per refactoring strategy

### 13.3 HTML Dashboard (Phase 3)

Interactive web-based report with:
- Visual diff viewer (side-by-side)
- Filterable cluster table
- Statistics dashboard
- Approve/reject UI for refactorings

---

## 14. Competitive Analysis

### Comparison with Existing Tools

| Feature | This Design | PMD CPD | SonarQube | Simian |
|---------|-------------|---------|-----------|--------|
| **Semantic similarity** | ✅ Method-aware token normalization | ❌ Text-based | ⚠️ AST-based (limited) | ❌ Text-based |
| **Gradual scoring** | ✅ 0-100% similarity | ❌ Binary yes/no | ⚠️ Line count only | ❌ Binary yes/no |
| **Refactoring suggestions** | ✅ Detailed with parameters | ❌ None | ❌ None | ❌ None |
| **Automated refactoring** | ✅ Phase 2 | ❌ None | ❌ None | ❌ None |
| **Test-specific patterns** | ✅ @BeforeEach, @ParameterizedTest | ❌ None | ❌ None | ❌ None |
| **Hybrid algorithm** | ✅ LCS+Levenshtein+Structural | ❌ Simple text match | ⚠️ AST-based | ❌ Simple text match |
| **Interactive review** | ✅ CLI with approve/edit | ❌ None | ⚠️ Web UI (view only) | ❌ None |
| **Variation tracking** | ✅ For parameter extraction | ❌ None | ❌ None | ❌ None |

### Unique Competitive Advantages

1. **Automated Refactoring**: Only tool that not only _detects_ but also _fixes_ duplicates
2. **Test-Specific Intelligence**: Understands test patterns (@BeforeEach extraction, parameterized tests)
3. **Semantic Awareness**: Preserves method names to avoid false positives (setActive ≠ setDeleted)
4. **Gradual Similarity**: Fine-grained scores enable threshold tuning
5. **Refactoring Metadata**: Phase 1 captures everything needed for Phase 2 (no re-parsing)

---

## 15. Success Criteria

### Detection Quality

- **Precision**: >85% (flagged duplicates are genuine refactoring opportunities)
- **Recall**: >75% (actual duplicates are detected)

### Performance

- Analyze 10K sequences in <20 minutes (with optimizations)

### Refactoring Quality (Phase 2)

- Auto-refactor success rate: >90% for high-confidence cases (similarity > 0.95)
- Zero test failures from refactoring
- Positive code review feedback on extracted methods

---

## 16. Implementation Roadmap

### Phase 1 - Enhanced Detection (MVP) | 4-6 weeks

**Core Deliverables**:
1. Token normalization (preserve method names!)
2. LCS + Levenshtein + Structural similarity
3. Enhanced data structures (StatementSequence, Token, ScopeContext)
4. Variation tracking during normalization
5. Scope analysis (variables, fields, static imports)
6. Type compatibility checking
7. Refactoring feasibility analysis
8. JSON/Text report generation
9. CLI with CI/CD exit codes

**Validation**:
- Unit tests for each similarity calculator
- Integration tests on known duplicate samples
- Performance benchmarking on real projects
- Empirical weight tuning

### Phase 2 - Automated Refactoring | 4-6 weeks

**Core Deliverables**:
1. Strategy selection system (decision tree)
2. Extract method refactoring (5-parameter limit)
3. Extract to @BeforeEach (with mutable state warnings)
4. Extract to @ParameterizedTest
5. Parameter extraction from variations
6. Interactive review CLI with session management
7. Safety validators (side effects, annotations, scope)
8. File-based rollback mechanism
9. Post-refactor verification (compilation, tests)

**Validation**:
- Refactoring correctness tests (before/after comparison)
- Test suite regression tests
- User acceptance testing on real projects

### Phase 3 - Production Features | Ongoing

1. HTML dashboard with visual diffs
2. Advanced strategies (base class, template method)
3. Cross-class/cross-project refactoring
4. Adaptive weight tuning (ML-based)
5. IDE integration (IntelliJ, VS Code)
6. CI/CD plugins (Maven, Gradle)
7. Clone evolution tracking
8. Advanced optimizations (LSH if needed)

---

## Key Takeaways

✅ **Hybrid approach**: LCS + Levenshtein + Structural provides robust semantic detection  
✅ **Method-aware normalization**: Preserves method names to avoid false positives  
✅ **Enhanced data capture**: Phase 1 preserves everything needed for Phase 2 refactoring  
✅ **Variation tracking**: Enables automatic parameter extraction  
✅ **Type safety**: Ensures generated method signatures are valid  
✅ **Scope awareness**: Validates variables are in scope before extraction  
✅ **Test-specific intelligence**: @BeforeEach, @ParameterizedTest, field promotion  
✅ **Safety validations**: Pre-checks + post-verification prevent breaking changes  
✅ **Interactive workflow**: User maintains control with approve/edit/skip  
✅ **Integration ready**: Leverages existing Antikythera + TestFixer infrastructure  
✅ **Competitive advantage**: Only tool that detects AND automatically refactors duplicates

This design enables a complete **detect-and-refactor workflow** for eliminating code duplication across Java codebases.

---

**End of Document**
