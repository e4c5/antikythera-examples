package sa.com.cloudsolutions.antikythera.examples.util;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import sa.com.cloudsolutions.antikythera.generator.TypeWrapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Centralized utility class for JPA repository analysis with consistent identification logic,
 * query method extraction, and annotation parsing.
 * 
 * Consolidates repository analysis patterns from:
 * - QueryOptimizationChecker.isJpaRepository()
 * - HardDelete.FieldVisitor.isJpaRepository()
 * - Various query method and annotation extraction logic
 */
public class RepositoryAnalyzer {
    
    /**
     * Represents metadata about a repository method.
     */
    public static class RepositoryMethod {
        private final MethodDeclaration method;
        private final String methodName;
        private final List<QueryAnnotation> annotations;
        private final boolean isDerivedQuery;
        
        public RepositoryMethod(MethodDeclaration method, String methodName, 
                               List<QueryAnnotation> annotations, boolean isDerivedQuery) {
            this.method = method;
            this.methodName = methodName;
            this.annotations = annotations;
            this.isDerivedQuery = isDerivedQuery;
        }
        
        public MethodDeclaration getMethod() { return method; }
        public String getMethodName() { return methodName; }
        public List<QueryAnnotation> getAnnotations() { return annotations; }
        public boolean isDerivedQuery() { return isDerivedQuery; }
    }
    
    /**
     * Represents a query annotation (@Query, @Modifying, etc.).
     */
    public static class QueryAnnotation {
        private final String value;

        public QueryAnnotation(String annotationName, String value) {
            this.value = value;
        }
        
        public String getValue() { return value; }

    }
    
    /**
     * Represents comprehensive metadata about a repository.
     */
    public static class RepositoryMetadata {
        private final String fullyQualifiedName;
        private final boolean isJpaRepository;
        private final List<String> extendedInterfaces;
        private final List<RepositoryMethod> methods;
        private final TypeWrapper typeWrapper;
        
        public RepositoryMetadata(String fullyQualifiedName, boolean isJpaRepository,
                                 List<String> extendedInterfaces, List<RepositoryMethod> methods,
                                 TypeWrapper typeWrapper) {
            this.fullyQualifiedName = fullyQualifiedName;
            this.isJpaRepository = isJpaRepository;
            this.extendedInterfaces = extendedInterfaces;
            this.methods = methods;
            this.typeWrapper = typeWrapper;
        }
        
        public String getFullyQualifiedName() { return fullyQualifiedName; }
        public boolean isJpaRepository() { return isJpaRepository; }
        public List<String> getExtendedInterfaces() { return extendedInterfaces; }
        public List<RepositoryMethod> getMethods() { return methods; }
        public TypeWrapper getTypeWrapper() { return typeWrapper; }
    }
    
