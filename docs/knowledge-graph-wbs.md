# Knowledge Graph Builder: Work Breakdown Structure

## Quality Standards
*   **Testing**: Each phase includes tests targeting ≥80% branch coverage.
*   **Complexity**: No function shall exceed cognitive complexity of 20.

---

## Phase 1: Antikythera Core Improvements
- [ ] **Task 1.1: Expand `getNodeSignature` in `DependencyAnalyzer`**
    - [ ] Handle `FieldDeclaration`, `TypeDeclaration`, `InitializerDeclaration`
- [ ] **Task 1.2: Add `createVisitor()` hook to `DependencyAnalyzer`**
    - [ ] Refactor `callableSearch()` to call protected factory method
- [ ] **Task 1.3: Add `FieldAccessExpr` visitor**
    - [ ] Implement `visit(FieldAccessExpr, GraphNode)` for read-only accesses
- [ ] **Task 1.4: Add `onTypeUsed` hook**
    - [ ] Intercept type dependencies from `ImportUtils.addImport`
- [ ] **Task 1.5: Add Inner Class/Lambda traversal**
    - [ ] Add `visit(ClassOrInterfaceDeclaration, GraphNode)` for nested types
    - [ ] Add `visit(LambdaExpr, GraphNode)` for lambdas
    - [ ] Add `visit(ObjectCreationExpr, GraphNode)` for anonymous classes
- [ ] **Task 1.6: Phase 1 Unit Tests (80% coverage)**

## Phase 2: Data Structures & Neo4j Store
- [ ] **Task 2.1: Stable Signature Utility**
    - [ ] Create `SignatureUtils` for method, field, class, static-block signatures
- [ ] **Task 2.2: Edge Schema**
    - [ ] Implement `EdgeType` enum (7 types)
    - [ ] Implement `KnowledgeGraphEdge` record + Builder
- [ ] **Task 2.3: Neo4jGraphStore**
    - [ ] Add Neo4j Java Driver dependency
    - [ ] Create `Neo4jConfig` for connection settings
    - [ ] Implement `persistNode(GraphNode)` method
    - [ ] Implement `persistEdge(KnowledgeGraphEdge)` method
    - [ ] Implement batch commit logic (configurable batch size)
- [ ] **Task 2.4: Phase 2 Unit Tests (80% coverage)**
    - [ ] Create test schema before each test
    - [ ] Tear down test schema after each test

## Phase 3: Builder & Streaming
- [ ] **Task 3.1: KnowledgeGraphBuilder**
    - [ ] Extend `DependencyAnalyzer`
    - [ ] Inject `Neo4jGraphStore` dependency
    - [ ] Stream edges to Neo4j during DFS (no in-memory accumulation)
- [ ] **Task 3.2: Build Orchestration**
    - [ ] Implement `build(List<CompilationUnit>)` entry point
    - [ ] Ensure proper transaction management
- [ ] **Task 3.3: Phase 3 Unit Tests (80% coverage)**

## Phase 4: Relationship Extraction
- [ ] **Task 4.1: Hook Integration**
    - [ ] Override `onCallableDiscovered` for CALLS
    - [ ] Override `onTypeUsed` for USES
- [ ] **Task 4.2: CALLS Edges**
    - [ ] Capture method call targets
    - [ ] Integrate `Evaluator.evaluateLiteral` for constant values
- [ ] **Task 4.3: ACCESSES Edges**
    - [ ] Capture field accesses with READ/WRITE detection
- [ ] **Task 4.4: Structural Edges**
    - [ ] CONTAINS from `TypeDeclaration.getMembers()`
    - [ ] ENCLOSES for inner classes, anonymous classes, lambdas
    - [ ] IMPLEMENTS/EXTENDS via `AntikytheraRunTime`
- [ ] **Task 4.5: Phase 4 Unit Tests (80% coverage)**

## Phase 5: Query API & Integration
- [ ] **Task 5.1: Cypher Query Wrappers**
    - [ ] `findCallers(methodSignature)`
    - [ ] `findCallees(methodSignature)`
    - [ ] `findUsages(typeSignature)`
- [ ] **Task 5.2: Integration Validation**
    - [ ] Run against `antikythera-test-helper` samples
    - [ ] Run against `test-bed` Spring Boot projects
    - [ ] Validate cross-service dependency mapping
