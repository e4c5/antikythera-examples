# SDET Reference Guide: Java, JUnit 5 (Jupiter), & Spring Boot Annotations

**Scope:** Java, JUnit 5 (Jupiter), Spring Framework 5+, Spring Boot 2.7+/3+  

---

## Chapter 1 — Annotation Reference

### 1.1 JUnit 5 Core (Lifecycle & Execution)
_Packages: `org.junit.jupiter.api`_

| Annotation | Target | Description |
|-----------:|:------:|:-----------|
| `@Test` | Method | Marks a standard Jupiter test method. |
| `@BeforeEach` | Method | Runs before every test. Use for per-test setup. |
| `@AfterEach` | Method | Runs after every test. Handles cleanup. |
| `@BeforeAll` | Static/Instance* Method | Executes once before all tests. Requires `static` unless using `@TestInstance(PER_CLASS)`. |
| `@AfterAll` | Static/Instance* Method | Executes once after all tests. Same static rules as `@BeforeAll`. |
| `@DisplayName` | Method/Class | Overrides the generated test name for readable reports. |
| `@DisplayNameGeneration` | Class | Configures class-wide name generation (e.g., `DisplayNameGenerator.ReplaceUnderscores`). |
| `@Tag` | Method/Class | Logical tagging for filtering suites (replacement for JUnit 4 `@Category`). |
| `@Disabled` | Method/Class | Skips execution with an optional reason. |
| `@Nested` | Inner Class | Groups related tests with scoped lifecycle callbacks. |
| `@TestInstance` | Class | Controls lifecycle (`PER_CLASS` lets you use non-static `@BeforeAll`). |
| `@TestMethodOrder` | Class | Controls ordering (e.g., `MethodOrderer.OrderAnnotation`). |
| `@Order` | Method | Works with `MethodOrderer.OrderAnnotation` when ordering is unavoidable. |

> *Jupiter defaults to `PER_METHOD` test instances, so `@BeforeAll`/`@AfterAll` must be `static` unless `@TestInstance(PER_CLASS)` is present.

---

### 1.2 Parameterized & Dynamic Tests
_Packages: `org.junit.jupiter.params`, `org.junit.jupiter.api`_

| Annotation | Description |
|:-----------|:------------|
| `@ParameterizedTest` | Declares a test template that runs for each provided argument set. |
| `@ValueSource` | Provides literal argument lists (e.g., numbers, strings). |
| `@EnumSource` | Iterates over enum constants (optionally filtered). |
| `@MethodSource` | Uses factory methods returning `Stream`, `Iterable`, or arrays. |
| `@CsvSource` / `@CsvFileSource` | Supplies arguments as CSV inline or from a file. |
| `@ArgumentsSource` | Plugs in custom argument providers. |
| `@ArgumentsAggregator` | Combines multiple parameters into a single object. |
| `DynamicTest.dynamicTest` | Creates tests at runtime inside `@TestFactory`. |
| `@TestFactory` | Declares a method that returns `Stream`/`Collection` of `DynamicNode` for generated tests. |

---

### 1.3 Assertions, Assumptions, and Timeout Controls

| API | Description |
|:----|:------------|
| `Assertions.*` | Rich assertion set (e.g., `assertThrows`, `assertAll`, `assertTimeout`). |
| `Assumptions.*` | Skips test when assumptions fail (e.g., environment pre-check). |
| `@Timeout` | Fails if method exceeds given duration (per-test or per-class). |
| `@RepeatedTest` | Repeats the same test N times with iteration info injected. |

---

### 1.4 Extension Model
_Package: `org.junit.jupiter.api.extension`_

| Annotation | Description |
|:-----------|:------------|
| `@ExtendWith` | Registers Jupiter extensions (replacement for JUnit 4 `@RunWith` + `@Rule`). Multiple extensions allowed. |
| `@RegisterExtension` | Programmatically registers an extension as a field (static or instance). |
| Common Extensions | `SpringExtension`, `MockitoExtension`, `RestDocumentationExtension`, custom ones for logging or containers. |

---

### 1.5 Spring Boot Integration (Context & Config)
_Packages: `org.springframework.boot.test.context`, `org.springframework.test.context`_

| Annotation | Description |
|:-----------|:------------|
| `@SpringBootTest` | Boots the full `ApplicationContext`. Works naturally with `@ExtendWith(SpringExtension.class)` (already meta-present). |
| `@ContextConfiguration` | Manual context wiring for legacy or non-Boot modules. |
| `@TestPropertySource` | Injects extra property files or inline overrides. |
| `@ActiveProfiles` | Activates named profiles (e.g., `test`). |
| `@DirtiesContext` | Marks context as dirty, forcing rebuild (use sparingly). |
| `@TestConfiguration` | Declares test-only beans. Combine with `@Primary` to override real beans. |
| `@Primary` | Chooses a preferred bean for autowiring. |
| `@Profile` | Guards bean creation by active profile. |

