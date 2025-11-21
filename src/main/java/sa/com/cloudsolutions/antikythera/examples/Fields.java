package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.generator.TypeWrapper;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Fields {
    /**
     * Stores the known field dependencies of any class
     * Storage is: repository class name → (dependent class name → list of field
     * names)
     * This handles cases where a class has multiple fields of the same repository
     * type.
     */
    protected static final Map<String, Map<String, List<String>>> fieldDependencies = new HashMap<>();

    private Fields() {
    }

    public static void buildDependencies() {
        for (TypeWrapper type : AntikytheraRunTime.getResolvedTypes().values()) {
            String name = type.getFullyQualifiedName();
            for (FieldDeclaration fd : type.getType().getFields()) {
                // Process ALL variables in the field declaration, not just the first one
                // This handles cases like: private UserRepository repo1, repo2, repo3;
                fd.getVariables().forEach(variable -> {
                    Type fieldType = variable.getType();
                    if (fieldType instanceof ClassOrInterfaceType ct) {
                        TypeWrapper tw = AbstractCompiler.findType(AntikytheraRunTime.getCompilationUnit(name), ct);
                        if (tw != null) {
                            Map<String, List<String>> dependentClasses = fieldDependencies.computeIfAbsent(
                                    tw.getFullyQualifiedName(), k -> new HashMap<>());
                            List<String> fieldNames = dependentClasses.computeIfAbsent(name, k -> new ArrayList<>());
                            fieldNames.add(variable.getNameAsString());
                        }
                    }
                });
            }
        }
    }

    public static Map<String, List<String>> getFieldDependencies(String name) {
        return fieldDependencies.get(name);
    }
}
