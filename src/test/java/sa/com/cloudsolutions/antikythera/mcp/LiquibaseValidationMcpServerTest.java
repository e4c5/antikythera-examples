package sa.com.cloudsolutions.antikythera.mcp;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LiquibaseValidationMcpServerTest {

    @Test
    void testValidateToolRejectsMissingFilepath() throws Exception {
        CallToolResult result = invokeValidate(null);

        assertTrue(result.isError());
        assertEquals("Missing required parameter: filepath", text(result));
    }

    @Test
    void testValidateToolReturnsSuccessfulMcpResultForValidChangelog() throws Exception {
        String changelog = resourcePath("liquibase/sample-changelog.xml");

        CallToolResult result = invokeValidate(changelog);

        assertFalse(result.isError());
        String response = text(result);
        assertTrue(response.contains("Liquibase Validation Result"));
        assertTrue(response.contains("File: " + changelog));
        assertTrue(response.contains("Status: ✓ VALID"));
    }

    @Test
    void testValidateToolReturnsErrorMcpResultForMissingFile() throws Exception {
        String changelog = "/non/existent/file.xml";

        CallToolResult result = invokeValidate(changelog);

        assertTrue(result.isError());
        String response = text(result);
        assertTrue(response.contains("Status: ✗ INVALID"));
        assertTrue(response.contains("not found"));
    }

    @Test
    void testServerCanStartAndStopWhenInterrupted() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread serverThread = new Thread(() -> {
            try {
                new LiquibaseValidationMcpServer().start();
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "liquibase-validation-mcp-server-test");
        serverThread.setDaemon(true);

        serverThread.start();
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (serverThread.isAlive() && failure.get() == null) {
                serverThread.interrupt();
                serverThread.join(100);
            }
        });

        assertNull(failure.get());
        assertFalse(serverThread.isAlive());
    }

    private static CallToolResult invokeValidate(String filepath) throws Exception {
        Method method = LiquibaseValidationMcpServer.class.getDeclaredMethod("handleValidateLiquibase", String.class);
        method.setAccessible(true);
        return (CallToolResult) method.invoke(new LiquibaseValidationMcpServer(), filepath);
    }

    private static String resourcePath(String name) throws Exception {
        URL url = LiquibaseValidationMcpServerTest.class.getClassLoader().getResource(name);
        assertNotNull(url, "Missing test resource: " + name);
        return Path.of(url.toURI()).toString();
    }

    private static String text(CallToolResult result) {
        assertEquals(1, result.content().size());
        assertInstanceOf(TextContent.class, result.content().getFirst());
        return ((TextContent) result.content().getFirst()).text();
    }
}
