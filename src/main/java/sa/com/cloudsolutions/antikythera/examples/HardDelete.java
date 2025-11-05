package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import sa.com.cloudsolutions.antikythera.examples.util.RepositoryAnalyzer;
import sa.com.cloudsolutions.antikythera.generator.TypeWrapper;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@SuppressWarnings("java:S106")
public class HardDelete {
    static TypeDeclaration<?> current;
    // Map repository variable name to its type
    private static final Map<String, String> repoVars = new HashMap<>();
    private static String currentMethod;

    public static class FieldVisitor extends VoidVisitorAdapter<CompilationUnit> {
        @Override
        public void visit(FieldDeclaration field, CompilationUnit cu) {
            super.visit(field, cu);
            VariableDeclarator vdecl = field.getVariable(0);
            TypeWrapper wrapper = AbstractCompiler.findType(cu, vdecl.getTypeAsString());
            if (wrapper != null && RepositoryAnalyzer.isJpaRepository(wrapper)) {
                repoVars.put(vdecl.getNameAsString(), wrapper.getFullyQualifiedName());
            }
        }
    }

    /**
     * Visitor to detect hard delete method calls on JPARepository instances.
     * If a delete method has a custom annotation, it's not considered a hard delete.
     */
    public static class HardDeleteVisitor extends VoidVisitorAdapter<CompilationUnit> {
        private static final String SOFT_DELETE_ANNOTATION = "Query";

        @Override
        public void visit(MethodCallExpr mce, CompilationUnit cu) {
            super.visit(mce, cu);

            if (!mce.getNameAsString().toLowerCase().contains("delete")) return;

            mce.getScope().ifPresent(scope -> {
                String varName = scope.toString();
                if (repoVars.containsKey(varName) && !hasSoftDeleteAnnotation(mce, cu, varName)) {
                    String className = current.asClassOrInterfaceDeclaration().getFullyQualifiedName().orElseThrow();
                    System.out.println(className + "," + currentMethod
                            + "," + mce );
                }
            });
        }


        private boolean hasSoftDeleteAnnotation(MethodCallExpr mce, CompilationUnit cu, String repoVar) {
            // Try to resolve the repository type and method, then check for custom annotation
            Optional<TypeDeclaration<?>> repoType = cu.getTypes().stream()
                .filter(td -> td.getMembers().stream()
                    .anyMatch(m -> m instanceof FieldDeclaration field &&
                        field.getVariables().stream()
                            .anyMatch(v -> v.getNameAsString().equals(repoVar))))
                .findFirst();

            if (repoType.isPresent()) {
                List<MethodDeclaration> methods = repoType.get().getMethodsByName(mce.getNameAsString());
                for (MethodDeclaration method : methods) {
                    // Use RepositoryAnalyzer utility to check for Query annotation
                    if (RepositoryAnalyzer.hasQueryAnnotation(method)) {
                        System.err.println(currentMethod + "," + mce + " is a soft delete" );
                        return true;
                    }
                    // Also check for other query-related annotations that might indicate soft delete
                    for (AnnotationExpr ann : method.getAnnotations()) {
                        if (RepositoryAnalyzer.isQueryRelatedAnnotation(ann.getNameAsString())) {
                            System.err.println(currentMethod + "," + mce + " is a soft delete" );
                            return true;
                        }
                    }
                }
            }
            // Could not find annotation, assume hard delete
            return false;
        }
    }

    public static class MethodVisitor extends VoidVisitorAdapter<CompilationUnit> {
        @Override
        public void visit(MethodDeclaration md, CompilationUnit cu) {
            super.visit(md, cu);
            setCurrentMethod(md.getNameAsString());
            md.accept(new HardDeleteVisitor(), cu);
        }
    }

    static void setCurrentMethod(String currentMethod) {
        HardDelete.currentMethod = currentMethod;
    }

    public static void main(String[] args) throws IOException {
        Settings.loadConfigMap();
        AbstractCompiler.preProcess();

        for (var entry : AntikytheraRunTime.getResolvedCompilationUnits().entrySet()) {
            try {
                CompilationUnit cu = entry.getValue();
                for (TypeDeclaration<?> decl : cu.getTypes()) {
                    TypeWrapper wrapper = AbstractCompiler.findType(cu, decl.getNameAsString());
                    if (wrapper != null && wrapper.getType() != null) {
                        current = wrapper.getType();
                        repoVars.clear();
                        decl.accept(new FieldVisitor(), cu);
                        decl.accept(new MethodVisitor(), cu);
                    }
                }
            } catch (UnsupportedOperationException uoe) {
                System.out.println(entry.getKey() + " : " + uoe.getMessage());
            }
        }

    }
}
