package sa.com.cloudsolutions.antikythera.mcp;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.stdio.StdioServerTransport;
import io.modelcontextprotocol.spec.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * MCP Server that provides Liquibase XML validation capabilities over stdio transport.
 * 
 * This server exposes a single tool: validate_liquibase that validates Liquibase changelog files.
 */
public class LiquibaseValidationMcpServer {
    
    private static final Logger logger = LoggerFactory.getLogger(LiquibaseValidationMcpServer.class);
    private final LiquibaseValidator validator;
    
    public LiquibaseValidationMcpServer() {
        this.validator = new LiquibaseValidator();
    }
    
    /**
     * Starts the MCP server on stdio transport.
     */
    public void start() {
        logger.info("Starting Liquibase Validation MCP Server");
        
        // Create stdio transport
        StdioServerTransport transport = new StdioServerTransport();
        
        // Create the MCP server
        McpServer server = McpServer.builder()
            .serverInfo(ServerInfo.builder()
                .name("liquibase-validator")
                .version("1.0.0")
                .build())
            .capabilities(ServerCapabilities.builder()
                .tools(true)
                .build())
            .toolsProvider(() -> CompletableFuture.completedFuture(getTools()))
            .callToolHandler(this::handleToolCall)
            .build();
        
        // Connect transport to server
        transport.connect(server);
        
        logger.info("Liquibase Validation MCP Server started successfully");
        
        // Keep the server running
        try {
            transport.waitForClose();
        } catch (InterruptedException e) {
            logger.error("Server interrupted", e);
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Define the tools exposed by this server.
     */
    private List<Tool> getTools() {
        return List.of(
            Tool.builder()
                .name("validate_liquibase")
                .description("Validates a Liquibase XML changelog file for syntax and structural correctness. " +
                           "Returns validation results including errors and warnings.")
                .inputSchema(Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "filepath", Map.of(
                            "type", "string",
                            "description", "Absolute path to the Liquibase changelog XML file"
                        )
                    ),
                    "required", List.of("filepath")
                ))
                .build()
        );
    }
    
    /**
     * Handle tool calls from clients.
     */
    private CompletableFuture<CallToolResult> handleToolCall(CallToolRequest request) {
        String toolName = request.params().name();
        
        logger.info("Received tool call: {}", toolName);
        
        if ("validate_liquibase".equals(toolName)) {
            return handleValidateLiquibase(request);
        }
        
        return CompletableFuture.completedFuture(
            CallToolResult.builder()
                .isError(true)
                .content(List.of(
                    TextContent.builder()
                        .text("Unknown tool: " + toolName)
                        .build()
                ))
                .build()
        );
    }
    
    /**
     * Handle the validate_liquibase tool call.
     */
    private CompletableFuture<CallToolResult> handleValidateLiquibase(CallToolRequest request) {
        try {
            // Extract filepath from arguments
            Map<String, Object> arguments = request.params().arguments();
            String filepath = (String) arguments.get("filepath");
            
            if (filepath == null || filepath.isEmpty()) {
                return CompletableFuture.completedFuture(
                    CallToolResult.builder()
                        .isError(true)
                        .content(List.of(
                            TextContent.builder()
                                .text("Missing required parameter: filepath")
                                .build()
                        ))
                        .build()
                );
            }
            
            logger.info("Validating Liquibase file: {}", filepath);
            
            // Perform validation
            LiquibaseValidator.ValidationResult result = validator.validate(filepath);
            
            // Build response
            StringBuilder response = new StringBuilder();
            response.append("Liquibase Validation Result\n");
            response.append("==========================\n\n");
            response.append("File: ").append(filepath).append("\n");
            response.append("Status: ").append(result.isValid() ? "✓ VALID" : "✗ INVALID").append("\n\n");
            
            if (!result.getErrors().isEmpty()) {
                response.append("Errors:\n");
                for (String error : result.getErrors()) {
                    response.append("  - ").append(error).append("\n");
                }
                response.append("\n");
            }
            
            if (!result.getWarnings().isEmpty()) {
                response.append("Warnings:\n");
                for (String warning : result.getWarnings()) {
                    response.append("  - ").append(warning).append("\n");
                }
            }
            
            return CompletableFuture.completedFuture(
                CallToolResult.builder()
                    .isError(!result.isValid())
                    .content(List.of(
                        TextContent.builder()
                            .text(response.toString())
                            .build()
                    ))
                    .build()
            );
            
        } catch (Exception e) {
            logger.error("Error during validation", e);
            return CompletableFuture.completedFuture(
                CallToolResult.builder()
                    .isError(true)
                    .content(List.of(
                        TextContent.builder()
                            .text("Error during validation: " + e.getMessage())
                            .build()
                    ))
                    .build()
            );
        }
    }
    
    public static void main(String[] args) {
        LiquibaseValidationMcpServer server = new LiquibaseValidationMcpServer();
        server.start();
    }
}

