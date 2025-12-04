
# SDET Reference Guide: Java, JUnit 4, & Spring Boot Annotations

**Scope:** Java, JUnit 4, Spring Framework 5+, Spring Boot 2+  

---

## Chapter 1 — Annotation Reference

### 1.1 JUnit 4 Core (Lifecycle & Execution)
_Package: `org.junit`_

These annotations govern test lifecycle and execution.

| Annotation | Target | Description |
|-----------:|:------:|:-----------|
| `@Test` | Method | Marks a method as a test. |
| `@Before` | Method | Runs before each test. Use for complex setup. |
| `@After` | Method | Runs after each test (always). For cleanup. |
| `@BeforeClass` | Static Method | Executes once before the class. `public static`. Use for expensive setup. |
| `@AfterClass` | Static Method | Executes once after the class. `public static`. |
| `@Ignore` | Method/Class | Skips the test or class. |
| `@RunWith` | Class | Selects a custom JUnit runner (e.g., `SpringRunner`). |
| `@FixMethodOrder` | Class | Enforces execution order. |
| `@Category` | Class/Method | Tags tests for filtering. |
| `@Rule` | Field | Adds JUnit rules (e.g., `TemporaryFolder`). |

---

### 1.2 Spring Boot Integration (Context & Config)
_Packages: `org.springframework.boot.test.context`, `org.springframework.test.context`_

| Annotation | Description |
|:-----------|:------------|
| `@SpringBootTest` | Loads the full `ApplicationContext`. `webEnvironment` controls embedded server behavior. |
| `@ContextConfiguration` | Manually specify config classes or XML. Useful for legacy modules. |
| `@TestPropertySource` | Override properties via file or inline values. |
| `@ActiveProfiles` | Activate Spring profiles (e.g., `test`). |
| `@DirtiesContext` | Marks context dirty; forces reload after test/class. |
| `@TestConfiguration` | Test-only configuration beans. |
| `@Primary` | Marks a bean preferred for injection. |
| `@Profile` | Load beans only when profile active. |

---

### 1.3 Spring Boot Slices (Layer Isolation)
_Package: `org.springframework.boot.test.autoconfigure.*`_

Slice tests load only the beans relevant to a layer — faster than `@SpringBootTest`.

| Annotation | Target Layer | Beans Loaded |
|:-----------|:------------:|:-------------|
| `@WebMvcTest` | Controller | Controllers, `ControllerAdvice`, JSON components. No services/repos. |
| `@DataJpaTest` | JPA/Repository | Repositories, `EntityManager`. Transactional by default. |
| `@RestClientTest` | HTTP client | `MockRestServiceServer`, `RestTemplateBuilder`. |
| `@JsonTest` | Serialization | Jackson/Gson modules only. |
| `@JdbcTest` | JDBC | `JdbcTemplate`, `DataSource`. No ORM. |

---

### 1.4 Mocking & Injection
_Package: `org.springframework.boot.test.mock.mockito`_

| Annotation | Description |
|:-----------|:------------|
| `@MockBean` | Adds a Mockito mock to the Spring Context; replaces existing bean of same type. |
| `@SpyBean` | Wraps existing bean with a Mockito spy. |
| `@Autowired` | Standard Spring injection in tests. |

---

### 1.5 Data & Transactions
_Packages: `org.springframework.test.annotation`, `org.springframework.transaction.annotation`_

| Annotation | Description |
|:-----------|:------------|
| `@Transactional` | Wraps test in a transaction. Rolls back by default. |
| `@Rollback` | Control rollback behavior (`@Rollback(false)` to commit). |
| `@Sql` | Execute SQL scripts before/after tests. |

---

## Chapter 2 — Setup Templates & Usage Guidelines

This chapter covers recommended patterns and common anti-patterns that cause slow or brittle tests.

---

### 2.1 Pure Unit Tests (Fastest)
Use when no Spring context is required.

