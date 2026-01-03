# Circular Dependency Tool Guide

The **Circular Dependency Tool** is a powerful utility within the [Antikythera Examples](../README.md) suite designed to detect and automatically resolve circular dependencies in Spring Boot applications. It visualizes dependency graphs, identifies cycles, and applies various refactoring strategies to break them.

## Key Features

- **Cycle Detection**: Analyzes your Spring Boot codebase to find circular references between Beans (`@Component`, `@Service`, `@Controller`, etc.).
- **Automatic Resolution**: Offers multiple strategies to break cycles automatically:
  - **@Lazy Injection**: The simplest and least invasive fix (default).
  - **Setter Injection**: Moves dependency injection from constructor to setter.
  - **Interface Extraction**: Extracts interfaces to decouple implementation dependencies.
  - **Method Extraction**: Extracts shared logic into a new mediator/operations class (ideal for `@PostConstruct` cycles).
- **Interactive & Batch Modes**: Run fully automated or review fixes.
- **Support for Edge Cases**: Handles `@PostConstruct` initialization loops and preserves generic types during refactoring.

## Quick Start

### 1. Configuration

Create a configuration file (e.g., `cycle-config.yml`) pointing to your project:

```yaml
projectPath: /absolute/path/to/your/project
ignore:
  - com.example.some.legacy.Package  # Optional: packages to ignore
```

### 2. Run the Tool

Run the tool using Maven from the `antikythera-examples` directory:

```bash
mvn exec:java -Dexec.mainClass="com.raditha.spring.cycle.CircularDependencyTool" \
  -Dexec.args="--config cycle-config.yml --strategy auto"
```

## CLI Usage

### Arguments

| Argument | Description | Default |
| :--- | :--- | :--- |
| `--config <file>` | Path to the YAML configuration file. | (Required) |
| `--strategy <mode>` | The resolution strategy to apply (`auto`, `lazy`, `setter`, `interface`, `extract`). | `auto` |

### Strategies

The tool supports the following strategies via the `--strategy` flag:

1.  **`auto` (Recommended)**:
    *   Automatically selects the best strategy for each cycle.
    *   Prioritizes **Method Extraction** for cycles involving `@PostConstruct` (where `@Lazy` fails).
    *   Uses **@Lazy** for standard field/constructor cycles.
    *   Falls back to **Setter Injection** if `@Lazy` is not applicable.

2.  **`lazy`**:
    *   Adds `@Lazy` annotation to the injection point (field or constructor parameter).
    *   Least invasive; breaks the cycle at runtime by proxying one of the beans.

3.  **`setter`**:
    *   Converts constructor injection to setter injection (if not already using field injection).
    *   Spring resolves setter circular dependencies natively (mostly).

4.  **`interface`**:
    *   Extracts an interface from one of the classes in the cycle.
    *   Updates the other class to depend on the interface instead of the implementation.
    *   Preserves generic type parameters.

5.  **`extract`**:
    *   **Advanced**: Extracts the logic causing the cycle (e.g., methods called during initialization) into a new intermediate "Mediator" or "Operations" class.
    *   Both original classes then depend on this new class, breaking the direct cycle between them.
    *   **Crucial for `@PostConstruct` cycles** where lazy loading triggers an immediate deadlock or initialization error.

## Examples

### Fixing a Simple Cycle (@Lazy)

Given:
```java
@Service
public class A {
    @Autowired private B b;
}

@Service
public class B {
    @Autowired private A a;
}
```

Running with `--strategy auto` or `--strategy lazy`:
```java
@Service
public class A {
    @Autowired @Lazy private B b; // Tool adds @Lazy
}
```

### Fixing a @PostConstruct Cycle (Method Extraction)

Cycles involving `@PostConstruct` often crash even with `@Lazy` because the proxy initialization triggers the real object creation immediately.

Given:
```java
@Service
public class A {
    @Autowired private B b;
    @PostConstruct void init() { b.doSomething(); }
}

@Service
public class B {
    @Autowired private A a;
    public void doSomething() { a.help(); }
}
```

Running with `--strategy auto`:
1.  Detects the `@PostConstruct` usage.
2.  Creates `ABOperations.java` (Mediator).
3.  Moves `init()` and `doSomething()` logic to `ABOperations`.
4.  Updates `A` and `B` to remove the circular dependency.

## Troubleshooting

-   **Files not writing?**: Ensure `projectPath` in your config points to the root of your source tree (e.g., specific module root if multi-module).
-   **Compilation errors?**: The tool attempts to add necessary imports, but complex generic types or static imports might need manual adjustment.
-   **"Method Extraction" output**: If you see a new class ending in `...Operations` or `...Mediator`, this is the result of the method extraction strategy breaking a complex cycle.
