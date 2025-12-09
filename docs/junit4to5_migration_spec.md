# JUnit 4 to 5 Migration Specification

## Overview

This document specifies the exact conversions required for migrating a Java project from JUnit 4 to JUnit 5. The migration tool will be integrated into the `TestFixer` ecosystem and triggered via the `--425` command line argument.

## 1. POM Dependency Conversions

### 1.1 Dependencies to Remove

| GroupId | ArtifactId | Notes |
|---------|-----------|-------|
| `junit` | `junit` | JUnit 4 core library |
| `org.junit.vintage` | `junit-vintage-engine` | Only if present, removes backward compatibility |

### 1.2 Dependencies to Add

| GroupId | ArtifactId | Version | Scope | Notes |
|---------|-----------|---------|-------|-------|
| `org.junit.jupiter` | `junit-jupiter` | `5.9.3` (or latest) | `test` | JUnit 5 aggregator dependency |
| `org.junit.jupiter` | `junit-jupiter-api` | `5.9.3` | `test` | For writing tests |
| `org.junit.jupiter` | `junit-jupiter-engine` | `5.9.3` | `test` | For running tests |
| `org.junit.jupiter` | `junit-jupiter-params` | `5.9.3` | `test` | For parameterized tests |

### 1.3 Mockito Version Compatibility

JUnit 4 typically uses Mockito 1.x or 2.x, while JUnit 5 requires Mockito 3.x+.

| JUnit Version | Compatible Mockito Version |
|--------------|----------------------------|
| JUnit 4.x | Mockito 1.x - 2.x |
| JUnit 5.x | Mockito 3.x+ (recommended 5.x) |

**Migration Rule:**
- If Mockito version is < 3.0, upgrade to Mockito 5.(latest) or at minimum 3.3.3
- GroupId remains `org.mockito`, ArtifactId remains `mockito-core`
- Add `mockito-junit-jupiter` for JUnit 5 integration if using `@ExtendWith(MockitoExtension.class)`

```xml
<!-- Remove or upgrade -->
<dependency>
    <groupId>org.mockito</groupId>
    <artifactId>mockito-core</artifactId>
    <version>2.x.x</version> <!-- Upgrade to 5.x+ -->
</dependency>

<!-- Add for JUnit 5 -->
<dependency>
    <groupId>org.mockito</groupId>
    <artifactId>mockito-junit-jupiter</artifactId>
    <version>5.11.0</version>
    <scope>test</scope>
</dependency>
```

### 1.4 Maven Surefire Plugin

