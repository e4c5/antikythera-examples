# Phase 2: Automated Duplicate Removal - Design Document

## Overview

Phase 2 focuses on **automated refactoring** to remove detected duplicates. This phase transforms the duplication detector from a reporting tool into an **active refactoring assistant** that can propose, preview, and apply code consolidation changes.

## Core Capabilities

### 1. Extract Method Refactoring
### 2. Interactive Review Workflow
### 3. Batch Refactoring Operations
### 4. Safety Validations
### 5. Test-Specific Refactoring Patterns

---

## 1. Extract Method Refactoring

### 1.1 Strategy Selection

Given a duplicate cluster, automatically determine the best refactoring strategy:

| Pattern Type | Refactoring Strategy | Example |
|--------------|---------------------|---------|
| **Test Setup** | Extract to `@BeforeEach` or helper method | Creating test data objects |
| **Shared Assertions** | Extract to assertion helper | Verifying multiple fields |
| **Utility Operations** | Extract to utility class | String formatting, date manipulation |
| **Common Initialization** | Extract to factory method | Object construction with many parameters |

### 1.2 Parameter Extraction Analysis

**Goal**: Identify which parts vary between duplicates and extract them as parameters.

```java
public class ParameterExtractor {
    
    public MethodSignature extractSignature(DuplicateCluster cluster) {
        // 1. Identify varying elements across all duplicates
        VariationAnalysis analysis = analyzeVariations(cluster);
        
        // 2. Categorize variations into parameters
        List<Parameter> parameters = new ArrayList<>();
        for (Variation var : analysis.variations()) {
            parameters.add(createParameter(var));
        }
        
        // 3. Generate method signature
        String methodName = generateMethodName(cluster);
        String returnType = inferReturnType(cluster);
        
        return new MethodSignature(methodName, returnType, parameters);
    }
    
    private VariationAnalysis analyzeVariations(DuplicateCluster cluster) {
        // Compare normalized vs original tokens to find variations
        List<Variation> variations = new ArrayList<>();
        
        StatementSequence primary = cluster.primary();
        for (SimilarityPair duplicate : cluster.duplicates()) {
            // Find differing literals, variable names, method calls
            List<Token> normalizedPrimary = normalizer.normalize(primary);
            List<Token> normalizedDup = normalizer.normalize(duplicate.seq2());
            
            // Compare original ASTs to find what differs
            for (int i = 0; i < primary.statements().size(); i++) {
                Statement stmt1 = primary.statements().get(i);
                Statement stmt2 = duplicate.seq2().statements().get(i);
                
                findDifferences(stmt1, stmt2, variations);
            }
        }
        
        return new VariationAnalysis(variations);
    }
}
```

### 1.3 Example: Test Setup Refactoring

**Before:**
```java
@Test
void testCreateAdmission() {
    Admission admission = new Admission();
    admission.setPatientId("P123");
    admission.setHospitalId("H456");
    admission.setStatus("PENDING");
    admission.setAdmissionDate(LocalDate.now());
    // ... test logic
}

@Test
void testUpdateAdmission() {
    Admission admission = new Admission();
    admission.setPatientId("P999");
    admission.setHospitalId("H888");
    admission.setStatus("APPROVED");
    admission.setAdmissionDate(LocalDate.now());
    // ... test logic
}
```

**Analysis:**
- Variations: `patientId`, `hospitalId`, `status`
- Common: object creation, `admissionDate`

**After (Automated Refactoring):**
```java
private Admission createTestAdmission(String patientId, String hospitalId, String status) {
    Admission admission = new Admission();
    admission.setPatientId(patientId);
    admission.setHospitalId(hospitalId);
    admission.setStatus(status);
    admission.setAdmissionDate(LocalDate.now());
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

### 1.4 Example: General Code Refactoring

**Before:**
```java
// In ServiceA
public void processUser(Long userId) {
    User user = userRepository.findById(userId).orElse(null);
    if (user != null && user.isActive()) {
        user.setLastProcessed(LocalDateTime.now());
        userRepository.save(user);
        logger.info("Processed user: {}", userId);
    }
}

// In ServiceB
public void updateCustomer(Long customerId) {
    Customer customer = customerRepository.findById(customerId).orElse(null);
    if (customer != null && customer.isActive()) {
        customer.setLastProcessed(LocalDateTime.now());
        customerRepository.save(customer);
        logger.info("Processed customer: {}", customerId);
    }
}
```

**After (Extract to Utility):**
```java
// New utility class
public class EntityProcessingUtil {
    public static <T extends Processable> void processIfActive(
            Long id,
            JpaRepository<T, Long> repository,
            Logger logger,
            String entityType
    ) {
        T entity = repository.findById(id).orElse(null);
        if (entity != null && entity.isActive()) {
            entity.setLastProcessed(LocalDateTime.now());
            repository.save(entity);
            logger.info("Processed {}: {}", entityType, id);
        }
    }
}

