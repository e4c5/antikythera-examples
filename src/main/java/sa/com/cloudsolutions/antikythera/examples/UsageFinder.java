package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.ast.body.TypeDeclaration;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;

public class UsageFinder {

    public static void main(String[] args) throws IOException {
        File yamlFile = new File(Settings.class.getClassLoader().getResource("depsolver.yml").getFile());
        Settings.loadConfigMap(yamlFile);
        AbstractCompiler.preProcess();

        AntikytheraRunTime.getResolvedCompilationUnits().forEach((cls, cu) -> {
            cu.getTypes().stream()
                .filter(TypeDeclaration::isClassOrInterfaceDeclaration)
                .map(TypeDeclaration::asClassOrInterfaceDeclaration)
                .filter(cdecl -> cdecl.getAnnotationByName("Entity").isEmpty())
                .filter(cdecl -> !cdecl.getFullyQualifiedName().orElse("").contains("dto"))
                .forEach(cdecl -> cdecl.getFields().stream()
                    .filter(fd -> isCollectionType(fd.getVariable(0).getTypeAsString()))
                    .forEach(fd -> System.out.println(cdecl.getFullyQualifiedName().get() + " : " 
                        + fd.getVariable(0).getTypeAsString() + " : " + fd.getVariable(0).getNameAsString())));
        });
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
