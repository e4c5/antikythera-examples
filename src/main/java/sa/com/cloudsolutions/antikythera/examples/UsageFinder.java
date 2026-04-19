package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
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
    private ParsedSignature parsedMethodSignature;

    private final Collection<CompilationUnit> compilationUnits;
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
            for (TypeDeclaration<?> type : findAllTypes(cu)) {
                if (type.getAnnotationByName("Entity").isPresent()) continue;
                if (type.getFullyQualifiedName().orElse("").contains("dto")) continue;
                collectCollectionFields(type, matches);
            }
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
        this.parsedMethodSignature = parseMethodSignature(methodSignature);

        List<MethodUsage> results = new ArrayList<>();

        for (CompilationUnit cu : compilationUnits) {
            findAllTypes(cu).forEach(type -> collectMethodCallsFromType(type, results));
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
        this.simpleClassName = AbstractCompiler.fullyQualifiedToShortName(classFqn);

        List<ClassUsage> results = new ArrayList<>();

        for (CompilationUnit cu : compilationUnits) {
            findAllTypes(cu).stream()
                    .filter(type -> !type.getFullyQualifiedName().orElse("").equals(classFqn))
                    .forEach(type -> collectClassReferences(type, results));
        }

        results.sort(Comparator.comparing(ClassUsage::usingClassFqn).thenComparing(ClassUsage::usageKind));
        return results;
    }

    private void collectMethodCallsFromType(TypeDeclaration<?> type, List<MethodUsage> results) {
        String callerFqn = type.getFullyQualifiedName().orElse("");
        Map<String, String> fieldTypes = buildFieldTypeMap(type);
        type.getMethods().forEach(method ->
                collectCallSites(method, method.getNameAsString(), callerFqn, fieldTypes, buildParamTypeMap(method), results));
        type.getConstructors().forEach(ctor ->
                collectCallSites(ctor, ctor.getNameAsString(), callerFqn, fieldTypes, buildParamTypeMap(ctor), results));
        type.getMembers().stream()
                .filter(InitializerDeclaration.class::isInstance)
                .map(InitializerDeclaration.class::cast)
                .forEach(initializer -> collectCallSites(initializer,
                        initializer.isStatic() ? "<clinit>" : "<init>",
                        callerFqn, fieldTypes, Collections.emptyMap(), results));
    }

    private void collectCallSites(Node searchRoot,
                                  String callerMethod,
                                  String callerFqn,
                                  Map<String, String> fieldTypes,
                                  Map<String, String> paramTypes,
                                  List<MethodUsage> results) {
        Map<String, String> localTypes = buildLocalTypeMap(searchRoot);

        searchRoot.findAll(MethodCallExpr.class).forEach(call -> {
            if (!call.getNameAsString().equals(parsedMethodSignature.targetMethod())) return;
            Expression scope = call.getScope().orElse(null);
            if (!scopeMatchesClass(scope, parsedMethodSignature.targetClass(), callerFqn, fieldTypes, paramTypes, localTypes)) return;
            int line = call.getBegin().map(p -> p.line).orElse(-1);
            results.add(new MethodUsage(callerFqn, callerMethod, line));
        });

        searchRoot.findAll(MethodReferenceExpr.class).forEach(ref -> {
            if (!ref.getIdentifier().equals(parsedMethodSignature.targetMethod())) return;
            if (parsedMethodSignature.targetClass() != null && !matchesSimpleType(ref.getScope().toString(), parsedMethodSignature.targetClass())) return;
            int line = ref.getBegin().map(p -> p.line).orElse(-1);
            results.add(new MethodUsage(callerFqn, callerMethod + "[ref]", line));
        });
    }

    private void collectClassReferences(TypeDeclaration<?> type, List<ClassUsage> results) {
        String usingClass = type.getFullyQualifiedName().orElse("");
        collectInheritanceUsages(type, usingClass, results);
        collectFieldUsages(type, usingClass, results);
        collectConstructorParamUsages(type, usingClass, results);
        collectMethodSignatureUsages(type, usingClass, results);
    }

    private void collectInheritanceUsages(TypeDeclaration<?> type,
                                          String usingClass, List<ClassUsage> results) {
        if (type instanceof ClassOrInterfaceDeclaration cid) {
            cid.getExtendedTypes().forEach(ext -> {
                if (matchesSimpleType(ext.getNameAsString(), simpleClassName)) {
                    int line = ext.getBegin().map(p -> p.line).orElse(-1);
                    results.add(new ClassUsage(usingClass, "EXTENDS", cid.getNameAsString(), ext.getNameAsString(), line));
                }
            });
            cid.getImplementedTypes().forEach(impl -> {
                if (matchesSimpleType(impl.getNameAsString(), simpleClassName)) {
                    int line = impl.getBegin().map(p -> p.line).orElse(-1);
                    results.add(new ClassUsage(usingClass, "IMPLEMENTS", cid.getNameAsString(), impl.getNameAsString(), line));
                }
            });
            return;
        }
        if (type instanceof EnumDeclaration enumDeclaration) {
            enumDeclaration.getImplementedTypes().forEach(impl -> {
                if (matchesSimpleType(impl.getNameAsString(), simpleClassName)) {
                    int line = impl.getBegin().map(p -> p.line).orElse(-1);
                    results.add(new ClassUsage(usingClass, "IMPLEMENTS", enumDeclaration.getNameAsString(), impl.getNameAsString(), line));
                }
            });
            return;
        }
        if (type instanceof RecordDeclaration recordDeclaration) {
            recordDeclaration.getImplementedTypes().forEach(impl -> {
                if (matchesSimpleType(impl.getNameAsString(), simpleClassName)) {
                    int line = impl.getBegin().map(p -> p.line).orElse(-1);
                    results.add(new ClassUsage(usingClass, "IMPLEMENTS", recordDeclaration.getNameAsString(), impl.getNameAsString(), line));
                }
            });
        }
    }

    private void collectFieldUsages(TypeDeclaration<?> type,
                                    String usingClass, List<ClassUsage> results) {
        type.getFields().forEach(field -> field.getVariables().forEach(variable -> {
            String typeName = variable.getTypeAsString();
            if (typeContainsClass(typeName, simpleClassName)) {
                int line = field.getBegin().map(p -> p.line).orElse(-1);
                results.add(new ClassUsage(usingClass, "FIELD", variable.getNameAsString(), typeName, line));
            }
        }));
    }

    private void collectConstructorParamUsages(TypeDeclaration<?> type,
                                               String usingClass, List<ClassUsage> results) {
        type.getConstructors().forEach(ctor -> findParamUsage(ctor.getParameters(), results, usingClass, type.getNameAsString()));
    }

    private void findParamUsage(NodeList<Parameter> ctor, List<ClassUsage> results, String usingClass, String cid) {
        ctor.forEach(param -> {
            String paramType = param.getTypeAsString();
            if (typeContainsClass(paramType, simpleClassName)) {
                int line = param.getBegin().map(p -> p.line).orElse(-1);
                results.add(new ClassUsage(usingClass, "PARAMETER",
                        cid + "(" + param.getNameAsString() + ")", paramType, line));
            }
        });
    }

    private void collectMethodSignatureUsages(TypeDeclaration<?> type,
                                              String usingClass, List<ClassUsage> results) {
        type.getMethods().forEach(method -> {
            collectReturnTypeUsage(method, simpleClassName, usingClass, results);
            findParamUsage(method.getParameters(), results, usingClass, method.getNameAsString());
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

    private void collectCollectionFields(TypeDeclaration<?> cdecl, List<CollectionFieldUsage> matches) {
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
        return new ParsedSignature(AbstractCompiler.fullyQualifiedToShortName(parts[0]), targetMethod);
    }


    private static Map<String, String> buildFieldTypeMap(TypeDeclaration<?> type) {
        Map<String, String> map = new HashMap<>();
        type.getFields().forEach(f -> f.getVariables()
                .forEach(v -> map.put(v.getNameAsString(), v.getTypeAsString())));
        return map;
    }

    private static Map<String, String> buildParamTypeMap(com.github.javaparser.ast.nodeTypes.NodeWithParameters<?> callable) {
        Map<String, String> map = new HashMap<>();
        callable.getParameters().forEach(p -> map.put(p.getNameAsString(), p.getTypeAsString()));
        return map;
    }

    private static Map<String, String> buildLocalTypeMap(Node node) {
        Map<String, String> map = new HashMap<>();
        node.findAll(VariableDeclarator.class).forEach(v -> map.put(v.getNameAsString(), v.getTypeAsString()));
        return map;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<TypeDeclaration<?>> findAllTypes(CompilationUnit cu) {
        return (List<TypeDeclaration<?>>) (List<?>) cu.findAll(TypeDeclaration.class);
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
        while (raw.endsWith("[]")) {
            raw = raw.substring(0, raw.length() - 2);
        }
        return AbstractCompiler.fullyQualifiedToShortName(raw.trim()).equals(simpleClass);
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
    private static boolean scopeMatchesClass(Expression scope, String targetClass, String callerFqn,
                                             Map<String, String> fieldTypes,
                                             Map<String, String> paramTypes,
                                             Map<String, String> localTypes) {
        if (targetClass == null || scope == null) return true;
        String scopeStr = scope.toString();
        if ("this".equals(scopeStr)) {
            return matchesSimpleType(callerFqn, targetClass);
        }
        if (scopeStr.startsWith("this.")) {
            scopeStr = scopeStr.substring("this.".length());
        }
        String resolved = localTypes.getOrDefault(scopeStr,
                paramTypes.getOrDefault(scopeStr, fieldTypes.getOrDefault(scopeStr, scopeStr)));
        return matchesSimpleType(resolved, targetClass);
    }
}
