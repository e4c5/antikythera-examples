# UsageFinder

## Overview

`UsageFinder` is a Java source analysis utility that works over parsed `CompilationUnit`s. It now has three core analysis modes:

1. **Collection field scan** — find `List`, `Set`, and `Map` fields in non-entity, non-DTO classes.
2. **Class usage scan** — find structural references to a target class in fields, constructor parameters, method parameters, return types, `extends`, and `implements` clauses.
3. **Method usage scan** — find call sites and method references for a target method.

The collection scan is still useful, but it is only one part of the tool now.

## CLI modes

`UsageFinder` switches mode based on the first command-line argument:

| Invocation | Mode | Notes |
|---|---|---|
| No arguments | Collection field scan | Default mode |
| `com.example.Foo` | Class usage scan | Matches the class by simple name |
| `com.example.Foo#doSomething` | Method usage scan | Parameter list suffix, if present, is ignored |

### Examples

```bash
# Collection fields
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.UsageFinder"

# Class usages
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.UsageFinder" \
  -Dexec.args="com.example.MyService"

# Method usages
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.UsageFinder" \
  -Dexec.args="com.example.MyService#processOrder"
```

## Public API

`UsageFinder` is instance-based. The available entry points are:

| API | Purpose |
|---|---|
| `new UsageFinder(Collection<CompilationUnit>)` | Scan a supplied set of parsed compilation units |
| `UsageFinder.forProject()` | Scan the project currently loaded in `AntikytheraRunTime` |
| `findCollectionFields()` | Return collection field matches |
| `countCollectionFields()` | Convenience wrapper around `findCollectionFields().size()` |
| `findClassUsages(String classFqn)` | Return structural usages of the target class |
| `findMethodUsages(String methodSignature)` | Return call-site usages of the target method |

There are no static search helpers on this class.

## Output records

### `CollectionFieldUsage`

Returned by `findCollectionFields()`.

| Field | Description |
|---|---|
| `classFqn` | Fully-qualified name of the containing class |
| `fieldType` | Field type exactly as written in source |
| `fieldName` | Field variable name |

Console format:

```text
FullyQualifiedClassName : CollectionType : FieldName
```

Example:

```text
com.example.service.OrderService : List<Order> : pendingOrders
```

### `MethodUsage`

Returned by `findMethodUsages(String)`.

| Field | Description |
|---|---|
| `callerFqn` | Fully-qualified name of the calling class |
| `callerMethod` | Method containing the call site |
| `lineNumber` | 1-based line number, or `-1` if unknown |

Console format:

```text
Found N call site(s) for com.example.Foo#doSomething
  caller.fqn#callerMethod  (line 12)
  caller.fqn#callerMethod[ref]  (line 20)
```

Notes:

- Plain method calls use `callerMethod` as-is.
- Method references (`Foo::doSomething`) are reported with a `[ref]` suffix on the caller method name.
- When a declaring class is supplied, the receiver scope is checked against field and parameter types to filter unrelated calls with the same method name.
- Unqualified calls (same-class or super calls) are always included.

### `ClassUsage`

Returned by `findClassUsages(String)`.

| Field | Description |
|---|---|
| `usingClassFqn` | Fully-qualified name of the class containing the reference |
| `usageKind` | One of `FIELD`, `PARAMETER`, `RETURN_TYPE`, `EXTENDS`, `IMPLEMENTS` |
| `memberName` | Field name, method name, constructor name, or class name depending on `usageKind` |
| `typeName` | Type string exactly as written in source |
| `lineNumber` | 1-based line number, or `-1` if unknown |

Console format:

```text
Found N usage(s) of com.example.MyService
  FIELD  com.example.SomeClass  myField : MyService  (line 14)
```

## What each mode analyzes

### Collection field scan

Includes field declarations whose type string contains `List`, `Set`, or `Map`.

Important details:

- One record is emitted per field variable, not per field statement.
- Only classes are scanned; interfaces are ignored.
- Classes annotated with `@Entity` are skipped.
- Classes whose fully-qualified name contains `dto` are skipped.

### Class usage scan

Matches references in:

- fields
- constructor parameters
- method parameters
- method return types
- `extends`
- `implements`

Generic arguments are also checked, so `List<MyService>` counts as a field usage of `MyService`.

### Method usage scan

Matches:

- `MethodCallExpr`
- `MethodReferenceExpr`

The method name is matched directly. If the query includes a class name, the class part is reduced to a simple name and used as a lightweight scope filter.

## Limitations and matching rules

These are the current implementation constraints and should be treated as behavior, not just documentation gaps:

- Class matching is based on simple names, not full type resolution.
- Generic matching is string-based and only checks the top-level generic arguments.
- `findClassUsages(String)` skips the exact target class itself.
- `findMethodUsages(String)` accepts a parameter-list suffix such as `Foo#doWork(String)` but ignores the parameter list.
- Scope filtering for method calls relies on the declared type of fields and parameters in the enclosing class; it does not perform full semantic type resolution.
- Local variables inside method bodies are not used for class-usage scanning.
- Collection scanning does not analyze collection contents, runtime allocation, or collection usage patterns.

## Usage examples

### Collection field scan

```java
@Service
public class OrderService {
    private List<Order> pendingOrders = new ArrayList<>();
    private Set<String> processedOrderIds = new HashSet<>();
}
```

Output:

```text
com.example.service.OrderService : List<Order> : pendingOrders
com.example.service.OrderService : Set<String> : processedOrderIds
```

### Class usage scan

```java
public class ReportService {
    private MyService myService;

    public ReportService(MyService myService) {
        this.myService = myService;
    }

    public MyService getMyService() {
        return myService;
    }
}
```

Output includes `FIELD`, `PARAMETER`, and `RETURN_TYPE` usages for `MyService`.

### Method usage scan

```java
public class OrderProcessor {
    public void process() {
        orderService.processOrder();
        OrderService::processOrder;
    }
}
```

Output includes a summary line plus call-site rows for the matching invocations and method references.

## Configuration

`UsageFinder.main` loads `depsolver.yml` from the classpath resources and then calls `AbstractCompiler.preProcess()` before running the scan. When using the API directly, you can provide your own `Collection<CompilationUnit>` instead of bootstrapping the full runtime.

## Related tools

- `QueryOptimizationChecker` for query-pattern analysis
- `HardDelete` for delete-usage analysis
- `Fields` for dependency tracking in the examples module

## See also

- `README.md` in the examples module
- JavaParser documentation
- Java Collections Framework documentation
