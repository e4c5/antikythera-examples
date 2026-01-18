# Liquibase Validation MCP Server Usage Guide

This document outlines how to configure and use the Liquibase Validation MCP Server in various AI agents and IDEs. This server provides a `validate_liquibase` tool that checks Liquibase XML changelogs for structural correctness and SQL syntax errors inside `<sql>` and `<createView>` tags.

## Prerequisites
- **Java**: 21 or higher.
- **Maven**: To build and run the project.
- **Path**: Ensure you use absolute paths in your configurations.

## 1. Build the Server
Before using the server, ensure the project is compiled:
```bash
mvn clean compile -pl antikythera-examples
```

## 2. Configuration for Agents

### VS Code (e.g., Roo Code, Continue, Claude Dev)
To use this server in VS Code agents that support MCP, add the following entry to your `mcp_config.json` (usually located in your global app data or agent settings):

```json
{
  "mcpServers": {
    "liquibase-validator": {
      "command": "mvn",
      "args": [
        "-f",
        "/home/raditha/csi/Antikythera/antikythera-examples/pom.xml",
        "-q",
        "exec:java",
        "-Dexec.mainClass=sa.com.cloudsolutions.antikythera.mcp.LiquibaseValidationMcpServer"
      ],
      "env": {
        "JAVA_HOME": "/path/to/your/java21"
      }
    }
  }
}
```
*Note: Using the `-q` flag for Maven ensures that only the application's stdout (the JSON-RPC messages) is sent to the client, preventing noise from Maven logs.*

### Antigravity (Project Internal Agent)
If you are using this within the Antikythera/Antigravity ecosystem, you can register the server in your local agent configuration:

1. Create/Update `.agent/config.json` in the root of your project:
```json
{
  "mcp": {
    "servers": [
      {
        "id": "liquibase-validator",
        "name": "Liquibase Validator",
        "transport": "stdio",
        "command": "mvn",
        "args": [
          "-f",
          "/home/raditha/csi/Antikythera/antikythera-examples/pom.xml",
          "-q",
          "exec:java",
          "-Dexec.mainClass=sa.com.cloudsolutions.antikythera.mcp.LiquibaseValidationMcpServer"
        ]
      }
    ]
  }
}
```

### IntelliJ IDEA
IntelliJ currently supports MCP via specific plugins (like "MCP Client") or through the **AI Assistant** in some versions.
1. Open **Settings** > **Tools** > **MCP Servers** (if available) or the configuration of your specific AI plugin.
2. Add a new server with the following details:
   - **Type**: Stdio
   - **Command**: `mvn`
   - **Arguments**: `-f /home/raditha/csi/Antikythera/antikythera-examples/pom.xml -q exec:java -Dexec.mainClass=sa.com.cloudsolutions.antikythera.mcp.LiquibaseValidationMcpServer`

## 3. Tool Usage
Once connected, your agent will have access to the following tool:

### `validate_liquibase`
**Arguments:**
- `filepath` (string, required): The absolute path to the Liquibase XML changelog file.

**Output:**
- A detailed validation report.
- Success/Failure status.
- Specific SQL syntax errors (via JSQLParser integration).

## 4. Troubleshooting
- **Maven Logs**: If the server fails to start, remove the `-q` flag to see Maven's initialization errors.
- **Portability**: For faster startup, consider building a "Fat JAR" and running it directly with `java -jar` instead of invoking Maven every time.