---

### 1.6 Spring Boot Slices (Layer Isolation)
_Package: `org.springframework.boot.test.autoconfigure.*`_

| Annotation | Target Layer | Beans Loaded |
|:-----------|:------------:|:-------------|
| `@WebMvcTest` | Controller | MVC infrastructure, Jackson, `ControllerAdvice`. No services/repos. |
| `@DataJpaTest` | Repository | JPA repositories, `EntityManager`. Transactional by default. |
| `@RestClientTest` | HTTP client | `RestTemplateBuilder`, `MockRestServiceServer`, JSON converters. |
| `@JsonTest` | Serialization | Jackson/Gson configuration only. |
| `@JdbcTest` | JDBC | `JdbcTemplate`, embedded DB config. No ORM. |
| `@GraphQlTest` | GraphQL controller | GraphQL components without full MVC. |
| `@WebFluxTest` | Reactive controller | Configures `WebTestClient` and WebFlux-only beans. |

---

### 1.7 Mocking & Injection Helpers

| Annotation | Package | Description |
|:-----------|:--------|:------------|
| `@MockBean` | `org.springframework.boot.test.mock.mockito` | Injects Mockito mock into the Spring context, replacing beans by type. |
| `@SpyBean` | same | Wraps an existing bean with a Mockito spy to verify interactions. |
| `@Mock` / `@InjectMocks` | `org.mockito` | Pure Mockito annotations, typically paired with `@ExtendWith(MockitoExtension.class)`. |
| `@Captor` | `org.mockito` | Declares argument captors. |
| `@Autowired` | `org.springframework.beans.factory.annotation` | Standard dependency injection in tests. |

---

### 1.8 Data & Transactions

| Annotation | Package | Description |
|:-----------|:--------|:------------|
| `@Transactional` | `org.springframework.transaction.annotation` | Wraps tests in a transaction that rolls back automatically. |
| `@Rollback` | `org.springframework.test.annotation` | Toggles rollback behavior (`false` commits). |
| `@Sql` / `@SqlGroup` | `org.springframework.test.context.jdbc` | Executes SQL scripts before/after tests. |
| `@SqlConfig` | same | Tunes SQL execution settings (e.g., transaction mode). |

---

## Chapter 2 — Setup Templates & Usage Guidelines

This chapter mirrors common scenarios with JUnit 5 idioms plus Spring-specific guidance.

---

### 2.1 Pure Unit Tests (Fastest Path)
Use when Spring is unnecessary. Rely on Mockito or other libraries only.

```java
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @InjectMocks
    private UserService userService;

    @Mock
    private UserRepository userRepository;

    @Test
    void findsUserById() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(new User("John")));

        var result = userService.findUser(1L);

        assertAll(
            () -> assertNotNull(result),
            () -> assertEquals("John", result.getName())
        );
    }
}
```

**Anti-patterns**

- Heavyweight Setup: Avoid `@SpringBootTest` when mocking is cheaper.
- Legacy Assertions: Prefer `assertThrows`/`assertThatThrownBy` over `@Test(expected=...)` (no longer supported).
- Static State: Do not rely on static singletons; Jupiter creates fresh instances per test by default.

---

### 2.2 Controller Tests (Slice Pattern)

```java
@WebMvcTest(UserController.class)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;

    @Test
    void getUser_returns200() throws Exception {
        when(userService.findById(1L)).thenReturn(new User("John"));

        mockMvc.perform(get("/users/{id}", 1L))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.name").value("John"));
    }
}
```

**Anti-patterns**

- Security Tunnel Vision: If MVC slice pulls in DB-backed security beans, either mock them or fall back to `@SpringBootTest` with test profile.
- Over-mocking DTOs: Instantiate simple POJOs directly.
- Hidden Extensions: `@WebMvcTest` already imports `SpringExtension`; do not re-declare `@ExtendWith(SpringExtension.class)`.

---

