# Knowledge Graph Builder Design

## 1. Overview
The Knowledge Graph Builder maps structural and behavioral relationships within Java source code by leveraging the **Antikythera** engine. Edges are **streamed directly to Neo4j** during traversal to support large codebases without memory pressure.

**Output**: A Neo4j graph where nodes represent code elements and edges represent relationships.

## 2. Quality Standards
*   **Testing**: Each phase includes unit tests targeting ≥80% branch coverage.
*   **Complexity**: No function shall exceed cognitive complexity of 20.

## 3. Technical Architecture

### 3.1 Core Engine Integration
*   **Base Class**: `sa.com.cloudsolutions.antikythera.depsolver.DependencyAnalyzer`.
*   **Compiler Interface**: `sa.com.cloudsolutions.antikythera.parser.AbstractCompiler`.

### 3.2 Data Model

#### Nodes
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
| `ENCLOSES` | Structural | Outer class encloses Inner class/Lambda |
| `IMPLEMENTS` | Structural | Class implements Interface |
| `EXTENDS` | Structural | Class extends Class |
| `CALLS` | Behavioral | Method invokes Method |
| `ACCESSES` | Behavioral | Method reads/writes Field |
| `USES` | Dependency | Method uses Type |

### 3.3 Streaming Architecture
Edges are **NOT accumulated in memory**. As each edge is discovered during DFS traversal, it is immediately persisted to Neo4j via batch transactions.

```
Traverse AST → discover edge → Neo4jGraphStore.persistEdge(edge) → commit every N edges
```

### 3.4 Neo4j Storage

#### Schema
*   **Nodes**: Labeled by type (`Class`, `Method`, `Field`, `StaticBlock`).
*   **Properties**: `signature`, `name`, `fqn`, `lineNumber`.
*   **Relationships**: Labeled by edge type with `attributes` map.

#### Configuration (`application.yml`)
```yaml
antikythera:
  graph:
    neo4j:
      uri: bolt://localhost:7687
      username: neo4j
      password: ${NEO4J_PASSWORD}
      database: antikythera
      batchSize: 1000
```

## 4. Implementation Plan

### Phase 1: Antikythera Core Improvements
*   **Task 1.1-1.5**: Signature expansion, visitor hooks, inner class/lambda traversal.
*   **Task 1.6**: Unit tests (80% coverage).

### Phase 2: Data Structures & Neo4j Store
*   **Task 2.1**: `SignatureUtils` for deterministic IDs.
*   **Task 2.2**: `EdgeType` enum + `KnowledgeGraphEdge` record.
*   **Task 2.3**: `Neo4jGraphStore` with `persistNode()` and `persistEdge()`.
*   **Task 2.4**: Unit tests (80% coverage).

### Phase 3: Builder & Streaming
*   **Task 3.1**: `KnowledgeGraphBuilder` with injected `Neo4jGraphStore`.
*   **Task 3.2**: `build(List<CompilationUnit>)` entry point with streaming.
*   **Task 3.3**: Unit tests (80% coverage).

### Phase 4: Relationship Extraction
*   **Task 4.1-4.4**: CALLS, ACCESSES, structural edges.
*   **Task 4.5**: Unit tests (80% coverage).

### Phase 5: Query API & Integration
*   **Task 5.1**: Cypher query wrappers (`findCallers`, `findCallees`).
*   **Task 5.2**: Integration tests with test-bed/test-helper.

## Appendix: Critical Findings

1.  **getNodeSignature() incomplete**: Only handles `CallableDeclaration`.
2.  **No `createVisitor()` hook**: `DependencyVisitor` instantiated inline.
3.  **`FieldAccessExpr` not visited**: Relies on `ExpressionStmt` handling.
4.  **Inner class/lambda not traversed**: Requires new visitor methods.
