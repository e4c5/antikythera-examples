package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;
import java.util.stream.Collectors;

/**
 * Tool to find all classes and methods annotated with a specific annotation.
 * Handles both simple names (e.g., "Service") and fully qualified names (e.g., "org.springframework.stereotype.Service").
 * 
 * <p>Usage:
 * <pre>
 * mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.AnnotationFinder" \
 *   -Dexec.args="Service"
 * </pre>
 * 
 * <p>Output format:
 * <ul>
 *   <li>For class annotations: Fully qualified class name (e.g., com.example.MyService)</li>
 *   <li>For method annotations: Fully qualified class name#method signature 
 *       (e.g., com.example.MyClass#print() or com.example.MyClass#print(String s))</li>
 * </ul>
 */
@SuppressWarnings("java:S106")
public class AnnotationFinder {

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("Usage: AnnotationFinder <annotation-name>");
            System.err.println("Example: AnnotationFinder Service");
            System.err.println("Example: AnnotationFinder org.springframework.stereotype.Service");
            System.exit(1);
        }

        String searchTerm = args[0];
        String simpleName = extractSimpleName(searchTerm);
        
        File yamlFile = new File(Settings.class.getClassLoader().getResource("depsolver.yml").getFile());
        Settings.loadConfigMap(yamlFile);
        AbstractCompiler.preProcess();

        AntikytheraRunTime.getResolvedCompilationUnits().forEach((cls, cu) -> 
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(classDecl -> {
                String className = classDecl.getFullyQualifiedName().orElse(classDecl.getNameAsString());
                
                if (hasAnnotation(classDecl.getAnnotations(), searchTerm, simpleName)) {
                    System.out.println(className);
                }
                
                classDecl.getMethods().forEach(method -> {
                    if (hasAnnotation(method.getAnnotations(), searchTerm, simpleName)) {
                        System.out.println(className + "#" + buildMethodSignature(method));
                    }
                });
            })
        );
    }

    /**
     * Checks if any annotation matches the search term (handles both simple and fully qualified names).
     */
    static boolean hasAnnotation(java.util.List<AnnotationExpr> annotations, String searchTerm, String simpleName) {
        return annotations.stream().anyMatch(ann -> matchesAnnotation(ann.getNameAsString(), searchTerm, simpleName));
    }

    /**
     * Checks if an annotation name matches the search term.
     * Matches if: exact match, fully qualified match, or simple name match.
     */
    static boolean matchesAnnotation(String annotationName, String searchTerm, String simpleName) {
        return annotationName.equals(searchTerm) 
            || annotationName.equals(simpleName)
            || annotationName.endsWith("." + searchTerm)
            || annotationName.endsWith("." + simpleName);
    }

    /**
     * Extracts the simple name from a fully qualified name.
     */
    static String extractSimpleName(String name) {
        int lastDot = name.lastIndexOf('.');
        return lastDot >= 0 ? name.substring(lastDot + 1) : name;
    }

    /**
     * Builds a method signature: methodName(ParamType1 param1, ParamType2 param2)
     */
    static String buildMethodSignature(MethodDeclaration method) {
        String params = method.getParameters().stream()
                .map(p -> p.getType().asString() + " " + p.getNameAsString())
                .collect(Collectors.joining(", "));
        return method.getNameAsString() + "(" + params + ")";
    }
}

