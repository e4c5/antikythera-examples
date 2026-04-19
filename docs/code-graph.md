Query to find all out going relationships from the class `sa.com.cloudsolutions.antikythera.depsolver.BeanDependencyGraph`:

```cypher   
MATCH (c:CodeElement)
WHERE c.signature = 'sa.com.cloudsolutions.antikythera.depsolver.BeanDependencyGraph'
OR c.fqn = 'sa.com.cloudsolutions.antikythera.depsolver.BeanDependencyGraph'
MATCH (c)-[r]->(other:CodeElement)
RETURN DISTINCT c, r, other;
```

Cypher to find the incoming relationships to the class `sa.com.cloudsolutions.antikythera.depsolver.BeanDependencyGraph`:

```cypher
MATCH (t:CodeElement)
WHERE t.signature = $id OR t.fqn = $id
MATCH (other:CodeElement)-[r]->(t)
RETURN DISTINCT other, r, t;
```
