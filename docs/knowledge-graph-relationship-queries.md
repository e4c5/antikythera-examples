# Knowledge Graph Relationship Queries (Apache AGE + Cypher)

This query set is based on the current implementation in:
- `antikythera-examples/src/main/java/com/raditha/graph/KnowledgeGraphCLI.java`
- `antikythera-examples/src/main/java/com/raditha/graph/KnowledgeGraphBuilder.java`
- `antikythera-examples/src/main/java/com/raditha/graph/ApacheAgeGraphStore.java`

## Schema Notes (Current Implementation)

- All nodes use label `CodeElement`.
- Logical type is stored in property `nodeType` (`Class`, `Interface`, `Method`, `Constructor`, `Field`, `StaticBlock`, `Lambda`, `Enum`, `EnumConstant`).
- Key node property is `signature`.
- Relationships currently emitted: `CONTAINS`, `ENCLOSES`, `IMPLEMENTS`, `EXTENDS`, `CALLS`, `ACCESSES`, `USES`, `REFERENCES`.

Replace `'antikythera_graph'` below with your configured `graph.age.graph_name`.

## 1. Relationship Coverage and Health

### 1.1 Count edges by type

Cypher:
```cypher
MATCH ()-[r]->()
RETURN type(r) AS edgeType, count(r) AS edgeCount
ORDER BY edgeCount DESC;
```

Raw SQL (Apache AGE):
```sql
SELECT *
FROM ag_catalog.cypher('antikythera_graph', $$
  MATCH ()-[r]->()
  RETURN type(r) AS edgeType, count(r) AS edgeCount
  ORDER BY edgeCount DESC
$$) AS (edgeType agtype, edgeCount agtype);
```

### 1.2 Top nodes by outgoing relationships

Cypher:
```cypher
MATCH (n:CodeElement)-[r]->()
RETURN n.signature AS signature, n.nodeType AS nodeType, count(r) AS outDegree
ORDER BY outDegree DESC
LIMIT 50;
```

Raw SQL (Apache AGE):
```sql
SELECT *
FROM ag_catalog.cypher('antikythera_graph', $$
  MATCH (n:CodeElement)-[r]->()
  RETURN n.signature AS signature, n.nodeType AS nodeType, count(r) AS outDegree
  ORDER BY outDegree DESC
  LIMIT 50
$$) AS (signature agtype, nodeType agtype, outDegree agtype);
```

### 1.3 Find orphan/untyped nodes (often unresolved edge targets)

Cypher:
```cypher
MATCH (n:CodeElement)
WHERE n.nodeType IS NULL OR n.name IS NULL
RETURN n.signature AS signature, n.nodeType AS nodeType, n.name AS name
LIMIT 200;
```

Raw SQL (Apache AGE):
```sql
SELECT *
FROM ag_catalog.cypher('antikythera_graph', $$
  MATCH (n:CodeElement)
  WHERE n.nodeType IS NULL OR n.name IS NULL
  RETURN n.signature AS signature, n.nodeType AS nodeType, n.name AS name
  LIMIT 200
$$) AS (signature agtype, nodeType agtype, name agtype);
```

## 2. Structural Relationship Queries

### 2.1 Class hierarchy (`EXTENDS`)

Cypher:
```cypher
MATCH (c:CodeElement {nodeType:'Class'})-[:EXTENDS]->(p:CodeElement)
RETURN c.signature AS child, p.signature AS parent
ORDER BY child;
```

Raw SQL (Apache AGE):
```sql
SELECT *
FROM ag_catalog.cypher('antikythera_graph', $$
  MATCH (c:CodeElement {nodeType:'Class'})-[:EXTENDS]->(p:CodeElement)
  RETURN c.signature AS child, p.signature AS parent
  ORDER BY child
$$) AS (child agtype, parent agtype);
```

### 2.2 Interface implementation map (`IMPLEMENTS`)

Cypher:
```cypher
MATCH (c:CodeElement)-[:IMPLEMENTS]->(i:CodeElement)
RETURN c.signature AS classSig, i.signature AS interfaceSig
ORDER BY classSig;
```

Raw SQL (Apache AGE):
```sql
SELECT *
FROM ag_catalog.cypher('antikythera_graph', $$
  MATCH (c:CodeElement)-[:IMPLEMENTS]->(i:CodeElement)
  RETURN c.signature AS classSig, i.signature AS interfaceSig
  ORDER BY classSig
$$) AS (classSig agtype, interfaceSig agtype);
```

### 2.3 Members of a specific type (`CONTAINS`)

Cypher:
```cypher
MATCH (t:CodeElement {signature:'com.example.service.UserService'})-[:CONTAINS]->(m:CodeElement)
RETURN m.signature AS memberSig, m.nodeType AS memberType
ORDER BY memberType, memberSig;
```

