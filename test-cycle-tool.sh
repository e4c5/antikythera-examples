#!/bin/bash
# Test script for all CircularDependencyTool strategies
# Enhanced with timeout handling and verbose output

set -e

STRATEGIES=("auto" "lazy" "setter" "interface" "extract")
TIMEOUT=300  # 5 minutes per strategy

echo "Testing all CircularDependencyTool strategies"
echo "=============================================="
echo "Timeout per strategy: ${TIMEOUT}s"
echo ""

# Ensure we're in the right directory
cd "$(dirname "$0")"
ROOT_DIR=$(pwd)

# Build the project
echo "Building antikythera-examples..."
mvn compile -q

echo "✅ Build complete"
echo ""

for strategy in "${STRATEGIES[@]}"; do
    echo "Testing: $strategy"
    echo "----------------------------"
    
    cd "$ROOT_DIR"
    # Clean up spring-boot-cycles - rollback all changes and remove untracked files
    cd testbeds/spring-boot-cycles
    echo "Resetting spring-boot-cycles to clean state..."
    git reset --hard HEAD 2>/dev/null || true
    git clean -fd 2>/dev/null || true

    # Create config file
    echo "base_path: $(pwd)/src/main/java" > cycle-detector-test.yml
    echo "output_path: $(pwd)/src/main/java" >> cycle-detector-test.yml
    
    # Run the tool with timeout
    cd ../..
    echo "Running with --strategy $strategy (timeout: ${TIMEOUT}s)..."
    
    # Run with timeout and capture exit code
    TOOL_OUTPUT=$(timeout ${TIMEOUT} mvn exec:java \
        -Dexec.mainClass="com.raditha.spring.cycle.CircularDependencyTool" \
        -Dexec.args="--config testbeds/spring-boot-cycles/cycle-detector-test.yml --strategy $strategy" \
        2>&1) || TOOL_EXIT=$?
    
    # Check if timeout occurred
    if [ "${TOOL_EXIT:-0}" -eq 124 ]; then
        echo "❌ $strategy: TIMEOUT after ${TIMEOUT}s"
        echo "Last output:"
        echo "$TOOL_OUTPUT" | tail -50
        exit 1
    elif [ "${TOOL_EXIT:-0}" -ne 0 ]; then
        echo "❌ $strategy: Tool failed with exit code ${TOOL_EXIT}"
        echo "Output:"
        echo "$TOOL_OUTPUT" | tail -100
        exit 1
    fi
    
    # Show last few lines of tool output
    echo "Tool output (last 10 lines):"
    echo "$TOOL_OUTPUT" | tail -10
    echo ""
    
    # Validate compilation
    echo "Validating compilation..."
    cd testbeds/spring-boot-cycles
    
    # Run mvn compile with timeout
    COMPILE_OUTPUT=$(timeout 60 mvn compile 2>&1) || COMPILE_EXIT=$?
    
    if [ "${COMPILE_EXIT:-0}" -eq 124 ]; then
        echo "❌ $strategy: Compilation TIMEOUT"
        exit 1
    elif echo "$COMPILE_OUTPUT" | grep -q "BUILD SUCCESS"; then
        echo "✅ $strategy: Compilation successful"
    else
        echo "❌ $strategy: Compilation FAILED"
        echo ""
        echo "Build output:"
        echo "$COMPILE_OUTPUT" | grep -A 10 "\[ERROR\]" || echo "$COMPILE_OUTPUT" | tail -30
        exit 1
    fi
    
    echo ""
done

echo "=============================================="
echo "✅ All strategies tested successfully!"
