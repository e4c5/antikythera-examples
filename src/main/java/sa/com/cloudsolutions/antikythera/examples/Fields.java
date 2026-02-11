package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.generator.TypeWrapper;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Fields {
    private static final Logger logger = LoggerFactory.getLogger(Fields.class);

    /**
     * Stores the known field dependencies of any class
     * Storage is: repository class name → (dependent class name → collection of
     * field
     * names)
     * This handles cases where a class has multiple fields of the same repository
     * type.
     */
    protected static final Map<String, Map<String, Set<String>>> fieldDependencies = new HashMap<>();

    /**
     * Method call index for fast lookup of which classes call which repository methods.
     * Storage is: repositoryFQN → methodName → Set of CallerInfo (class + field name)
     * This enables O(callers) instead of O(all_dependent_classes) when renaming methods.
     */
    protected static final Map<String, Map<String, Set<CallerInfo>>> methodCallIndex = new HashMap<>();

    /**
     * Records information about a caller of a repository method.
     */
    public record CallerInfo(String callerClass, String fieldName) {}

    private Fields() {
    }

    public static void buildDependencies() {
        // Phase 1: Build field dependencies (existing logic)
        for (TypeWrapper type : AntikytheraRunTime.getResolvedTypes().values()) {
            String name = type.getFullyQualifiedName();
            for (FieldDeclaration fd : type.getType().getFields()) {
                // Process ALL variables in the field declaration, not just the first one

                fd.getVariables().forEach(variable -> {
                    Type fieldType = variable.getType();
                    if (fieldType instanceof ClassOrInterfaceType ct) {
                        TypeWrapper tw = AbstractCompiler.findType(AntikytheraRunTime.getCompilationUnit(name), ct);
                        if (tw != null) {
                            Map<String, Set<String>> dependentClasses = fieldDependencies.computeIfAbsent(
                                    tw.getFullyQualifiedName(), k -> new HashMap<>());
                            Set<String> fieldNames = dependentClasses.computeIfAbsent(name, k -> new HashSet<>());
                            fieldNames.add(variable.getNameAsString());
                        }
                    }
                });
            }
        }
        propagateInheritedFields();

        // Phase 2: Build method call index for each repository
        buildMethodCallIndex();
    }

    private static void propagateInheritedFields() {
        for (Map<String, Set<String>> dependentClasses : fieldDependencies.values()) {
            List<String> parentClasses = new ArrayList<>(dependentClasses.keySet());
            for (String parentClass : parentClasses) {
                Set<String> fieldNames = dependentClasses.get(parentClass);
                propagateToSubclasses(dependentClasses, parentClass, fieldNames);
            }
        }
    }

    private static void propagateToSubclasses(Map<String, Set<String>> dependentClasses, String parentClass,
            Set<String> fieldNames) {
        for (String subClass : AntikytheraRunTime.findSubClasses(parentClass)) {
            Set<String> subClassFields = dependentClasses.computeIfAbsent(subClass, k -> new HashSet<>());
            boolean added = false;
            for (String fieldName : fieldNames) {
                if (subClassFields.add(fieldName)) {
                    added = true;
                }
            }
            if (added) {
                propagateToSubclasses(dependentClasses, subClass, fieldNames);
            }
        }
    }

    public static Map<String, Set<String>> getFieldDependencies(String name) {
        return fieldDependencies.get(name);
    }

    /**
     * Clears all field dependency mappings. Useful for testing to reset state between test runs.
     */
    public static void clearFieldDependencies() {
        fieldDependencies.clear();
        methodCallIndex.clear();
    }

    /**
     * Builds an index of which classes call which repository methods.
     * This enables fast lookup when renaming methods - only classes that actually
     * call the method need to be processed.
     */
    private static void buildMethodCallIndex() {
        long startTime = System.currentTimeMillis();
        int totalMethodsIndexed = 0;
        int totalCallsIndexed = 0;

        // For each repository that has dependents
        for (Map.Entry<String, Map<String, Set<String>>> repoEntry : fieldDependencies.entrySet()) {
            String repositoryFqn = repoEntry.getKey();
            Map<String, Set<String>> dependentClasses = repoEntry.getValue();

            // For each class that has a field of this repository type
            for (Map.Entry<String, Set<String>> classEntry : dependentClasses.entrySet()) {
                String callerClass = classEntry.getKey();
                Set<String> fieldNames = classEntry.getValue();

                CompilationUnit cu = AntikytheraRunTime.getCompilationUnit(callerClass);
                if (cu == null) {
                    continue;
                }

                // Find all method calls and method references in this compilation unit
                indexMethodCalls(cu, repositoryFqn, callerClass, fieldNames);
                indexMethodReferences(cu, repositoryFqn, callerClass, fieldNames);
            }
        }

        // Calculate statistics
        for (Map<String, Set<CallerInfo>> repoIndex : methodCallIndex.values()) {
            totalMethodsIndexed += repoIndex.size();
            for (Set<CallerInfo> callers : repoIndex.values()) {
                totalCallsIndexed += callers.size();
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        logger.info("Method call index built in {}ms: {} repositories, {} methods, {} call sites",
                elapsed, methodCallIndex.size(), totalMethodsIndexed, totalCallsIndexed);
    }

    /**
     * Indexes method calls (e.g., repository.findByEmail(...)) in a compilation unit.
     */
    private static void indexMethodCalls(CompilationUnit cu, String repositoryFqn,
            String callerClass, Set<String> fieldNames) {
        cu.findAll(MethodCallExpr.class).forEach(mce ->
            indexMethodCall(repositoryFqn, callerClass, fieldNames, mce)
        );
    }

    private static void indexMethodCall(String repositoryFqn, String callerClass, Set<String> fieldNames, MethodCallExpr mce) {
        mce.getScope().ifPresent(scope -> {
            String matchedField = findMatchingField(scope, fieldNames);
            if (matchedField != null) {
                String methodName = mce.getNameAsString();
                addToMethodCallIndex(repositoryFqn, methodName, callerClass, matchedField);
            }

            // Also check Mockito patterns: verify(field).methodName() and
            // doReturn(val).when(field).methodName()
            if (scope instanceof MethodCallExpr mockitoCall &&
                    isMockitoStubbingOrVerify(mockitoCall) &&
                    !mockitoCall.getArguments().isEmpty()) {
                String mockitoField = findMatchingField(mockitoCall.getArgument(0), fieldNames);
                if (mockitoField != null) {
                    String methodName = mce.getNameAsString();
                    addToMethodCallIndex(repositoryFqn, methodName, callerClass, mockitoField);
                }
            }
        });
    }

    /**
     * Indexes method references (e.g., repository::findByEmail) in a compilation unit.
     */
    private static void indexMethodReferences(CompilationUnit cu, String repositoryFqn,
            String callerClass, Set<String> fieldNames) {
        cu.findAll(MethodReferenceExpr.class).forEach(mre -> {
            Expression scope = mre.getScope();
            String matchedField = findMatchingField(scope, fieldNames);
            if (matchedField != null) {
                String methodName = mre.getIdentifier();
                addToMethodCallIndex(repositoryFqn, methodName, callerClass, matchedField);
            }
        });
    }

    /**
     * Checks if an expression matches one of the field names.
     * Returns the matched field name or null.
     */
    private static String findMatchingField(Expression expr, Set<String> fieldNames) {
        if (expr instanceof NameExpr ne) {
            String name = ne.getNameAsString();
            if (fieldNames.contains(name)) {
                return name;
            }
        } else {
            if (expr instanceof FieldAccessExpr fae) {
                String name = fae.getNameAsString();
                if (fieldNames.contains(name)  && fae.getScope().isThisExpr()) {
                    return name;
                }
            }
        }
        return null;
    }

    /**
     * Checks if a method call is a Mockito stubbing or verification call
     * (i.e., {@code verify(field)} or {@code when(field)}).
     * Used to detect patterns like {@code verify(repo).method()} and
     * {@code doReturn(val).when(repo).method()}.
     */
    static boolean isMockitoStubbingOrVerify(MethodCallExpr call) {
        String name = call.getNameAsString();
        return "verify".equals(name) || "when".equals(name);
    }

    /**
     * Adds an entry to the method call index.
     */
    private static void addToMethodCallIndex(String repositoryFqn, String methodName,
            String callerClass, String fieldName) {
        methodCallIndex
                .computeIfAbsent(repositoryFqn, k -> new HashMap<>())
                .computeIfAbsent(methodName, k -> new HashSet<>())
                .add(new CallerInfo(callerClass, fieldName));
    }

    /**
     * Gets the set of classes that call a specific method on a repository.
     * Returns empty set if no callers found.
     *
     * @param repositoryFqn the fully qualified name of the repository
     * @param methodName the method name to look up
     * @return set of CallerInfo for classes that call this method
     */
    public static Set<CallerInfo> getMethodCallers(String repositoryFqn, String methodName) {
        Map<String, Set<CallerInfo>> repoIndex = methodCallIndex.get(repositoryFqn);
        if (repoIndex == null) {
            return Set.of();
        }
        Set<CallerInfo> callers = repoIndex.get(methodName);
        return callers != null ? callers : Set.of();
    }

    /**
     * Gets all method names that have been indexed for a repository.
     * Useful for debugging and statistics.
     *
     * @param repositoryFqn the fully qualified name of the repository
     * @return set of method names that have callers indexed
     */
    public static Set<String> getIndexedMethodNames(String repositoryFqn) {
        Map<String, Set<CallerInfo>> repoIndex = methodCallIndex.get(repositoryFqn);
        return repoIndex != null ? repoIndex.keySet() : Set.of();
    }
}
