package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.generator.TypeWrapper;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.util.HashMap;
import java.util.Map;

public class Fields {
    /**
     * Stores the known field dependencies of any class
     * Storage is class name -> (other class name, field name)
     */
    protected static final Map<String, Map<String, String>> fieldDependencies = new HashMap<>();

    private Fields() {}

    public static void buildDependencies() {
        for (TypeWrapper type : AntikytheraRunTime.getResolvedTypes().values()) {
            String name = type.getFullyQualifiedName();
            for (FieldDeclaration fd : type.getType().getFields()) {
                Type fieldType = fd.getVariable(0).getType();
                if (fieldType instanceof ClassOrInterfaceType ct) {
                    TypeWrapper tw = AbstractCompiler.findType(AntikytheraRunTime.getCompilationUnit(name), ct);
                    if (tw != null) {
                        Map<String, String> f = fieldDependencies.computeIfAbsent(tw.getFullyQualifiedName(), k -> new HashMap<>());
                        f.put(name, fd.getVariable(0).getNameAsString());
                    }
                }
            }
        }
    }

    public static Map<String, String> getFieldDependencies(String name) {
        return fieldDependencies.get(name);
    }
}
