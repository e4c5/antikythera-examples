#!/bin/bash

# Liquibase Validation MCP Server Launcher
# This script builds and runs the Liquibase validation MCP server

set -e

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m' # No Color

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_DIR="$SCRIPT_DIR"

echo -e "${BLUE}Liquibase Validation MCP Server${NC}"
echo "=================================="
echo ""

# Check if we need to build
if [ ! -f "$PROJECT_DIR/target/antikythera-examples-1.0-SNAPSHOT.jar" ] || [ "$1" == "--build" ]; then
    echo -e "${BLUE}Building project...${NC}"
    cd "$PROJECT_DIR"
    mvn clean package -DskipTests
    echo -e "${GREEN}✓ Build complete${NC}"
    echo ""
fi

echo -e "${BLUE}Starting Liquibase Validation MCP Server...${NC}"
echo "Press Ctrl+C to stop the server"
echo ""

# Run the server
cd "$PROJECT_DIR"
mvn exec:java \
    -Dexec.mainClass="sa.com.cloudsolutions.antikythera.mcp.LiquibaseValidationMcpServer" \
    -Dexec.cleanupDaemonThreads=false \
    -q

