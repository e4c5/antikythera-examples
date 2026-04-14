package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test coverage for UsageFinder
 * Note: These tests focus on the class structure and basic functionality
 * without requiring the full Antikythera runtime environment.
 */
class UsageFinderCoverageTest {

    @Test
    void testUsageFinderClassExists() {
        // Verify the class exists
        assertNotNull(UsageFinder.class);
    }

    @Test
    void testMainMethodSignature() throws NoSuchMethodException {
        // Verify the main method has the correct signature
        var mainMethod = UsageFinder.class.getMethod("main", String[].class);
        
        assertNotNull(mainMethod);
        assertEquals("main", mainMethod.getName());
        assertEquals(void.class, mainMethod.getReturnType());
        assertTrue(java.lang.reflect.Modifier.isStatic(mainMethod.getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isPublic(mainMethod.getModifiers()));
    }

    @Test
    void testClassModifiers() {
        // Test class modifiers
        Class<?> clazz = UsageFinder.class;
        
        assertTrue(java.lang.reflect.Modifier.isPublic(clazz.getModifiers()));
        assertFalse(java.lang.reflect.Modifier.isAbstract(clazz.getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isFinal(clazz.getModifiers()));
        assertFalse(java.lang.reflect.Modifier.isInterface(clazz.getModifiers()));
    }

    @Test
    void testPackageInfo() {
        // Test package information
        Package pkg = UsageFinder.class.getPackage();
        
        assertNotNull(pkg);
        assertEquals("sa.com.cloudsolutions.antikythera.examples", pkg.getName());
    }

    @Test
    void testConstructorIsPrivate() throws NoSuchMethodException {
        // Verify this is a utility class with a private constructor
        var constructor = UsageFinder.class.getDeclaredConstructor();

        assertNotNull(constructor);
        assertTrue(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()));
        assertEquals(0, constructor.getParameterCount());
    }

    @Test
    void testMainMethodThrowsIOException() throws NoSuchMethodException {
        var mainMethod = UsageFinder.class.getMethod("main", String[].class);
        Class<?>[] exceptionTypes = mainMethod.getExceptionTypes();

        boolean throwsIOException = false;
        for (Class<?> exceptionType : exceptionTypes) {
            if (IOException.class.isAssignableFrom(exceptionType)) {
                throwsIOException = true;
                break;
            }
        }

        assertTrue(throwsIOException, "Main method should declare IOException");

    }

    @Test
    void testFindCollectionFieldsReturnsDeterministicMatches() {
        CompilationUnit normalClass = StaticJavaParser.parse("""
                package demo;

                import java.util.List;
                import java.util.Map;
                import java.util.Set;

                public class SampleUsage {
                    private List<String> names;
                    private Set<Integer> ids;
                    private Map<String, Long> lookup;
                    private String ignored;
                }
                """);

        CompilationUnit entityClass = StaticJavaParser.parse("""
                package demo.entity;

                import jakarta.persistence.Entity;
                import java.util.List;

                @Entity
                public class EntityUsage {
                    private List<String> skipped;
                }
                """);

        CompilationUnit dtoClass = StaticJavaParser.parse("""
                package demo.dto;

                import java.util.List;

                public class SampleDto {
                    private List<String> skipped;
                }
                """);

        List<UsageFinder.CollectionFieldUsage> matches = UsageFinder.findCollectionFields(
                List.of(normalClass, entityClass, dtoClass));

        assertEquals(3, matches.size());
        assertEquals(3, UsageFinder.countCollectionFields(List.of(normalClass, entityClass, dtoClass)));

        assertTrue(matches.stream().anyMatch(match ->
                match.classFqn().equals("demo.SampleUsage")
                        && match.fieldName().equals("names")
                        && match.fieldType().contains("List")));
        assertTrue(matches.stream().anyMatch(match ->
                match.classFqn().equals("demo.SampleUsage")
                        && match.fieldName().equals("ids")
                        && match.fieldType().contains("Set")));
        assertTrue(matches.stream().anyMatch(match ->
                match.classFqn().equals("demo.SampleUsage")
                        && match.fieldName().equals("lookup")
                        && match.fieldType().contains("Map")));
    }
}
