# Knowledge Graph Builder Design

## 1. Overview
The Knowledge Graph Builder maps structural and behavioral relationships within Java source code by leveraging the **Antikythera** engine. Edges are **streamed directly to the graph store** during traversal to support large codebases without memory pressure.

**Output**: A graph (Neo4j or Apache AGE) where nodes represent code elements and edges represent relationships.

**Supported Backends**:
*   **Neo4j** — Bolt protocol via the official Neo4j Java driver.
*   **Apache AGE** — PostgreSQL extension accessed via JDBC.

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
*   **Supported Types**: `TypeDeclaration` (Class/Interface/Enum), `CallableDeclaration`, `FieldDeclaration`, `InitializerDeclaration`, `EnumConstantDeclaration`, `LambdaExpr`.

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
| `REFERENCES` | Behavioral | Method references Method (Method Reference) |

### 3.3 Streaming Architecture
Edges are **NOT accumulated in memory**. As each edge is discovered during DFS traversal, it is immediately persisted to the graph store via batch transactions.

```
Traverse AST → discover edge → GraphStore.persistEdge(edge) → commit every N edges
```

### 3.4 Graph Storage

#### Schema
*   **Nodes**: Labeled by type (`Class`, `Interface`, `Enum`, `Method`, `Field`, `StaticBlock`, `Lambda`).
*   **Properties**: `signature`, `name`, `fqn`, `lineNumber`.
*   **Relationships**: Labeled by edge type with `attributes` map.

#### Backend Abstraction
The `GraphStore` interface decouples the builder from any specific database. `GraphStoreFactory` reads the `graph:` section from the YAML configuration (loaded via `Settings`) and creates the appropriate implementation.

```
GraphStore (interface)
├── Neo4jGraphStore    — Bolt protocol, batched Cypher UNWIND
└── ApacheAgeGraphStore — JDBC, SQL-wrapped Cypher
```

#### Configuration (`graph.yml`)
All graph settings live under a single `graph:` key. The `type` field selects the backend; `batch_size` is shared.

**Neo4j**
```yaml
graph:
  type: neo4j
  batch_size: 1000
  neo4j:
    uri: bolt://localhost:7687
    username: neo4j
    password: ${NEO4J_PASSWORD}
    database: neo4j
```

**Apache AGE**
```yaml
graph:
  type: age
  batch_size: 1000
  age:
    url: jdbc:postgresql://localhost:5432/postgres
    user: postgres
    password: ${AGE_PASSWORD}
    graph_name: antikythera_graph
```

Sample configuration files are provided in `src/main/resources/`:
*   `graph-neo4j.yml.example`
*   `graph-age.yml.example`

## 4. Implementation Plan

### Phase 1: Antikythera Core Improvements
*   **Task 1.1-1.5**: Signature expansion, visitor hooks, inner class/lambda traversal.
*   **Task 1.6**: Unit tests (80% coverage).

### Phase 2: Data Structures & Graph Store
*   **Task 2.1**: `SignatureUtils` for deterministic IDs.
*   **Task 2.2**: `EdgeType` enum + `KnowledgeGraphEdge` record.
*   **Task 2.3**: `GraphStore` interface + `Neo4jGraphStore` and `ApacheAgeGraphStore` implementations.
*   **Task 2.4**: `GraphStoreFactory` — reads `graph:` config from `Settings`, creates the right backend.
*   **Task 2.5**: Unit tests (80% coverage).

### Phase 3: Builder & Streaming
*   **Task 3.1**: `KnowledgeGraphBuilder` with injected `GraphStore`.
*   **Task 3.2**: `build(List<CompilationUnit>)` entry point with streaming.
*   **Task 3.3**: Unit tests (80% coverage).

### Phase 4: Relationship Extraction
*   **Task 4.1-4.4**: CALLS, ACCESSES, structural edges.
*   **Task 4.5**: Unit tests (80% coverage).

### Phase 5: Query API & Integration
*   **Task 5.1**: Cypher query wrappers (`findCallers`, `findCallees`).
*   **Task 5.2**: Integration tests with test-bed/test-helper.

## 5. Modeling Guidelines

### 5.1 Enums
Enums are treated as `TypeDeclaration` nodes. Their constants are linked via `CONTAINS` edges.

### 5.2 Lambdas and Functional Interfaces
*   **Lambdas**: Modeled as distinct nodes with a generated stable signature. All behavioral edges (`CALLS`, `ACCESSES`) from within the lambda must originate from the lambda node, not the enclosing method.
*   **Method References**: Modeled as a `REFERENCES` edge from the source method to the target method.

### 5.3 Chain Resolution
Behavioral chains (fluent APIs, Streams) must be decomposed into individual edges. Intermediate types in the chain (e.g., `Stream<T>`) are captured via `USES` edges.

## Appendix: Original Finding (Revised)

1.  **getNodeSignature() incomplete**: Expanded to handle Enums, Initializers, and Lambdas.
2.  **Structural Integrity**: Entry point moved to `CompilationUnit` level to ensure `EXTENDS`/`IMPLEMENTS`/`CONTAINS` are captured.
3.  **Behavioral Accuracy**: Scope resolution improved to distinguish between local, instance, and static calls.
