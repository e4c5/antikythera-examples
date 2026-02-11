# Implementation Plan: Knowledge Graph Structural and Behavioral Fixes

This plan revises the previous remediation steps based on the current implementation in `antikythera-examples` and the design expectations in `knowledge-graph-design.md`.

## 1. Gap Summary (Why the previous plan is incomplete)

1. `KnowledgeGraphBuilder` still builds from `Collection<MethodDeclaration>` only, so class-level structure is not traversed.
2. `GraphVisitor` does not visit `ClassOrInterfaceDeclaration`, `EnumDeclaration`, or `MethodReferenceExpr`, so `EXTENDS`/`IMPLEMENTS`/most `CONTAINS`/`REFERENCES` edges are not produced.
3. `onMemberDiscovered(...)` collapses non-method members to `Class#member`, creating ID collisions for fields/initializers/enum constants.
4. Behavioral resolution still uses raw scope text (`repo#save()`, `user#name`) rather than resolved type signatures.
5. Lambda bodies are traversed with enclosing method scope, so lambda-origin edges are flattened incorrectly.
6. The previous plan referenced `Resolver.resolveMethodReference(...)`, but that API is package-private in `sa.com.cloudsolutions.antikythera.depsolver` and not callable from `com.raditha.graph`.
7. `EdgeType` lacks `REFERENCES`, while the design includes it.
8. Verification referenced `KnowledgeGraphBuilderBugReproductionTest`, which does not exist.
9. Test compilation currently fails because `KnowledgeGraphIntegrationTest` imports `org.testcontainers.containers.Neo4jContainer`, but no Testcontainers dependency is declared in `antikythera-examples/pom.xml`.
10. Config model is inconsistent: design shows `antikythera.graph.neo4j.batchSize`, while runtime code reads `neo4j.batch_size`.

## 2. Corrected Remediation Plan

### Component: `antikythera-examples`

#### [MODIFY] `src/main/java/com/raditha/graph/KnowledgeGraphBuilder.java`

1. Add `build(List<CompilationUnit> units)` as the primary entry point.
2. Keep `build(Collection<MethodDeclaration> methods)` temporarily as compatibility wrapper (delegate to the new flow where possible).
3. Traverse each `CompilationUnit` by top-level `TypeDeclaration<?>`, not by methods only.
4. Refactor `GraphVisitor` to support scope transitions:
   - Type scope (`ClassOrInterfaceDeclaration`, `EnumDeclaration`).
   - Member scope (`MethodDeclaration`, `ConstructorDeclaration`, `FieldDeclaration`, `InitializerDeclaration`, enum constants).
   - Lambda scope (`LambdaExpr` gets its own synthetic signature and source context).
5. Emit structural edges during type/member traversal:
   - `EXTENDS`, `IMPLEMENTS`, `CONTAINS`, `ENCLOSES`.
6. Add explicit handling for `MethodReferenceExpr` and emit `REFERENCES` edges.
7. Resolve behavioral targets by type, not raw variable text:
   - Build a local symbol table per callable using parameter and variable declarations.
   - Use symbol table first, then `AbstractCompiler.findType(...)`, then fallback textual target.
8. Ensure chain calls and stream/lambda constructs generate per-invocation edges with stable source IDs.

#### [MODIFY] `src/main/java/com/raditha/graph/SignatureUtils.java`

1. Add stable signatures for:
   - Enum constants (for `CONTAINS` on enum members).
   - Lambda nodes (deterministic suffix strategy tied to enclosing callable + source position/index).
2. Keep initializer signature behavior and ensure uniqueness across multiple blocks.
3. Add utility methods used by builder for member signature generation to avoid `Class#member` fallback.

#### [MODIFY] `src/main/java/com/raditha/graph/EdgeType.java`

1. Add `REFERENCES` to align with the design model.

#### [MODIFY] `src/main/java/com/raditha/graph/KnowledgeGraphCLI.java`

1. Collect `CompilationUnit`s from `AntikytheraRunTime.getResolvedCompilationUnits().values()`.
2. Invoke `builder.build(units)` as the default path.

#### [MODIFY] `src/main/java/com/raditha/graph/Neo4jConfig.java`

1. Normalize config schema support:
   - Accept current `neo4j.batch_size`.
   - Optionally accept `neo4j.batchSize`.
2. Document the accepted format in `graph.yml.example` to avoid drift.

#### [MODIFY] `src/main/java/com/raditha/graph/Neo4jGraphStore.java` (optional but recommended)

1. Keep edge-streaming behavior.
2. Add/ensure node-upsert calls for key node kinds so node labels/properties in the design are materially represented (not only relationship-created placeholder nodes).

#### [MODIFY] `pom.xml`

1. Add missing test dependency for Testcontainers Neo4j, or gate `KnowledgeGraphIntegrationTest` so the class compiles without that dependency.

## 3. Implementation Notes

### 3.1 Scope-Safe Traversal

Use a traversal model that explicitly changes source context:

`CompilationUnit -> Type -> Member -> Expression/Lambda`

Edges discovered in each nested context must use that context's signature as `sourceId`.

### 3.2 Method Reference Resolution

Do not call `Resolver.resolveMethodReference(...)` directly from `com.raditha.graph`.
Instead:

1. Resolve scope type using symbol table + `AbstractCompiler`.
2. Build a method target signature from resolved type and identifier.
3. Emit `REFERENCES` edge.
4. Use conservative fallback when resolution is partial.

### 3.3 Behavioral Fallback Rules

1. Exact type resolved: emit fully-qualified target signature.
2. Type unresolved but scope present: emit scoped textual target with a `resolution=partial` attribute.
3. Unscoped call: resolve against enclosing type first, then fallback to method name signature.

## 4. Verification Plan

### Automated Tests

1. Extend `RelationshipExtractionTest` with:
   - Class-level traversal assertions (`EXTENDS`, `IMPLEMENTS`, `CONTAINS`).
   - Enum and enum-constant containment.
   - Lambda attribution (edge source is lambda node).
   - Method reference extraction (`REFERENCES`).
   - Resolution quality cases (resolved and fallback).
2. Extend `KnowledgeGraphDataStructuresTest` with signature uniqueness for enum constants and lambdas.
3. Update `KnowledgeGraphIntegrationTest` to use compilation-unit entrypoint and assert presence of structural + behavioral edge categories.
4. Ensure test build is green by fixing Testcontainers dependency or test gating strategy.

### Manual Verification

1. Run `KnowledgeGraphCLI` on a medium testbed and compare edge counts by category before/after.
2. Inspect sample Cypher:
   - `MATCH ()-[r:EXTENDS|IMPLEMENTS|CONTAINS]->() RETURN type(r), count(*)`
   - `MATCH (m)-[r:REFERENCES]->(t) RETURN m.signature, t.signature LIMIT 50`
   - `MATCH (l)-[:ENCLOSES {kind:'lambda'}]->(x) RETURN l.signature, x.signature LIMIT 50`
