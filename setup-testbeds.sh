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
PETCLINIC_REPO="https://github.com/spring-projects/spring-petclinic.git"
TESTBEDS_DIR="./testbeds"

# Commit hashes for each Spring Boot version
# These were identified from Spring PetClinic git history
declare -A COMMITS=(
    ["2.1"]="7481e8841a452a101b14363ac36ff8df5c7c1956"  # Spring Boot 2.1.6
    ["2.2"]="ce7c3f93deb53798e3842e583fecea3514e90f1e"  # Spring Boot 2.2.0
    ["2.3"]="d19963e1744f56ff330be879d35672733d66f641"  # Spring Boot 2.3.3
    ["2.4"]="09e07869ac8cea1d08bd62802d5e0dad97827b87"  # Spring Boot 2.4.5
    ["2.5"]="11f1234b424fe2c55e5c02ad5683913090966ffa"  # Spring Boot 2.5.4
)

# Function to print colored messages
print_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Function to check if command exists
command_exists() {
    command -v "$1" >/dev/null 2>&1
}

# Check prerequisites
check_prerequisites() {
    print_info "Checking prerequisites..."
    
    if ! command_exists git; then
        print_error "git is not installed. Please install git first."
        exit 1
    fi
    
    if ! command_exists mvn; then
        print_warning "Maven is not installed. Build verification will be skipped."
    fi
    
    print_success "Prerequisites check passed"
}

# Clone and setup a specific version
setup_version() {
    local version=$1
    local commit=$2
    local dir_name="spring-boot-${version}"
    local full_path="${TESTBEDS_DIR}/${dir_name}"
    
    print_info "Setting up Spring Boot ${version} testbed..."
    
    # Remove existing directory if it exists
    if [ -d "$full_path" ]; then
        print_warning "Directory $full_path already exists. Removing..."
        rm -rf "$full_path"
    fi
    
    # Clone the repository
    print_info "Cloning Spring PetClinic..."
    git clone "$PETCLINIC_REPO" "$full_path" >/dev/null 2>&1
    
    # Checkout specific commit
    print_info "Checking out commit for Spring Boot ${version}..."
    (cd "$full_path" && git checkout "$commit" >/dev/null 2>&1)
    
    # Verify the Spring Boot version in pom.xml
    if [ -f "$full_path/pom.xml" ]; then
        local boot_version=$(grep -m1 "<parent>" -A 5 "$full_path/pom.xml" | grep "<version>" | sed 's/.*<version>\(.*\)<\/version>.*/\1/')
        print_success "Spring Boot ${version} testbed created (actual version: $boot_version)"
    else
        print_error "pom.xml not found in $full_path"
        return 1
    fi
    
    # Optionally verify build
    if command_exists mvn && [ "$VERIFY_BUILD" = "true" ]; then
        print_info "Verifying build for Spring Boot ${version}..."
        if (cd "$full_path" && mvn clean verify -DskipTests >/dev/null 2>&1); then
            print_success "Build verification passed for Spring Boot ${version}"
        else
            print_warning "Build verification failed for Spring Boot ${version} (this may be due to environment issues)"
        fi
    fi
}

# Main function
main() {
    echo ""
    echo "=========================================="
    echo "  Spring Boot Migration Testbed Setup"
    echo "=========================================="
    echo ""
    
    # Check prerequisites
    check_prerequisites
    
    # Create testbeds directory
    if [ ! -d "$TESTBEDS_DIR" ]; then
        mkdir -p "$TESTBEDS_DIR"
        print_info "Created $TESTBEDS_DIR directory"
    fi
    
    # Ask user if they want to verify builds
    echo ""
    read -p "Do you want to verify builds? This will take longer. (y/N): " verify_choice
    if [[ "$verify_choice" =~ ^[Yy]$ ]]; then
        VERIFY_BUILD="true"
    else
        VERIFY_BUILD="false"
    fi
    
    echo ""
    print_info "Setting up testbeds for Spring Boot versions: 2.1, 2.2, 2.3, 2.4, 2.5"
    echo ""
    
    # Setup each version
    for version in 2.1 2.2 2.3 2.4 2.5; do
        setup_version "$version" "${COMMITS[$version]}"
        echo ""
    done
    
    # Summary
    echo "=========================================="
    print_success "All testbeds have been set up successfully!"
    echo "=========================================="
    echo ""
    print_info "Testbeds location: $TESTBEDS_DIR"
    echo ""
    print_info "Available testbeds:"
    for version in 2.1 2.2 2.3 2.4 2.5; do
        echo "  - spring-boot-${version}/"
    done
    echo ""
    print_info "Next steps:"
    echo "  1. Navigate to a testbed: cd $TESTBEDS_DIR/spring-boot-2.1"
    echo "  2. Build the project: mvn clean install"
    echo "  3. Test your migration scripts on these testbeds"
    echo ""
}

# Run main function
main
