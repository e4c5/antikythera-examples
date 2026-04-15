package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Analyzes Java source to find collection fields and cross-class usages.
 *
 * <h3>CLI modes</h3>
 * <ul>
 *   <li><b>No args</b> — collection (List/Set/Map) fields in non-entity, non-DTO classes.</li>
 *   <li><b>Class FQN</b> — fields, parameters, return types, extends, implements referencing the class.</li>
 *   <li><b>Method FQN</b> ({@code com.example.Foo#doSomething}) — all callers of the method.</li>
 * </ul>
 *
 * <h3>Programmatic usage</h3>
 * <pre>{@code
 * // Full project scan
 * UsageFinder finder = UsageFinder.forProject();
 *
 * // Targeted scan (useful in unit tests — no runtime needed)
 * UsageFinder finder = new UsageFinder(myCompilationUnits);
 * }</pre>
 */
public class UsageFinder {

    /** Output logger used by {@link #main} — separate from the class logger so output can be configured independently. */
    private static final Logger out = LoggerFactory.getLogger("UsageFinder.output");
    private String methodSignature;
    private ParsedSignature parsedMethodSignature;

    private final Collection<CompilationUnit> compilationUnits;
    private String className;
    private String simpleClassName;

    public UsageFinder(Collection<CompilationUnit> compilationUnits) {
        this.compilationUnits = compilationUnits == null ? Collections.emptyList() : compilationUnits;
    }

    /** Creates a finder that scans the full parsed project loaded by {@link AntikytheraRunTime}. */
    public static UsageFinder forProject() {
        return new UsageFinder(AntikytheraRunTime.getResolvedCompilationUnits().values());
    }

    /** A collection field found in a non-entity, non-DTO class. */
    public record CollectionFieldUsage(String classFqn, String fieldType, String fieldName) {}

    /**
     * A call site where the target method is invoked.
     *
     * @param callerFqn    fully-qualified name of the class containing the call
     * @param callerMethod simple name of the method containing the call
     * @param lineNumber   1-based line number, or -1 if unknown
     */
    public record MethodUsage(String callerFqn, String callerMethod, int lineNumber) {}

    /**
     * A reference to the target class from another class.
     *
     * @param usingClassFqn fully-qualified name of the class referencing the target
     * @param usageKind     one of: FIELD | PARAMETER | RETURN_TYPE | EXTENDS | IMPLEMENTS
     * @param memberName    field name, method name, or class name depending on {@code usageKind}
     * @param typeName      the type string exactly as written in source
     * @param lineNumber    1-based line number, or -1 if unknown
     */
    public record ClassUsage(String usingClassFqn, String usageKind, String memberName,
                             String typeName, int lineNumber) {}

    @SuppressWarnings("java:S106")
    public static void main(String[] args) throws IOException {
        File yamlFile = new File(Objects.requireNonNull(
                Settings.class.getClassLoader().getResource("depsolver.yml")).getFile());
        Settings.loadConfigMap(yamlFile);
        AbstractCompiler.preProcess();

        UsageFinder finder = UsageFinder.forProject();

        if (args.length == 0) {
            finder.findCollectionFields().forEach(m ->
                    out.info("{} : {} : {}", m.classFqn(), m.fieldType(), m.fieldName()));
        } else if (args[0].contains("#")) {
            List<MethodUsage> usages = finder.findMethodUsages(args[0]);
            out.info("Found {} call site(s) for {}", usages.size(), args[0]);
            usages.forEach(u -> out.info("  {}#{}  (line {})", u.callerFqn(), u.callerMethod(), u.lineNumber()));
        } else {
            List<ClassUsage> usages = finder.findClassUsages(args[0]);
            out.info("Found {} usage(s) of {}", usages.size(), args[0]);
            usages.forEach(u -> out.info("  {}  {}  {} : {}  (line {})",
                    u.usageKind(), u.usingClassFqn(), u.memberName(), u.typeName(), u.lineNumber()));
        }
    }

    /**
     * Returns all collection-type ({@code List}, {@code Set}, {@code Map}) fields
     * in non-{@code @Entity}, non-DTO classes.
     */
    public List<CollectionFieldUsage> findCollectionFields() {
        List<CollectionFieldUsage> matches = new ArrayList<>();
        for (CompilationUnit cu : compilationUnits) {
            if (cu == null) continue;
            cu.getTypes().stream()
                    .filter(TypeDeclaration::isClassOrInterfaceDeclaration)
                    .map(TypeDeclaration::asClassOrInterfaceDeclaration)
                    .filter(c -> c.getAnnotationByName("Entity").isEmpty())
                    .filter(c -> !c.getFullyQualifiedName().orElse("").contains("dto"))
                    .forEach(c -> collectCollectionFields(c, matches));
        }
        return matches;
    }

    /** Convenience wrapper — returns {@link #findCollectionFields()}{@code .size()}. */
    public int countCollectionFields() {
        return findCollectionFields().size();
    }

    /**
     * Returns every call site for the given method in the scanned compilation units.
     *
     * <p>Input: {@code com.example.Foo#doSomething} or just {@code doSomething}.
     * A parameter-list suffix is accepted but ignored.  When a declaring class is
     * present, call-site scope is resolved against field/parameter types to filter
     * out calls on unrelated objects with the same method name.  Unqualified calls
     * (same-class / super) are always included.  Method references ({@code Foo::bar})
     * are reported with a {@code [ref]} suffix on the caller method name.</p>
     */
    public List<MethodUsage> findMethodUsages(String methodSignature) {
        this.methodSignature = methodSignature;
        this.parsedMethodSignature = parseMethodSignature(methodSignature);

        List<MethodUsage> results = new ArrayList<>();

        for (CompilationUnit cu : compilationUnits) {
            for (TypeDeclaration<?> td : cu.getTypes()) {
                if (!td.isClassOrInterfaceDeclaration()) continue;
                ClassOrInterfaceDeclaration cid = td.asClassOrInterfaceDeclaration();
                collectMethodCallsFromClass(cid, results);
            }
        }

        results.sort(Comparator.comparing(MethodUsage::callerFqn).thenComparing(MethodUsage::callerMethod));
        return results;
    }

    /**
     * Returns every reference to {@code classFqn} in fields, constructor/method
     * parameters, method return types, {@code extends}, and {@code implements} clauses.
     * Generic arguments are matched too: a {@code List<Foo>} field is a {@code FIELD}
     * usage of {@code Foo}.
     */
    public List<ClassUsage> findClassUsages(String classFqn) {
        String simpleClass = simpleNameOf(classFqn);
        List<ClassUsage> results = new ArrayList<>();

        for (CompilationUnit cu : compilationUnits) {
            if (cu == null) continue;
            cu.getTypes().stream()
                    .filter(TypeDeclaration::isClassOrInterfaceDeclaration)
                    .map(TypeDeclaration::asClassOrInterfaceDeclaration)
                    .filter(cid -> !cid.getFullyQualifiedName().orElse("").equals(classFqn))
                    .forEach(cid -> collectClassReferences(cid, simpleClass, results));
        }

        results.sort(Comparator.comparing(ClassUsage::usingClassFqn).thenComparing(ClassUsage::usageKind));
        return results;
    }

    private void collectMethodCallsFromClass(ClassOrInterfaceDeclaration cid, List<MethodUsage> results) {
        String callerFqn = cid.getFullyQualifiedName().orElse("");
        Map<String, String> fieldTypes = buildFieldTypeMap(cid);
        for (MethodDeclaration method : cid.getMethods()) {
            Map<String, String> paramTypes = buildParamTypeMap(method);
            collectCallSites(method, parsedMethodSignature, callerFqn, fieldTypes, paramTypes, results);
        }
    }

    private void collectCallSites(MethodDeclaration method,
                                  ParsedSignature sig,
                                  String callerFqn,
                                  Map<String, String> fieldTypes,
                                  Map<String, String> paramTypes,
                                  List<MethodUsage> results) {
        String callerMethod = method.getNameAsString();

        method.findAll(MethodCallExpr.class).forEach(call -> {
            if (!call.getNameAsString().equals(sig.targetMethod())) return;
            Expression scope = call.getScope().orElse(null);
            if (!scopeMatchesClass(scope, sig.targetClass(), fieldTypes, paramTypes)) return;
            int line = call.getBegin().map(p -> p.line).orElse(-1);
            results.add(new MethodUsage(callerFqn, callerMethod, line));
        });

        method.findAll(MethodReferenceExpr.class).forEach(ref -> {
            if (!ref.getIdentifier().equals(sig.targetMethod())) return;
            if (sig.targetClass() != null && !matchesSimpleType(ref.getScope().toString(), sig.targetClass())) return;
            int line = ref.getBegin().map(p -> p.line).orElse(-1);
            results.add(new MethodUsage(callerFqn, callerMethod + "[ref]", line));
        });
    }

    private void collectClassReferences(ClassOrInterfaceDeclaration cid,
                                        String simpleClass,
                                        List<ClassUsage> results) {
        String usingClass = cid.getFullyQualifiedName().orElse("");
        collectInheritanceUsages(cid, simpleClass, usingClass, results);
        collectFieldUsages(cid, simpleClass, usingClass, results);
        collectConstructorParamUsages(cid, simpleClass, usingClass, results);
        collectMethodSignatureUsages(cid, simpleClass, usingClass, results);
    }

    private void collectInheritanceUsages(ClassOrInterfaceDeclaration cid, String simpleClass,
                                          String usingClass, List<ClassUsage> results) {
        cid.getExtendedTypes().forEach(ext -> {
            if (matchesSimpleType(ext.getNameAsString(), simpleClass)) {
                int line = ext.getBegin().map(p -> p.line).orElse(-1);
                results.add(new ClassUsage(usingClass, "EXTENDS", cid.getNameAsString(), ext.getNameAsString(), line));
            }
        });
        cid.getImplementedTypes().forEach(impl -> {
            if (matchesSimpleType(impl.getNameAsString(), simpleClass)) {
                int line = impl.getBegin().map(p -> p.line).orElse(-1);
                results.add(new ClassUsage(usingClass, "IMPLEMENTS", cid.getNameAsString(), impl.getNameAsString(), line));
            }
        });
    }

    private void collectFieldUsages(ClassOrInterfaceDeclaration cid, String simpleClass,
                                    String usingClass, List<ClassUsage> results) {
        cid.getFields().forEach(field -> field.getVariables().forEach(variable -> {
            String typeName = variable.getTypeAsString();
            if (typeContainsClass(typeName, simpleClass)) {
                int line = field.getBegin().map(p -> p.line).orElse(-1);
                results.add(new ClassUsage(usingClass, "FIELD", variable.getNameAsString(), typeName, line));
            }
        }));
    }

    private void collectConstructorParamUsages(ClassOrInterfaceDeclaration cid, String simpleClass,
                                               String usingClass, List<ClassUsage> results) {
        cid.getConstructors().forEach(ctor -> ctor.getParameters().forEach(param -> {
            String paramType = param.getTypeAsString();
            if (typeContainsClass(paramType, simpleClass)) {
                int line = param.getBegin().map(p -> p.line).orElse(-1);
                results.add(new ClassUsage(usingClass, "PARAMETER",
                        cid.getNameAsString() + "(" + param.getNameAsString() + ")", paramType, line));
            }
        }));
    }

    private void collectMethodSignatureUsages(ClassOrInterfaceDeclaration cid, String simpleClass,
                                              String usingClass, List<ClassUsage> results) {
        cid.getMethods().forEach(method -> {
            collectReturnTypeUsage(method, simpleClass, usingClass, results);
            method.getParameters().forEach(param -> {
                String paramType = param.getTypeAsString();
                if (typeContainsClass(paramType, simpleClass)) {
                    int line = param.getBegin().map(p -> p.line).orElse(-1);
                    results.add(new ClassUsage(usingClass, "PARAMETER",
                            method.getNameAsString() + "(" + param.getNameAsString() + ")", paramType, line));
                }
            });
        });
    }

    private void collectReturnTypeUsage(MethodDeclaration method, String simpleClass,
                                        String usingClass, List<ClassUsage> results) {
        String retType = method.getTypeAsString();
        if (typeContainsClass(retType, simpleClass)) {
            int line = method.getBegin().map(p -> p.line).orElse(-1);
            results.add(new ClassUsage(usingClass, "RETURN_TYPE", method.getNameAsString(), retType, line));
        }
    }

    private void collectCollectionFields(ClassOrInterfaceDeclaration cdecl, List<CollectionFieldUsage> matches) {
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

    private record ParsedSignature(String targetClass, String targetMethod) {}

    private ParsedSignature parseMethodSignature(String signature) {
        if (!signature.contains("#")) {
            return new ParsedSignature(null, signature);
        }
        String[] parts = signature.split("#", 2);
        String methodPart = parts[1];
        String targetMethod = methodPart.contains("(")
                ? methodPart.substring(0, methodPart.indexOf('(')) : methodPart;
        return new ParsedSignature(simpleNameOf(parts[0]), targetMethod);
    }

    private static String simpleNameOf(String fqn) {
        return fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
    }

    private static Map<String, String> buildFieldTypeMap(ClassOrInterfaceDeclaration cid) {
        Map<String, String> map = new HashMap<>();
        cid.getFields().forEach(f -> f.getVariables()
                .forEach(v -> map.put(v.getNameAsString(), v.getTypeAsString())));
        return map;
    }

    private static Map<String, String> buildParamTypeMap(MethodDeclaration method) {
        Map<String, String> map = new HashMap<>();
        method.getParameters().forEach(p -> map.put(p.getNameAsString(), p.getTypeAsString()));
        return map;
    }

    private static boolean isCollectionType(String type) {
        return type.contains("List<") || type.contains("List ")
                || type.contains("Set<")  || type.contains("Set ")
                || type.contains("Map<")  || type.contains("Map ");
    }

    /**
     * Returns {@code true} if the raw (non-generic) part of {@code typeStr} equals
     * {@code simpleClass}. E.g. {@code "UserService<T>"} matches {@code "UserService"}.
     */
    private static boolean matchesSimpleType(String typeStr, String simpleClass) {
        if (typeStr == null || simpleClass == null) return false;
        String raw = typeStr.contains("<") ? typeStr.substring(0, typeStr.indexOf('<')) : typeStr;
        return simpleNameOf(raw).equals(simpleClass);
    }

    /**
     * Returns {@code true} if {@code typeStr} references {@code simpleClass} directly
     * ({@code Foo}, {@code Foo<Bar>}) or as a generic argument ({@code List<Foo>},
     * {@code Map<String, Foo>}).
     */
    private static boolean typeContainsClass(String typeStr, String simpleClass) {
        if (typeStr == null || simpleClass == null) return false;
        if (matchesSimpleType(typeStr, simpleClass)) return true;
        int lt = typeStr.indexOf('<');
        if (lt < 0) return false;
        int gt = typeStr.lastIndexOf('>');
        String inner = typeStr.substring(lt + 1, gt < 0 ? typeStr.length() : gt);
        for (String part : inner.split("[,<>]")) {
            if (matchesSimpleType(part.trim(), simpleClass)) return true;
        }
        return false;
    }

    /**
     * Returns {@code true} when the call-site scope matches the target class.
     * A {@code null} scope means the call is unqualified (same class/super), which always passes.
     *
     * @param scope       scope expression of the call site, or {@code null} when absent
     * @param targetClass simple name of the declaring class, or {@code null} to match all
     */
    private static boolean scopeMatchesClass(Expression scope, String targetClass,
                                             Map<String, String> fieldTypes,
                                             Map<String, String> paramTypes) {
        if (targetClass == null || scope == null) return true;
        String scopeStr = scope.toString();
        String resolved = fieldTypes.getOrDefault(scopeStr, paramTypes.getOrDefault(scopeStr, scopeStr));
        return matchesSimpleType(resolved, targetClass);
    }
}