Raw SQL (Apache AGE):
```sql
SELECT *
FROM ag_catalog.cypher('antikythera_graph', $$
  MATCH (t:CodeElement {signature:'com.example.service.UserService'})-[:CONTAINS]->(m:CodeElement)
  RETURN m.signature AS memberSig, m.nodeType AS memberType
  ORDER BY memberType, memberSig
$$) AS (memberSig agtype, memberType agtype);
```

### 2.4 Inner classes/lambdas enclosed by a type or method (`ENCLOSES`)

Cypher:
```cypher
MATCH (outer:CodeElement)-[r:ENCLOSES]->(inner:CodeElement)
RETURN outer.signature AS outerSig, inner.signature AS innerSig, r.kind AS kind, inner.nodeType AS innerType
ORDER BY outerSig
LIMIT 200;
```

Raw SQL (Apache AGE):
```sql
SELECT *
FROM ag_catalog.cypher('antikythera_graph', $$
  MATCH (outer:CodeElement)-[r:ENCLOSES]->(inner:CodeElement)
  RETURN outer.signature AS outerSig, inner.signature AS innerSig, r.kind AS kind, inner.nodeType AS innerType
  ORDER BY outerSig
  LIMIT 200
$$) AS (outerSig agtype, innerSig agtype, kind agtype, innerType agtype);
```

## 3. Behavioral Relationship Queries

### 3.1 Direct method call graph (`CALLS`)

Cypher:
```cypher
MATCH (caller:CodeElement)-[r:CALLS]->(callee:CodeElement)
RETURN caller.signature AS callerSig, callee.signature AS calleeSig, r.resolution AS resolution
ORDER BY callerSig
LIMIT 500;
```

Raw SQL (Apache AGE):
```sql
SELECT *
FROM ag_catalog.cypher('antikythera_graph', $$
  MATCH (caller:CodeElement)-[r:CALLS]->(callee:CodeElement)
  RETURN caller.signature AS callerSig, callee.signature AS calleeSig, r.resolution AS resolution
  ORDER BY callerSig
  LIMIT 500
$$) AS (callerSig agtype, calleeSig agtype, resolution agtype);
```

### 3.2 Incoming callers for a target method

Cypher:
```cypher
MATCH (caller:CodeElement)-[:CALLS]->(target:CodeElement {signature:'com.example.repo.UserRepo#save()'})
RETURN caller.signature AS callerSig
ORDER BY callerSig;
```

Raw SQL (Apache AGE):
```sql
SELECT *
FROM ag_catalog.cypher('antikythera_graph', $$
  MATCH (caller:CodeElement)-[:CALLS]->(target:CodeElement {signature:'com.example.repo.UserRepo#save()'})
  RETURN caller.signature AS callerSig
  ORDER BY callerSig
$$) AS (callerSig agtype);
```

### 3.3 Field access relationships (`ACCESSES`)

Cypher:
```cypher
MATCH (src:CodeElement)-[r:ACCESSES]->(f:CodeElement)
RETURN src.signature AS sourceSig, f.signature AS fieldSig, r.accessType AS accessType, r.resolution AS resolution
ORDER BY sourceSig
LIMIT 500;
```

Raw SQL (Apache AGE):
```sql
SELECT *
FROM ag_catalog.cypher('antikythera_graph', $$
  MATCH (src:CodeElement)-[r:ACCESSES]->(f:CodeElement)
  RETURN src.signature AS sourceSig, f.signature AS fieldSig, r.accessType AS accessType, r.resolution AS resolution
  ORDER BY sourceSig
  LIMIT 500
$$) AS (sourceSig agtype, fieldSig agtype, accessType agtype, resolution agtype);
```

### 3.4 Type usage relationships (`USES`)

Cypher:
```cypher
MATCH (src:CodeElement)-[:USES]->(t:CodeElement)
RETURN src.signature AS sourceSig, t.signature AS usedType
ORDER BY sourceSig
LIMIT 500;
```

Raw SQL (Apache AGE):
```sql
SELECT *
FROM ag_catalog.cypher('antikythera_graph', $$
  MATCH (src:CodeElement)-[:USES]->(t:CodeElement)
  RETURN src.signature AS sourceSig, t.signature AS usedType
  ORDER BY sourceSig
  LIMIT 500
$$) AS (sourceSig agtype, usedType agtype);
```

### 3.5 Method references (`REFERENCES`)

Cypher:
```cypher
MATCH (src:CodeElement)-[r:REFERENCES]->(dst:CodeElement)
RETURN src.signature AS sourceSig, dst.signature AS targetSig, r.resolution AS resolution
ORDER BY sourceSig
LIMIT 500;
```

Raw SQL (Apache AGE):
```sql
SELECT *
FROM ag_catalog.cypher('antikythera_graph', $$
  MATCH (src:CodeElement)-[r:REFERENCES]->(dst:CodeElement)
  RETURN src.signature AS sourceSig, dst.signature AS targetSig, r.resolution AS resolution
  ORDER BY sourceSig
  LIMIT 500
$$) AS (sourceSig agtype, targetSig agtype, resolution agtype);
```

