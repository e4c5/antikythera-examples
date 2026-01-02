# Spring Boot Migration Test Beds

## Overview
This directory contains Spring PetClinic clones at different Spring Boot versions (2.1-2.5) to serve as test beds for validating migration guides and testing migration automation tools.

## Quick Start

### Automated Setup (Recommended)
Run the setup script from the `antikythera-examples` directory:
```bash
./setup-testbeds.sh
```

This script will:
1. Clone the Spring PetClinic repository 5 times
2. Check out specific commits for each Spring Boot version
3. Verify the correct version is set up
4. Optionally verify that each version builds successfully

### Manual Setup
If you prefer to set up manually or need a specific configuration:

```bash
# Create testbeds directory
mkdir -p testbeds
cd testbeds

# Spring Boot 2.1.6
git clone https://github.com/spring-projects/spring-petclinic.git spring-boot-2.1
cd spring-boot-2.1
git checkout 7481e8841a452a101b14363ac36ff8df5c7c1956
cd ..

# Spring Boot 2.2.0
git clone https://github.com/spring-projects/spring-petclinic.git spring-boot-2.2
cd spring-boot-2.2
git checkout ce7c3f93deb53798e3842e583fecea3514e90f1e
cd ..

# Spring Boot 2.3.3
git clone https://github.com/spring-projects/spring-petclinic.git spring-boot-2.3
cd spring-boot-2.3
git checkout d19963e1744f56ff330be879d35672733d66f641
cd ..

# Spring Boot 2.4.5
git clone https://github.com/spring-projects/spring-petclinic.git spring-boot-2.4
cd spring-boot-2.4
git checkout 09e07869ac8cea1d08bd62802d5e0dad97827b87
cd ..

# Spring Boot 2.5.4
git clone https://github.com/spring-projects/spring-petclinic.git spring-boot-2.5
cd spring-boot-2.5
git checkout 11f1234b424fe2c55e5c02ad5683913090966ffa
cd ..
```

## Testbed Structure

```
testbeds/
├── spring-boot-2.1/    # Spring PetClinic with Spring Boot 2.1.6
├── spring-boot-2.2/    # Spring PetClinic with Spring Boot 2.2.0
├── spring-boot-2.3/    # Spring PetClinic with Spring Boot 2.3.3
├── spring-boot-2.4/    # Spring PetClinic with Spring Boot 2.4.5
└── spring-boot-2.5/    # Spring PetClinic with Spring Boot 2.5.4
```

## Version Details

| Directory | Spring Boot Version | Commit Hash | Java Version |
|-----------|---------------------|-------------|--------------|
| spring-boot-2.1 | 2.1.6.RELEASE | 7481e8841a4 | Java 8+ |
| spring-boot-2.2 | 2.2.0.RELEASE | ce7c3f93deb | Java 8+ |
| spring-boot-2.3 | 2.3.3 | d19963e1744 | Java 8+ |
| spring-boot-2.4 | 2.4.5 | 09e07869ac8 | Java 8+ |
| spring-boot-2.5 | 2.5.4 | 11f1234b424 | Java 8+ (Java 17 ready) |

## Usage

### Building a Testbed
```bash
cd testbeds/spring-boot-2.1
mvn clean install
```

### Running a Testbed
```bash
cd testbeds/spring-boot-2.1
mvn spring-boot:run
```

Then open http://localhost:8080 in your browser.

### Testing Migration
Use these testbeds to test migration scripts:

```bash
# Example: Test migration from 2.1 to 2.2
cd testbeds/spring-boot-2.1

# Run your migration tool
# java -jar ../../antikythera/target/antikythera.jar migrate --from=2.1 --to=2.2

# Verify the changes
mvn clean install
```

## Common Issues

### Build Failures
If a testbed fails to build:
1. Ensure you have the correct Java version (Java 8+ for all versions)
2. Check your Maven version (`mvn --version`)
3. Check if external dependencies are available

### Port Conflicts
If you're running multiple testbeds, they'll all try to use port 8080. Either:
- Run them one at a time
- Configure different ports in `application.properties`:
  ```properties
  server.port=8081
  ```

### Database Issues
All PetClinic versions use an in-memory H2 database by default, so no external database setup is needed.

## Maintenance

### Updating Commit Hashes
If you need to use different versions, update the commit hashes in:
1. `setup-testbeds.sh` - Update the `COMMITS` associative array
2. This README - Update the version details table

### Cleaning Up
To remove all testbeds:
```bash
rm -rf testbeds/
```

## Notes
- **These directories are not committed to git** - They're listed in `.gitignore`
- **Total size**: ~50MB per testbed (5 testbeds = ~250MB total)
- **Setup time**: ~2-5 minutes depending on network speed
- **Build time per testbed**: ~1-2 minutes

## About Spring PetClinic
Spring PetClinic is a sample Spring Boot application that demonstrates:
- Spring MVC with Thymeleaf templates
- Spring Data JPA
- Spring Boot DevTools
- Spring Boot Actuator
- Comprehensive testing with JUnit

It's maintained by the Spring team and serves as a reference implementation for Spring Boot applications.

**Repository**: https://github.com/spring-projects/spring-petclinic