Ensure Maven Surefire Plugin version is 2.22.0 or higher to support JUnit 5:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <version>3.2.5</version> <!-- Minimum 2.22.0 -->
</plugin>
```

---

## 2. Import Statement Conversions

### 2.1 Core Test Annotations

| JUnit 4 Import | JUnit 5 Import |
|---------------|---------------|
| `import org.junit.Test;` | `import org.junit.jupiter.api.Test;` |
| `import org.junit.Before;` | `import org.junit.jupiter.api.BeforeEach;` |
| `import org.junit.After;` | `import org.junit.jupiter.api.AfterEach;` |
| `import org.junit.BeforeClass;` | `import org.junit.jupiter.api.BeforeAll;` |
| `import org.junit.AfterClass;` | `import org.junit.jupiter.api.AfterAll;` |
| `import org.junit.Ignore;` | `import org.junit.jupiter.api.Disabled;` |

### 2.2 Assertion Imports

| JUnit 4 Import | JUnit 5 Import |
|---------------|---------------|
| `import static org.junit.Assert.*;` | `import static org.junit.jupiter.api.Assertions.*;` |
| `import org.junit.Assert;` | `import org.junit.jupiter.api.Assertions;` |

### 2.3 Assumptions

| JUnit 4 Import | JUnit 5 Import |
|---------------|---------------|
| `import org.junit.Assume;` | `import org.junit.jupiter.api.Assumptions;` |
| `import static org.junit.Assume.*;` | `import static org.junit.jupiter.api.Assumptions.*;` |

### 2.4 Runners and Rules (Remove)

These imports should be **removed** as they are replaced by the Extension model:

- `import org.junit.runner.RunWith;`
- `import org.junit.Rule;`
- `import org.junit.ClassRule;`
- `import org.junit.runners.*;` (all runner variants)

### 2.5 Extension Model (Add)

| New Import | Purpose |
|-----------|---------|
| `import org.junit.jupiter.api.extension.ExtendWith;` | For registering extensions |
| `import org.mockito.junit.jupiter.MockitoExtension;` | For Mockito support |
| `import org.springframework.test.context.junit.jupiter.SpringExtension;` | For Spring support |

---

## 3. Annotation Conversions

### 3.1 Lifecycle Annotations

| JUnit 4 | JUnit 5 | Notes |
|---------|---------|-------|
| `@Test` | `@Test` | No change in name, but package changes |
| `@Before` | `@BeforeEach` | Renamed for clarity |
| `@After` | `@AfterEach` | Renamed for clarity |
| `@BeforeClass` | `@BeforeAll` | Must be static |
| `@AfterClass` | `@AfterAll` | Must be static |
| `@Ignore` | `@Disabled` | Can take optional reason |
| `@Ignore("reason")` | `@Disabled("reason")` | Reason preserved |

### 3.2 Test Annotation Parameters

The `@Test` annotation no longer supports `expected` and `timeout` parameters.

#### Expected Exception Conversion

**JUnit 4:**
```java
@Test(expected = IllegalArgumentException.class)
public void testException() {
    // test code
}
```

**JUnit 5:**
```java
@Test
public void testException() {
    assertThrows(IllegalArgumentException.class, () -> {
        // test code
    });
}
```

#### Timeout Conversion

**JUnit 4:**
```java
@Test(timeout = 1000)
public void testTimeout() {
    // test code
}
```

**JUnit 5:**
```java
@Test
@Timeout(value = 1000, unit = TimeUnit.MILLISECONDS)
public void testTimeout() {
    // test code
}
```

Or using `assertTimeout`:
```java
@Test
public void testTimeout() {
    assertTimeout(Duration.ofMillis(1000), () -> {
        // test code
    });
}
```

### 3.3 Runner to Extension Conversion

#### Mockito Runner

**JUnit 4:**
```java
@RunWith(MockitoJUnitRunner.class)
public class MyTest {
    @Mock
    private SomeDependency dependency;
    // ...
}
```

**JUnit 5:**
```java
@ExtendWith(MockitoExtension.class)
public class MyTest {
    @Mock
    private SomeDependency dependency;
    // ...
}
```

#### Spring Runner

**JUnit 4:**
```java
@RunWith(SpringRunner.class)
@ContextConfiguration(classes = TestConfig.class)
public class MyTest {
    // ...
}
```

**JUnit 5:**
```java
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = TestConfig.class)
public class MyTest {
    // ...
}
```

Or use the composite annotation:
```java
@SpringJUnitConfig(TestConfig.class)
public class MyTest {
    // ...
}
```

#### Parameterized Tests

**JUnit 4:**
```java
@RunWith(Parameterized.class)
public class MyTest {
    @Parameterized.Parameters
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] { {1, 2}, {3, 4} });
    }
    // ...
}
```

**JUnit 5:**
```java
public class MyTest {
    @ParameterizedTest
    @MethodSource("data")
    void test(int first, int second) {
        // ...
    }
    
    static Stream<Arguments> data() {
        return Stream.of(
            Arguments.of(1, 2),
            Arguments.of(3, 4)
        );
    }
}
```

Or using `@ValueSource`, `@CsvSource`, etc.:
```java
@ParameterizedTest
@ValueSource(ints = {1, 2, 3})
void testWithValueSource(int argument) {
    // ...
}
```

### 3.4 Rules to Extensions

Common JUnit 4 rules need to be converted:

| JUnit 4 Rule | JUnit 5 Alternative |
|-------------|---------------------|
| `@Rule ExpectedException` | `assertThrows()` |
| `@Rule TemporaryFolder` | `@TempDir` annotation |
| `@Rule Timeout` | `@Timeout` annotation or `assertTimeout()` |
| `@Rule TestName` | `TestInfo` parameter injection |

**Example - ExpectedException:**

**JUnit 4:**
```java
@Rule
public ExpectedException thrown = ExpectedException.none();

