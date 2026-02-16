package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AnnotationFinder tool to verify it correctly identifies
 * classes and methods with specific annotations, handling both simple
 * and fully qualified annotation names.
 */
class AnnotationFinderTest {

    @Test
    void testMatchesAnnotation_SimpleName() {
        // Test that simple name "Service" matches both @Service and @org.springframework.stereotype.Service
        assertTrue(AnnotationFinder.matchesAnnotation("Service", "Service", "Service"));
        assertTrue(AnnotationFinder.matchesAnnotation("org.springframework.stereotype.Service", "Service", "Service"));
        assertTrue(AnnotationFinder.matchesAnnotation("Service", "org.springframework.stereotype.Service", "Service"));
        assertTrue(AnnotationFinder.matchesAnnotation("org.springframework.stereotype.Service", 
                "org.springframework.stereotype.Service", "Service"));
    }

    @Test
    void testMatchesAnnotation_FullyQualifiedName() {
        // Test fully qualified name matching
        assertTrue(AnnotationFinder.matchesAnnotation("org.springframework.stereotype.Service", 
                "org.springframework.stereotype.Service", "Service"));
        assertTrue(AnnotationFinder.matchesAnnotation("Service", 
                "org.springframework.stereotype.Service", "Service"));
    }

    @Test
    void testMatchesAnnotation_NoMatch() {
        // Test that non-matching annotations are not matched
        assertFalse(AnnotationFinder.matchesAnnotation("Service", "Test", "Test"));
        assertFalse(AnnotationFinder.matchesAnnotation("org.springframework.stereotype.Service", 
                "Test", "Test"));
        assertFalse(AnnotationFinder.matchesAnnotation("Entity", "Service", "Service"));
    }

    @Test
    void testExtractSimpleName() {
        assertEquals("Service", AnnotationFinder.extractSimpleName("Service"));
        assertEquals("Service", AnnotationFinder.extractSimpleName("org.springframework.stereotype.Service"));
        assertEquals("Test", AnnotationFinder.extractSimpleName("org.junit.jupiter.api.Test"));
        assertEquals("Entity", AnnotationFinder.extractSimpleName("javax.persistence.Entity"));
    }

