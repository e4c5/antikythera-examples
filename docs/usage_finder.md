# UsageFinder - Collection Usage Analysis Tool

## Overview

UsageFinder is a static analysis tool that scans Java codebases to identify collection fields (List, Set, Map) in non-entity classes. This helps identify data structures that may need optimization, such as using appropriate collection types, analyzing N+1 query patterns, or detecting potential memory issues.

## Features

- **Collection Detection**: Identifies `List`, `Set`, and `Map` fields
- **Entity Filtering**: Excludes JPA `@Entity` classes (which have different optimization concerns)
- **DTO Filtering**: Excludes DTO classes (data transfer objects)
- **Fully Qualified Output**: Reports class name, collection type, and field name
- **Simple Output Format**: Easy to parse and analyze

## Usage

### Basic Usage

```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.UsageFinder"
```

### Output to File

```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.UsageFinder" \
  > collection-usage-report.txt
```

### Filter Specific Collection Types

```bash
# Find only List fields
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.UsageFinder" | \
  grep "List<"

# Find only Map fields
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.UsageFinder" | \
  grep "Map<"
```

## Output Format

Format: `FullyQualifiedClassName : CollectionType : FieldName`

```
com.example.service.OrderService : List<String> : orderNumbers
com.example.service.UserService : Set<Long> : processedUserIds
com.example.cache.ProductCache : Map<String, Product> : productCache
com.example.analyzer.DataAggregator : List<Order> : pendingOrders
```

### Fields

1. **FullyQualifiedClassName**: Complete package and class name
2. **CollectionType**: The full generic type (e.g., `List<Order>`, `Map<String, User>`)
3. **FieldName**: Name of the collection field

## What Gets Analyzed

### Included Classes

✅ Service classes
✅ Controller classes  
✅ Component classes
✅ Configuration classes
✅ Utility classes
✅ Any non-entity, non-DTO classes

### Excluded Classes

❌ Classes annotated with `@Entity`
❌ Classes in packages containing "dto"
❌ Interface definitions (only implementations)

## Examples

### Example 1: Service with Collection Field

**Code:**
```java
package com.example.service;

@Service
public class OrderService {
    @Autowired
    private OrderRepository orderRepository;
    
    private List<Order> pendingOrders = new ArrayList<>();
    private Set<String> processedOrderIds = new HashSet<>();
}
```

**Output:**
```
com.example.service.OrderService : List<Order> : pendingOrders
com.example.service.OrderService : Set<String> : processedOrderIds
```

### Example 2: Cache Implementation

**Code:**
```java
package com.example.cache;

@Component
public class UserCache {
    private Map<Long, User> userCache = new ConcurrentHashMap<>();
    private Map<String, Long> emailToIdCache = new HashMap<>();
}
```

**Output:**
```
com.example.cache.UserCache : Map<Long, User> : userCache
com.example.cache.UserCache : Map<String, Long> : emailToIdCache
```

### Example 3: Entity (Excluded)

**Code:**
```java
package com.example.model;

@Entity
@Table(name = "orders")
public class Order {
    @Id
    private Long id;
    
    @OneToMany
    private List<OrderItem> items;  // NOT reported (entity class)
}
```

**Output:**
```
(no output - entity classes are excluded)
```

## Use Cases

### 1. Memory Analysis

Identify classes holding large collections in memory:

```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.UsageFinder" | \
  grep "List<"
```

Review these for:
- Unbounded collection growth
- Missing cleanup/eviction logic
- Potential memory leaks

### 2. N+1 Query Detection

Find services that might be aggregating data:

```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.UsageFinder" | \
  grep "Service"
```

Then review the source to check for:
- Loops fetching individual entities
- Missing JOIN FETCH in queries
- Batch fetching opportunities

### 3. Caching Audit

Identify all cache-like structures:

```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.UsageFinder" | \
  grep "Map<"
```

Review for:
- Proper cache eviction
- Thread safety (use `ConcurrentHashMap`)
- Cache invalidation strategy

### 4. Collection Type Optimization

