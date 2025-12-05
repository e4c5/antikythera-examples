package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.examples.TestRefactorer.RefactorOutcome;

import static org.junit.jupiter.api.Assertions.*;

class TestRefactorerTest {

    private TestRefactorer refactorer;

    @BeforeEach
    void setUp() throws Exception {
        sa.com.cloudsolutions.antikythera.configuration.Settings.loadConfigMap();
        sa.com.cloudsolutions.antikythera.configuration.Settings.setProperty("base_path",
                System.getProperty("user.dir"));
        refactorer = new TestRefactorer(true); // dry-run
    }

    @Test
    void testHeavyweightUnitTest() {
        String src = "" +
                "import org.springframework.boot.test.context.SpringBootTest;\n" +
                "import org.mockito.Mock;\n" +
                "@SpringBootTest\n" +
                "class HeavyweightUnitTest {\n" +
                "  @Mock Object dep;\n" +
                "  @org.junit.jupiter.api.Test void t() { /* no real resources used */ }\n" +
                "}\n";
        CompilationUnit cu = StaticJavaParser.parse(src);

        RefactorOutcome outcome = refactorer.refactor(cu);

        assertEquals("CONVERTED", outcome.action);
        assertEquals("Unit Test", outcome.newAnnotation);
        assertEquals("No resources detected (all mocked)", outcome.reason);
    }

    @Test
    void testInefficientControllerTest() {
        String src = "" +
                "import org.springframework.boot.test.context.SpringBootTest;\n" +
                "import org.springframework.test.web.servlet.MockMvc;\n" +
                "@SpringBootTest\n" +
                "class InefficientControllerTest {\n" +
                "  @org.springframework.beans.factory.annotation.Autowired MockMvc mockMvc;\n" +
                "  @org.junit.jupiter.api.Test void t() throws Exception {\n" +
                "    mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(\"/x\"));\n" +
                "  }\n" +
                "}\n";
        CompilationUnit cu = StaticJavaParser.parse(src);

        RefactorOutcome outcome = refactorer.refactor(cu);

        assertEquals("CONVERTED", outcome.action);
        assertEquals("@WebMvcTest", outcome.newAnnotation);
        assertEquals("Only WEB resource detected (JSON allowed)", outcome.reason);
    }

    @Test
    void testInefficientRepositoryTest() {
        String src = "" +
                "import org.springframework.boot.test.context.SpringBootTest;\n" +
                "@SpringBootTest\n" +
                "class InefficientRepositoryTest {\n" +
                "  @org.springframework.beans.factory.annotation.Autowired UserRepository repo;\n" +
                "  @org.junit.jupiter.api.Test void t() { repo.findAll(); }\n" +
                "}\n" +
                "interface UserRepository { java.util.List findAll(); }\n";
        CompilationUnit cu = StaticJavaParser.parse(src);

        RefactorOutcome outcome = refactorer.refactor(cu);

        assertEquals("CONVERTED", outcome.action);
        assertEquals("@DataJpaTest", outcome.newAnnotation);
        assertEquals("Only DATABASE_JPA resource detected", outcome.reason);
    }

    @Test
    void testLegacyContextTest() {
        String src = "" +
                "import org.springframework.boot.test.context.SpringBootTest;\n" +
                "import org.springframework.test.context.ContextConfiguration;\n" +
                "@SpringBootTest\n" +
                "@ContextConfiguration(classes = {LegacyConfig.class})\n" +
                "class LegacyContextTest {\n" +
                "  @org.junit.jupiter.api.Test void t() {}\n" +
                "}\n" +
                "class LegacyConfig {}\n";
        CompilationUnit cu = StaticJavaParser.parse(src);

        RefactorOutcome outcome = refactorer.refactor(cu);

        if (outcome != null) {
            assertEquals("CONVERTED", outcome.action);
            assertEquals("Unit Test", outcome.newAnnotation);
        }
    }