    /**
     * Provides test cases for hasAnnotation method.
     */
    static Stream<Arguments> hasAnnotationTestCases() {
        return Stream.of(
            Arguments.of(
                "With matching annotation",
                """
                package test;
                import org.springframework.stereotype.Service;
                
                @Service
                class MyService {
                }
                """,
                true
            ),
            Arguments.of(
                "With fully qualified annotation",
                """
                package test;
                
                @org.springframework.stereotype.Service
                class MyService {
                }
                """,
                true
            ),
            Arguments.of(
                "No match",
                """
                package test;
                
                @Entity
                class MyEntity {
                }
                """,
                false
            )
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hasAnnotationTestCases")
    void testHasAnnotation(String testName, String code, boolean shouldMatch) {
        CompilationUnit cu = StaticJavaParser.parse(code);
        ClassOrInterfaceDeclaration classDecl = cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
        
        assertEquals(shouldMatch, AnnotationFinder.hasAnnotation(classDecl.getAnnotations(), "Service", "Service"));
        if (shouldMatch) {
            assertTrue(AnnotationFinder.hasAnnotation(classDecl.getAnnotations(), 
                    "org.springframework.stereotype.Service", "Service"));
        }
    }

    /**
     * Provides test cases for buildMethodSignature method.
     */
    static Stream<Arguments> buildMethodSignatureTestCases() {
        return Stream.of(
            Arguments.of(
                "No parameters",
                """
                package test;
                
                class MyClass {
                    void print() {
                    }
                }
                """,
                "print()"
            ),
            Arguments.of(
                "With parameters",
                """
                package test;
                
                class MyClass {
                    void print(String s) {
                    }
                }
                """,
                "print(String s)"
            ),
            Arguments.of(
                "Multiple parameters",
                """
                package test;
                
                class MyClass {
                    void process(String name, int count, boolean active) {
                    }
                }
                """,
                "process(String name, int count, boolean active)"
            )
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("buildMethodSignatureTestCases")
    void testBuildMethodSignature(String testName, String code, String expectedSignature) {
        CompilationUnit cu = StaticJavaParser.parse(code);
        MethodDeclaration method = cu.findFirst(MethodDeclaration.class).orElseThrow();
        
        String signature = AnnotationFinder.buildMethodSignature(method);
        assertEquals(expectedSignature, signature);
    }

    @Test
    void testBuildMethodSignature_WithComplexTypes() {
        String code = """
            package test;
            import java.util.List;
            import java.util.Map;
            
            class MyClass {
                void process(List<String> items, Map<String, Integer> counts) {
                }
            }
            """;
        
        CompilationUnit cu = StaticJavaParser.parse(code);
        MethodDeclaration method = cu.findFirst(MethodDeclaration.class).orElseThrow();
        
        String signature = AnnotationFinder.buildMethodSignature(method);
        assertTrue(signature.contains("process"));
        // Type might be resolved to fully qualified name or simple name depending on imports
        assertTrue(signature.contains("items") || signature.contains("List"));
        assertTrue(signature.contains("counts") || signature.contains("Map"));
        // Verify it has the method name and parameters
        assertTrue(signature.startsWith("process("));
        assertTrue(signature.endsWith(")"));
    }

    @Test
    void testClassAnnotation_SimpleName() {
        String code = """
            package com.example;
            import org.springframework.stereotype.Service;
            
            @Service
            class UserService {
            }
            """;
        
        CompilationUnit cu = StaticJavaParser.parse(code);
        ClassOrInterfaceDeclaration classDecl = cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
        
        assertTrue(AnnotationFinder.hasAnnotation(classDecl.getAnnotations(), "Service", "Service"));
    }

    @Test
    void testClassAnnotation_FullyQualified() {
        String code = """
            package com.example;
            
            @org.springframework.stereotype.Service
            class UserService {
            }
            """;
        
        CompilationUnit cu = StaticJavaParser.parse(code);
        ClassOrInterfaceDeclaration classDecl = cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
        
        assertTrue(AnnotationFinder.hasAnnotation(classDecl.getAnnotations(), "Service", "Service"));
        assertTrue(AnnotationFinder.hasAnnotation(classDecl.getAnnotations(), 
                "org.springframework.stereotype.Service", "Service"));
    }

    @Test
    void testMethodAnnotation_SimpleName() {
        String code = """
            package com.example;
            import org.junit.jupiter.api.Test;
            
            class TestClass {
                @Test
                void testMethod() {
                }
            }
            """;
        
        CompilationUnit cu = StaticJavaParser.parse(code);
        MethodDeclaration method = cu.findFirst(MethodDeclaration.class).orElseThrow();
        
        assertTrue(AnnotationFinder.hasAnnotation(method.getAnnotations(), "Test", "Test"));
    }

    @Test
    void testMethodAnnotation_FullyQualified() {
        String code = """
            package com.example;
            
            class TestClass {
                @org.junit.jupiter.api.Test
                void testMethod() {
                }
            }
            """;
        
        CompilationUnit cu = StaticJavaParser.parse(code);
        MethodDeclaration method = cu.findFirst(MethodDeclaration.class).orElseThrow();
        
        assertTrue(AnnotationFinder.hasAnnotation(method.getAnnotations(), "Test", "Test"));
        assertTrue(AnnotationFinder.hasAnnotation(method.getAnnotations(), 
                "org.junit.jupiter.api.Test", "Test"));
    }

    @Test
    void testMultipleAnnotations() {
        String code = """
            package com.example;
            import org.springframework.stereotype.Service;
            import org.springframework.transaction.annotation.Transactional;
            
            @Service
            class UserService {
                @Transactional
                void save() {
                }
            }
            """;
        
        CompilationUnit cu = StaticJavaParser.parse(code);
        ClassOrInterfaceDeclaration classDecl = cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
        MethodDeclaration method = cu.findFirst(MethodDeclaration.class).orElseThrow();
        
        assertTrue(AnnotationFinder.hasAnnotation(classDecl.getAnnotations(), "Service", "Service"));
        assertTrue(AnnotationFinder.hasAnnotation(method.getAnnotations(), "Transactional", "Transactional"));
        assertFalse(AnnotationFinder.hasAnnotation(classDecl.getAnnotations(), "Transactional", "Transactional"));
        assertFalse(AnnotationFinder.hasAnnotation(method.getAnnotations(), "Service", "Service"));
    }

    @Test
    void testSimpleModeOutput_JustMethodName() {
        String code = """
            package com.example;
            import org.junit.jupiter.api.Test;
            
            class TestClass {
                @Test
                void testMethod() {
                }
                
                @Test
                void testMethod(String param) {
                }
            }
            """;
        
        CompilationUnit cu = StaticJavaParser.parse(code);
        ClassOrInterfaceDeclaration classDecl = cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
        String className = classDecl.getFullyQualifiedName().orElse(classDecl.getNameAsString());
        
        // In simple mode, both overloaded methods should produce the same output
        java.util.List<MethodDeclaration> methods = classDecl.getMethods();
        Set<String> seen = new java.util.HashSet<>();
        
        methods.forEach(method -> {
            if (AnnotationFinder.hasAnnotation(method.getAnnotations(), "Test", "Test")) {
                String output = className + "#" + method.getNameAsString();
                seen.add(output);
            }
        });
        
        // Should only have one entry (duplicates eliminated)
        assertEquals(1, seen.size());
        assertTrue(seen.contains(className + "#testMethod"));
    }

    @Test
    void testSimpleModeOutput_DifferentMethodNames() {
        String code = """
            package com.example;
            import org.junit.jupiter.api.Test;
            
            class TestClass {
                @Test
                void testMethod1() {
                }
                
                @Test
                void testMethod2() {
                }
            }
            """;
        
        CompilationUnit cu = StaticJavaParser.parse(code);
        ClassOrInterfaceDeclaration classDecl = cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
        String className = classDecl.getFullyQualifiedName().orElse(classDecl.getNameAsString());
        
        java.util.List<MethodDeclaration> methods = classDecl.getMethods();
        Set<String> seen = new java.util.HashSet<>();
        
        methods.forEach(method -> {
            if (AnnotationFinder.hasAnnotation(method.getAnnotations(), "Test", "Test")) {
                String output = className + "#" + method.getNameAsString();
                seen.add(output);
            }
        });
        
        // Should have two entries (different method names)
        assertEquals(2, seen.size());
        assertTrue(seen.contains(className + "#testMethod1"));
        assertTrue(seen.contains(className + "#testMethod2"));
    }

    @Test
    void testSimpleModeOutput_NoParameters() {
        String code = """
            package com.example;
            import org.junit.jupiter.api.Test;
            
            class TestClass {
                @Test
                void testMethod() {
                }
            }
            """;
        
        CompilationUnit cu = StaticJavaParser.parse(code);
        MethodDeclaration method = cu.findFirst(MethodDeclaration.class).orElseThrow();
        
        // In simple mode, output should be just className#methodName (no parameters)
        String simpleOutput = "com.example.TestClass#" + method.getNameAsString();
        assertEquals("com.example.TestClass#testMethod", simpleOutput);
        
        // Detailed mode would include parameters
        String detailedOutput = "com.example.TestClass#" + AnnotationFinder.buildMethodSignature(method);
        assertEquals("com.example.TestClass#testMethod()", detailedOutput);
    }
}

