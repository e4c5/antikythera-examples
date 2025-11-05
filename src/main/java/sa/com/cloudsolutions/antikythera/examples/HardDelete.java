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

/**
 * Analyzes Java code to detect hard delete operations in JPA repositories.
 * 
 * This analyzer identifies hard deletes by examining:
 * 1. Standard JPA repository delete methods (delete, deleteById, deleteAll, etc.)
 * 2. Derived query methods (deleteBy*, removeBy*)
 * 3. Custom @Query annotations containing DELETE statements
 * 
 * It distinguishes between hard and soft deletes by:
 * - Checking for @Query annotations with UPDATE statements (typical soft delete pattern)
 * - Looking for soft delete indicators in query conditions (deleted flags, status fields)
 * - Analyzing @Modifying annotations combined with UPDATE queries
 * 
 * Output format: ClassName,MethodName,MethodCallExpression
 */
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
            
            // Check for custom query methods that perform deletes
            if (isCustomDeleteQuery(mce, cu, repoVar)) {
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
         * Checks if the method has a custom @Query annotation with DELETE statement.
         */
        private boolean isCustomDeleteQuery(MethodCallExpr mce, CompilationUnit cu, String repoVar) {
            String repoTypeName = repoVars.get(repoVar);
            if (repoTypeName == null) return false;

            // Try to find the repository interface definition
            Optional<TypeDeclaration<?>> repoType = findRepositoryType(cu, repoVar, repoTypeName);
            
            if (repoType.isPresent()) {
                List<MethodDeclaration> methods = repoType.get().getMethodsByName(mce.getNameAsString());
                for (MethodDeclaration method : methods) {
                    if (RepositoryAnalyzer.hasQueryAnnotation(method)) {
                        String queryValue = extractQueryValue(method);
                        if (queryValue.toUpperCase().contains("DELETE")) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        /**
         * Determines if a delete method implementation is actually a soft delete.
         */
        private boolean isSoftDeleteImplementation(MethodCallExpr mce, CompilationUnit cu, String repoVar) {
            String repoTypeName = repoVars.get(repoVar);
            if (repoTypeName == null) return false;

            Optional<TypeDeclaration<?>> repoType = findRepositoryType(cu, repoVar, repoTypeName);
            
            if (repoType.isPresent()) {
                List<MethodDeclaration> methods = repoType.get().getMethodsByName(mce.getNameAsString());
                for (MethodDeclaration method : methods) {
                    // Check for @Query annotation with UPDATE statement (soft delete pattern)
                    if (RepositoryAnalyzer.hasQueryAnnotation(method)) {
                        String queryValue = extractQueryValue(method);
                        String upperQuery = queryValue.toUpperCase();
                        
                        // Soft delete typically uses UPDATE to set a flag/timestamp
                        if (upperQuery.contains("UPDATE") && 
                            (upperQuery.contains("DELETED") || upperQuery.contains("ACTIVE") || 
                             upperQuery.contains("STATUS") || upperQuery.contains("ENABLED"))) {
                            System.err.println(currentMethod + "," + mce + " is a soft delete (UPDATE query)");
                            return true;
                        }
                        
                        // If it's a DELETE query, check for soft delete conditions
                        if (upperQuery.contains("DELETE") && 
                            (upperQuery.contains("WHERE") && 
                             (upperQuery.contains("DELETED") || upperQuery.contains("ACTIVE") || 
                              upperQuery.contains("STATUS")))) {
                            System.err.println(currentMethod + "," + mce + " might be a conditional delete");
                            // This could be either hard or soft delete depending on the condition
                            // For now, we'll be conservative and not flag it as hard delete
                            return true;
                        }
                    }
                    
                    // Check for @Modifying annotation combined with @Query
                    if (RepositoryAnalyzer.hasModifyingAnnotation(method) && 
                        RepositoryAnalyzer.hasQueryAnnotation(method)) {
                        String queryValue = extractQueryValue(method);
                        if (queryValue.toUpperCase().contains("UPDATE")) {
                            System.err.println(currentMethod + "," + mce + " is a soft delete (@Modifying + UPDATE)");
                            return true;
                        }
                    }
                }
            }
            
            return false;
        }

        /**
         * Attempts to find the repository type definition.
         */
        private Optional<TypeDeclaration<?>> findRepositoryType(CompilationUnit cu, String repoVar, String repoTypeName) {
            // First try to find in the current compilation unit
            Optional<TypeDeclaration<?>> localType = cu.getTypes().stream()
                .filter(td -> td.getMembers().stream()
                    .anyMatch(m -> m instanceof FieldDeclaration field &&
                        field.getVariables().stream()
                            .anyMatch(v -> v.getNameAsString().equals(repoVar))))
                .findFirst();
                
            if (localType.isPresent()) {
                return localType;
            }
            
            // Try to find by type name in the compilation unit
            return cu.getTypes().stream()
                .filter(td -> repoTypeName.endsWith(td.getNameAsString()))
                .findFirst();
        }

        /**
         * Extracts the query value from a @Query annotation.
         */
        private String extractQueryValue(MethodDeclaration method) {
            return method.getAnnotationByName("Query")
                .flatMap(ann -> {
                    if (ann instanceof com.github.javaparser.ast.expr.SingleMemberAnnotationExpr single) {
                        return Optional.of(single.getMemberValue().toString().replaceAll("^\"|\"$", ""));
                    } else if (ann instanceof com.github.javaparser.ast.expr.NormalAnnotationExpr normal) {
                        return normal.getPairs().stream()
                            .filter(pair -> "value".equals(pair.getNameAsString()))
                            .map(pair -> pair.getValue().toString().replaceAll("^\"|\"$", ""))
                            .findFirst();
                    }
                    return Optional.empty();
                })
                .orElse("");
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
