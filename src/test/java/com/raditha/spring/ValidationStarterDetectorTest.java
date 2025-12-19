package com.raditha.spring;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ValidationStarterDetector.
 * Tests validation annotation detection and automatic dependency addition.
 */
class ValidationStarterDetectorTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        Settings.loadConfigMap();
        Settings.setProperty("base_path", tempDir.toString());
        AntikytheraRunTime.reset();
    }

    @Test
    void testDetectValidAnnotation() throws Exception {
        // Given: A class using @Valid annotation
        String javaCode = """
                package com.example;
                import javax.validation.Valid;

                public class UserController {
                    public void createUser(@Valid UserDto user) {
                        // method body
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(javaCode);
        AntikytheraRunTime.addCompilationUnit("UserController", cu);

        // When: Running validation detector
        ValidationStarterDetector detector = new ValidationStarterDetector(true);
        MigrationPhaseResult result = detector.migrate();

        // Then: Validation usage should be detected
        assertFalse(result.getChanges().isEmpty(), "Should detect validation usage");
        assertTrue(result.getChanges().stream()
                .anyMatch(change -> change.contains("Detected validation usage")),
                "Should report validation detection");
    }

    @Test
    void testDetectValidatedAnnotation() throws Exception {
        // Given: A class using @Validated annotation
        String javaCode = """
                package com.example;
                import org.springframework.validation.annotation.Validated;

                @Validated
                public class UserService {
                    public void processUser(String name) {
                        // method body
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(javaCode);
        AntikytheraRunTime.addCompilationUnit("UserService", cu);

        // When: Running validation detector
        ValidationStarterDetector detector = new ValidationStarterDetector(true);
        MigrationPhaseResult result = detector.migrate();

        // Then: Validation usage should be detected
        assertFalse(result.getChanges().isEmpty(), "Should detect @Validated usage");
    }

    @Test
    void testDetectConstraintAnnotations() throws Exception {
        // Given: A class using constraint annotations
        String javaCode = """
                package com.example;
                import javax.validation.constraints.*;

                public class UserDto {
                    @NotNull
                    private String name;

                    @Email
                    @NotEmpty
                    private String email;

                    @Size(min = 8, max = 20)
                    private String password;
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(javaCode);
        AntikytheraRunTime.addCompilationUnit("UserDto", cu);

        // When: Running validation detector
        ValidationStarterDetector detector = new ValidationStarterDetector(true);
        MigrationPhaseResult result = detector.migrate();

        // Then: Validation usage should be detected
        assertTrue(result.getChanges().stream()
                .anyMatch(change -> change.contains("validation usage")),
                "Should detect constraint annotations");
    }

    @Test
    void testDetectJavaxValidationImports() throws Exception {
        // Given: A class importing javax.validation
        String javaCode = """
                package com.example;
                import javax.validation.constraints.NotNull;
                import javax.validation.constraints.Size;

                public class Product {
                    @NotNull
                    @Size(min = 1, max = 100)
                    private String name;
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(javaCode);
        AntikytheraRunTime.addCompilationUnit("Product", cu);

        // When: Running validation detector
        ValidationStarterDetector detector = new ValidationStarterDetector(true);
        MigrationPhaseResult result = detector.migrate();

        // Then: javax.validation imports should be detected
        assertFalse(result.getChanges().isEmpty(), "Should detect javax.validation imports");
    }

    @Test
    void testNoDetectionWhenNoValidation() throws Exception {
        // Given: A class with no validation annotations
        String javaCode = """
                package com.example;

                public class SimpleService {
                    public void doSomething() {
                        System.out.println("No validation here");
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(javaCode);
        AntikytheraRunTime.addCompilationUnit("SimpleService", cu);

        // When: Running validation detector
        ValidationStarterDetector detector = new ValidationStarterDetector(true);
        MigrationPhaseResult result = detector.migrate();

        // Then: Should report no validation usage or have empty changes
        boolean hasNoValidationMessage = result.getChanges().stream()
                .anyMatch(change -> change.toLowerCase().contains("no validation") ||
                        change.toLowerCase().contains("starter not needed") ||
                        change.toLowerCase().contains("not detected"));
        boolean hasNoChanges = result.getChanges().isEmpty();

        assertTrue(hasNoValidationMessage || hasNoChanges,
                "Should report no validation or have no changes. Actual: " + result.getChanges());
    }

    @Test
    void testAddValidationStarterDryRun() throws Exception {
        // Given: Validation usage detected and dry-run mode
        String javaCode = """
                package com.example;
                import javax.validation.constraints.NotNull;

                public class User {
                    @NotNull
                    private String name;
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(javaCode);
        AntikytheraRunTime.addCompilationUnit("User", cu);

        // Create a minimal POM without validation starter
        String pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test-project</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-web</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """;

        Path pomPath = tempDir.resolve("pom.xml");
        Files.writeString(pomPath, pomContent);

        // When: Running validation detector in dry-run mode
        ValidationStarterDetector detector = new ValidationStarterDetector(true);
        MigrationPhaseResult result = detector.migrate();

        // Then: Should report what would be added but not modify POM
        assertTrue(result.getChanges().stream()
                .anyMatch(change -> change.contains("Would add spring-boot-starter-validation")),
                "Should report planned dependency addition in dry-run");

        // Verify POM was not modified
        String pomAfter = Files.readString(pomPath);
        assertFalse(pomAfter.contains("spring-boot-starter-validation"),
                "POM should not be modified in dry-run mode");
    }

    @Test
    void testMultipleFilesWithValidation() throws Exception {
        // Given: Multiple classes using validation
        String userDto = """
                package com.example;
                import javax.validation.constraints.NotNull;

                public class UserDto {
                    @NotNull
                    private String name;
                }
                """;

        String productDto = """
                package com.example;
                import javax.validation.constraints.Size;

                public class ProductDto {
                    @Size(min = 1)
                    private String name;
                }
                """;

        AntikytheraRunTime.addCompilationUnit("UserDto", StaticJavaParser.parse(userDto));
        AntikytheraRunTime.addCompilationUnit("ProductDto", StaticJavaParser.parse(productDto));

        // When: Running validation detector
        ValidationStarterDetector detector = new ValidationStarterDetector(true);
        MigrationPhaseResult result = detector.migrate();

        // Then: Should aggregate validation usage across files
        assertTrue(result.getChanges().stream()
                .anyMatch(change -> change.contains("2 files") || change.contains("files")),
                "Should report validation usage in multiple files");
    }

    @Test
    void testNoPomFile() throws Exception {
        // Given: Validation usage but no pom.xml file
        String javaCode = """
                package com.example;
                import javax.validation.constraints.NotNull;

                public class User {
                    @NotNull
                    private String name;
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(javaCode);
        AntikytheraRunTime.addCompilationUnit("User", cu);

        // When: Running validation detector (no POM file exists)
        ValidationStarterDetector detector = new ValidationStarterDetector(false);
        MigrationPhaseResult result = detector.migrate();

        // Then: Should handle gracefully
        assertNotNull(result, "Should return result even without POM");
        // Should detect validation but not be able to add dependency
        assertTrue(result.getChanges().stream()
                .anyMatch(change -> change.toLowerCase().contains("validation")),
                "Should still detect validation usage");
    }

    @Test
    void testGetPhaseName() {
        ValidationStarterDetector detector = new ValidationStarterDetector(false);
        assertEquals("Validation Starter Detection", detector.getPhaseName());
    }

    @Test
    void testGetPriority() {
        ValidationStarterDetector detector = new ValidationStarterDetector(false);
        assertEquals(5, detector.getPriority(), "Should have highest priority for Spring Boot 2.3");
    }
}