@Test
public void testException() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Invalid");
    methodThatThrows();
}
```

**JUnit 5:**
```java
@Test
public void testException() {
    IllegalArgumentException exception = assertThrows(
        IllegalArgumentException.class,
        () -> methodThatThrows()
    );
    assertTrue(exception.getMessage().contains("Invalid"));
}
```

**Example - TemporaryFolder:**

**JUnit 4:**
```java
@Rule
public TemporaryFolder folder = new TemporaryFolder();

@Test
public void testWithTempFolder() {
    File file = folder.newFile("test.txt");
    // ...
}
```

**JUnit 5:**
```java
@Test
public void testWithTempFolder(@TempDir Path tempDir) {
    Path file = tempDir.resolve("test.txt");
    // ...
}
```

**Example - TestName:**

**JUnit 4:**
```java
@Rule
public TestName testName = new TestName();

@Test
public void testSomething() {
    System.out.println("Running: " + testName.getMethodName());
}
```

**JUnit 5:**
```java
@Test
public void testSomething(TestInfo testInfo) {
    System.out.println("Running: " + testInfo.getDisplayName());
}
```

---

## 4. Assertion Method Conversions

### 4.1 Basic Assertions

Most assertion method names remain the same, but the package and some argument orders change:

| JUnit 4 | JUnit 5 | Notes |
|---------|---------|-------|
| `assertEquals(expected, actual)` | `assertEquals(expected, actual)` | Same method, different package |
| `assertEquals(message, expected, actual)` | `assertEquals(expected, actual, message)` | **Message parameter moved to end** |
| `assertNotEquals(...)` | `assertNotEquals(...)` | Same |
| `assertTrue(condition)` | `assertTrue(condition)` | Same |
| `assertTrue(message, condition)` | `assertTrue(condition, message)` | **Message parameter moved to end** |
| `assertFalse(...)` | `assertFalse(...)` | Same |
| `assertNull(...)` | `assertNull(...)` | Same |
| `assertNotNull(...)` | `assertNotNull(...)` | Same |
| `assertSame(...)` | `assertSame(...)` | Same |
| `assertNotSame(...)` | `assertNotSame(...)` | Same |
| `assertArrayEquals(...)` | `assertArrayEquals(...)` | Same |

### 4.2 Message Parameter Reordering

**CRITICAL:** In JUnit 5, the optional message parameter is always **last**, while in JUnit 4 it was first.

**JUnit 4:**
```java
assertEquals("Values should match", expected, actual);
assertTrue("Should be true", condition);
```

**JUnit 5:**
```java
assertEquals(expected, actual, "Values should match");
assertTrue(condition, "Should be true");
```

### 4.3 Lazy Message Evaluation

JUnit 5 supports lambda expressions for lazy message evaluation:

```java
// Only evaluates message if assertion fails
assertEquals(expected, actual, () -> "Expensive message: " + computeExpensiveMessage());
```

### 4.4 Exception Assertions

**JUnit 4:**
```java
try {
    methodThatThrows();
    fail("Should have thrown exception");
} catch (IllegalArgumentException e) {
    assertEquals("Invalid input", e.getMessage());
}
```

**JUnit 5:**
```java
IllegalArgumentException exception = assertThrows(
    IllegalArgumentException.class,
    () -> methodThatThrows()
);
assertEquals("Invalid input", exception.getMessage());
```

### 4.5 Grouped Assertions

JUnit 5 introduces `assertAll()` for grouped assertions:

```java
@Test
void testPerson() {
    Person person = new Person("John", "Doe");
    assertAll("person",
        () -> assertEquals("John", person.getFirstName()),
        () -> assertEquals("Doe", person.getLastName())
    );
}
```

### 4.6 Timeout Assertions

**JUnit 4:**
```java
@Test(timeout = 1000)
public void testMethod() {
    // test code
}
```

**JUnit 5:**
```java
@Test
void testMethod() {
    assertTimeout(Duration.ofMillis(1000), () -> {
        // test code
    });
}
```

---

## 5. Assumption Conversions

Assumptions have similar changes to assertions:

| JUnit 4 | JUnit 5 |
|---------|---------|
| `import static org.junit.Assume.*;` | `import static org.junit.jupiter.api.Assumptions.*;` |
| `assumeTrue(condition)` | `assumeTrue(condition)` |
| `assumeFalse(condition)` | `assumeFalse(condition)` |
| `assumeNotNull(object)` | `assumeTrue(object != null)` |

---

## 6. Access Modifier Changes

**JUnit 4:** Test classes and methods must be `public`

**JUnit 5:** Test classes and methods can be package-private (default access)

**Migration Strategy:** Leave access modifiers as-is. While JUnit 5 allows package-private, keeping `public` is still valid and maintains backward compatibility for documentation tools.

---

## 7. Migration Algorithm

### 7.1 Phase 1: POM Dependency Migration

1. Parse `pom.xml` using Maven Model API
2. Remove JUnit 4 dependencies
3. Add JUnit 5 dependencies
4. Upgrade Mockito if version < 3.0
5. Add `mockito-junit-jupiter` if Mockito is present
6. Verify/upgrade Surefire plugin to >= 2.22.0
7. Write updated POM back to filesystem

### 7.2 Phase 2: Source Code Migration

For each test class (classes with `@Test` methods):

1. **Update imports:**
   - Replace JUnit 4 imports with JUnit 5 equivalents
   - Remove runner/rule imports
   - Add extension imports as needed

2. **Update annotations:**
   - Replace lifecycle annotations (`@Before` → `@BeforeEach`, etc.)
   - Convert `@RunWith` to `@ExtendWith`
   - Remove `@Rule` and `@ClassRule` annotations
   - Convert rule implementations to extensions

3. **Update @Test annotations:**
   - Extract `expected` parameter → wrap test body in `assertThrows()`
   - Extract `timeout` parameter → add `@Timeout` or `assertTimeout()`

4. **Update assertions:**
   - Change import from `org.junit.Assert` to `org.junit.jupiter.api.Assertions`
   - Reorder message parameters (move to end)
   - Convert try-catch-fail patterns to `assertThrows()`

5. **Update assumptions:**
   - Change import from `org.junit.Assume` to `org.junit.jupiter.api.Assumptions`

### 7.3 Phase 3: Verification

After migration:
- Compile the project
- Run all tests
- Report any failures for manual review

---

## 8. Edge Cases and Special Scenarios

### 8.1 Hamcrest Matchers

Hamcrest matchers work with both JUnit 4 and 5, no changes needed:

```java
assertThat(actual, is(equalTo(expected))); // Works in both
```

### 8.2 Spring Boot Test Annotations

Spring Boot test slices work seamlessly with JUnit 5:

```java
@DataJpaTest
@ExtendWith(SpringExtension.class) // Often optional with Spring Boot
public class RepositoryTest {
    // ...
}
```

Or use composite annotations:
```java
@DataJpaTest // Automatically includes Spring extension in Spring Boot 2.2+
public class RepositoryTest {
    // ...
}
```

### 8.3 Custom Runners

Custom runners must be converted to extensions. This may require:
- Creating a new `Extension` implementation
- Migrating logic from `Runner` interface to extension callbacks
- This is complex and may require manual intervention

**Migration Strategy:** Flag for manual review

### 8.4 Parameterized Tests with Complex Setup

JUnit 5 parameterized tests have a different model. Complex parameterized tests may need significant refactoring.

**Migration Strategy:** 
- Simple cases: Convert to `@ParameterizedTest` with `@MethodSource`
- Complex cases: Flag for manual review

### 8.5 Test Suites

**JUnit 4:**
```java
@RunWith(Suite.class)
@Suite.SuiteClasses({Test1.class, Test2.class})
public class TestSuite {
}
```

**JUnit 5:**
```java
@Suite
@SelectClasses({Test1.class, Test2.class})
public class TestSuite {
}
```

Requires:
```xml
<dependency>
    <groupId>org.junit.platform</groupId>
    <artifactId>junit-platform-suite</artifactId>
    <version>1.9.3</version>
    <scope>test</scope>
