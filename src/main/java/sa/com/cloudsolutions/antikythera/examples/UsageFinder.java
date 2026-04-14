package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Static analysis utility for finding collection fields and cross-class usages.
 *
 * <h3>Modes</h3>
 * <ul>
 *   <li><b>No args</b> — lists all collection (List/Set/Map) fields in non-entity, non-DTO classes.</li>
 *   <li><b>Class FQN</b> — lists every field, method parameter, return type, extends, or implements
 *       that references the given class.</li>
 *   <li><b>Method FQN</b> ({@code com.example.Foo#doSomething}) — lists every method in the project
 *       that calls the given method.</li>
 * </ul>
 */
public final class UsageFinder {

    private UsageFinder() {}

    // -------------------------------------------------------------------------
    // Public result types
    // -------------------------------------------------------------------------

    /** A collection field found in a non-entity, non-DTO class. */
    public record CollectionFieldUsage(String classFqn, String fieldType, String fieldName) {}

    /**
     * A call site where the target method is invoked.
     *
     * @param callerFqn    fully-qualified name of the class containing the call
     * @param callerMethod simple name of the method containing the call
     * @param lineNumber   1-based line number of the call site, or -1 if unknown
     */
    public record MethodUsage(String callerFqn, String callerMethod, int lineNumber) {}

    /**
     * A reference to the target class from another class.
     *
     * @param usingClassFqn fully-qualified name of the class that references the target
     * @param usageKind     FIELD | PARAMETER | RETURN_TYPE | EXTENDS | IMPLEMENTS
     * @param memberName    field name, method name, or class name depending on usageKind
     * @param typeName      the type string exactly as written in source
     * @param lineNumber    1-based line number, or -1 if unknown
     */
    public record ClassUsage(String usingClassFqn, String usageKind, String memberName,
                             String typeName, int lineNumber) {}

    // -------------------------------------------------------------------------
    // main
    // -------------------------------------------------------------------------

    public static void main(String[] args) throws IOException {
        File yamlFile = new File(Objects.requireNonNull(
                Settings.class.getClassLoader().getResource("depsolver.yml")).getFile());
        Settings.loadConfigMap(yamlFile);
        AbstractCompiler.preProcess();

        if (args.length == 0) {
            // default: collection fields
            findCollectionFields().forEach(m -> System.out.println(
                    m.classFqn() + " : " + m.fieldType() + " : " + m.fieldName()));
        } else if (args[0].contains("#")) {
            // method usage mode
            List<MethodUsage> usages = findMethodUsages(args[0]);
            System.out.printf("Found %d call site(s) for %s%n%n", usages.size(), args[0]);
            usages.forEach(u -> System.out.printf("  %s#%s  (line %d)%n",
                    u.callerFqn(), u.callerMethod(), u.lineNumber()));
        } else {
            // class usage mode
            List<ClassUsage> usages = findClassUsages(args[0]);
            System.out.printf("Found %d usage(s) of %s%n%n", usages.size(), args[0]);
            usages.forEach(u -> System.out.printf("  %-12s  %s  %s : %s  (line %d)%n",
                    u.usageKind(), u.usingClassFqn(), u.memberName(), u.typeName(), u.lineNumber()));
        }
    }

    /** Scans the full parsed project. */
    public static List<CollectionFieldUsage> findCollectionFields() {
        return findCollectionFields(AntikytheraRunTime.getResolvedCompilationUnits().values());
    }

    /** Scans the supplied compilation units — accepts in-memory JavaParser fixtures for tests. */
    public static List<CollectionFieldUsage> findCollectionFields(Collection<CompilationUnit> compilationUnits) {
        List<CollectionFieldUsage> matches = new ArrayList<>();
        if (compilationUnits == null) return matches;

        for (CompilationUnit cu : compilationUnits) {
            if (cu == null) continue;
            cu.getTypes().stream()
                    .filter(TypeDeclaration::isClassOrInterfaceDeclaration)
                    .map(TypeDeclaration::asClassOrInterfaceDeclaration)
                    .filter(c -> c.getAnnotationByName("Entity").isEmpty())
                    .filter(c -> !c.getFullyQualifiedName().orElse("").contains("dto"))
                    .forEach(c -> collectCollectionFieldUsages(c, matches));
        }
        return matches;
    }

    /** Convenience count method. */
    public static int countCollectionFields(Collection<CompilationUnit> compilationUnits) {
        return findCollectionFields(compilationUnits).size();
    }

    /**
     * Finds all call sites for the given method across the parsed project.
     *
     * <p>Input format: {@code com.example.Foo#doSomething} or simply {@code doSomething}.
     * An optional parameter list suffix ({@code #doSomething(String,int)}) is accepted;
     * parameter types are not used for matching.</p>
     *
     * <p>When a declaring class is given the scope of each call site is resolved against
     * the caller's field and parameter types so that only calls on instances (or subclasses)
     * of the target class are returned.  Calls without an explicit scope are always included.</p>
     */
    public static List<MethodUsage> findMethodUsages(String methodSignature) {
        return findMethodUsages(methodSignature, AntikytheraRunTime.getResolvedCompilationUnits().values());
    }

    /** Scans the supplied compilation units — accepts in-memory JavaParser fixtures for tests. */
    public static List<MethodUsage> findMethodUsages(String methodSignature,
                                                      Collection<CompilationUnit> compilationUnits) {
        String targetClass = null;
        String targetMethod;

        if (methodSignature.contains("#")) {
            String[] parts = methodSignature.split("#", 2);
            String fqn = parts[0];
            targetClass = fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
            String methodPart = parts[1];
            targetMethod = methodPart.contains("(") ? methodPart.substring(0, methodPart.indexOf('(')) : methodPart;
        } else {
            targetMethod = methodSignature;
        }

        final String finalTargetClass = targetClass;
        final String finalTargetMethod = targetMethod;
        List<MethodUsage> results = new ArrayList<>();

        for (CompilationUnit cu : compilationUnits) {
            if (cu == null) continue;

            cu.getTypes().stream()
                    .filter(TypeDeclaration::isClassOrInterfaceDeclaration)
                    .map(TypeDeclaration::asClassOrInterfaceDeclaration)
                    .forEach(cid -> {
                        String callerFqn = cid.getFullyQualifiedName().orElse("");

                        // Build field-name → declared-type map for scope resolution
                        Map<String, String> fieldTypes = new HashMap<>();
                        cid.getFields().forEach(f -> f.getVariables()
                                .forEach(v -> fieldTypes.put(v.getNameAsString(), v.getTypeAsString())));

                        cid.getMethods().forEach(method -> {
                            String callerMethod = method.getNameAsString();

                            // Also include parameter types for scope resolution
                            Map<String, String> paramTypes = new HashMap<>();
                            method.getParameters().forEach(p ->
                                    paramTypes.put(p.getNameAsString(), p.getTypeAsString()));

                            // MethodCallExpr: foo.doSomething() or doSomething()
                            method.findAll(MethodCallExpr.class).forEach(call -> {
                                if (!call.getNameAsString().equals(finalTargetMethod)) return;
                                if (!scopeMatchesClass(call.getScope(), finalTargetClass, fieldTypes, paramTypes)) return;
                                int line = call.getBegin().map(p -> p.line).orElse(-1);
                                results.add(new MethodUsage(callerFqn, callerMethod, line));
                            });

                            // MethodReferenceExpr: Foo::doSomething
                            method.findAll(MethodReferenceExpr.class).forEach(ref -> {
                                if (!ref.getIdentifier().equals(finalTargetMethod)) return;
                                if (finalTargetClass != null) {
                                    String scopeStr = ref.getScope().toString();
                                    if (!matchesSimpleType(scopeStr, finalTargetClass)) return;
                                }
                                int line = ref.getBegin().map(p -> p.line).orElse(-1);
                                results.add(new MethodUsage(callerFqn, callerMethod + "[ref]", line));
                            });
                        });
                    });
        }

        results.sort(Comparator.comparing(MethodUsage::callerFqn).thenComparing(MethodUsage::callerMethod));
        return results;
    }

    /**
     * Finds all references to {@code classFqn} across the parsed project.
     * Reports fields, method parameters, return types, extends, and implements.
     */
    public static List<ClassUsage> findClassUsages(String classFqn) {
        return findClassUsages(classFqn, AntikytheraRunTime.getResolvedCompilationUnits().values());
    }

    /** Scans the supplied compilation units — accepts in-memory JavaParser fixtures for tests. */
    public static List<ClassUsage> findClassUsages(String classFqn,
                                                    Collection<CompilationUnit> compilationUnits) {
        String simpleClass = classFqn.contains(".")
                ? classFqn.substring(classFqn.lastIndexOf('.') + 1) : classFqn;
        List<ClassUsage> results = new ArrayList<>();

        for (CompilationUnit cu : compilationUnits) {
            if (cu == null) continue;

            cu.getTypes().stream()
                    .filter(TypeDeclaration::isClassOrInterfaceDeclaration)
                    .map(TypeDeclaration::asClassOrInterfaceDeclaration)
                    .filter(cid -> !cid.getFullyQualifiedName().orElse("").equals(classFqn))
                    .forEach(cid -> {
                        String usingClass = cid.getFullyQualifiedName().orElse("");

                        // Extends
                        cid.getExtendedTypes().forEach(ext -> {
                            if (matchesSimpleType(ext.getNameAsString(), simpleClass)) {
                                int line = ext.getBegin().map(p -> p.line).orElse(-1);
                                results.add(new ClassUsage(usingClass, "EXTENDS",
                                        cid.getNameAsString(), ext.getNameAsString(), line));
                            }
                        });

                        // Implements
                        cid.getImplementedTypes().forEach(impl -> {
                            if (matchesSimpleType(impl.getNameAsString(), simpleClass)) {
                                int line = impl.getBegin().map(p -> p.line).orElse(-1);
                                results.add(new ClassUsage(usingClass, "IMPLEMENTS",
                                        cid.getNameAsString(), impl.getNameAsString(), line));
                            }
                        });

                        // Fields
                        cid.getFields().forEach(field -> field.getVariables().forEach(var -> {
                            String typeName = var.getTypeAsString();
                            if (typeContainsClass(typeName, simpleClass)) {
                                int line = field.getBegin().map(p -> p.line).orElse(-1);
                                results.add(new ClassUsage(usingClass, "FIELD",
                                        var.getNameAsString(), typeName, line));
                            }
                        }));

                        // Constructor parameters
                        cid.getConstructors().forEach(ctor -> ctor.getParameters().forEach(param -> {
                            String paramType = param.getTypeAsString();
                            if (typeContainsClass(paramType, simpleClass)) {
                                int line = param.getBegin().map(p -> p.line).orElse(-1);
                                results.add(new ClassUsage(usingClass, "PARAMETER",
                                        cid.getNameAsString() + "(" + param.getNameAsString() + ")",
                                        paramType, line));
                            }
                        }));

                        // Method return types and parameters
                        cid.getMethods().forEach(method -> {
                            String retType = method.getTypeAsString();
                            if (typeContainsClass(retType, simpleClass)) {
                                int line = method.getBegin().map(p -> p.line).orElse(-1);
                                results.add(new ClassUsage(usingClass, "RETURN_TYPE",
                                        method.getNameAsString(), retType, line));
                            }
                            method.getParameters().forEach(param -> {
                                String paramType = param.getTypeAsString();
                                if (typeContainsClass(paramType, simpleClass)) {
                                    int line = param.getBegin().map(p -> p.line).orElse(-1);
                                    results.add(new ClassUsage(usingClass, "PARAMETER",
                                            method.getNameAsString() + "(" + param.getNameAsString() + ")",
                                            paramType, line));
                                }
                            });
                        });
                    });
        }

        results.sort(Comparator.comparing(ClassUsage::usingClassFqn).thenComparing(ClassUsage::usageKind));
        return results;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static void collectCollectionFieldUsages(ClassOrInterfaceDeclaration cdecl,
                                                     List<CollectionFieldUsage> matches) {
        String classFqn = cdecl.getFullyQualifiedName().orElse("");
        for (FieldDeclaration field : cdecl.getFields()) {
            field.getVariables().forEach(variable -> {
                String fieldType = variable.getTypeAsString();
                if (isCollectionType(fieldType)) {
                    matches.add(new CollectionFieldUsage(classFqn, fieldType, variable.getNameAsString()));
                }
            });
        }
    }

    private static boolean isCollectionType(String type) {
        return type.contains("List<") || type.contains("List ")
                || type.contains("Set<")  || type.contains("Set ")
                || type.contains("Map<")  || type.contains("Map ");
    }

    /**
     * Returns {@code true} if the raw (non-generic) part of {@code typeStr} matches
     * {@code simpleClass}.  E.g. {@code "UserService<T>"} matches {@code "UserService"}.
     */
    private static boolean matchesSimpleType(String typeStr, String simpleClass) {
        if (typeStr == null || simpleClass == null) return false;
        String raw = typeStr.contains("<") ? typeStr.substring(0, typeStr.indexOf('<')) : typeStr;
        String last = raw.contains(".") ? raw.substring(raw.lastIndexOf('.') + 1) : raw;
        return last.equals(simpleClass);
    }

    /**
     * Returns {@code true} if {@code typeStr} references {@code simpleClass} either as
     * the direct type ({@code Foo}, {@code Foo<Bar>}) or as a generic argument
     * ({@code List<Foo>}, {@code Map<String, Foo>}).
     */
    private static boolean typeContainsClass(String typeStr, String simpleClass) {
        if (typeStr == null || simpleClass == null) return false;
        if (matchesSimpleType(typeStr, simpleClass)) return true;
        // Check generic arguments
        int lt = typeStr.indexOf('<');
        if (lt >= 0) {
            String inner = typeStr.substring(lt + 1, typeStr.lastIndexOf('>') < 0
                    ? typeStr.length() : typeStr.lastIndexOf('>'));
            for (String part : inner.split("[,<>]")) {
                if (matchesSimpleType(part.trim(), simpleClass)) return true;
            }
        }
        return false;
    }

    /**
     * Checks whether the scope of a call matches the target class.
     * Returns {@code true} when:
     * <ul>
     *   <li>no target class is given (match everything), or</li>
     *   <li>the call has no explicit scope (same-class / super call), or</li>
     *   <li>the scope resolves to the target class via the caller's field/parameter types.</li>
     * </ul>
     */
    private static boolean scopeMatchesClass(Optional<Expression> scope,
                                              String targetClass,
                                              Map<String, String> fieldTypes,
                                              Map<String, String> paramTypes) {
        if (targetClass == null) return true;         // no filter → include all
        if (scope.isEmpty()) return true;             // no scope → same-class call

        String scopeStr = scope.get().toString();
        // Resolve the scope to its declared type (field, parameter, or use as-is for static calls)
        String resolved = fieldTypes.getOrDefault(scopeStr,
                paramTypes.getOrDefault(scopeStr, scopeStr));
        return matchesSimpleType(resolved, targetClass);
    }
}
