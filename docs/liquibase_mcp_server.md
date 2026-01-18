# Liquibase Validation MCP Server

A Model Context Protocol (MCP) server that provides Liquibase XML changelog validation capabilities over stdio transport.

## Overview

This MCP server exposes a single tool `validate_liquibase` that validates Liquibase changelog files for syntax and structural correctness using the Liquibase Core library.

## Features

- **XML Validation**: Validates Liquibase changelog XML files against the Liquibase schema
- **Structural Validation**: Checks for proper changelog structure and change sets
- **Error Reporting**: Provides detailed error messages for validation failures
- **Warning Detection**: Identifies potential issues like empty changelogs
- **Stdio Transport**: Uses standard input/output for communication, making it easy to integrate with MCP clients

## Building

```bash
cd /home/raditha/csi/Antikythera/antikythera-examples
mvn clean package
```

## Running the Server

### Using Maven

```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.mcp.LiquibaseValidationMcpServer"
```

### Using Java directly

```bash
java -cp target/antikythera-examples-1.0-SNAPSHOT.jar:~/.m2/repository/... \
  sa.com.cloudsolutions.antikythera.mcp.LiquibaseValidationMcpServer
```

## MCP Tool Specification

### Tool: `validate_liquibase`

Validates a Liquibase XML changelog file.

**Input Schema:**
```json
{
  "type": "object",
  "properties": {
    "filepath": {
      "type": "string",
      "description": "Absolute path to the Liquibase changelog XML file"
    }
  },
  "required": ["filepath"]
}
```

**Example Request:**
```json
{
  "method": "tools/call",
  "params": {
    "name": "validate_liquibase",
    "arguments": {
      "filepath": "/path/to/changelog.xml"
    }
  }
}
```

**Example Response (Valid):**
```
Liquibase Validation Result
==========================

File: /path/to/changelog.xml
Status: ✓ VALID

Warnings:
  - Changelog contains 2 change set(s)
```

**Example Response (Invalid):**
```
Liquibase Validation Result
==========================

File: /path/to/changelog.xml
Status: ✗ INVALID

Errors:
  - Validation error: Change set '1' has invalid syntax
  - Missing required attribute: tableName
```

## Testing

Run the unit tests:

```bash
mvn test -Dtest=LiquibaseValidatorTest
```

## Server Capabilities

- **Server Name**: liquibase-validator
- **Version**: 1.0.0
- **Capabilities**: Tools
- **Transport**: stdio

## Architecture

The server consists of two main components:

1. **LiquibaseValidator**: Core validation logic using Liquibase Core library
   - Parses changelog files using Liquibase's ChangeLogParser
   - Validates against an offline PostgreSQL database
   - Collects errors and warnings
   
2. **LiquibaseValidationMcpServer**: MCP server implementation
   - Exposes tools via MCP protocol
   - Handles tool calls and formats responses
   - Uses stdio transport for communication

## Dependencies

- Liquibase Core 4.29.1
- MCP Server SDK 0.9.0
- SLF4J 2.0.13

## Example Liquibase File

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.0.xsd">

    <changeSet id="1" author="test">
        <createTable tableName="users">
            <column name="id" type="bigint" autoIncrement="true">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="username" type="varchar(255)">
                <constraints nullable="false" unique="true"/>
            </column>
        </createTable>
    </changeSet>

</databaseChangeLog>
```

## Integration with MCP Clients

This server can be integrated with any MCP-compatible client. Example configuration for Claude Desktop:

```json
{
  "mcpServers": {
    "liquibase-validator": {
      "command": "java",
      "args": [
        "-cp",
        "/path/to/antikythera-examples-1.0-SNAPSHOT.jar:/path/to/dependencies/*",
        "sa.com.cloudsolutions.antikythera.mcp.LiquibaseValidationMcpServer"
      ]
    }
  }
}
```

## License

Part of the Antikythera project.

