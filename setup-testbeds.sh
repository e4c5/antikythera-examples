#!/bin/bash
#
# Setup script for Spring Boot migration test beds
# This script clones Spring PetClinic at different Spring Boot versions (2.1-2.5)
# for testing migration guides.
#

set -e  # Exit on error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
TESTBEDS_DIR="./testbeds"
CYCLES_REPO="https://github.com/e4c5/spring-boot-cycles.git"

# Function to print colored messages
print_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

# Setup spring-boot-cycles project
setup_cycles_project() {
    local dir_name="spring-boot-cycles"
    local full_path="${TESTBEDS_DIR}/${dir_name}"
    
    print_info "Setting up Spring Boot Cycles testbed..."
    
    # Check if directory already exists
    if [ -d "$full_path" ]; then
        print_info "Directory exists, resetting to main branch..."
        (cd "$full_path" && git fetch origin >/dev/null 2>&1 || true)
        (cd "$full_path" && git reset --hard origin/main >/dev/null 2>&1)
        (cd "$full_path" && git clean -fd >/dev/null 2>&1)
        print_success "Reset Spring Boot Cycles testbed"
    else
        # Clone the repository
        print_info "Cloning Spring Boot Cycles..."
        git clone -b main "$CYCLES_REPO" "$full_path" >/dev/null 2>&1
        print_success "Cloned Spring Boot Cycles testbed"
    fi
}

# Main function
main() {
    echo ""
    echo "=========================================="
    echo "  Spring Boot Migration Testbed Setup"
    echo "=========================================="
    echo ""
    
    # Create testbeds directory
    if [ ! -d "$TESTBEDS_DIR" ]; then
        mkdir -p "$TESTBEDS_DIR"
        print_info "Created $TESTBEDS_DIR directory"
    fi
    
    # Setup spring-boot-cycles
    setup_cycles_project
    
    # Summary
    echo "=========================================="
    print_success "Testbed set up successfully!"
    echo "=========================================="
    echo ""
    print_info "Testbeds location: $TESTBEDS_DIR"
    echo ""
    print_info "Available testbed:"
    echo "  - spring-boot-cycles/"
    echo ""
}

# Run main function
main
