package sa.com.cloudsolutions.antikythera.mcp;

import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * MCP Server that provides Liquibase XML validation capabilities over stdio
 * transport.
 * 
 * This server exposes a single tool: validate_liquibase that validates
 * Liquibase changelog files.
 */
public class LiquibaseValidationMcpServer {

    private static final Logger logger = LoggerFactory.getLogger(LiquibaseValidationMcpServer.class);
    private final LiquibaseValidator validator;

    public LiquibaseValidationMcpServer() {
        this.validator = new LiquibaseValidator();
    }

    private static final String FILEPATH_PARAM = "filepath";

    /**
     * Define the tools exposed by this server and start it.
     */
    public void start() {
        logger.info("Starting Liquibase Validation MCP Server");

        JacksonMcpJsonMapper mapper = new JacksonMcpJsonMapper(new ObjectMapper());
        StdioServerTransportProvider transportProvider = new StdioServerTransportProvider(mapper);

        McpAsyncServer server = McpServer.async(transportProvider)
                .serverInfo(new Implementation("liquibase-validator", "1.0.0", "1.0.0"))
                .tools(AsyncToolSpecification.builder()
                        .tool(Tool.builder()
                                .name("validate_liquibase")
                                .description(
                                        "Validates a Liquibase XML changelog file for syntax and structural correctness. Returns validation results including errors and warnings.")
                                .inputSchema(new JsonSchema(
                                        "object",
                                        Map.of(
                                                FILEPATH_PARAM, Map.of(
                                                        "type", "string",
                                                        "description",
                                                        "Absolute path to the Liquibase changelog XML file")),
                                        List.of(FILEPATH_PARAM),
                                        false,
                                        null,
                                        null))
                                .build())
                        .callHandler((exchange, request) -> {
                            Map<String, Object> arguments = request.arguments();
                            String filepath = (String) arguments.get(FILEPATH_PARAM);
                            return Mono.fromCallable(() -> handleValidateLiquibase(filepath));
                        })
                        .build())
                .build();

        logger.info("Liquibase Validation MCP Server started successfully");

        // Keep the server running until closed
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            logger.error("Server interrupted", e);
            Thread.currentThread().interrupt();
        } finally {
            server.close();
        }
    }

    /**
     * Handle the validate_liquibase tool call logic.
     */
    private CallToolResult handleValidateLiquibase(String filepath) {
        try {
            if (filepath == null || filepath.isEmpty()) {
                return CallToolResult.builder()
                        .content(List.of(new TextContent("Missing required parameter: filepath")))
                        .isError(true)
                        .build();
            }

            logger.info("Validating Liquibase file: {}", filepath);

            // Perform validation
            LiquibaseValidator.ValidationResult result = validator.validate(filepath);

            // Build response
            StringBuilder response = new StringBuilder();
            response.append("Liquibase Validation Result\n");
            response.append("==========================\n\n");
            response.append("File: ").append(filepath).append("\n");
            response.append("Status: ").append(result.valid() ? "✓ VALID" : "✗ INVALID").append("\n\n");

            if (!result.errors().isEmpty()) {
                response.append("Errors:\n");
                for (String error : result.errors()) {
                    response.append("  - ").append(error).append("\n");
                }
                response.append("\n");
            }

            if (!result.warnings().isEmpty()) {
                response.append("Warnings:\n");
                for (String warning : result.warnings()) {
                    response.append("  - ").append(warning).append("\n");
                }
            }

            return CallToolResult.builder()
                    .content(List.of(new TextContent(response.toString())))
                    .isError(!result.valid())
                    .build();

        } catch (Exception e) {
            logger.error("Error during validation", e);
            return CallToolResult.builder()
                    .content(List.of(new TextContent("Error during validation: " + e.getMessage())))
                    .isError(true)
                    .build();
        }
    }

    public static void main(String[] args) {
        LiquibaseValidationMcpServer server = new LiquibaseValidationMcpServer();
        server.start();
    }
}