// Refactored services
public void processUser(Long userId) {
    EntityProcessingUtil.processIfActive(userId, userRepository, logger, "user");
}

public void updateCustomer(Long customerId) {
    EntityProcessingUtil.processIfActive(customerId, customerRepository, logger, "customer");
}
```

---

## 2. Interactive Review Workflow

### 2.1 Review Modes

#### Automatic Mode
- Apply refactorings without user intervention
- Only for high-confidence cases (similarity > 95%)
- Run tests after each refactoring
- Rollback on failure

#### Interactive Mode (Recommended)
- Present each refactoring for user approval
- Show side-by-side diff
- Allow editing of generated method names/parameters
- Apply only approved changes

#### Batch Mode
- Review all refactorings upfront
- Apply all approved changes at once
- Useful for large-scale cleanups

### 2.2 Interactive CLI Interface

```
Code Duplication Refactoring Tool
==================================

Found 23 duplicate clusters. Review and approve refactorings:

Cluster #1: Test setup duplication (Similarity: 94.1%)
  Files affected: AdmissionServiceImplTest.java
  Methods: testCreateAdmission, testUpdateAdmission, testDeleteAdmission
  Duplicated lines: 8 lines × 3 occurrences = 24 total

  Proposed Refactoring: Extract helper method
  
  [BEFORE - testCreateAdmission L45-52]
  │ Admission admission = new Admission();
  │ admission.setPatientId("P123");
  │ admission.setHospitalId("H456");
  │ admission.setAdmissionDate(LocalDate.now());
  │ admission.setStatus("PENDING");
  │ when(admissionRepository.save(any())).thenReturn(admission);
  
  [AFTER]
  │ private Admission createTestAdmission(String patientId, String hospitalId, String status) {
  │     Admission admission = new Admission();
  │     admission.setPatientId(patientId);
  │     admission.setHospitalId(hospitalId);
  │     admission.setAdmissionDate(LocalDate.now());
  │     admission.setStatus(status);
  │     when(admissionRepository.save(any())).thenReturn(admission);
  │     return admission;
  │ }
  │
  │ // In testCreateAdmission:
  │ Admission admission = createTestAdmission("P123", "H456", "PENDING");
  
  Options:
    [a] Apply this refactoring
    [e] Edit method name/parameters
    [s] Skip this cluster
    [v] View full context
    [q] Quit and save progress
  
  Your choice: _
```

### 2.3 Web-Based Review Interface (Future)

```html
<!-- Interactive review dashboard -->
<div class="refactoring-review">
  <div class="cluster-summary">
    <h3>Cluster #1 - Test Setup (94.1% similar)</h3>
    <span class="impact">Impact: -24 lines</span>
    <span class="files">Files: 1</span>
  </div>
  
  <div class="diff-viewer">
    <split-diff before="..." after="..."></split-diff>
  </div>
  
  <div class="actions">
    <button class="approve">✓ Approve</button>
    <button class="edit">✎ Edit</button>
    <button class="skip">→ Skip</button>
  </div>
</div>
```

---

## 3. Refactoring Implementation

### 3.1 AST Transformation Strategy

```java
public class DuplicateRefactorer {
    
    public RefactoringResult applyExtractMethod(
        DuplicateCluster cluster,
        MethodSignature signature,
        RefactoringConfig config
    ) {
        try {
            // 1. Create the extracted method
            MethodDeclaration extractedMethod = createExtractedMethod(cluster, signature);
            
            // 2. Insert method in appropriate location
            ClassOrInterfaceDeclaration targetClass = findTargetClass(cluster);
            insertMethod(targetClass, extractedMethod);
            
            // 3. Replace all duplicate occurrences with method calls
            for (StatementSequence seq : cluster.allSequences()) {
                replaceWithMethodCall(seq, signature);
            }
            
            // 4. Add necessary imports
            addRequiredImports(targetClass.findCompilationUnit().get(), signature);
            
            // 5. Format and save
            formatAndSave(targetClass);
            
            // 6. Verify compilation
            if (config.verifyCompilation()) {
                verifyCompiles();
            }
            
            // 7. Run tests if requested
            if (config.runTests()) {
                TestResult result = runTests();
                if (!result.allPassed()) {
                    rollback();
                    return RefactoringResult.failed("Tests failed after refactoring");
                }
            }
            
            return RefactoringResult.success();
            
        } catch (Exception e) {
            rollback();
            return RefactoringResult.failed(e.getMessage());
        }
    }
    