### 2.3 Integration Tests (Full Context + Random Port)

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class UserFlowIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void fullFlow_happyPath() {
        var response = restTemplate.getForEntity("/users/{id}", User.class, 1L);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }
}
```

**Anti-patterns**

- Profile Sprawl: Reuse a shared `test` profile to benefit from context caching.
- Blocking Calls in Parallel Tests: When using `@Execution(CONCURRENT)`, ensure endpoints are idempotent or reset state per test.
- Missing `spring.main.lazy-initialization=true` during diagnostics—turn it on temporarily to profile startup hot spots.

---

### 2.4 Database & Repository Tests

```java
@DataJpaTest
@ActiveProfiles("test")
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    @Sql("/seed-users.sql")
    void queryReturnsSeededUser() {
        var user = userRepository.findByEmail("john@test.dev");
        assertThat(user).isPresent();
    }
}
```

**Anti-patterns**

- Ghost Script: `@Sql` does nothing if your `DataSource` bean is mocked—ensure real DB connectivity exists.
- Thread Leaks: Async tasks inside transactional tests run outside the rollback transaction. Prefer synchronous code or explicit cleanup.
- Relying on `@Rollback(false)`: Tests must remain isolated; never depend on prior DB state.

---

### 2.5 Specialized Slices & Reactive Scenarios

- `@RestClientTest` for REST clients built with `RestTemplate`.
- `@WebFluxTest` + `WebTestClient` for reactive controllers.
- `@GraphQlTest` for Spring GraphQL controllers.
- `@JsonTest` for verifying Jackson modules and custom serializers.

**Anti-patterns**

- Wrong Abstraction: Do not use `@RestClientTest` for WebClient; use `@AutoConfigureWebTestClient` with `@SpringBootTest` or `WebClient`-focused libraries.
- Overkill Slice: If the code under test is pure serialization logic, use `@JsonTest` or even plain unit tests instead of booting MVC.

---

### 2.6 Advanced Lifecycle & Execution Patterns

- Prefer dependency-injected constructors over field injection so Jupiter can take advantage of parameter resolution.
- Use `@TestInstance(PER_CLASS)` only when costly setup with non-static `@BeforeAll` is required. Document why to avoid accidental shared state.
- Replace `@FixMethodOrder` with `@TestMethodOrder`. If ordering is necessary, guard it with clear comments and `@Order` values.
- Replace JUnit 4 Rules with Jupiter extensions (`@RegisterExtension`).

**Anti-patterns**

- Coupled Suites: Do not share mutable state via `PER_CLASS` unless thread-safe.
- Rotting Ignores: `@Disabled` should include a ticket reference and expiration plan.
- Extension Roulette: Limit extension stacking; too many cross-cutting concerns complicate debugging.

---

### 2.7 Context Configuration & State Management

```java
@ContextConfiguration(classes = LegacyConfig.class)
@TestPropertySource(properties = "app.timezone=UTC")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LegacyContextTest {

    @Autowired
    LegacyService legacyService;

    @Test
    void runsAgainstLegacyConfig() {
        // ...
    }
}
```

**Guidelines**

- Prefer auto-configuration via `@SpringBootTest` unless legacy XML or manual wiring demands `@ContextConfiguration`.
- Keep `@TestPropertySource` small; move large sets into `application-test.properties`.
- Use `@DirtiesContext` only when caches or static singletons corrupt subsequent tests.

---

## Additional Section A — Diagrams (ASCII)

### A.1 Spring Test Context Lifecycle (Jupiter Edition)

```
┌───────────────────────────┐
│       Test Class          │
└──────────────┬────────────┘
               │
               ▼
     ┌───────────────────┐
     │  Need Context?    │
     └───────┬───────────┘
             │
   No ------ │ ------ Yes
    (unit)   │    ┌───────────────┐
             ▼    │ Build/Reuse   │
          Execute │ Application   │
          Tests   │ Context       │
                  └──────┬────────┘
                         │
                    Cached? → re-use config key
                         │
                         ▼
                 Jupiter lifecycle:
                 @BeforeAll → @BeforeEach
                 @Test/@ParameterizedTest
                 @AfterEach → @AfterAll
                         │
                     @DirtiesContext? → discard
```

### A.2 Test Complexity vs Cost (Updated)

```
Cost ↑
     │                Full Integration (@SpringBootTest)
     │       Slice Tests (@WebMvcTest, @DataJpaTest, @WebFluxTest)
     │  Unit & Parameterized Tests (@ExtendWith(MockitoExtension))
     └────────────────────────────────────────→ Test Speed
