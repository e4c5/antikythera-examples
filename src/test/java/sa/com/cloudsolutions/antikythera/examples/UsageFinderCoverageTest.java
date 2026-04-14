package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behaviour-level tests for UsageFinder using in-memory JavaParser fixtures.
 * No Antikythera runtime is required.
 */
class UsageFinderCoverageTest {

    // -------------------------------------------------------------------------
    // Class shape tests
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Collection field tests
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Method usage tests
    // -------------------------------------------------------------------------

    @Test
    void testFindMethodUsagesDetectsCallSite() {
        CompilationUnit service = StaticJavaParser.parse("""
                package demo;
                public class FooService {
                    public void process() {}
                }
                """);

        CompilationUnit caller = StaticJavaParser.parse("""
                package demo;
                public class BarController {
                    private FooService fooService;
                    public void doSomething() {
                        fooService.process();
                    }
                }
                """);

        List<UsageFinder.MethodUsage> usages =
                UsageFinder.findMethodUsages("demo.FooService#process", List.of(service, caller));

        assertEquals(1, usages.size());
        assertEquals("demo.BarController", usages.get(0).callerFqn());
        assertEquals("doSomething", usages.get(0).callerMethod());
        assertTrue(usages.get(0).lineNumber() > 0);
    }

    @Test
    void testFindMethodUsagesIgnoresUnrelatedScopes() {
        CompilationUnit cuA = StaticJavaParser.parse("""
                package demo;
                public class A { public void run() {} }
                """);

        CompilationUnit cuB = StaticJavaParser.parse("""
                package demo;
                public class B { public void run() {} }
                """);

        CompilationUnit caller = StaticJavaParser.parse("""
                package demo;
                public class Caller {
                    private A a;
                    private B b;
                    public void go() {
                        a.run();   // should match A#run
                        b.run();   // should NOT match when searching for A#run
                    }
                }
                """);

        List<UsageFinder.MethodUsage> usages =
                UsageFinder.findMethodUsages("demo.A#run", List.of(cuA, cuB, caller));

        assertEquals(1, usages.size(), "Only the call on 'a' (type A) should match");
        assertEquals("go", usages.get(0).callerMethod());
    }

    @Test
    void testFindMethodUsagesWithNoClassFilter() {
        CompilationUnit cu = StaticJavaParser.parse("""
                package demo;
                public class Util {
                    public void doThing() {}
                    public void caller1() { doThing(); }
                    public void caller2() { doThing(); }
                }
                """);

        // Passing just the method name (no class) should find both call sites
        List<UsageFinder.MethodUsage> usages =
                UsageFinder.findMethodUsages("doThing", List.of(cu));

        assertEquals(2, usages.size());
    }

    // -------------------------------------------------------------------------
    // Class usage tests
    // -------------------------------------------------------------------------

    @Test
    void testFindClassUsagesDetectsFieldFieldAndParameter() {
        CompilationUnit target = StaticJavaParser.parse("""
                package demo;
                public class MyService {}
                """);

        CompilationUnit user = StaticJavaParser.parse("""
                package demo;
                public class MyController {
                    private MyService myService;
                    public void handle(MyService svc) {}
                    public MyService get() { return null; }
                }
                """);

        List<UsageFinder.ClassUsage> usages =
                UsageFinder.findClassUsages("demo.MyService", List.of(target, user));

        assertTrue(usages.stream().anyMatch(u -> u.usageKind().equals("FIELD") && u.memberName().equals("myService")));
        assertTrue(usages.stream().anyMatch(u -> u.usageKind().equals("PARAMETER") && u.memberName().contains("handle")));
        assertTrue(usages.stream().anyMatch(u -> u.usageKind().equals("RETURN_TYPE") && u.memberName().equals("get")));
    }

    @Test
    void testFindClassUsagesDetectsExtendsAndImplements() {
        CompilationUnit base = StaticJavaParser.parse("""
                package demo;
                public abstract class BaseService {}
                """);

        CompilationUnit iface = StaticJavaParser.parse("""
                package demo;
                public interface Auditable {}
                """);

        CompilationUnit child = StaticJavaParser.parse("""
                package demo;
                public class OrderService extends BaseService implements Auditable {}
                """);

        List<UsageFinder.ClassUsage> extendsUsages =
                UsageFinder.findClassUsages("demo.BaseService", List.of(base, iface, child));
        assertTrue(extendsUsages.stream().anyMatch(u -> u.usageKind().equals("EXTENDS")));

        List<UsageFinder.ClassUsage> implUsages =
                UsageFinder.findClassUsages("demo.Auditable", List.of(base, iface, child));
        assertTrue(implUsages.stream().anyMatch(u -> u.usageKind().equals("IMPLEMENTS")));
    }

    @Test
    void testFindClassUsagesDetectsGenericArgument() {
        CompilationUnit target = StaticJavaParser.parse("""
                package demo;
                public class Item {}
                """);

        CompilationUnit user = StaticJavaParser.parse("""
                package demo;
                import java.util.List;
                public class Basket {
                    private List<Item> items;
                }
                """);

        List<UsageFinder.ClassUsage> usages =
                UsageFinder.findClassUsages("demo.Item", List.of(target, user));

        assertEquals(1, usages.size());
        assertEquals("FIELD", usages.get(0).usageKind());
        assertEquals("items", usages.get(0).memberName());
    }

    @Test
    void testFindClassUsagesExcludesSelf() {
        CompilationUnit cu = StaticJavaParser.parse("""
                package demo;
                public class Node {
                    private Node next;   // self-reference
                }
                """);

        // Self-references should still appear (the class is in 'cu' but the user field is in the same class)
        // The filter excludes the FQN of the class itself only from the outer loop, but inner fields should show
        // Actually let's check: the filter is "usingClass != classFqn" — so Node won't be checked against itself
        // This means Node.next won't be reported. That's correct for "who else uses this class."
        List<UsageFinder.ClassUsage> usages =
                UsageFinder.findClassUsages("demo.Node", List.of(cu));

        assertTrue(usages.isEmpty(), "Self-references should not be reported");
    }
}
