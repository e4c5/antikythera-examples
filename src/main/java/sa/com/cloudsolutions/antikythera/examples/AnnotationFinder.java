package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Tool to find all classes and methods annotated with a specific annotation.
 * Handles both simple names (e.g., "Service") and fully qualified names (e.g., "org.springframework.stereotype.Service").
 * 
 * <p>Usage:
 * <pre>
 * mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.AnnotationFinder" \
 *   -Dexec.args="Service"
 * 
 * # Simple output mode (method name only, no duplicates)
 * mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.examples.AnnotationFinder" \
 *   -Dexec.args="Service --simple"
 * </pre>
 * 
 * <p>Output format:
 * <ul>
 *   <li>For class annotations: Fully qualified class name (e.g., com.example.MyService)</li>
 *   <li>For method annotations (detailed mode): Fully qualified class name#method signature 
 *       (e.g., com.example.MyClass#print() or com.example.MyClass#print(String s))</li>
 *   <li>For method annotations (simple mode): Fully qualified class name#method name
 *       (e.g., com.example.MyClass#print) - duplicates eliminated</li>
 * </ul>
 */
@SuppressWarnings("java:S106")
public class AnnotationFinder {

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("Usage: AnnotationFinder <annotation-name> [--simple|-s]");
            System.err.println("Example: AnnotationFinder Service");
            System.err.println("Example: AnnotationFinder org.springframework.stereotype.Service --simple");
            System.err.println("");
            System.err.println("Options:");
            System.err.println("  --simple, -s    Simple output mode: method name only (no parameters), duplicates eliminated");
            System.exit(1);
        }

        String searchTerm = null;
        boolean simpleMode = false;
        
        // Parse arguments
        for (String arg : args) {
            if (arg.equals("--simple") || arg.equals("-s")) {
                simpleMode = true;
            } else if (!arg.startsWith("-") && searchTerm == null) {
                searchTerm = arg;
            }
        }
        
        if (searchTerm == null) {
            System.err.println("Error: Annotation name is required");
            System.exit(1);
        }

        final String finalSearchTerm = searchTerm;
        final String simpleName = extractSimpleName(finalSearchTerm);
        final boolean isSimpleMode = simpleMode;
        
        File yamlFile = new File(Settings.class.getClassLoader().getResource("graph.yml").getFile());
        Settings.loadConfigMap(yamlFile);
        AbstractCompiler.preProcess();

        final Set<String> seen = isSimpleMode ? new HashSet<>() : null;

        AntikytheraRunTime.getResolvedCompilationUnits().forEach((cls, cu) -> 
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(classDecl -> {
                final String className = classDecl.getFullyQualifiedName().orElse(classDecl.getNameAsString());
                
                if (hasAnnotation(classDecl.getAnnotations(), finalSearchTerm, simpleName)) {
                    System.out.println(className);
                }
                
                classDecl.getMethods().forEach(method -> {
                    if (hasAnnotation(method.getAnnotations(), finalSearchTerm, simpleName)) {
                        if (isSimpleMode && seen != null) {
                            final String output = className + "#" + method.getNameAsString();
                            if (seen.add(output)) {
                                System.out.println(output);
                            }
                        } else {
                            final String output = className + "#" + buildMethodSignature(method);
                            System.out.println(output);
                        }
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