```java
@RunWith(MockitoJUnitRunner.class)
public class UserServiceTest {

    @InjectMocks
    private UserService userService;

    @Mock
    private UserRepository userRepository;

    @Test
    public void testFindUser() {
        when(userRepository.findById(1)).thenReturn(Optional.of(new User("John")));
        assertEquals("John", userService.findUser(1).getName());
    }
}
```

**Anti-patterns**

- Heavyweight Unit: Avoid `@SpringBootTest` for pure logic.
- Overuse of `@Before` for trivial init.
- `@Test(expected=...)` hides where exception came from — prefer `assertThrows`.

---

### 2.2 Controller Tests (Slice Pattern)

```java
@RunWith(SpringRunner.class)
@WebMvcTest(UserController.class)
public class UserControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private UserService userService;

    @Test
    public void testGetUser() throws Exception {
        when(userService.findById(1)).thenReturn(new User("John"));
        mockMvc.perform(get("/users/1")).andExpect(status().isOk());
    }
}
```

**Anti-patterns**

- Security Tunnel Vision: If security config requires DB, `@WebMvcTest` becomes difficult. Consider `@SpringBootTest` or test profiles.
- Mock Everything: Don't mock simple DTOs — instantiate them.

---

### 2.3 Integration Tests (Full Context)

```java
@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class UserFlowIntegrationTest {
    @Autowired private TestRestTemplate restTemplate;
    @Test public void testFullFlow() { ... }
}
```

**Anti-patterns**

- Profile Explosion: Avoid creating unique profiles per test class; reuse `test` profile to enable context caching.

---

### 2.4 Database Tests (Persistence Layer)

```java
@RunWith(SpringRunner.class)
@DataJpaTest
public class UserRepositoryTest {
    @Autowired private UserRepository userRepository;

    @Test
    @Sql("/seed-users.sql")
    public void testQuery() { ... }
}
```

**Anti-patterns**

- Ghost Script: Don't use `@Sql` when `DataSource` is mocked.
- Leaky Transaction: Threads inside `@Transactional` tests don't share transactions.
- Test Dependency: Avoid relying on `@Rollback(false)` to feed other tests.

---

### 2.5 Specialized Slices

- `@RestClientTest` for testing `RestTemplate` clients.
- `@JsonTest` for testing Jackson/Gson configuration.

**Anti-patterns**

- Wrong Tool: Don't use `@RestClientTest` for reactive `WebClient`.
- Overkill: Don't use `@JsonTest` for trivial getters/setters.

---

### 2.6 Advanced Lifecycle & Execution Patterns

**Anti-patterns**

- Static Trap: `@BeforeClass` can't access `@Autowired` instance fields.
- Coupled Suite: Avoid using `@FixMethodOrder` to pass state between tests.
- Rotting Ignore: Don't commit `@Ignore` tests without a remediation plan.

---

### 2.7 Context Configuration & State Management

Use `@ContextConfiguration` only for non-Boot legacy modules. Prefer `@SpringBootTest` otherwise.

Example pattern:

```java
@ContextConfiguration(classes = MyManualConfig.class)
@TestPropertySource(properties = "timezone=GMT")
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
public class ComplexLegacyTest { ... }
```

**Anti-patterns**

- Manual override instead of Boot conventions.
- `@DirtiesContext` used for lazy cleanup — it slows suites.
- Inline large property sets in `@TestPropertySource` — move to `application-test.properties`.

---

## Additional Section A — Diagrams (ASCII)

### A.1 Spring Test Context Lifecycle

```
┌───────────────────────────┐
│       Test Class          │
└──────────────┬────────────┘
               │
               ▼
     ┌───────────────────┐
     │  Load Context?    │
     └───────┬───────────┘
             │
   No ------ │ ------ Yes
    (unit)   │    ┌───────────────┐
             ▼    │ Build/Reuse   │
          Execute │ Application   │
          Tests   │ Context       │
                  └──────┬────────┘
                         │
                    Cached? → Reuse if same config
                         │
                         ▼
                  Execute @Before / @Test
                         │
                      @After
                         │
                     @DirtiesContext? → discard
```