    /**
     * Determines if a TypeWrapper represents a JPA repository interface.
     * Consolidates logic from QueryOptimizationChecker and HardDelete.
     * 
     * @param typeWrapper the TypeWrapper to analyze
     * @return true if it's a JPA repository, false otherwise
     */
    public static boolean isJpaRepository(TypeWrapper typeWrapper) {
        if (typeWrapper == null) {
            return false;
        }
        
        // Check by fully qualified name first (most reliable)
        String fqn = typeWrapper.getFullyQualifiedName();
        if ("org.springframework.data.jpa.repository.JpaRepository".equals(fqn)) {
            return true;
        }
        
        // Check runtime class interfaces if available
        if (typeWrapper.getClazz() != null) {
            Class<?> clazz = typeWrapper.getClazz();
            for (Class<?> iface : clazz.getInterfaces()) {
                if (isRepositoryInterface(iface.getName())) {
                    return true;
                }
            }
        }
        
        // Check AST-based type information
        if (typeWrapper.getType() instanceof ClassOrInterfaceDeclaration classOrInterface && 
            classOrInterface.isInterface()) {
            
            // Check extended types
            for (ClassOrInterfaceType extendedType : classOrInterface.getExtendedTypes()) {
                String typeName = extendedType.getNameAsString();
                String fullTypeName = extendedType.toString();
                
                if (isRepositoryInterface(typeName) || isRepositoryInterface(fullTypeName)) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * Extracts all repository methods from a TypeWrapper.
     * 
     * @param repository the repository TypeWrapper to analyze
     * @return list of RepositoryMethod objects
     */
    public static List<RepositoryMethod> extractRepositoryMethods(TypeWrapper repository) {
        List<RepositoryMethod> methods = new ArrayList<>();
        
        if (repository.getType() instanceof ClassOrInterfaceDeclaration classOrInterface) {
            for (MethodDeclaration method : classOrInterface.getMethods()) {
                String methodName = method.getNameAsString();
                List<QueryAnnotation> annotations = extractQueryAnnotations(method);
                boolean isDerivedQuery = isDerivedQueryMethod(methodName) && annotations.isEmpty();
                
                methods.add(new RepositoryMethod(method, methodName, annotations, isDerivedQuery));
            }
        }
        
        return methods;
    }
    
    /**
     * Extracts query annotations from a repository TypeWrapper.
     * 
     * @param repository the repository TypeWrapper to analyze
     * @return list of QueryAnnotation objects
     */
    public static List<QueryAnnotation> extractQueryAnnotations(TypeWrapper repository) {
        List<QueryAnnotation> annotations = new ArrayList<>();
        
        if (repository.getType() instanceof ClassOrInterfaceDeclaration classOrInterface) {
            for (MethodDeclaration method : classOrInterface.getMethods()) {
                annotations.addAll(extractQueryAnnotations(method));
            }
        }
        
        return annotations;
    }
    
    /**
     * Extracts query annotations from a specific method.
     * 
     * @param method the method to analyze
     * @return list of QueryAnnotation objects
     */
    public static List<QueryAnnotation> extractQueryAnnotations(MethodDeclaration method) {
        List<QueryAnnotation> annotations = new ArrayList<>();
        
        for (AnnotationExpr annotation : method.getAnnotations()) {
            String annotationName = annotation.getNameAsString();
            
            if (isQueryRelatedAnnotation(annotationName)) {
                String value = extractAnnotationValue(annotation);
                annotations.add(new QueryAnnotation(annotationName, value));
            }
        }
        
        return annotations;
    }

    /**
     * Checks if a method name indicates a derived query method.
     * 
     * @param methodName the method name to check
     * @return true if it's a derived query method, false otherwise
     */
    public static boolean isDerivedQueryMethod(String methodName) {
        return methodName.startsWith("findBy") || 
               methodName.startsWith("countBy") || 
               methodName.startsWith("deleteBy") || 
               methodName.startsWith("existsBy") ||
               methodName.startsWith("readBy") ||
               methodName.startsWith("queryBy") ||
               methodName.startsWith("getBy");
    }
    
    /**
     * Checks if an annotation name is query-related.
     * 
     * @param annotationName the annotation name to check
     * @return true if it's query-related, false otherwise
     */
    public static boolean isQueryRelatedAnnotation(String annotationName) {
        return "Query".equals(annotationName) || 
               "Modifying".equals(annotationName) ||
               "Procedure".equals(annotationName) ||
               "NamedQuery".equals(annotationName) ||
               "NamedQueries".equals(annotationName);
    }
    
    /**
     * Checks if a method has a @Query annotation.
     * 
     * @param method the method to check
     * @return true if the method has a @Query annotation, false otherwise
     */
    public static boolean hasQueryAnnotation(MethodDeclaration method) {
        return method.getAnnotationByName("Query").isPresent();
    }
    
    /**
     * Checks if a method has a @Modifying annotation.
     * 
     * @param method the method to check
     * @return true if the method has a @Modifying annotation, false otherwise
     */
    public static boolean hasModifyingAnnotation(MethodDeclaration method) {
        return method.getAnnotationByName("Modifying").isPresent();
    }
    
    // Private helper methods
    
    private static boolean isRepositoryInterface(String interfaceName) {
        return interfaceName != null && (
            interfaceName.contains("JpaRepository") ||
            interfaceName.contains("CrudRepository") ||
            interfaceName.contains("PagingAndSortingRepository") ||
            interfaceName.contains("Repository") && 
            (interfaceName.contains("org.springframework.data") || interfaceName.endsWith("Repository"))
        );
    }

    private static String extractAnnotationValue(AnnotationExpr annotation) {
        if (annotation instanceof SingleMemberAnnotationExpr singleMember) {
            return singleMember.getMemberValue().toString().replaceAll("^\"|\"$", "");
        } else if (annotation instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if ("value".equals(pair.getNameAsString())) {
                    return pair.getValue().toString().replaceAll("^\"|\"$", "");
                }
            }
        }
        return "";
    }
}
