package com.raditha.cleanunit;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.raditha.cleanunit.TestRefactorer.RefactorOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRefactorerTest {

    private TestRefactorer refactorer;

    @BeforeEach
    void setUp() throws Exception {
        sa.com.cloudsolutions.antikythera.configuration.Settings.loadConfigMap();
        sa.com.cloudsolutions.antikythera.configuration.Settings.setProperty("base_path",
                System.getProperty("user.dir"));
        refactorer = new TestRefactorer(true); // dry-run
    }

    static Stream<Arguments> refactorTestCases() {
        return Stream.of(
                Arguments.of(
                        "HeavyweightUnitTest",
                        """
                        import org.springframework.boot.test.context.SpringBootTest;
                        import org.mockito.Mock;
                        @SpringBootTest
                        class HeavyweightUnitTest {
                          @Mock Object dep;
                          @org.junit.jupiter.api.Test void t() { /* no real resources used */ }
                        }
                        """,
                        "CONVERTED",
                        "Unit Test",
                        "No resources detected (all mocked)"
                ),
                Arguments.of(
                        "InefficientControllerTest",
                        """
                        import org.springframework.boot.test.context.SpringBootTest;
                        import org.springframework.test.web.servlet.MockMvc;
                        @SpringBootTest
                        class InefficientControllerTest {
                          @org.springframework.beans.factory.annotation.Autowired MockMvc mockMvc;
                          @org.junit.jupiter.api.Test void t() throws Exception {
                            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/x"));
                          }
                        }
                        """,
                        "CONVERTED",
                        "@WebMvcTest",
                        "Only WEB resource detected (JSON allowed)"
                ),
                Arguments.of(
                        "InefficientRepositoryTest",
                        """
                        import org.springframework.boot.test.context.SpringBootTest;
                        @SpringBootTest
                        class InefficientRepositoryTest {
                          @org.springframework.beans.factory.annotation.Autowired UserRepository repo;
                          @org.junit.jupiter.api.Test void t() { repo.findAll(); }
                        }
                        interface UserRepository { java.util.List findAll(); }
                        """,
                        "CONVERTED",
                        "@DataJpaTest",
                        "Only DATABASE_JPA resource detected"
                ),
                Arguments.of(
                        "UnsafeIntegrationTest",
                        """
                        import org.springframework.boot.test.context.SpringBootTest;
                        import org.springframework.boot.test.web.server.LocalServerPort;
                        @SpringBootTest
                        class UnsafeIntegrationTest {
                          @LocalServerPort int port;
                          @org.junit.jupiter.api.Test void t() {}
                        }
                        """,
                        "KEPT",
                        "@SpringBootTest(webEnvironment = RANDOM_PORT)",
                        "Requires running server"
                ),
                Arguments.of(
                        "JdbcOnlyTest",
                        """
                        import org.springframework.boot.test.context.SpringBootTest;
                        @SpringBootTest
                        class JdbcOnlyTest {
                          @org.springframework.beans.factory.annotation.Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
                          @org.junit.jupiter.api.Test void t() { jdbc.execute("select 1"); }
                        }
                        """,
                        "CONVERTED",
                        "@JdbcTest",
                        "Only JDBC resource detected"
                ),
                Arguments.of(
                        "ReactiveControllerTest",
                        """
                        import org.springframework.boot.test.context.SpringBootTest;
                        import org.springframework.test.web.reactive.server.WebTestClient;
                        @SpringBootTest
                        class ReactiveControllerTest {
                          @org.springframework.beans.factory.annotation.Autowired WebTestClient client;
                          @org.junit.jupiter.api.Test void t() { client.get().uri("/x").exchange(); }
                        }
                        """,
                        "CONVERTED",
                        "@WebFluxTest",
                        "Only WEBFLUX resource detected"
                ),
                Arguments.of(
                        "GraphQlOnlyTest",
                        """
                        import org.springframework.boot.test.context.SpringBootTest;
                        import org.springframework.graphql.test.tester.GraphQlTester;
                        @SpringBootTest
                        class GraphQlOnlyTest {
                          @org.springframework.beans.factory.annotation.Autowired GraphQlTester tester;
                          @org.junit.jupiter.api.Test void t() { tester.toString(); }
                        }
                        """,
                        "CONVERTED",
                        "@GraphQlTest",
                        "Only GRAPHQL resource detected"
                ),
                Arguments.of(
                        "RestClientOnlyTest",
                        """
                        import org.springframework.boot.test.context.SpringBootTest;
                        import org.springframework.test.web.client.MockRestServiceServer;
                        @SpringBootTest
                        class RestClientOnlyTest {
                          @org.springframework.beans.factory.annotation.Autowired MockRestServiceServer server;
                          @org.junit.jupiter.api.Test void t() { server.toString(); }
                        }
                        """,
                        "CONVERTED",
                        "@RestClientTest",
                        "Only REST_CLIENT resource detected"
                ),
                Arguments.of(
                        "JsonOnlyTest",
                        """
                        import org.springframework.boot.test.context.SpringBootTest;
                        import com.fasterxml.jackson.databind.ObjectMapper;
                        @SpringBootTest
                        class JsonOnlyTest {
                          @org.springframework.beans.factory.annotation.Autowired ObjectMapper mapper;
                          @org.junit.jupiter.api.Test void t() { mapper.createObjectNode(); }
                        }
                        """,
                        "CONVERTED",
                        "@JsonTest",
                        "Only JSON resource detected"
                )
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refactorTestCases")
    void testRefactoring(String testName, String src, String expectedAction,
                         String expectedAnnotation, String expectedReason) {
        CompilationUnit cu = StaticJavaParser.parse(src);

        RefactorOutcome outcome = refactorer.refactor(cu);

        assertEquals(expectedAction, outcome.action);
        assertEquals(expectedAnnotation, outcome.newAnnotation);
        assertEquals(expectedReason, outcome.reason);
    }

    @Test
    void testLegacyContextTest() {
        String src = """
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.test.context.ContextConfiguration;
                @SpringBootTest
                @ContextConfiguration(classes = {LegacyConfig.class})
                class LegacyContextTest {
                  @org.junit.jupiter.api.Test void t() {}
                }
                class LegacyConfig {}
                """;
        CompilationUnit cu = StaticJavaParser.parse(src);

        RefactorOutcome outcome = refactorer.refactor(cu);

        if (outcome != null) {
            assertEquals("CONVERTED", outcome.action);
            assertEquals("Unit Test", outcome.newAnnotation);
        }
    }

    @Test
    void testMixedResourcesFallBackToSpringBootTest() {
        String src = """
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.test.web.servlet.MockMvc;
                @SpringBootTest
                class MixedResourcesTest {
                  @org.springframework.beans.factory.annotation.Autowired MockMvc mockMvc;
                  @org.springframework.beans.factory.annotation.Autowired UserRepository repo;
                  @org.junit.jupiter.api.Test void t() { mockMvc.toString(); repo.findAll(); }
                }
                interface UserRepository { java.util.List findAll(); }
                """;
        CompilationUnit cu = StaticJavaParser.parse(src);
        RefactorOutcome outcome = refactorer.refactor(cu);
        assertTrue(outcome.action.equals("REVERTED") || outcome.action.equals("KEPT"));
        assertTrue(outcome.newAnnotation.startsWith("@SpringBootTest"));
    }

    @Test
    void testMultiClassCompilationUnit() {
        String src = """
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.test.web.servlet.MockMvc;
                @SpringBootTest
                class AControllerTest {
                  @org.springframework.beans.factory.annotation.Autowired MockMvc mockMvc;
                  @org.junit.jupiter.api.Test void t() { mockMvc.toString(); }
                }
                @SpringBootTest
                class BRepositoryTest {
                  @org.springframework.beans.factory.annotation.Autowired UserRepository repo;
                  @org.junit.jupiter.api.Test void t() { repo.findAll(); }
                }
                interface UserRepository { java.util.List findAll(); }
                """;
        CompilationUnit cu = StaticJavaParser.parse(src);
        java.util.List<RefactorOutcome> outcomes = refactorer.refactorAll(cu);
        assertEquals(2, outcomes.size());
        // Expect one to be WebMvcTest and the other DataJpaTest
        boolean hasMvc = outcomes.stream().anyMatch(o -> "@WebMvcTest".equals(o.newAnnotation));
        boolean hasJpa = outcomes.stream().anyMatch(o -> "@DataJpaTest".equals(o.newAnnotation));
        assertTrue(hasMvc && hasJpa);
    }

    @Test
    void testIdempotency() {
        String src = """
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.test.web.servlet.MockMvc;
                @SpringBootTest
                class IdempotentControllerTest {
                  @org.springframework.beans.factory.annotation.Autowired MockMvc mockMvc;
                  @org.junit.jupiter.api.Test void t() { mockMvc.toString(); }
                }
                """;
        CompilationUnit cu = StaticJavaParser.parse(src);
        RefactorOutcome outcome1 = refactorer.refactor(cu);
        RefactorOutcome outcome2 = refactorer.refactor(cu);
        assertEquals("@WebMvcTest", outcome1.newAnnotation);
        assertEquals("@WebMvcTest", outcome2.newAnnotation);
        assertEquals(outcome1.newAnnotation, outcome2.newAnnotation);
    }

}