    private MethodDeclaration createExtractedMethod(
        DuplicateCluster cluster,
        MethodSignature signature
    ) {
        MethodDeclaration method = new MethodDeclaration();
        method.setName(signature.name());
        method.setType(signature.returnType());
        method.setModifiers(Modifier.Keyword.PRIVATE);
        
        // Add parameters
        for (Parameter param : signature.parameters()) {
            method.addParameter(param.type(), param.name());
        }
        
        // Build method body from primary sequence
        BlockStmt body = new BlockStmt();
        for (Statement stmt : cluster.primary().statements()) {
            // Replace varying parts with parameters
            Statement transformed = replaceVariationsWithParams(stmt, signature);
            body.addStatement(transformed);
        }
        
        // Add return statement if needed
        if (!signature.returnType().equals("void")) {
            body.addStatement(new ReturnStmt(/* infer return expression */));
        }
        
        method.setBody(body);
        return method;
    }
    
    private void replaceWithMethodCall(
        StatementSequence sequence,
        MethodSignature signature
    ) {
        // Extract actual argument values from this instance
        List<Expression> arguments = extractArgumentValues(sequence, signature);
        
        // Create method call expression
        MethodCallExpr methodCall = new MethodCallExpr(signature.name());
        arguments.forEach(methodCall::addArgument);
        
        // Handle return value if applicable
        Statement replacement;
        if (!signature.returnType().equals("void")) {
            // Need to capture return value
            VariableDeclarationExpr varDecl = findVariableDeclaration(sequence);
            ExpressionStmt assignStmt = new ExpressionStmt(
                new VariableDeclarationExpr(
                    new VariableDeclarator(
                        new ClassOrInterfaceType(signature.returnType()),
                        varDecl.getVariables().get(0).getNameAsString(),
                        methodCall
                    )
                )
            );
            replacement = assignStmt;
        } else {
            replacement = new ExpressionStmt(methodCall);
        }
        
        // Replace block in parent
        BlockStmt parent = (BlockStmt) sequence.statements().get(0).getParentNode().get();
        int startIndex = parent.getStatements().indexOf(sequence.statements().get(0));
        
        // Remove old statements
        for (int i = 0; i < sequence.statements().size(); i++) {
            parent.remove(sequence.statements().get(i));
        }
        
        // Insert new statement
        parent.getStatements().add(startIndex, replacement);
    }
}
```

### 3.2 Test-Specific Patterns

#### Pattern 1: Extract to @BeforeEach

For duplicates that appear at the start of multiple test methods:

```java
public class TestSetupRefactorer {
    
    public boolean canExtractToBeforeEach(DuplicateCluster cluster) {
        // Check if all sequences are at the start of @Test methods
        return cluster.allSequences().stream()
            .allMatch(seq -> {
                MethodDeclaration method = findContainingMethod(seq);
                return method.getAnnotationByName("Test").isPresent() &&
                       seq.startOffset() == 0;
            });
    }
    
    public void extractToBeforeEach(DuplicateCluster cluster) {
        // Create @BeforeEach method
        MethodDeclaration beforeEach = new MethodDeclaration();
        beforeEach.setName("setUp");
        beforeEach.addAnnotation("BeforeEach");
        beforeEach.setModifiers(Modifier.Keyword.PUBLIC);
        
        // Move common setup code
        BlockStmt body = new BlockStmt();
        // Promote local variables to class fields
        for (Statement stmt : cluster.primary().statements()) {
            if (stmt.isExpressionStmt() && 
                stmt.asExpressionStmt().getExpression().isVariableDeclarationExpr()) {
                
                VariableDeclarationExpr varDecl = 
                    stmt.asExpressionStmt().getExpression().asVariableDeclarationExpr();
                
                // Add as field
                addFieldToClass(varDecl);
                
                // Transform to assignment in @BeforeEach
                body.addStatement(createAssignment(varDecl));
            } else {
                body.addStatement(stmt.clone());
            }
        }
        
        beforeEach.setBody(body);
        
        // Insert @BeforeEach method
        ClassOrInterfaceDeclaration testClass = findTestClass(cluster);
        testClass.addMember(beforeEach);
        
        // Remove duplicates from all test methods
        for (StatementSequence seq : cluster.allSequences()) {
            removeStatements(seq);
        }
    }
}
```

**Example:**

**Before:**
```java
public class AdmissionServiceTest {
    @Test
    void testCreate() {
        Admission admission = new Admission();
        admission.setStatus("PENDING");
        when(repository.save(any())).thenReturn(admission);
        // test logic
    }
    