    @Test
    void testUnsafeIntegrationTest() {
        String src = "" +
                "import org.springframework.boot.test.context.SpringBootTest;\n" +
                "import org.springframework.boot.test.web.server.LocalServerPort;\n" +
                "@SpringBootTest\n" +
                "class UnsafeIntegrationTest {\n" +
                "  @LocalServerPort int port;\n" +
                "  @org.junit.jupiter.api.Test void t() {}\n" +
                "}\n";
        CompilationUnit cu = StaticJavaParser.parse(src);

        RefactorOutcome outcome = refactorer.refactor(cu);

        assertEquals("KEPT", outcome.action);
        assertEquals("@SpringBootTest(webEnvironment = RANDOM_PORT)", outcome.newAnnotation);
        assertEquals("Requires running server", outcome.reason);
    }


    @Test
    void testJdbcSlice() {
        String src = "" +
                "import org.springframework.boot.test.context.SpringBootTest;\n" +
                "@SpringBootTest\n" +
                "class JdbcOnlyTest {\n" +
                "  @org.springframework.beans.factory.annotation.Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;\n" +
                "  @org.junit.jupiter.api.Test void t() { jdbc.execute(\"select 1\"); }\n" +
                "}\n";
        CompilationUnit cu = StaticJavaParser.parse(src);
        RefactorOutcome outcome = refactorer.refactor(cu);
        assertEquals("CONVERTED", outcome.action);
        assertEquals("@JdbcTest", outcome.newAnnotation);
        assertEquals("Only JDBC resource detected", outcome.reason);
    }

    @Test
    void testWebFluxSlice() {
        String src = "" +
                "import org.springframework.boot.test.context.SpringBootTest;\n" +
                "import org.springframework.test.web.reactive.server.WebTestClient;\n" +
                "@SpringBootTest\n" +
                "class ReactiveControllerTest {\n" +
                "  @org.springframework.beans.factory.annotation.Autowired WebTestClient client;\n" +
                "  @org.junit.jupiter.api.Test void t() { client.get().uri(\"/x\").exchange(); }\n" +
                "}\n";
        CompilationUnit cu = StaticJavaParser.parse(src);
        RefactorOutcome outcome = refactorer.refactor(cu);
        assertEquals("CONVERTED", outcome.action);
        assertEquals("@WebFluxTest", outcome.newAnnotation);
        assertEquals("Only WEBFLUX resource detected", outcome.reason);
    }

    @Test
    void testGraphQlSlice() {
        String src = "" +
                "import org.springframework.boot.test.context.SpringBootTest;\n" +
                "import org.springframework.graphql.test.tester.GraphQlTester;\n" +
                "@SpringBootTest\n" +
                "class GraphQlOnlyTest {\n" +
                "  @org.springframework.beans.factory.annotation.Autowired GraphQlTester tester;\n" +
                "  @org.junit.jupiter.api.Test void t() { tester.toString(); }\n" +
                "}\n";
        CompilationUnit cu = StaticJavaParser.parse(src);
        RefactorOutcome outcome = refactorer.refactor(cu);
        assertEquals("CONVERTED", outcome.action);
        assertEquals("@GraphQlTest", outcome.newAnnotation);
        assertEquals("Only GRAPHQL resource detected", outcome.reason);
    }

    @Test
    void testRestClientSlice() {
        String src = "" +
                "import org.springframework.boot.test.context.SpringBootTest;\n" +
                "import org.springframework.test.web.client.MockRestServiceServer;\n" +
                "@SpringBootTest\n" +
                "class RestClientOnlyTest {\n" +
                "  @org.springframework.beans.factory.annotation.Autowired MockRestServiceServer server;\n" +
                "  @org.junit.jupiter.api.Test void t() { server.toString(); }\n" +
                "}\n";
        CompilationUnit cu = StaticJavaParser.parse(src);
        RefactorOutcome outcome = refactorer.refactor(cu);
        assertEquals("CONVERTED", outcome.action);
        assertEquals("@RestClientTest", outcome.newAnnotation);
        assertEquals("Only REST_CLIENT resource detected", outcome.reason);
    }