</dependency>
```

---

## 9. Implementation Priorities

### Priority 1: Critical (Must Have)
- POM dependency updates
- Basic annotation conversions (`@Test`, `@Before`, `@After`, etc.)
- Import statement updates
- Basic assertion package changes
- `@RunWith(MockitoJUnitRunner.class)` → `@ExtendWith(MockitoExtension.class)`
- `@RunWith(SpringRunner.class)` → `@ExtendWith(SpringExtension.class)`

### Priority 2: High (Should Have)
- `@Test(expected)` → `assertThrows()` conversion
- `@Test(timeout)` → `@Timeout` or `assertTimeout()` conversion
- Assert message parameter reordering
- `@Rule ExpectedException` → `assertThrows()` conversion
- `@Rule TemporaryFolder` → `@TempDir` conversion

### Priority 3: Medium (Nice to Have)
- `@Rule TestName` → `TestInfo` parameter injection
- `@Rule Timeout` → `@Timeout` annotation
- Try-catch-fail patterns → `assertThrows()` conversion
- Test suite conversions

### Priority 4: Low (Manual Review)
- Custom runners (flag for manual review)
- Complex parameterized tests (flag for manual review)
- Complex custom rules (flag for manual review)

---

## 10. Tool Output Format

The migration tool should provide:

1. **Summary Statistics:**
   - Number of classes migrated
   - Number of tests migrated
   - Number of annotations converted
   - Number of assertions updated

2. **Detailed Report:**
   - Per-class conversion details
   - List of conversions applied
   - Warnings for manual review items

3. **Dry Run Mode:**
   - Support `--dry-run` flag
   - Report changes without modifying files

**Example Output:**
```
JUnit 4 to 5 Migration Summary:
================================================================================
POM Dependencies:
  ✓ Removed: junit:junit:4.12
  ✓ Added: org.junit.jupiter:junit-jupiter:5.9.3
  ✓ Upgraded: org.mockito:mockito-core:2.28.2 → 5.11.0
  ✓ Added: org.mockito:mockito-junit-jupiter:5.11.0

