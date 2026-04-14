package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

public final class UsageFinder {

    private UsageFinder() {
    }

    public record CollectionFieldUsage(String classFqn, String fieldType, String fieldName) {
    }

    public static void main(String[] args) throws IOException {
        File yamlFile = new File(Objects.requireNonNull(Settings.class.getClassLoader()
                .getResource("depsolver.yml")).getFile());
        Settings.loadConfigMap(yamlFile);
        AbstractCompiler.preProcess();

        findCollectionFields()
                .forEach(match -> System.out.println(match.classFqn() + " : "
                        + match.fieldType() + " : " + match.fieldName()));
    }

    public static List<CollectionFieldUsage> findCollectionFields() {
        return findCollectionFields(AntikytheraRunTime.getResolvedCompilationUnits().values());
    }

    public static List<CollectionFieldUsage> findCollectionFields(Collection<CompilationUnit> compilationUnits) {
        List<CollectionFieldUsage> matches = new ArrayList<>();

        if (compilationUnits == null) {
            return matches;
        }

        for (CompilationUnit cu : compilationUnits) {
            if (cu == null) {
                continue;
            }

            cu.getTypes().stream()
                    .filter(TypeDeclaration::isClassOrInterfaceDeclaration)
                    .map(TypeDeclaration::asClassOrInterfaceDeclaration)
                    .filter(cdecl -> cdecl.getAnnotationByName("Entity").isEmpty())
                    .filter(cdecl -> !cdecl.getFullyQualifiedName().orElse("").contains("dto"))
                    .forEach(cdecl -> collectCollectionFieldUsages(cdecl, matches));
        }

        return matches;
    }

    public static int countCollectionFields(Collection<CompilationUnit> compilationUnits) {
        return findCollectionFields(compilationUnits).size();
    }

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

    /**
     * Checks if a type string represents a collection type (List, Set, Map).
     * Consolidates the collection detection logic into a single method.
     */
    private static boolean isCollectionType(String type) {
        return type.contains("List<") || type.contains("List ") || 
               type.contains("Set<") || type.contains("Set ") || 
               type.contains("Map<") || type.contains("Map ");
    }
}
