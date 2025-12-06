# Logger Cleanup Tool - Documentation

## Overview

The Logger cleanup tool processes Java source files to standardize and optimize logging statements. It handles both SLF4J logger calls and console output 
(System.out/System.err), applying context-aware transformations.

---

## What It Does

The tool automatically:
- **Converts logger levels** to `debug()` for regular code or `error()` for catch blocks
- **Removes logging** from loops, forEach statements, and @RestController classes (performance optimization)
- **Removes System.out/err** statements completely (all contexts)
- **Preserves utility methods** like `isDebugEnabled()`, `isInfoEnabled()`, etc.
- **Cleans up empty forEach/peek/ifPresent** statements left after removing logging code
- **Supports multiple loggers** in the same class (e.g., @Slf4j + explicit loggers)
- **Preserves empty catch blocks and method bodies** while removing other empty blocks

---

## Transformation Rules

| Input Statement | Context | Output |
|----------------|---------|--------|
| `logger.info("msg")` | Regular code | `logger.debug("msg")` |
| `logger.warn("msg")` | Regular code | `logger.debug("msg")` |
| `logger.info("msg")` | Inside loop/forEach | **REMOVED** |
| `logger.info("msg")` | @RestController class | **REMOVED** |
| `logger.info("msg")` | Catch block | `logger.error("msg")` |
| `logger.isDebugEnabled()` | Anywhere | **PRESERVED** |
| `logger.isInfoEnabled()` | Anywhere | **PRESERVED** |
| `System.out.println("msg")` | Anywhere | **REMOVED** |
| `System.out.print("msg")` | Anywhere | **REMOVED** |
| `System.out.printf(...)` | Anywhere | **REMOVED** |
| `System.err.println("msg")` | Anywhere | **REMOVED** |
| `items.forEach(i -> logger.info(i))` | Anywhere | **REMOVED** (empty forEach) |
| `items.peek(i -> logger.info(i))` | Anywhere | **REMOVED** (empty peek) |
| `optional.ifPresent(v -> logger.info(v))` | Anywhere | **REMOVED** (empty ifPresent) |

---

## Examples

### Example 1: Logger Level Conversion
```java
// BEFORE
public void processData() {
    logger.info("Starting process");
    logger.warn("This is a warning");
}

// AFTER
public void processData() {
    logger.debug("Starting process");
    logger.debug("This is a warning");
}
```

### Example 2: System.out Removed
```java
// BEFORE
public void processData() {
    System.out.println("Processing data");
    System.out.printf("Count: %d", count);
}

// AFTER
public void processData() {
    // System.out statements removed
}
```

### Example 3: Logger in Loops (Removed)
```java
// BEFORE
public void processItems(List<String> items) {
    for (String item : items) {
        logger.info("Processing: " + item);
        process(item);
    }
}

// AFTER
public void processItems(List<String> items) {
    for (String item : items) {
        // logger removed
        process(item);
    }
}
```

### Example 4: Empty forEach Cleanup
```java
// BEFORE
items.forEach(item -> {
    logger.info("Item: " + item);
});

// AFTER
// [completely removed - forEach had only logging]
```

### Example 5: forEach with Mixed Code
```java
// BEFORE
items.forEach(item -> {
    logger.info("Processing: " + item);
    System.out.println("Item: " + item);
    processItem(item);
});

// AFTER
items.forEach(item -> {
    // logger and System.out removed
    processItem(item);
});
```

### Example 6: Catch Block → Error Level
```java
// BEFORE
try {
    riskyOperation();
} catch (Exception e) {
    logger.info("Error occurred");
    System.err.println("Error: " + e.getMessage());
}

// AFTER
try {
    riskyOperation();
} catch (Exception e) {
    logger.error("Error occurred");
    // System.err removed
}
```

### Example 7: isDebugEnabled Preserved
```java
// BEFORE & AFTER (unchanged)
if (logger.isDebugEnabled()) {
    logger.debug("Expensive debug info: " + computeExpensiveString());
}
```

### Example 8: @RestController - All Logging Removed
```java
// BEFORE
@RestController
public class OrderController {
    private static final Logger logger = LoggerFactory.getLogger(OrderController.class);
    
    @GetMapping("/orders")
    public List<Order> getOrders() {
        logger.info("Getting all orders");
        List<Order> orders = orderService.findAll();
        logger.debug("Found " + orders.size() + " orders");
        return orders;
    }
}

// AFTER
@RestController
public class OrderController {
    private static final Logger logger = LoggerFactory.getLogger(OrderController.class);
    
    @GetMapping("/orders")
    public List<Order> getOrders() {
        // All logger calls removed for performance
        List<Order> orders = orderService.findAll();
        return orders;
    }
}
```

### Example 9: Multiple Loggers in One Class
```java
// BEFORE
@Slf4j
public class MyService {
    private static final Logger auditLogger = LoggerFactory.getLogger("audit");
    
    public void process() {
        log.info("Processing started");
        auditLogger.info("Audit: process started");
        
        items.forEach(item -> {
            log.debug("Item: " + item);
            auditLogger.info("Audit: item processed");
        });
    }
}

// AFTER
@Slf4j
public class MyService {
    private static final Logger auditLogger = LoggerFactory.getLogger("audit");
    
    public void process() {
        log.debug("Processing started");
        auditLogger.debug("Audit: process started");
        
        // forEach removed - contained only logging
    }
}
```