Class: com.example.MyTest
  ✓ Updated imports (5 conversions)
  ✓ @Before → @BeforeEach
  ✓ @After → @AfterEach  
  ✓ @RunWith(MockitoJUnitRunner.class) → @ExtendWith(MockitoExtension.class)
  ✓ Converted @Test(expected=...) to assertThrows() (2 occurrences)
  ⚠ Manual review: Custom runner detected

Total: 15 classes, 87 tests migrated
Warnings: 2 items require manual review
```

---

## 11. Integration with TestFixer

The migration tool will integrate with the existing `TestFixer` architecture:

1. **Command Line Argument:** `--425` triggers JUnit 4 to 5 migration
2. **Architecture:** Create a `JUnit425Migrator` class similar to `EmbeddedResourceRefactorer`
3. **POM Management:** Reuse POM manipulation code from `TestRefactorer` and `EmbeddedResourceRefactorer`
4. **Code Transformation:** Use JavaParser for AST manipulation
5. **Outcome Tracking:** Create a `MigrationOutcome` class to track migration results
6. **Dry Run Support:** Respect the existing `--dry-run` flag

---

## 12. References

- [JUnit 5 User Guide](https://junit.org/junit5/docs/current/user-guide/)
- [JUnit 4 to 5 Migration Guide](https://junit.org/junit5/docs/current/user-guide/#migrating-from-junit4)
- [Mockito Migration Guide](https://github.com/mockito/mockito/wiki/What's-new-in-Mockito-2)
- [Maven Surefire Plugin](https://maven.apache.org/surefire/maven-surefire-plugin/)