    @Test
    void testUpdate() {
        Admission admission = new Admission();
        admission.setStatus("APPROVED");
        when(repository.save(any())).thenReturn(admission);
        // test logic
    }
}
```

**After:**
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
        admission.setStatus("PENDING");
        // test logic
    }
    
    @Test
    void testUpdate() {
        admission.setStatus("APPROVED");
        // test logic
    }
}
```

---

## 4. Safety Validations

### 4.1 Pre-Refactoring Checks

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
        
        // 3. All variations captured as parameters
        if (!allVariationsCaptured(cluster, signature)) {
            issues.add(ValidationIssue.warning("Some variations not parameterized"));
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

### 4.2 Post-Refactoring Verification

```java
public class RefactoringVerifier {
    
    public VerificationResult verify(RefactoringResult refactoring) {
        // 1. Compilation check
        CompilationResult compilation = compileProject();
        if (!compilation.success()) {
            return VerificationResult.failed("Compilation failed: " + compilation.errors());
        }
        
        // 2. Test execution
        TestResult tests = runAffectedTests(refactoring.affectedClasses());
        if (!tests.allPassed()) {
            return VerificationResult.failed("Tests failed: " + tests.failures());
        }
        
        // 3. Code coverage comparison (optional)
        if (config.checkCoverage()) {
            CoverageResult coverage = compareCoverage();
            if (coverage.decreased()) {
                return VerificationResult.warning("Coverage decreased");
            }
        }
        
        return VerificationResult.success();
    }
}
```

---

## 5. Integration with TestFixer

### 5.1 Unified Workflow

```java
public class TestFixerIntegration {
    
    public void runCompleteTestCleanup(Path projectRoot) {
        // 1. Run existing TestFixer checks
        TestFixer testFixer = new TestFixer();
        testFixer.fixAnnotations();
        testFixer.removeTestsWithoutAssertions();
        testFixer.migrateToJUnit5();
        
        // 2. Run duplication detection
        DuplicationAnalyzer analyzer = new DuplicationAnalyzer(
            DuplicationConfig.moderate()
        );
        DuplicationReport report = analyzer.analyzeProject(projectRoot);
        
        // 3. Auto-refactor test-specific patterns
        TestDuplicationRefactorer refactorer = new TestDuplicationRefactorer();
        for (DuplicateCluster cluster : report.clusters()) {
            if (refactorer.canAutoRefactor(cluster)) {
                refactorer.applyRefactoring(cluster);
            }
        }
        
        // 4. Present remaining duplicates for review
        InteractiveReviewer reviewer = new InteractiveReviewer();
        reviewer.reviewAndApply(report.remainingClusters());
        
        // 5. Verify all tests still pass
        runTests();
    }
}
```

---

## 6. Configuration

```java
public record RefactoringConfig(
    boolean interactive,           // Enable interactive review
    boolean runTests,             // Run tests after refactoring
    boolean verifyCompilation,    // Verify compilation
    double autoApplyThreshold,    // Auto-apply if similarity > threshold (0.95+)
    boolean createBackup,         // Create backup before refactoring
    String extractedMethodPrefix, // Prefix for generated methods (e.g., "create", "setup")
    boolean extractToBeforeEach,  // Prefer @BeforeEach for test setup
    boolean promoteToFields       // Promote local vars to fields in tests
) {
    public static RefactoringConfig safe() {
        return new RefactoringConfig(
            true,    // interactive
            true,    // runTests
            true,    // verifyCompilation
            0.98,    // very high threshold for auto-apply
            true,    // createBackup
            "create",
            true,
            true
        );
    }
    
    public static RefactoringConfig aggressive() {
        return new RefactoringConfig(
            false,   // non-interactive
            true,
            true,
            0.95,
            true,
            "create",
            true,
            true
        );
    }
}
```

---

## 7. Command-Line Interface

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

# Dry-run mode (show what would be refactored)
java -jar duplication-detector.jar refactor \
  --project /path/to/project \
  --dry-run \
  --output refactoring-plan.json

# Test-only refactoring
java -jar duplication-detector.jar refactor \
  --project /path/to/project \
  --tests-only \
  --prefer-before-each
```

---

## Summary

Phase 2 transforms the duplication detector into a **complete refactoring solution**:

✅ **Automated extraction** of helper methods, utilities, @BeforeEach setup  
✅ **Interactive review** with side-by-side diffs and approval workflow  
✅ **Safety validations** including compilation checks and test execution  
✅ **Test-specific patterns** like @BeforeEach extraction and field promotion  
✅ **Seamless integration** with existing TestFixer infrastructure  
✅ **Configurable modes** from safe/interactive to aggressive/automatic  

This creates a powerful tool for both **detecting and eliminating** code duplication across general code and test suites.
