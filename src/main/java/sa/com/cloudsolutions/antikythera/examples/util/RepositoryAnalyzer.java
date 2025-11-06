package sa.com.cloudsolutions.antikythera.examples.util;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import sa.com.cloudsolutions.antikythera.generator.TypeWrapper;


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
     * Checks if a method has a @Query annotation.
     * 
     * @param method the method to check
     * @return true if the method has a @Query annotation, false otherwise
     */
    public static boolean hasQueryAnnotation(MethodDeclaration method) {
        return method.getAnnotationByName("Query").isPresent();
    }

    private static boolean isRepositoryInterface(String interfaceName) {
        return interfaceName != null && (
            interfaceName.contains("JpaRepository") ||
            interfaceName.contains("CrudRepository") ||
            interfaceName.contains("PagingAndSortingRepository") ||
            interfaceName.contains("Repository") && 
            (interfaceName.contains("org.springframework.data") || interfaceName.endsWith("Repository"))
        );
    }
}
