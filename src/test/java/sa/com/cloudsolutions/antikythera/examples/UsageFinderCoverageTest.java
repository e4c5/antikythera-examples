package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behaviour-level tests for UsageFinder using in-memory JavaParser fixtures.
 * No Antikythera runtime is required.
 */
class UsageFinderCoverageTest {

    @BeforeAll
    static void configureParser() {
        StaticJavaParser.setConfiguration(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
    }

    // -------------------------------------------------------------------------
    // Class shape tests
    // -------------------------------------------------------------------------

    @Test
    void testUsageFinderCanBeInstantiated() {
        UsageFinder finder = new UsageFinder(List.of());
        assertNotNull(finder);
    }

    @Test
    void testMainMethodSignature() throws NoSuchMethodException {
        var mainMethod = UsageFinder.class.getMethod("main", String[].class);
        assertNotNull(mainMethod);
        assertEquals("main", mainMethod.getName());
        assertEquals(void.class, mainMethod.getReturnType());
        assertTrue(java.lang.reflect.Modifier.isStatic(mainMethod.getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isPublic(mainMethod.getModifiers()));
    }

    @Test
    void testClassModifiers() {
        Class<?> clazz = UsageFinder.class;
        assertTrue(java.lang.reflect.Modifier.isPublic(clazz.getModifiers()));
        assertFalse(java.lang.reflect.Modifier.isAbstract(clazz.getModifiers()));
        assertFalse(java.lang.reflect.Modifier.isFinal(clazz.getModifiers()));
        assertFalse(java.lang.reflect.Modifier.isInterface(clazz.getModifiers()));
    }

    @Test
    void testPackageInfo() {
        assertEquals("sa.com.cloudsolutions.antikythera.examples", UsageFinder.class.getPackageName());
    }

    @Test
    void testPublicConstructorExists() throws NoSuchMethodException {
        var constructor = UsageFinder.class.getConstructor(java.util.Collection.class);
        assertNotNull(constructor);
        assertTrue(java.lang.reflect.Modifier.isPublic(constructor.getModifiers()));
    }

    @Test
    void testMainMethodThrowsIOException() throws NoSuchMethodException {
        var mainMethod = UsageFinder.class.getMethod("main", String[].class);
        boolean throwsIOException = false;
        for (Class<?> ex : mainMethod.getExceptionTypes()) {
            if (IOException.class.isAssignableFrom(ex)) { throwsIOException = true; break; }
        }
        assertTrue(throwsIOException, "main must declare IOException");
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
                public class EntityUsage { private List<String> skipped; }
                """);

        CompilationUnit dtoClass = StaticJavaParser.parse("""
                package demo.dto;
                import java.util.List;
                public class SampleDto { private List<String> skipped; }
                """);

        UsageFinder finder = new UsageFinder(List.of(normalClass, entityClass, dtoClass));
        List<UsageFinder.CollectionFieldUsage> matches = finder.findCollectionFields();

        assertEquals(3, matches.size());
        assertEquals(3, finder.countCollectionFields());
        assertTrue(matches.stream().anyMatch(m -> m.fieldName().equals("names") && m.fieldType().contains("List")));
        assertTrue(matches.stream().anyMatch(m -> m.fieldName().equals("ids")    && m.fieldType().contains("Set")));
        assertTrue(matches.stream().anyMatch(m -> m.fieldName().equals("lookup") && m.fieldType().contains("Map")));
    }

    @Test
    void testFindCollectionFieldsIncludesNestedEnumsAndRecords() {
        CompilationUnit cu = StaticJavaParser.parse("""
                package demo;
                import java.util.List;
                import java.util.Set;
                public class Outer {
                    static class Nested { private List<String> names; }
                    enum State { READY; private Set<String> labels; }
                    record Holder(List<String> values) { private static List<String> cache; }
                }
                """);

        List<UsageFinder.CollectionFieldUsage> matches = new UsageFinder(List.of(cu)).findCollectionFields();

        assertTrue(matches.stream().anyMatch(m -> m.classFqn().equals("demo.Outer.Nested") && m.fieldName().equals("names")));
        assertTrue(matches.stream().anyMatch(m -> m.classFqn().equals("demo.Outer.State") && m.fieldName().equals("labels")));
        assertTrue(matches.stream().anyMatch(m -> m.classFqn().equals("demo.Outer.Holder") && m.fieldName().equals("cache")));
    }

    // -------------------------------------------------------------------------
    // Method usage tests
    // -------------------------------------------------------------------------

    @Test
    void testFindMethodUsagesDetectsCallSite() {
        CompilationUnit service = StaticJavaParser.parse("""
                package demo;
                public class FooService { public void process() {} }
                """);

        CompilationUnit caller = StaticJavaParser.parse("""
                package demo;
                public class BarController {
                    private FooService fooService;
                    public void doSomething() { fooService.process(); }
                }
                """);

        List<UsageFinder.MethodUsage> usages =
                new UsageFinder(List.of(service, caller)).findMethodUsages("demo.FooService#process");

        assertEquals(1, usages.size());
        assertEquals("demo.BarController", usages.get(0).callerFqn());
        assertEquals("doSomething", usages.get(0).callerMethod());
        assertTrue(usages.get(0).lineNumber() > 0);
    }

    @Test
    void testFindMethodUsagesIgnoresUnrelatedScopes() {
        CompilationUnit cuA = StaticJavaParser.parse("""
                package demo; public class A { public void run() {} }""");
        CompilationUnit cuB = StaticJavaParser.parse("""
                package demo; public class B { public void run() {} }""");
        CompilationUnit caller = StaticJavaParser.parse("""
                package demo;
                public class Caller {
                    private A a;
                    private B b;
                    public void go() { a.run(); b.run(); }
                }
                """);

        List<UsageFinder.MethodUsage> usages =
                new UsageFinder(List.of(cuA, cuB, caller)).findMethodUsages("demo.A#run");

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

        assertEquals(2, new UsageFinder(List.of(cu)).findMethodUsages("doThing").size());
    }

    @Test
    void testFindMethodUsagesScansConstructorsAndInitializers() {
        CompilationUnit target = StaticJavaParser.parse("package demo; public class Worker { public void run() {} }");
        CompilationUnit caller = StaticJavaParser.parse("""
                package demo;
                public class Caller {
                    private Worker worker;
                    { worker.run(); }
                    static { Worker worker = new Worker(); worker.run(); }
                    public Caller(Worker worker) { worker.run(); }
                }
                """);

        List<UsageFinder.MethodUsage> usages =
                new UsageFinder(List.of(target, caller)).findMethodUsages("demo.Worker#run");

        assertTrue(usages.stream().anyMatch(u -> u.callerMethod().equals("Caller")));
        assertTrue(usages.stream().anyMatch(u -> u.callerMethod().equals("<init>")));
        assertTrue(usages.stream().anyMatch(u -> u.callerMethod().equals("<clinit>")));
    }

    @Test
    void testFindMethodUsagesResolvesThisFieldAndLocalScopes() {
        CompilationUnit target = StaticJavaParser.parse("package demo; public class Worker { public void run() {} }");
        CompilationUnit caller = StaticJavaParser.parse("""
                package demo;
                public class Caller {
                    private Worker worker;
                    public void go() {
                        this.worker.run();
                        Worker local = new Worker();
                        local.run();
                    }
                }
                """);

        List<UsageFinder.MethodUsage> usages =
                new UsageFinder(List.of(target, caller)).findMethodUsages("demo.Worker#run");

        assertEquals(2, usages.size());
        assertTrue(usages.stream().allMatch(u -> u.callerMethod().equals("go")));
    }

    @Test
    void testFindMethodUsagesDoesNotLetLocalVariableShadowExplicitThisFieldScope() {
        CompilationUnit target = StaticJavaParser.parse("package demo; public class Worker { public void run() {} }");
        CompilationUnit other = StaticJavaParser.parse("package demo; public class Other { public void run() {} }");
        CompilationUnit caller = StaticJavaParser.parse("""
                package demo;
                public class Caller {
                    private Worker worker;
                    public void go() {
                        Other worker = new Other();
                        this.worker.run();
                        worker.run();
                    }
                }
                """);

        List<UsageFinder.MethodUsage> usages =
                new UsageFinder(List.of(target, other, caller)).findMethodUsages("demo.Worker#run");

        assertEquals(1, usages.size());
        assertEquals("go", usages.getFirst().callerMethod());
    }

    @Test
    void testFindMethodUsagesResolvesThisScopeForSameClass() {
        CompilationUnit cu = StaticJavaParser.parse("""
                package demo;
                public class Worker {
                    public void run() {}
                    public void go() { this.run(); }
                }
                """);

        List<UsageFinder.MethodUsage> usages =
                new UsageFinder(List.of(cu)).findMethodUsages("demo.Worker#run");

        assertEquals(1, usages.size());
        assertEquals("go", usages.getFirst().callerMethod());
    }

    // -------------------------------------------------------------------------
    // Class usage tests
    // -------------------------------------------------------------------------

    @Test
    void testFindClassUsagesDetectsFieldParameterAndReturnType() {
        CompilationUnit target = StaticJavaParser.parse("""
                package demo; public class MyService {}""");
        CompilationUnit user = StaticJavaParser.parse("""
                package demo;
                public class MyController {
                    private MyService myService;
                    public void handle(MyService svc) {}
                    public MyService get() { return null; }
                }
                """);

        List<UsageFinder.ClassUsage> usages =
                new UsageFinder(List.of(target, user)).findClassUsages("demo.MyService");

        assertTrue(usages.stream().anyMatch(u -> u.usageKind().equals("FIELD")       && u.memberName().equals("myService")));
        assertTrue(usages.stream().anyMatch(u -> u.usageKind().equals("PARAMETER")   && u.memberName().contains("handle")));
        assertTrue(usages.stream().anyMatch(u -> u.usageKind().equals("RETURN_TYPE") && u.memberName().equals("get")));
    }

    @Test
    void testFindClassUsagesDetectsExtendsAndImplements() {
        CompilationUnit base  = StaticJavaParser.parse("package demo; public abstract class BaseService {}");
        CompilationUnit iface = StaticJavaParser.parse("package demo; public interface Auditable {}");
        CompilationUnit child = StaticJavaParser.parse("""
                package demo;
                public class OrderService extends BaseService implements Auditable {}
                """);

        assertTrue(new UsageFinder(List.of(base, iface, child))
                .findClassUsages("demo.BaseService").stream()
                .anyMatch(u -> u.usageKind().equals("EXTENDS")));

        assertTrue(new UsageFinder(List.of(base, iface, child))
                .findClassUsages("demo.Auditable").stream()
                .anyMatch(u -> u.usageKind().equals("IMPLEMENTS")));
    }

    @Test
    void testFindClassUsagesDetectsGenericArgument() {
        CompilationUnit target = StaticJavaParser.parse("package demo; public class Item {}");
        CompilationUnit user   = StaticJavaParser.parse("""
                package demo;
                import java.util.List;
                public class Basket { private List<Item> items; }
                """);

        List<UsageFinder.ClassUsage> usages =
                new UsageFinder(List.of(target, user)).findClassUsages("demo.Item");

        assertEquals(1, usages.size());
        assertEquals("FIELD", usages.get(0).usageKind());
        assertEquals("items", usages.get(0).memberName());
    }

    @Test
    void testFindClassUsagesDetectsArrayTypeAndNestedTypes() {
        CompilationUnit target = StaticJavaParser.parse("package demo; public class Item {}");
        CompilationUnit user = StaticJavaParser.parse("""
                package demo;
                public class Outer {
                    static class Nested { private Item[] items; }
                    enum State { READY; private Item item; }
                    record Holder(Item item) { Item[] copy() { return null; } }
                }
                """);

        List<UsageFinder.ClassUsage> usages =
                new UsageFinder(List.of(target, user)).findClassUsages("demo.Item");

        assertTrue(usages.stream().anyMatch(u -> u.usingClassFqn().equals("demo.Outer.Nested")
                && u.usageKind().equals("FIELD") && u.typeName().equals("Item[]")));
        assertTrue(usages.stream().anyMatch(u -> u.usingClassFqn().equals("demo.Outer.State")
                && u.usageKind().equals("FIELD") && u.memberName().equals("item")));
        assertTrue(usages.stream().anyMatch(u -> u.usingClassFqn().equals("demo.Outer.Holder")
                && u.usageKind().equals("RETURN_TYPE") && u.typeName().equals("Item[]")));
    }

    @Test
    void testFindClassUsagesExcludesSelf() {
        CompilationUnit cu = StaticJavaParser.parse("""
                package demo;
                public class Node { private Node next; }
                """);

        assertTrue(new UsageFinder(List.of(cu)).findClassUsages("demo.Node").isEmpty(),
                "Self-references should not be reported");
    }
}
