package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
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

/**
 * Analyzes Java code to detect hard delete operations in JPA repositories.
 * <p>
 * This analyzer identifies hard deletes by examining:
 * 1. Standard JPA repository delete methods (delete, deleteById, deleteAll, etc.)
 * 2. Derived query methods (deleteBy*, removeBy*)
 * 3. Custom @Query annotations containing DELETE statements
 * <p>
 * It distinguishes between hard and soft deletes by:
 * - Checking for @Query annotations with UPDATE statements (typical soft delete pattern)
 * - Looking for soft delete indicators in query conditions (deleted flags, status fields)
 * - Analyzing @Modifying annotations combined with UPDATE queries
 * <p>
 * Output format: ClassName,MethodName,MethodCallExpression
 */
@SuppressWarnings("java:S106")
public class HardDelete {
    // Map repository variable name to its type
    private static final Map<String, String> repoVars = new HashMap<>();
    public static final String QUERY = "Query";
    static TypeDeclaration<?> current;
    private static String currentMethod;

    static void setCurrentMethod(String currentMethod) {
        HardDelete.currentMethod = currentMethod;
    }

    public static void main(String[] args) throws IOException {
        Settings.loadConfigMap();
        AbstractCompiler.preProcess();

        detectHardDeletes();

    }

    static void detectHardDeletes() {
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

    public static class FieldVisitor extends VoidVisitorAdapter<CompilationUnit> {
        @Override
        public void visit(FieldDeclaration field, CompilationUnit cu) {
            super.visit(field, cu);
            VariableDeclarator vdecl = field.getVariable(0);
            TypeWrapper wrapper = AbstractCompiler.findType(cu, vdecl.getTypeAsString());
            if (RepositoryAnalyzer.isJpaRepository(wrapper)) {
                repoVars.put(vdecl.getNameAsString(), wrapper.getFullyQualifiedName());
            }
        }
    }

    /**
     * Visitor to detect hard delete method calls on JPARepository instances.
     * A hard delete is identified as:
     * 1. Standard JPA repository delete methods (delete, deleteById, deleteAll, etc.)
     * 2. Derived query methods starting with "deleteBy" without custom @Query annotation
     * 3. Methods with @Query annotation containing DELETE statements without soft delete logic
     */
    public static class HardDeleteVisitor extends VoidVisitorAdapter<CompilationUnit> {
        @Override
        public void visit(MethodCallExpr mce, CompilationUnit cu) {
            super.visit(mce, cu);

            mce.getScope().ifPresent(scope -> {
                String varName = scope.toString();
                if (repoVars.containsKey(varName) && isHardDeleteMethod(mce, cu, varName)) {
                    String className = current.asClassOrInterfaceDeclaration().getFullyQualifiedName().orElseThrow();
                    System.out.println(className + "," + currentMethod + "," + mce);
                }
            });
        }

        /**
         * Determines if a method call represents a hard delete operation.
         */
        private boolean isHardDeleteMethod(MethodCallExpr mce, CompilationUnit cu, String repoVar) {
            String methodName = mce.getNameAsString();

            // Check for standard JPA repository delete methods
            if (isStandardJpaDeleteMethod(methodName)) {
                return !isSoftDeleteImplementation(mce, cu, repoVar);
            }

            // Check for derived query delete methods
            if (isDerivedDeleteMethod(methodName)) {
                return !isSoftDeleteImplementation(mce, cu, repoVar);
            }


            return false;
        }

        /**
         * Checks if the method name matches standard JPA repository delete methods.
         */
        private boolean isStandardJpaDeleteMethod(String methodName) {
            return "delete".equals(methodName) ||
                    "deleteById".equals(methodName) ||
                    "deleteAll".equals(methodName) ||
                    "deleteAllById".equals(methodName) ||
                    "deleteInBatch".equals(methodName) ||
                    "deleteAllInBatch".equals(methodName) ||
                    "deleteAllByIdInBatch".equals(methodName);
        }

        /**
         * Checks if the method name follows the derived query pattern for delete operations.
         */
        private boolean isDerivedDeleteMethod(String methodName) {
            return methodName.startsWith("deleteBy") || methodName.startsWith("removeBy");
        }

        /**
         * Determines if a delete method implementation is actually a soft delete.
         */
        private boolean isSoftDeleteImplementation(MethodCallExpr mce, CompilationUnit cu, String repoVar) {
            String repoTypeName = repoVars.get(repoVar);
            if (repoTypeName == null) return false;

            TypeWrapper repoType = findRepositoryType(cu, repoTypeName);

            if (repoType != null && repoType.getType() != null) {
                List<MethodDeclaration> methods = repoType.getType().getMethodsByName(mce.getNameAsString());
                for (MethodDeclaration method : methods) {
                    // Check for @Query annotation with UPDATE statement (soft delete pattern)
                    if (method.getAnnotationByName(QUERY).isPresent()) {
                        String queryValue = AbstractCompiler.extractAnnotationAttributes(
                                method.getAnnotationByName(QUERY).orElseThrow()).get("value").toString().replace("\"","");
                        String upperQuery = queryValue.toUpperCase();
                        return !upperQuery.startsWith("DELETE");
                    }
                }
            }

            return false;
        }

        /**
         * Attempts to find the repository type definition.
         */
        private TypeWrapper findRepositoryType(CompilationUnit cu,  String repoTypeName) {
            return AbstractCompiler.findType(cu, repoTypeName);
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
}
