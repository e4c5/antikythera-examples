package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
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
    /**
     * Stores the known field dependencies of any class
     * Storage is: repository class name → (dependent class name → collection of
     * field
     * names)
     * This handles cases where a class has multiple fields of the same repository
     * type.
     */
    protected static final Map<String, Map<String, Set<String>>> fieldDependencies = new HashMap<>();

    private Fields() {
    }

    public static void buildDependencies() {
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
}