### Example 10: Stream peek() and Optional ifPresent()
```java
// BEFORE
public void processStream(List<String> items) {
    items.stream()
        .peek(item -> logger.info("Processing: " + item))
        .map(String::toUpperCase)
        .collect(Collectors.toList());
    
    Optional<String> result = findResult();
    result.ifPresent(r -> logger.info("Result: " + r));
}

// AFTER
public void processStream(List<String> items) {
    items.stream()
        // peek removed - contained only logging
        .map(String::toUpperCase)
        .collect(Collectors.toList());
    
    Optional<String> result = findResult();
    // ifPresent removed - contained only logging
}
```

---

## Empty Block Handling

The tool intelligently handles empty blocks after removing logging statements:

### Preserved Empty Blocks
- **Empty catch blocks**: Always preserved (required by Java syntax)
- **Empty method bodies**: Always preserved (valid for abstract/interface methods)

### Removed Empty Blocks
- **Empty if-else blocks**: Only removed if both if and else blocks are empty
- **Empty loop bodies**: Removed after logging statements are removed
- **Empty forEach/peek/ifPresent lambdas**: Entire statement is removed

### Examples

#### Empty Catch Block (Preserved)
```java
// BEFORE & AFTER (unchanged)
try {
    riskyOperation();
} catch (Exception e) {
    // Empty catch block preserved
}
```

#### Empty If-Else (Smart Removal)
```java
// BEFORE
if (condition) {
    logger.info("Condition true");
} else {
    logger.info("Condition false");
}

// AFTER
// Entire if-else removed - both blocks became empty
```

---

## Supported Logger Configurations

The tool handles all these scenarios:

### 1. @Slf4j Annotation Only
```java
@Slf4j
public class MyClass {
    public void method() {
        log.info("Message");  // ✅ Processed (uses "log" field)
    }
}
```

### 2. Explicit Logger Only
```java
public class MyClass {
    private static final Logger logger = LoggerFactory.getLogger(MyClass.class);
    
    public void method() {
        logger.info("Message");  // ✅ Processed
    }
}
```

### 3. Both @Slf4j and Explicit Logger
```java
@Slf4j
public class MyClass {
    private static final Logger auditLogger = LoggerFactory.getLogger("audit");
    
    public void method() {
        log.info("Main message");           // ✅ Processed
        auditLogger.info("Audit message");  // ✅ Processed
    }
}
```

### 4. Multiple Explicit Loggers
```java
public class MyClass {
    private static final Logger logger = LoggerFactory.getLogger(MyClass.class);
    private static final Logger perfLogger = LoggerFactory.getLogger("performance");
    private static final Logger secLogger = LoggerFactory.getLogger("security");
    
    public void method() {
        logger.info("Main");        // ✅ All processed
        perfLogger.info("Perf");    // ✅
        secLogger.info("Security"); // ✅
    }
}
```

---

## Usage

### Run the Logger cleanup tool:
```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.Logger"
```

### Run tests:
```bash
mvn test -Dtest=LoggerTest
```

---

## Complete Example

```java
// ========== BEFORE ==========
@Slf4j
public class OrderProcessor {
    private static final Logger auditLogger = LoggerFactory.getLogger("audit");
    
    public void processOrders(List<Order> orders) {
        System.out.println("Starting order processing");
        log.info("Processing " + orders.size() + " orders");
        
        if (log.isDebugEnabled()) {
            log.debug("Debug mode enabled");
        }
        
        orders.forEach(order -> {
            log.info("Processing order: " + order.getId());
            System.out.println("Order: " + order.getId());
        });
        
        try {
            validateOrders(orders);
        } catch (ValidationException e) {
            log.warn("Validation failed");
            System.err.println("Error: " + e.getMessage());
            auditLogger.info("Audit: validation error");
        }
        
        log.info("Processing complete");
    }
}

// ========== AFTER ==========
@Slf4j
public class OrderProcessor {
    private static final Logger auditLogger = LoggerFactory.getLogger("audit");
    
    public void processOrders(List<Order> orders) {
        // System.out removed
        log.debug("Processing " + orders.size() + " orders");
        
        if (log.isDebugEnabled()) {
            log.debug("Debug mode enabled");
        }
        
        // forEach removed - contained only logging
        
        try {
            validateOrders(orders);
        } catch (ValidationException e) {
            log.error("Validation failed");
            // System.err removed
            auditLogger.error("Audit: validation error");
        }
        
        log.debug("Processing complete");
    }
}
```

---

## Key Features

✅ **Preserves utility methods** - `isDebugEnabled()`, `isInfoEnabled()`, etc. remain unchanged  
✅ **Performance optimization** - Removes logging from loops and @RestController classes  
✅ **Empty forEach cleanup** - Automatically removes forEach, peek, ifPresent, and forEachOrdered statements that become empty  
✅ **System.out/err removal** - Completely removes all console output statements  
✅ **Multiple logger support** - Works with @Slf4j + multiple explicit loggers  
✅ **Context-aware** - Different transformations for regular code, loops, catch blocks, and controllers  
✅ **Smart empty block handling** - Preserves necessary empty blocks (catch, methods) while removing unnecessary ones  
✅ **Safe** - Never breaks code; utility methods and conditional logging preserved