### A.2 Test Complexity vs Cost

```
Cost ↑
     │                Full Integration (@SpringBootTest)
     │       Slice Tests (@WebMvcTest, @DataJpaTest)
     │  Unit Tests (@Mock, @InjectMocks)
     └────────────────────────────────────────→ Test Speed
```

### A.3 What Loads (Quick Table)

```
+-------------------+--------+----+------------+
| Annotation        | Server | DB | Autoconfig |
+-------------------+--------+----+------------+
| @SpringBootTest   | maybe  | yes| full       |
| @WebMvcTest       | no     | no | mvc only   |
| @DataJpaTest      | no     | yes| jpa only   |
| @JsonTest         | no     | no | jackson    |
| Unit Test         | no     | no | none       |
+-------------------+--------+----+------------+
```

---

## Additional Section B — Troubleshooting & FAQ

**B.1 My test is extremely slow.**  
- Check for inadvertent `@SpringBootTest`.  
- Look for many unique `@ActiveProfiles`.  
- Search for `@DirtiesContext` usages.  
- Enable Spring test cache logging.

**B.2 @WebMvcTest fails because of security beans.**  
- Create a lightweight security test profile or mock the minimal beans; otherwise use `@SpringBootTest`.

**B.3 @BeforeClass cannot access @Autowired fields.**  
- `@BeforeClass` is static and runs prior to instance creation.

**B.4 Threads inside @Transactional test see inconsistent data.**  
- Threads do not share the test transaction. Avoid or change approach.

**B.5 @Test(expected=...) passes unexpectedly.**  
- `assertThrows` is safer and more precise.

**B.6 @Sql not executing.**  
- Ensure `DataSource` is not mocked and script is on the classpath.

**B.7 @DataJpaTest failing due to missing beans.**  
- Remember it only configures the JPA layer; mock or test other layers separately.

**B.8 Controller test fails because of serialization.**  
- Check for missing no-args constructor, Lombok constructor annotations, or custom serializer registration.

**B.9 @DirtiesContext is slowing the suite.**  
- Avoid it unless state cannot be cleaned up; prefer transactional cleanup.

---

## Additional Section C — How to Choose the Right Annotation

### C.1 Quick Decision Tree (text)

1. Do you need Spring?  
   - No → Pure unit test (Mockito + `@RunWith(MockitoJUnitRunner.class)`)  
   - Yes → Do you need DB?  
     - No → Do you need MVC?  
       - Yes → `@WebMvcTest`  
       - No → `@JsonTest` / slice specific  
     - Yes → `@DataJpaTest` or `@SpringBootTest` for full integration

### C.2 Selection by Scenario

- Business logic: Mockito unit tests.  
- Controller: `@WebMvcTest` + `MockMvc`.  
- Repository: `@DataJpaTest` + `@Sql` for seeds.  
- Serialization: `@JsonTest`.  
- HTTP client: `@RestClientTest`.  
- Full workflow: `@SpringBootTest(webEnvironment = RANDOM_PORT)`.

### C.3 Profiles & TestConfiguration

- Use `@ActiveProfiles("test")` to load test config and properties.  
- Use `@TestConfiguration` for test-only bean overrides with `@Primary` if needed.

---

## Appendix — Useful Snippets

**Precise exception assertion (JUnit 4 with AssertJ or JUnit 5 style in comments):**

```java
// JUnit 4 style (with AssertJ)
@Test
public void shouldThrow() {
    assertThatThrownBy(() -> service.doThing()).isInstanceOf(MyException.class);
}
```

**Example: Replacing derived query with explicit `@Query`:**

```java
@Query("SELECT e FROM Appointment e WHERE e.appointmentNumber = :number "
     + "AND e.deleted = false "
     + "AND e.medicationApproval = true "
     + "AND e.approvalStatus IN :statuses")
Appointment fetchAppointment(@Param("number") String number, @Param("statuses") List<String> statuses);
```

---


