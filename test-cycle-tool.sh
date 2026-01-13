#!/bin/bash
# Test script for all CircularDependencyTool strategies
# Enhanced with timeout handling and verbose output

STRATEGIES=("auto" "lazy" "setter" "interface" "extract")
TIMEOUT=300  # 5 minutes per strategy

echo "Testing all CircularDependencyTool strategies"
echo "=============================================="
echo "Timeout per strategy: ${TIMEOUT}s"
echo ""

# Ensure we're in the right directory
cd "$(dirname "$0")"

# Build the project
echo "Building antikythera-examples..."
mvn compile -q

echo "✅ Build complete"
echo ""

# Define paths
CYCLES_DIR="./testbeds/spring-boot-cycles"
CONFIG_FILE="$(pwd)/cycle-config.yml"

for strategy in "${STRATEGIES[@]}"; do
    echo "Testing: $strategy"
    echo "----------------------------"
    
    # Clean up spring-boot-cycles - rollback all changes and remove untracked files
    cd "$CYCLES_DIR"
    echo "Resetting spring-boot-cycles to clean state..."
    git reset --hard HEAD >/dev/null 2>&1 || true
    git clean -fd >/dev/null 2>&1 || true

    # Return to root
    cd - >/dev/null
    
    # Run the tool with timeout
    echo "Running with --strategy $strategy (timeout: ${TIMEOUT}s)..."
    
    # Run with timeout and capture exit code
    TOOL_OUTPUT=$(timeout ${TIMEOUT} mvn exec:java \
        -Dexec.mainClass="com.raditha.spring.cycle.CircularDependencyTool" \
        -Dexec.args="--config $CONFIG_FILE --strategy $strategy" \
        2>&1) || TOOL_EXIT=$?
    
    # Check if timeout occurred
    if [ "${TOOL_EXIT:-0}" -eq 124 ]; then
        echo "❌ $strategy: TIMEOUT after ${TIMEOUT}s"
        echo "Last output:"
        echo "$TOOL_OUTPUT" | tail -50
        # continue
    elif [ "${TOOL_EXIT:-0}" -ne 0 ]; then
        echo "❌ $strategy: Tool failed with exit code ${TOOL_EXIT}"
        echo "Output:"
        echo "$TOOL_OUTPUT" | tail -100
        # continue
    fi
    
    # Show last few lines of tool output
    echo "Tool output (last 10 lines):"
    echo "$TOOL_OUTPUT" | tail -10
    echo ""
    
    # Validate compilation
    echo "Validating compilation..."
    cd "$CYCLES_DIR"
    
    # Run mvn compile with timeout
    # Suppress output unless error
    COMPILE_OUTPUT=$(timeout 60 mvn compile -DskipTests 2>&1) || COMPILE_EXIT=$?
    
    if [ "${COMPILE_EXIT:-0}" -eq 124 ]; then
        echo "❌ $strategy: Compilation TIMEOUT"
        cd - >/dev/null
        # continue
    elif echo "$COMPILE_OUTPUT" | grep -q "BUILD SUCCESS"; then
        echo "✅ $strategy: Compilation successful"
    else
        echo "❌ $strategy: Compilation FAILED"
        echo ""
        echo "Build output:"
        echo "$COMPILE_OUTPUT" | grep -A 10 "\[ERROR\]" || echo "$COMPILE_OUTPUT" | tail -30
        cd - >/dev/null
        # continue
    fi
    
    # Return to root
    cd - >/dev/null

    echo ""
done

echo "=============================================="
echo "✅ All strategies tested (check results above)!"
