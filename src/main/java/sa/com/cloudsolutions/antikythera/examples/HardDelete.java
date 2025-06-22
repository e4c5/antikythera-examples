package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import sa.com.cloudsolutions.antikythera.generator.TypeWrapper;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class HardDelete {

    /**
     * Visitor to detect hard delete method calls on JPARepository instances.
     * If a delete method has a custom annotation, it's not considered a hard delete.
     */
    public static class HardDeleteVisitor extends VoidVisitorAdapter<CompilationUnit> {
        // Map repository variable name to its type
        private final Map<String, String> repoVars = new HashMap<>();

        // Change this to your actual soft-delete annotation name
        private static final String SOFT_DELETE_ANNOTATION = "SoftDelete";

        @Override
        public void visit(FieldDeclaration field, CompilationUnit cu) {
            super.visit(field, cu);
            VariableDeclarator vdecl = field.getVariable(0);
            TypeWrapper wrapper = AbstractCompiler.findType(cu, vdecl.getTypeAsString());
            if (wrapper != null && isJpaRepository(wrapper)) {
                repoVars.put(vdecl.getNameAsString(), wrapper.getFullyQualifiedName());
            }
        }

        @Override
        public void visit(MethodCallExpr mce, CompilationUnit cu) {
            super.visit(mce, cu);

            if (!mce.getNameAsString().toLowerCase().contains("delete")) return;

            mce.getScope().ifPresent(scope -> {
                String varName = scope.toString();
                if (repoVars.containsKey(varName)) {
                    if (!hasSoftDeleteAnnotation(mce, cu, varName)) {
                        System.out.println("Hard delete detected: " + mce + " in " +
                                cu.getStorage().map(Object::toString).orElse("unknown file"));
                    }
                }
            });
        }

        private boolean isJpaRepository(TypeWrapper wrapper) {
            if (wrapper != null) {
                if ("org.springframework.data.jpa.repository.JpaRepository".equals(wrapper.getFullyQualifiedName())) {
                    return true;
                }
                if (wrapper.getClazz() != null) {
                    Class<?> clazz = wrapper.getClazz();
                    for (Class<?> iface : clazz.getInterfaces()) {
                        if (iface.getName().contains("Repository")) {
                            return true;
                        }
                    }
                }
                if (wrapper.getType() != null && wrapper.getType().isClassOrInterfaceDeclaration()) {
                    for(ClassOrInterfaceType iface : wrapper.getType().asClassOrInterfaceDeclaration().getExtendedTypes()) {
                        if (iface.getNameAsString().contains("Repository")) {
                            return true;
                        }
                    }
                }
            }

            return false;
        }

        private boolean hasSoftDeleteAnnotation(MethodCallExpr mce, CompilationUnit cu, String repoVar) {
            // Try to resolve the repository type and method, then check for custom annotation
            Optional<TypeDeclaration<?>> repoType = cu.getTypes().stream()
                .filter(td -> td.getMembers().stream()
                    .anyMatch(m -> m instanceof FieldDeclaration &&
                        ((FieldDeclaration) m).getVariables().stream()
                            .anyMatch(v -> v.getNameAsString().equals(repoVar))))
                .findFirst();

            if (repoType.isPresent()) {
                List<MethodDeclaration> methods = repoType.get().getMethodsByName(mce.getNameAsString());
                for (MethodDeclaration method : methods) {
                    for (AnnotationExpr ann : method.getAnnotations()) {
                        if (ann.getNameAsString().equals(SOFT_DELETE_ANNOTATION)) {
                            return true;
                        }
                    }
                }
            }
            // Could not find annotation, assume hard delete
            return false;
        }
    }

    public static void main(String[] args) throws IOException {
        sa.com.cloudsolutions.antikythera.configuration.Settings.loadConfigMap();
        sa.com.cloudsolutions.antikythera.parser.AbstractCompiler.preProcess();

        for (var entry : sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime.getResolvedCompilationUnits().entrySet()) {
            try {
                CompilationUnit cu = entry.getValue();
                for (TypeDeclaration<?> decl : cu.getTypes()) {
                    decl.accept(new HardDeleteVisitor(), cu);
                }
            } catch (UnsupportedOperationException uoe) {
                System.out.println(entry.getKey() + " : " + uoe.getMessage());
            }
        }

    }
}