    @Test
    void testJsonSlice() {
        String src = "" +
                "import org.springframework.boot.test.context.SpringBootTest;\n" +
                "import com.fasterxml.jackson.databind.ObjectMapper;\n" +
                "@SpringBootTest\n" +
                "class JsonOnlyTest {\n" +
                "  @org.springframework.beans.factory.annotation.Autowired ObjectMapper mapper;\n" +
                "  @org.junit.jupiter.api.Test void t() { mapper.createObjectNode(); }\n" +
                "}\n";
        CompilationUnit cu = StaticJavaParser.parse(src);
        RefactorOutcome outcome = refactorer.refactor(cu);
        assertEquals("CONVERTED", outcome.action);
        assertEquals("@JsonTest", outcome.newAnnotation);
        assertEquals("Only JSON resource detected", outcome.reason);
    }

    @Test
    void testMixedResourcesFallBackToSpringBootTest() {
        String src = "" +
                "import org.springframework.boot.test.context.SpringBootTest;\n" +
                "import org.springframework.test.web.servlet.MockMvc;\n" +
                "@SpringBootTest\n" +
                "class MixedResourcesTest {\n" +
                "  @org.springframework.beans.factory.annotation.Autowired MockMvc mockMvc;\n" +
                "  @org.springframework.beans.factory.annotation.Autowired UserRepository repo;\n" +
                "  @org.junit.jupiter.api.Test void t() { mockMvc.toString(); repo.findAll(); }\n" +
                "}\n" +
                "interface UserRepository { java.util.List findAll(); }\n";
        CompilationUnit cu = StaticJavaParser.parse(src);
        RefactorOutcome outcome = refactorer.refactor(cu);
        assertTrue(outcome.action.equals("REVERTED") || outcome.action.equals("KEPT"));
        assertTrue(outcome.newAnnotation.startsWith("@SpringBootTest"));
    }

    @Test
    void testMultiClassCompilationUnit() {
        String src = "" +
                "import org.springframework.boot.test.context.SpringBootTest;\n" +
                "import org.springframework.test.web.servlet.MockMvc;\n" +
                "@SpringBootTest\n" +
                "class AControllerTest {\n" +
                "  @org.springframework.beans.factory.annotation.Autowired MockMvc mockMvc;\n" +
                "  @org.junit.jupiter.api.Test void t() { mockMvc.toString(); }\n" +
                "}\n" +
                "@SpringBootTest\n" +
                "class BRepositoryTest {\n" +
                "  @org.springframework.beans.factory.annotation.Autowired UserRepository repo;\n" +
                "  @org.junit.jupiter.api.Test void t() { repo.findAll(); }\n" +
                "}\n" +
                "interface UserRepository { java.util.List findAll(); }\n";
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
        String src = "" +
                "import org.springframework.boot.test.context.SpringBootTest;\n" +
                "import org.springframework.test.web.servlet.MockMvc;\n" +
                "@SpringBootTest\n" +
                "class IdempotentControllerTest {\n" +
                "  @org.springframework.beans.factory.annotation.Autowired MockMvc mockMvc;\n" +
                "  @org.junit.jupiter.api.Test void t() { mockMvc.toString(); }\n" +
                "}\n";
        CompilationUnit cu = StaticJavaParser.parse(src);
        RefactorOutcome outcome1 = refactorer.refactor(cu);
        RefactorOutcome outcome2 = refactorer.refactor(cu);
        assertEquals("@WebMvcTest", outcome1.newAnnotation);
        assertEquals("@WebMvcTest", outcome2.newAnnotation);
        assertEquals(outcome1.newAnnotation, outcome2.newAnnotation);
    }

}