Find collections that could use more efficient types:

```bash
# Find List fields that might need Set
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.UsageFinder" | \
  grep "List<" | grep -i "id"
```

If a List stores unique IDs, consider using `Set` instead.

### 5. Code Cleanup

Identify unused or redundant collections:

```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.UsageFinder" > collections.txt

# Then review each field in the codebase to check usage
```

## Configuration

### Required Configuration File

UsageFinder requires `depsolver.yml` in the resources directory:

```yaml
variables:
  projects_folder: ${HOME}/your-projects

base_path: ${projects_folder}/your-project/
```

### Custom Configuration

To use a different configuration:

```java
File yamlFile = new File("/path/to/custom-config.yml");
Settings.loadConfigMap(yamlFile);
```

## Analysis Patterns

### Pattern 1: Stateful Service Anti-Pattern

**Finding:**
```
com.example.service.OrderService : List<Order> : pendingOrders
```

**Concern**: Stateful services don't scale horizontally

**Recommendation**: Move state to database or cache

### Pattern 2: Manual Caching

**Finding:**
```
com.example.service.ProductService : Map<Long, Product> : productCache
```

**Concern**: Manual cache management is error-prone

**Recommendation**: Use Spring `@Cacheable` or dedicated cache (Redis, Caffeine)

### Pattern 3: Constructor Injection Needed

**Finding:**
```
com.example.config.AppConfig : List<String> : allowedOrigins
```

**Concern**: Mutable collection in configuration

**Recommendation**: Make immutable with `List.of()` or `Collections.unmodifiableList()`

### Pattern 4: Potential N+1 Query

**Finding:**
```
com.example.service.ReportService : List<User> : users
```

**Concern**: May be fetching users one-by-one in a loop

**Recommendation**: Review for batch fetching opportunities

## Best Practices

### 1. Prefer Immutability

❌ **Bad:**
```java
private List<String> allowedRoles = new ArrayList<>();
```

✅ **Good:**
```java
private final List<String> allowedRoles = List.of("ADMIN", "USER");
```

### 2. Use Appropriate Collection Types

❌ **Bad:**
```java
private List<Long> processedIds = new ArrayList<>();
// Then: if (processedIds.contains(id)) { ... }  // O(n) lookup
```

✅ **Good:**
```java
private Set<Long> processedIds = new HashSet<>();
// Then: if (processedIds.contains(id)) { ... }  // O(1) lookup
```

### 3. Consider Thread Safety

❌ **Bad:**
```java
private Map<String, User> cache = new HashMap<>();  // Not thread-safe
```

✅ **Good:**
```java
private Map<String, User> cache = new ConcurrentHashMap<>();
```

### 4. Set Collection Bounds

❌ **Bad:**
```java
private List<Event> events = new ArrayList<>();  // Unbounded
```

✅ **Good:**
```java
private Queue<Event> events = new LinkedBlockingQueue<>(1000);  // Bounded
```

## Limitations

- **Only Field Declarations**: Doesn't track local variables or method parameters
- **No Collection Content Analysis**: Doesn't analyze what's stored in collections
- **No Usage Tracking**: Doesn't report how collections are used
- **Static Analysis Only**: Doesn't detect runtime collection creation

## Extending the Tool

To analyze additional collection types:

```java
private static boolean isCollectionType(String type) {
    return type.contains("List<") || type.contains("List ") || 
           type.contains("Set<") || type.contains("Set ") || 
           type.contains("Map<") || type.contains("Map ") ||
           type.contains("Queue<") || type.contains("Deque<");  // Add Queue/Deque
}
```

## Related Tools

- **QueryOptimizationChecker**: Analyzes N+1 query issues
- **HardDelete**: Detects data management patterns
- **Fields**: Tracks repository usage dependencies

## See Also

- [Query Optimization](../README.md) - Detecting N+1 queries
- Java Collections Framework Documentation
- [Effective Java](https://www.oreilly.com/library/view/effective-java/9780134686097/) - Item 28: Prefer lists to arrays
