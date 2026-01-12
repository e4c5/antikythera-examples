# Knowledge Graph Builder Design

## 1. Overview
The Knowledge Graph Builder maps structural and behavioral relationships within Java source code by leveraging the **Antikythera** engine.

**Output**: A directed graph where nodes represent code elements (Classes, Methods, Fields, Static Blocks) and edges represent relationships (Calls, Accesses, Uses, Contains, Implements, Extends).

## 2. Quality Standards
*   **Testing**: Each phase includes unit tests targeting ≥80% branch coverage.
*   **Complexity**: No function shall exceed cognitive complexity of 20.

## 3. Technical Architecture

### 3.1 Core Engine Integration
*   **Base Class**: `sa.com.cloudsolutions.antikythera.depsolver.DependencyAnalyzer` (DFS traversal, node discovery).
*   **Compiler Interface**: `sa.com.cloudsolutions.antikythera.parser.AbstractCompiler` (parsing, type resolution).

### 3.2 Data Model

#### Nodes (`GraphNode`)
Reuses `sa.com.cloudsolutions.antikythera.depsolver.GraphNode`.
*   **Identity**: Stable Signature (FQN + logical name/params).
*   **Supported Types**: `TypeDeclaration`, `CallableDeclaration`, `FieldDeclaration`, `InitializerDeclaration`.

#### Edges (`KnowledgeGraphEdge`)
```java
public record KnowledgeGraphEdge(
    String sourceId, String targetId, EdgeType type, Map<String, String> attributes
) {}
```

#### Edge Types
| Type | Category | Description |
| :--- | :--- | :--- |
| `CONTAINS` | Structural | Class contains Member |
| `IMPLEMENTS` | Structural | Class implements Interface |
| `EXTENDS` | Structural | Class extends Class |
| `CALLS` | Behavioral | Method/Block invokes Method |
| `ACCESSES` | Behavioral | Method/Block reads/writes Field |
| `USES` | Dependency | Method uses Type/Enum |

### 3.3 Graph Storage (Neo4j)

The Knowledge Graph is persisted to **Neo4j**, enabling powerful graph queries via Cypher.

#### Neo4j Schema
*   **Nodes**: Labeled by type (`Class`, `Method`, `Field`, `StaticBlock`).
*   **Node Properties**: `signature` (unique ID), `name`, `fqn`, `lineNumber`.
*   **Relationships**: Labeled by edge type (`CALLS`, `ACCESSES`, `CONTAINS`, etc.).
*   **Relationship Properties**: `attributes` map (e.g., parameter values).

#### Connection Configuration
Via `application.yml`:
```yaml
antikythera:
  graph:
    neo4j:
      uri: bolt://localhost:7687
      username: neo4j
      password: ${NEO4J_PASSWORD}
      database: antikythera
```

## 4. Implementation Plan

### Phase 1: Antikythera Core Improvements
*   **Task 1.1**: Expand `getNodeSignature` for fields, types, static blocks.
*   **Task 1.2**: Add `createVisitor()` hook for custom visitors.
*   **Task 1.3**: Add `FieldAccessExpr` visitor for read-only accesses.
*   **Task 1.4**: Add `onTypeUsed` hook for type dependencies.
*   **Task 1.5**: Phase 1 Unit Tests (80% coverage).

### Phase 2: Core Data Structures
*   **Task 2.1**: `SignatureUtils` for deterministic IDs.
*   **Task 2.2**: `EdgeType` enum + `KnowledgeGraphEdge` record + Builder.
*   **Task 2.3**: `KnowledgeGraph` container class.
*   **Task 2.4**: Phase 2 Unit Tests (80% coverage).

### Phase 3: Builder & Traversal
*   **Task 3.1**: `KnowledgeGraphBuilder` extending `DependencyAnalyzer`.
*   **Task 3.2**: `build(List<CompilationUnit>)` entry point.
*   **Task 3.3**: Phase 3 Unit Tests (80% coverage).

### Phase 4: Relationship Extraction
*   **Task 4.1**: Override hooks (`onCallableDiscovered`, `onTypeUsed`).
*   **Task 4.2**: CALLS edges with argument resolution.
*   **Task 4.3**: ACCESSES edges with READ/WRITE detection.
*   **Task 4.4**: Structural edges (CONTAINS, IMPLEMENTS, EXTENDS).
*   **Task 4.5**: Phase 4 Unit Tests (80% coverage).

### Phase 5: Export
*   **Task 5.1**: JSON serializer for `KnowledgeGraph`.
*   **Task 5.2**: Phase 5 Unit Tests (80% coverage).

### Phase 6: Integration Validation
*   **Task 6.1**: `antikythera-test-helper` validation.
*   **Task 6.2**: `test-bed` Spring Boot integration.

## 5. Advanced Reuse
*   **AntikytheraRunTime**: Pre-populated inheritance maps.
*   **Evaluator**: Literal resolution for call arguments.
*   **SymbolResolver**: Lombok and import resolution.

## Appendix: Critical Findings from Code Review

> [!IMPORTANT]
> The following findings from code review require Phase 1 improvements.

1.  **getNodeSignature() is incomplete**: Only handles `CallableDeclaration`.
2.  **No `createVisitor()` hook**: `DependencyVisitor` is instantiated inline.
3.  **`FieldAccessExpr` not directly visited**: Relies on `ExpressionStmt` handling.
4.  **`USES` edge needs explicit extraction**: Type dependencies via `ImportUtils.addImport`.

## 6. Verification & Testing

### Test Datasets
*   **`test-bed`**: Spring Boot applications (bean dependencies, cross-service calls).
*   **`antikythera-test-helper`**: Edge cases (static blocks, lambdas, overloaded methods).

### Verification Strategy
1.  **Node/Edge Parity**: JSON vs manual inspection.
2.  **Structural Integrity**: IMPLEMENTS/EXTENDS vs `AntikytheraRunTime` maps.
3.  **Behavioral Accuracy**: CALLS edges in polymorphic scenarios.
