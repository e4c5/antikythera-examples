# Circular Dependency Detection and Elimination Guide

## Overview

Starting with **Spring Boot 2.6**, circular dependencies between beans are prohibited by default. Applications containing circular dependencies will fail to start with a `BeanCurrentlyInCreationException`.

This guide covers how to use Antikythera's **Circular Dependency Tool** to detect and eliminate cycles **before** upgrading to Spring Boot 2.6+.

> [!IMPORTANT]
> Run this tool as a **separate step** before any Spring Boot version migration. This makes troubleshooting easier by changing one thing at a time.

---

## Table of Contents

1. [Understanding Circular Dependencies](#understanding-circular-dependencies)
2. [Detection Using Antikythera Infrastructure](#detection-using-antikythera-infrastructure)
3. [Resolution Strategies](#resolution-strategies-beyond-lazy)
4. [Using the Tool](#prerequisites)
5. [Manual Intervention](#manual-intervention)
6. [Verification](#verification)
7. [Troubleshooting](#troubleshooting)
8. [Automation Implementation](#automation-implementation)
   - [Graph Algorithms](#graph-algorithms)
   - [Tool Architecture](#tool-architecture)
   - [Interface Extraction Strategy](#interface-extraction-strategy)

---

## Understanding Circular Dependencies

### What is a Circular Dependency?

A circular dependency occurs when two or more beans depend on each other, creating a cycle:

```
ServiceA → depends on → ServiceB
    ↑                        ↓
    └──── depends on ────────┘
```

### Common Patterns

**Pattern 1: Simple A→B→A Cycle (Field Injection)**
```java
@Service
public class OrderService {
    @Autowired
    private PaymentService paymentService;  // Depends on PaymentService
}

@Service
public class PaymentService {
    @Autowired
    private OrderService orderService;  // Depends on OrderService → CYCLE!
}
```

**Pattern 2: Constructor Injection Cycle**
```java
@Service
public class UserService {
    private final NotificationService notificationService;
    
    public UserService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }
}

@Service
public class NotificationService {
    private final UserService userService;
    
    public NotificationService(UserService userService) {
        this.userService = userService;  // CYCLE!
    }
}
```

**Pattern 3: Transitive Cycle (A→B→C→A)**
```java
@Service
public class ServiceA {
    @Autowired private ServiceB serviceB;
}

@Service
public class ServiceB {
    @Autowired private ServiceC serviceC;
}

@Service
public class ServiceC {
    @Autowired private ServiceA serviceA;  // Completes the cycle
}
```

### Why Spring Boot 2.6 Changed This

Prior to 2.6, Spring would resolve circular dependencies automatically using early bean references. This was considered problematic because:
- It hides design issues (tight coupling)
- Can cause subtle initialization bugs
- Makes the application harder to understand and test

---

## Detection Using Antikythera Infrastructure

### How It Works

The Circular Dependency Tool leverages Antikythera's existing infrastructure:

1. **`AbstractCompiler.preProcess()`** - Parses all Java files and populates `AntikytheraRunTime`
2. **`AntikytheraRunTime.getResolvedTypes()`** - Access all parsed types with metadata
3. **`TypeWrapper`** - Identifies Spring beans (`isService()`, `isComponent()`, `isController()`)
4. **`DepSolver` patterns** - Field/method/constructor analysis for granular dependency tracking

```java
// The tool uses existing Antikythera patterns:
AbstractCompiler.preProcess();  // Parse all source files

// Then iterates resolved types to find Spring beans
for (TypeWrapper wrapper : AntikytheraRunTime.getResolvedTypes().values()) {
    if (wrapper.isService() || wrapper.isComponent() || wrapper.isController()) {
        analyzeBean(wrapper.getType());
    }
}
```

This approach:
- ✅ Works without running the application
- ✅ Catches all injection-based cycles (field, constructor, setter)
- ✅ Works on any Spring Boot version
- ✅ Provides actionable fix locations with AST node references
- ✅ Enables programmatic refactoring

---

## Resolution Strategies (Beyond @Lazy)

The tool provides **multiple programmatic strategies** for eliminating cycles:

### Strategy 1: @Lazy Injection (Simplest)

Add `@Lazy` to break the instantiation cycle.

```java
// BEFORE
@Service
public class OrderService {
    @Autowired
    private PaymentService paymentService;
}

// AFTER (automated)
@Service
public class OrderService {
    @Lazy
    @Autowired
    private PaymentService paymentService;
}
```

**Best for:** Field injection cycles.

### Strategy 2: Setter Injection Conversion (Programmatic)

Convert constructor injection to setter injection with `@Lazy`.

```java
// BEFORE (constructor cycle - cannot simply add @Lazy)
@Service
public class UserService {
    private final NotificationService notificationService;
    
    public UserService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }
}

// AFTER (automated conversion)
@Service
public class UserService {
    private NotificationService notificationService;
    
    @Lazy
    @Autowired
    public void setNotificationService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }
}
```

**Best for:** Constructor injection cycles where `@Lazy` on parameter isn't sufficient.

### Strategy 3: Interface Extraction (Programmatic)

Extract an interface to break direct coupling.

```java
// BEFORE: A → B → A (direct coupling)
@Service
public class PaymentService {
    @Autowired
    private OrderService orderService;
    
    public void processPayment(Order order) {
        orderService.updateOrderStatus(order.getId(), "PAID");
    }
}

// AFTER: A → OrderStatusUpdater interface ← B
public interface OrderStatusUpdater {
    void updateOrderStatus(Long orderId, String status);
}

@Service
public class PaymentService {
    @Autowired
    private OrderStatusUpdater orderStatusUpdater;
}

@Service
public class OrderService implements OrderStatusUpdater {
    @Override
    public void updateOrderStatus(Long orderId, String status) { ... }
}
```

**Best for:** Complex cycles with tight coupling.

### Strategy 4: Application Events (Programmatic)

Replace direct calls with Spring events for loose coupling.

```java
// BEFORE
@Service
public class OrderService {
    @Autowired
    private PaymentService paymentService;
    
    public void completeOrder(Order order) {
        paymentService.processPayment(order);
    }
}

// AFTER (automated)
@Service
public class OrderService {
    @Autowired
    private ApplicationEventPublisher eventPublisher;
    
    public void completeOrder(Order order) {
        eventPublisher.publishEvent(new OrderCompletedEvent(order));
    }
}

// Auto-generated event class
public class OrderCompletedEvent {
    private final Order order;
    public OrderCompletedEvent(Order order) { this.order = order; }
    public Order getOrder() { return order; }
}

@Service
public class PaymentService {
    @EventListener
    public void handleOrderCompleted(OrderCompletedEvent event) {
        processPayment(event.getOrder());
    }
}
```

**Best for:** Breaking cycles between unrelated domains.

---

### Prerequisites

1. Java 21+
2. Maven/Gradle project with source code accessible
3. Antikythera downloaded or built

### Configuration

Create `cycle-detector.yml`:

```yaml
base_path: /path/to/your/spring-boot-project/src/main/java
output_path: /path/to/your/spring-boot-project/src/main/java
```

### Report-Only Mode (Recommended First Step)

```bash
java -jar antikythera-examples.jar cycle-detector \
  --config cycle-detector.yml \
  --dry-run
```

**Example Output:**
```
Analyzing Spring Boot application...
Found 47 Spring beans

⚠ Detected 2 circular dependency cycle(s):

Cycle 1: [2 beans]
  com.example.OrderService → com.example.PaymentService (FIELD: paymentService)
  com.example.PaymentService → com.example.OrderService (FIELD: orderService)

Cycle 2: [3 beans]
  com.example.UserService → com.example.NotificationService (FIELD)
  com.example.NotificationService → com.example.AuditService (CONSTRUCTOR)
  com.example.AuditService → com.example.UserService (FIELD)

[Dry run - no changes made]
Run without --dry-run to apply @Lazy annotations automatically.
```

### Fix Mode (Apply @Lazy Annotations)

```bash
java -jar antikythera-examples.jar cycle-detector \
  --config cycle-detector.yml
```

**Example Output:**
```
Analyzing Spring Boot application...
Found 47 Spring beans

⚠ Detected 2 circular dependency cycle(s):
...

Applying fixes:
✓ Added @Lazy to OrderService.paymentService
✓ Added @Lazy to UserService.notificationService

Modified files:
  - src/main/java/com/example/OrderService.java
  - src/main/java/com/example/UserService.java

✓ All circular dependencies resolved
```

---

## Resolution Strategies

### Strategy 1: @Lazy Annotation (Automated)

The tool automatically adds `@Lazy` to break cycles:

```java
// BEFORE
@Service
public class OrderService {
    @Autowired
    private PaymentService paymentService;
}

// AFTER (tool-modified)
@Service
public class OrderService {
    @Lazy  // Added by tool
    @Autowired
    private PaymentService paymentService;
}
```

**How @Lazy Works:**
- Spring injects a **proxy** instead of the real bean
- The actual bean is created only when first accessed
- Breaks the instantiation cycle

**Preference Order:**
1. Field injection (simplest to modify)
2. Setter injection
3. Constructor injection (adds `@Lazy` to parameter)

### Strategy 2: Refactor to Eliminate Cycle (Manual, Recommended)

While `@Lazy` fixes the immediate problem, consider refactoring to eliminate the cycle entirely:

**Option A: Extract Common Dependency**
```java
// Before: A→B→A
// After: A→C, B→C (no cycle)

@Service
public class OrderPaymentCoordinator {  // New service
    // Common logic extracted here
}
```

**Option B: Use Events**
```java
@Service
public class OrderService {
    @Autowired
    private ApplicationEventPublisher eventPublisher;
    
    public void completeOrder(Order order) {
        // Instead of calling paymentService.process()
        eventPublisher.publishEvent(new OrderCompletedEvent(order));
    }
}

@Service
public class PaymentService {
    @EventListener
    public void handleOrderCompleted(OrderCompletedEvent event) {
        // Process payment
    }
}
```

**Option C: Interface Segregation**
```java
// Split interface to break the dependency
public interface OrderReader {
    Order getOrder(Long id);
}

public interface OrderWriter {
    void saveOrder(Order order);
}
```

---

## Manual Intervention

### When Manual Review is Required

The tool will flag these scenarios for manual review:

1. **Final fields** - Cannot add `@Lazy` to final constructor parameters
2. **Complex cycles** - Cycles with 5+ beans may need architectural review
3. **Already lazy** - Cycle exists despite `@Lazy` (indicates deeper issue)

### Final Field Workaround

```java
// Problem: Can't make final field lazy
@Service
public class UserService {
    private final NotificationService notificationService;  // FINAL
    
    public UserService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }
}

// Solution: Use @Lazy on parameter (same effect)
@Service
public class UserService {
    private final NotificationService notificationService;
    
    public UserService(@Lazy NotificationService notificationService) {
        this.notificationService = notificationService;
    }
}
```

---

## Verification

### Step 1: Verify Compilation

```bash
mvn compile
```

Ensure no compilation errors after tool modifications.

### Step 2: Run Tests

```bash
mvn test
```

All existing tests should pass.

### Step 3: Run the Tool Again

```bash
java -jar antikythera-examples.jar cycle-detector \
  --config cycle-detector.yml \
  --dry-run
```

Should report: `✓ No circular dependencies detected`

### Step 4: Test with Spring Boot 2.6+

After all cycles are eliminated:
1. Update Spring Boot version to 2.6+
2. Run `mvn spring-boot:run`
3. Verify application starts without `BeanCurrentlyInCreationException`

---

## Troubleshooting

### "Bean of type X not found" after applying @Lazy

**Cause:** The proxy may not implement all interfaces correctly.

**Solution:** Ensure the bean is injected by interface, not concrete class:
```java
// Good
@Autowired private PaymentService paymentService;

// Potentially problematic
@Autowired private PaymentServiceImpl paymentService;
```

### Cycle still detected after fix

**Cause:** Multiple edges in the cycle; only one was fixed.

**Solution:** Run the tool again to fix remaining edges, or manually add `@Lazy` to other injection points.

### Application works but tests fail

**Cause:** Test context may load beans differently.

**Solution:** Add `@Lazy` in test configuration or use `@MockBean` to break the cycle in tests:
```java
@SpringBootTest
class OrderServiceTest {
    @MockBean
    private PaymentService paymentService;  // Breaks cycle for test
}
```

---

## Automation Implementation

### Graph Algorithms

The tool uses three graph algorithms in sequence:

| Phase | Algorithm | Complexity | Purpose |
|-------|-----------|------------|---------|
| **Detection** | Tarjan's SCC | O(V+E) | Find all strongly connected components |
| **Enumeration** | Johnson's Algorithm | O((c+1)(n+e)) | List all elementary cycles |
| **Selection** | Weighted Greedy | O(E) | Select minimum-cost edges to cut |

#### Tarjan's SCC Algorithm

Finds all **Strongly Connected Components** (SCCs). Any SCC with more than one node contains at least one cycle.

```
Algorithm: Tarjan's SCC
Input: Dependency graph G = (Beans, Dependencies)
Output: List of SCCs

1. For each unvisited bean v:
   a. Assign index and lowlink
   b. Push to stack
   c. DFS neighbors:
      - If unvisited: recurse, update lowlink
      - If on stack: update lowlink (back edge found)
   d. If lowlink = index → root of SCC, pop all nodes
```

#### Johnson's Algorithm

Enumerates **all elementary cycles** (cycles where no node repeats). This gives us the exact cycle paths.

```
Algorithm: Johnson's Cycle Finding
Input: Strongly connected component
Output: All elementary cycles

1. For each node s in SCC:
   a. Run blocked DFS from s
   b. When s is reached again → cycle found
   c. Remove s from subgraph
   d. Repeat for next s
```

#### Weighted Edge Selection

Since Minimum Feedback Arc Set is NP-hard, we use a **weighted greedy heuristic**:

| Factor | Weight | Description |
|--------|--------|-------------|
| Injection Type | 1-3 | FIELD=1, SETTER=2, CONSTRUCTOR=3 |
| Method Count | +0.1/method | Fewer methods = easier interface extraction |
| Fan-out | +0.5/edge | Fewer dependencies from target = less impact |
| Importance | PageRank | Important beans should be preserved |

```
Algorithm: Weighted Edge Selection
Input: All cycles, edge weights
Output: Minimum edges to cut

1. While cycles remain:
   a. Count edge appearances across remaining cycles
   b. Score = (cycle_coverage) / (weight)
   c. Select highest-scoring edge
   d. Remove cycles broken by this edge
   e. Add edge to cut set
```

### Tool Architecture

```
CircularDependencyTool (CLI)
     │
     ├── BeanDependencyGraph
     │     └── AbstractCompiler.preProcess() → AntikytheraRunTime
     │     └── Analyzes @Autowired, constructors, setters
     │
     ├── CycleDetector (Tarjan's SCC)
     │     └── Finds all strongly connected components
     │
     ├── JohnsonCycleFinder
     │     └── Enumerates all elementary cycles
     │
     ├── EdgeSelector (Weighted Greedy)
     │     └── Selects optimal edges using PageRank + weights
     │
     └── InterfaceExtractionStrategy
           └── Generates interface from called methods
           └── Modifies calling class field type
           └── Adds implements to target class
```

### Interface Extraction Strategy

The key resolution strategy that enables **programmatic refactoring**:

1. **Find called methods**: Use visitor pattern to find all `MethodCallExpr` where scope matches dependency field
2. **Resolve signatures**: Use `AbstractCompiler.findCallableDeclaration()` to get method declarations
3. **Generate interface**: Create interface with discovered method signatures
4. **Modify caller**: Change field type from concrete class to interface
5. **Modify target**: Add `implements InterfaceName` to target class
6. **Register**: Update `AntikytheraRunTime.addImplementation()` for tracking

### Integration with Antikythera

The tool leverages existing Antikythera infrastructure:

- `AbstractCompiler.preProcess()` - Parses all source files
- `AntikytheraRunTime.getResolvedTypes()` - Access bean metadata
- `TypeWrapper.isService()` etc. - Identify Spring beans
- `DepSolver` patterns - Field/method/constructor analysis
- `ScopeChain.findScopeChain()` - Trace chained method calls
- `CopyUtils.writeFile()` - Write modified files

---

## Next Steps

After eliminating all circular dependencies:

1. **Commit changes** to version control
2. **Proceed with Spring Boot 2.6 migration** (see `spring_boot_2.5_to_2.6_migration.md`)
3. **Consider refactoring** `@Lazy` usages to eliminate cycles architecturally

---

## References

- [Spring Boot 2.6 Release Notes - Circular References](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-2.6-Release-Notes#circular-references-prohibited-by-default)
- [Spring @Lazy Documentation](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/context/annotation/Lazy.html)
- [Baeldung: Circular Dependencies in Spring](https://www.baeldung.com/circular-dependencies-in-spring)