```

### A.3 What Loads (Quick Table)

```
+-------------------+--------+----+------------+
| Annotation        | Server | DB | Autoconfig |
+-------------------+--------+----+------------+
| @SpringBootTest   | maybe  | yes| full       |
| @WebMvcTest       | no     | no | mvc only   |
| @WebFluxTest      | no     | no | webflux    |
| @DataJpaTest      | no     | yes| jpa only   |
| @JsonTest         | no     | no | jackson    |
| Pure JUnit Test   | no     | no | none       |
+-------------------+--------+----+------------+
```

---

## Additional Section B — Troubleshooting & FAQ

**B.1 Tests are still slow after migrating to Jupiter.**  
- Ensure `@SpringBootTest` is only used when necessary.  
- Parameterize similar cases instead of duplicating contexts.  
- Validate that `@DirtiesContext` is not scattering across the suite.  
- Enable `spring.test.context.cache.maxSize` logging to see cache churn.

**B.2 Parameterized test fails to resolve arguments.**  
- Check factory method visibility (`static`, `public` or package-private).  
- Ensure sources return Stream/Iterable/Iterator of `Arguments`.  
- Watch for mismatched argument counts.

**B.3 `@BeforeAll` refuses non-static methods.**  
- Add `@TestInstance(TestInstance.Lifecycle.PER_CLASS)` or make the callback static.

**B.4 Mixing Mockito with Spring causes `NullPointerException`.**  
- Use Spring slices with `@MockBean` to let Spring inject mocks, or stick to pure Mockito with `@ExtendWith(MockitoExtension.class)`—do not mix `@MockBean` into non-Spring tests.

**B.5 Dynamic tests do not run inside `@Nested` classes.**  
- `@TestFactory` methods must be top-level within each `@Nested` class; ensure the factory returns `DynamicTest` instances and not assertions directly.

**B.6 Upgrading from JUnit 4: rules no longer apply.**  
- Replace `@Rule`/`@ClassRule` with Jupiter extensions or `@RegisterExtension`. Libraries like Mockito, Spring, and RestDocs provide drop-in replacements.

**B.7 `@SpringBootTest` with WebFlux needs a client.**  
- Inject `WebTestClient` using `@AutoConfigureWebTestClient` or use `TestRestTemplate` only in servlet-based apps.

**B.8 `@Disabled` tests forgotten in CI.**  
- Add build enforcement (Gradle `junitPlatform` filters) or custom extension that fails build when disabled tests age past a deadline.

---

## Additional Section C — Selecting the Right Approach

### C.1 Quick Decision Flow

1. Do you need Spring?  
   - No → Pure Jupiter + Mockito (`@ExtendWith(MockitoExtension.class)`).  
   - Yes → Do you touch the DB?  
     - No → Need MVC or WebFlux?  
       - MVC → `@WebMvcTest` (servlet, MockMvc).  
       - WebFlux → `@WebFluxTest` + `WebTestClient`.  
       - Other slice (JSON, GraphQL, Rest client) → pick autoconfigure annotation.  
     - Yes → Use `@DataJpaTest` or full `@SpringBootTest` if multiple layers interact.

### C.2 Scenario Mapping

- Business logic: Mockito + `@ParameterizedTest` for combinatorial coverage.  
- Controller (servlet): `@WebMvcTest` + `MockMvc`.  
- Controller (reactive): `@WebFluxTest` + `WebTestClient`.  
- Repository: `@DataJpaTest` + `@Sql`.  
- Serialization: `@JsonTest`.  
- External REST client: `@RestClientTest`.  
- End-to-end workflow: `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `TestRestTemplate` or `WebTestClient`.

### C.3 Profiles, Config, and Extensions

- Use `@ActiveProfiles("test")` + `application-test.properties` for shared overrides.  
- Group test-only beans inside `@TestConfiguration` classes and annotate with `@Import` if needed.  
- Encapsulate repetitive setup within custom extensions instead of copy-pasting `@BeforeEach` logic.

---

## Appendix — Useful Snippets & Migration Tips

**Precise Exception Assertions**

```java
@Test
void shouldThrowWhenInputInvalid() {
    var exception = assertThrows(IllegalArgumentException.class,
        () -> service.doThing("bad"));
    assertThat(exception).hasMessageContaining("bad");
}
```

**Parameterized Example with CSV**

```java
@ParameterizedTest
@CsvSource({"john@example.com,true", "not-an-email,false"})
void validatesEmail(String candidate, boolean expected) {
    assertEquals(expected, validator.isValid(candidate));
}
```

**Registering a Custom Extension**

```java
class ClockExtension implements BeforeEachCallback {
    @Override
    public void beforeEach(ExtensionContext context) {
        Clock fixedClock = Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC);
        context.getStore(Namespace.GLOBAL).put("clock", fixedClock);
    }
}

@ExtendWith(ClockExtension.class)
class TimeSensitiveTest {
    @Test
    void assertsTime() {
        Clock clock = ExtensionContextStore.getClock();
        // ...
    }
}
```

**Spring + Mockito Hybrid (Test Slice)**

```java
@WebMvcTest(UserController.class)
class UserControllerWithDocsTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;

    @RegisterExtension
    static final RestDocumentationExtension restDocs = new RestDocumentationExtension();

    // tests
}
```

**Migration Checklist (4 → 5)**

1. Update build to use `org.junit.jupiter:junit-jupiter` BOM and enable the Jupiter engine in Surefire/Failsafe.  
2. Replace `@RunWith(SpringRunner.class)` with Jupiter-native annotations (`@SpringBootTest`, `@WebMvcTest` already include `SpringExtension`).  
3. Convert lifecycle callbacks: `@Before`→`@BeforeEach`, `@AfterClass`→`@AfterAll`, etc.  
4. Swap Rules for extensions; look for library-provided replacements.  
5. Modernize assertions and exception checks with `assertThrows`/`Assertions.assertAll`.  

---

