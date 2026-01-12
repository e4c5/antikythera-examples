# Knowledge Graph Builder: Work Breakdown Structure

## Quality Standards
*   **Testing**: Each phase includes tests targeting ≥80% branch coverage.
*   **Complexity**: No function shall exceed cognitive complexity of 20.

---

## Phase 1: Antikythera Core Improvements
- [ ] **Task 1.1: Expand `getNodeSignature` in `DependencyAnalyzer`**
    - [ ] Handle `FieldDeclaration` nodes
    - [ ] Handle `TypeDeclaration` nodes
    - [ ] Handle `InitializerDeclaration` (static blocks)
- [ ] **Task 1.2: Add `createVisitor()` hook to `DependencyAnalyzer`**
    - [ ] Refactor `callableSearch()` to call a protected factory method
    - [ ] Allow subclasses to provide custom `DependencyVisitor` instances
- [ ] **Task 1.3: Add `FieldAccessExpr` visitor to `DependencyVisitor`**
    - [ ] Implement `visit(FieldAccessExpr, GraphNode)` for read-only accesses
- [ ] **Task 1.4: Add `onTypeUsed` hook**
    - [ ] Allow subclasses to intercept type dependencies from `ImportUtils.addImport`
- [ ] **Task 1.5: Phase 1 Unit Tests**
    - [ ] Tests for expanded signature methods
    - [ ] Tests for visitor hooks
    - [ ] Verify 80% branch coverage

## Phase 2: Core Data Structures
- [ ] **Task 2.1: Stable Signature Utility**
    - [ ] Create `sa.com.cloudsolutions.antikythera.graph.SignatureUtils`
    - [ ] Implement method, field, class, and static-block signatures
- [ ] **Task 2.2: Edge Schema & Builder**
    - [ ] Implement `EdgeType` enum (CONTAINS, CALLS, ACCESSES, USES, IMPLEMENTS, EXTENDS)
    - [ ] Implement `KnowledgeGraphEdge` record
    - [ ] Implement `KnowledgeGraphEdge.Builder` for fluent construction
- [ ] **Task 2.3: Graph Container**
    - [ ] Implement `KnowledgeGraph` record/class to hold result sets
- [ ] **Task 2.4: Phase 2 Unit Tests**
    - [ ] Verify signature generation for edge cases
    - [ ] Verify edge builder
    - [ ] Verify 80% branch coverage

## Phase 3: Builder & Traversal
- [ ] **Task 3.1: KnowledgeGraphBuilder Skeleton**
    - [ ] Create `KnowledgeGraphBuilder` extending `DependencyAnalyzer`
    - [ ] Implement internal storage for edges and metadata side-car
- [ ] **Task 3.2: Build Orchestration**
    - [ ] Implement `build(List<CompilationUnit>)` entry point
    - [ ] Ensure `resetAnalysis()` is called correctly before runs
- [ ] **Task 3.3: Phase 3 Unit Tests**
    - [ ] Verify builder instantiation
    - [ ] Verify traversal with minimal input
    - [ ] Verify 80% branch coverage

## Phase 4: Relationship Extraction
- [ ] **Task 4.1: Hook Integration**
    - [ ] Override `onCallableDiscovered` for CALLS data
    - [ ] Override `onImportDiscovered` for USES data
    - [ ] Override `onTypeUsed` for type dependencies
- [ ] **Task 4.2: Behavioral Edges (CALLS)**
    - [ ] Capture method call targets from `Resolver.resolveArgumentTypes` results
    - [ ] Integrate `Evaluator.evaluateLiteral` for constant argument values
- [ ] **Task 4.3: Behavioral Edges (ACCESSES)**
    - [ ] Capture `AssignExpr`/`FieldAccessExpr` via custom visitor
    - [ ] Determine READ vs WRITE access type
- [ ] **Task 4.4: Structural Edges (CONTAINS, IMPLEMENTS, EXTENDS)**
    - [ ] Extract CONTAINS from `TypeDeclaration.getMembers()`
    - [ ] Query `AntikytheraRunTime.findSubClasses` and `findImplementations` post-scan
- [ ] **Task 4.5: Phase 4 Unit Tests**
    - [ ] Verify each edge type extraction
    - [ ] Verify 80% branch coverage

## Phase 5: Neo4j Storage & Export
- [ ] **Task 5.1: Neo4j Driver Integration**
    - [ ] Add Neo4j Java Driver dependency
    - [ ] Create `Neo4jConfig` for connection settings
    - [ ] Implement `Neo4jGraphStore` class
- [ ] **Task 5.2: Schema & Persistence**
    - [ ] Define node labels and relationship types
    - [ ] Implement `persist(KnowledgeGraph)` method
    - [ ] Handle upsert logic for incremental updates
- [ ] **Task 5.3: Query API**
    - [ ] Implement basic Cypher query wrappers
    - [ ] Add `findCallers(methodSignature)` and `findCallees(methodSignature)`
- [ ] **Task 5.4: Phase 5 Unit Tests**
    - [ ] Create test schema before each test
    - [ ] Tear down test schema after each test
    - [ ] Verify CRUD operations
    - [ ] Verify 80% branch coverage

## Phase 6: Integration Validation
- [ ] **Task 6.1: Test-Helper Validation**
    - [ ] Run against `antikythera-test-helper` samples
    - [ ] Verify static blocks and lambda capture
- [ ] **Task 6.2: Test-Bed Integration**
    - [ ] Run against `test-bed` Spring Boot projects
    - [ ] Validate cross-service dependency mapping
