# TestFixer - Test Quality Analysis and Automation Tool

## Overview

TestFixer is a comprehensive test analysis and refactoring tool that identifies and fixes common anti-patterns in Java unit tests. It combines three key capabilities:

1. **Test Without Assertions Detection**: Identifies and removes tests that lack proper assertions
2. **Test Framework Refactoring**: Converts tests between JUnit 4/5 and Spring Boot test annotations
3. **Embedded Resource Conversion**: Converts tests using TestContainers or live connections to embedded alternatives

## Features

### 1. Assertion Detection
- Detects test methods lacking assertions (e.g., `assert*`, `verify`)
- Traverses helper methods to find assertions in nested calls
- Prevents false positives by checking method call chains

### 2. Test Refactoring (`--refactor`)
- Converts between JUnit 4 and JUnit 5 annotations
- Updates Spring Boot test annotations appropriately
- Fixes misused test framework annotations
- Ensures proper test lifecycle annotations

### 3. Embedded Resource Conversion (`--convert-embedded`)
- Converts TestContainers to embedded alternatives (e.g., H2, embedded Kafka)
- Replaces live database connections with embedded databases
- Automatically updates POM dependencies
- Removes TestContainer configurations

## Usage

### Basic Usage (Detect Tests Without Assertions)

```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.TestFixer"
```

This will remove all test methods that don't contain assertions.

### Dry Run Mode

Test the tool without making changes:

```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.TestFixer" \
  -Dexec.args="--dry-run"
```

### Enable Test Refactoring

```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.TestFixer" \
  -Dexec.args="--refactor"
```

### Enable Embedded Resource Conversion

```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.TestFixer" \
  -Dexec.args="--convert-embedded"
```

### Combine Multiple Options

```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.TestFixer" \
  -Dexec.args="--dry-run --refactor --convert-embedded"
```

## Command-Line Options

| Option | Description |
|--------|-------------|
| `--dry-run` | Preview changes without modifying files |
| `--refactor` | Enable test framework refactoring |
| `--convert-embedded` | Convert tests to use embedded resources |

## Output

### Refactoring Summary

When `--refactor` is enabled, TestFixer outputs a table:

```
Class                                    | Original       -> New            | Action              | Reason
---------------------------------------------------------------------------------------------------------------------------
UserServiceTest                          | JUnit4         -> JUnit5         | FRAMEWORK_UPGRADE   | Modernize test framework
OrderRepositoryTest                      | @SpringBootTest-> @DataJpaTest   | SLICE_TEST          | Reduce context startup time
```

### Embedded Conversion Summary

When `--convert-embedded` is enabled:

```
Class                                    | Action              | Embedded Alternative | Reason
---------------------------------------------------------------------------------------------------------------------------
InvoiceIntegrationTest                   | CONVERT_DATABASE    | H2                   | Replace PostgreSQL TestContainer
KafkaConsumerTest                        | CONVERT_KAFKA       | Embedded Kafka       | Remove Kafka TestContainer
```

## Examples

### Example 1: Test Without Assertions

**Before:**
```java
@Test
public void testUserCreation() {
    userService.createUser("John");
    // No assertion!
}
```

**After:**
```
(Test method removed)
```

### Example 2: Test With Helper Method

**Before:**
```java
@Test
public void testOrderProcessing() {
    Order order = createOrder();
    orderService.process(order);
    verifyOrderProcessed(order);  // Helper method contains assertion
}

private void verifyOrderProcessed(Order order) {
    assertEquals("PROCESSED", order.getStatus());
}
```

**After:**
```
(Test method preserved - assertion found in helper)
```

### Example 3: Embedded Resource Conversion

**Before:**
```java
@SpringBootTest
@Testcontainers
public class UserRepositoryTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");
    
    @Test
    public void testFindUser() {
        // ...
    }
}
```

**After:**
```java
@DataJpaTest
public class UserRepositoryTest {
    // Using H2 embedded database (configured in application-test.properties)
    
    @Test
    public void testFindUser() {
        // ...
    }
}
```

## Configuration

Ensure `generator.yml` is properly configured:

```yaml
variables:
  projects_folder: ${HOME}/your-projects

base_path: ${projects_folder}/your-project/
```

## Anti-Patterns Detected

Refer to `docs/sdet_reference_guide.md` for a comprehensive list of test anti-patterns that TestFixer can detect and fix:

1. **Tests without assertions** - Empty tests that don't validate behavior
2. **Heavyweight unit tests** - Using `@SpringBootTest` for pure logic tests
3. **Wrong test slice** - Using full context when a slice would suffice
4. **TestContainers overuse** - When embedded alternatives are faster and simpler

## Best Practices

1. **Always run with `--dry-run` first** to preview changes
2. **Review the summary** before accepting refactoring suggestions
3. **Run tests after refactoring** to ensure behavior is preserved
4. **Commit changes incrementally** for easier review and rollback

## Related Tools

- **TestRefactorer**: Internal component that handles framework-specific refactoring logic
- **EmbeddedResourceRefactorer**: Handles conversion to embedded resources
- **LiveConnectionDetector**: Identifies tests using live external dependencies

## See Also

- [SDET Reference Guide](sdet_reference_guide.md) - Comprehensive testing anti-patterns
- [SDET Reference Guide JUnit 5](sdet_reference_guide_junit5.md) - JUnit 5 specific patterns