### 3.6 Calls originating specifically from lambda nodes

Cypher:
```cypher
MATCH (owner:CodeElement)-[:ENCLOSES {kind:'lambda'}]->(l:CodeElement {nodeType:'Lambda'})-[:CALLS]->(callee:CodeElement)
RETURN owner.signature AS enclosingSig, l.signature AS lambdaSig, callee.signature AS calleeSig
ORDER BY enclosingSig
LIMIT 500;
```

Raw SQL (Apache AGE):
```sql
SELECT *
FROM ag_catalog.cypher('antikythera_graph', $$
  MATCH (owner:CodeElement)-[:ENCLOSES {kind:'lambda'}]->(l:CodeElement {nodeType:'Lambda'})-[:CALLS]->(callee:CodeElement)
  RETURN owner.signature AS enclosingSig, l.signature AS lambdaSig, callee.signature AS calleeSig
  ORDER BY enclosingSig
  LIMIT 500
$$) AS (enclosingSig agtype, lambdaSig agtype, calleeSig agtype);
```

## 4. Multi-hop Relationship Views

### 4.1 Type-level dependency view (class -> method -> called method)

Cypher:
```cypher
MATCH (t:CodeElement {nodeType:'Class'})-[:CONTAINS]->(m:CodeElement)-[:CALLS]->(c:CodeElement)
RETURN t.signature AS classSig, m.signature AS memberSig, c.signature AS calledSig
LIMIT 500;
```

Raw SQL (Apache AGE):
```sql
SELECT *
FROM ag_catalog.cypher('antikythera_graph', $$
  MATCH (t:CodeElement {nodeType:'Class'})-[:CONTAINS]->(m:CodeElement)-[:CALLS]->(c:CodeElement)
  RETURN t.signature AS classSig, m.signature AS memberSig, c.signature AS calledSig
  LIMIT 500
$$) AS (classSig agtype, memberSig agtype, calledSig agtype);
```

### 4.2 Short path between two signatures (relationship trace)

Cypher:
```cypher
MATCH p = shortestPath(
  (a:CodeElement {signature:'com.example.api.UserController#getUser(java.lang.String)'})-[*..6]-
  (b:CodeElement {signature:'com.example.repo.UserRepo#findById()'})
)
RETURN p;
```

Raw SQL (Apache AGE):
```sql
SELECT *
FROM ag_catalog.cypher('antikythera_graph', $$
  MATCH p = shortestPath(
    (a:CodeElement {signature:'com.example.api.UserController#getUser(java.lang.String)'})-[*..6]-
    (b:CodeElement {signature:'com.example.repo.UserRepo#findById()'})
  )
  RETURN p
$$) AS (p agtype);
```

### 4.3 Boundary-crossing calls (caller type != callee type prefix)

Cypher:
```cypher
MATCH (caller:CodeElement)-[:CALLS]->(callee:CodeElement)
WHERE split(caller.signature, '#')[0] <> split(callee.signature, '#')[0]
RETURN caller.signature AS callerSig, callee.signature AS calleeSig
LIMIT 500;
```

Raw SQL (Apache AGE):
```sql
SELECT *
FROM ag_catalog.cypher('antikythera_graph', $$
  MATCH (caller:CodeElement)-[:CALLS]->(callee:CodeElement)
  WHERE split(caller.signature, '#')[0] <> split(callee.signature, '#')[0]
  RETURN caller.signature AS callerSig, callee.signature AS calleeSig
  LIMIT 500
$$) AS (callerSig agtype, calleeSig agtype);
```

## 5. Known Shortcomings in Current Graph Build

1. Method target signatures on `CALLS`/`REFERENCES` are reduced to `Type#method()` and do not encode parameter types, so overloaded methods are conflated.
2. Unscoped calls default to `enclosingType#method()` and can miss inherited methods, static imports, and polymorphic dispatch.
3. `ACCESSES` edges are emitted from `FieldAccessExpr` only; bare field usages via `NameExpr` are typically not captured as field accesses.
4. `ACCESSES` always records `accessType='READ'`; write operations are not differentiated yet.
5. For unresolved scopes/types, edges are still created with textual fallback plus `resolution='partial'`, which can create noisy target signatures.
6. In Apache AGE backend, node labels are not typed beyond `CodeElement`; `nodeType` is only a property. Label-based query optimization by type is therefore unavailable.
7. Edges can create nodes via `MERGE` with only `signature` (without `name`/`nodeType`) when the target was never explicitly upserted as a node.
8. Design mentions `lineNumber` on nodes, but current persisted nodes include `signature`, `name`, `fqn`, `nodeType` (no `lineNumber`).
9. AGE persistence currently executes one Cypher statement per edge in `flushEdges()`, which can become a throughput bottleneck on larger codebases.
