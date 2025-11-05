package sa.com.cloudsolutions.antikythera.examples.util;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.examples.util.RepositoryAnalyzer.QueryAnnotation;
import sa.com.cloudsolutions.antikythera.examples.util.RepositoryAnalyzer.RepositoryMetadata;
import sa.com.cloudsolutions.antikythera.examples.util.RepositoryAnalyzer.RepositoryMethod;
import sa.com.cloudsolutions.antikythera.generator.TypeWrapper;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RepositoryAnalyzer utility class.
 * Tests JPA repository identification with various interface types,
 * query method extraction, annotation parsing, and inheritance hierarchy handling.
 */
class RepositoryAnalyzerTest {

    private JavaParser javaParser;

    @BeforeEach
    void setUp() {
        javaParser = new JavaParser();
    }

    @Test
    void testIsJpaRepositoryWithNullTypeWrapper() {
        // Test with null TypeWrapper
        assertFalse(RepositoryAnalyzer.isJpaRepository(null));
    }

    @Test
    void testIsJpaRepositoryWithJpaRepositoryInterface() {
        // Create a mock TypeWrapper for JpaRepository
        String jpaRepositoryCode = """
                package org.springframework.data.jpa.repository;
                
                public interface JpaRepository<T, ID> {
                }
                """;
        
        CompilationUnit cu = javaParser.parse(jpaRepositoryCode).getResult().orElseThrow();
        ClassOrInterfaceDeclaration jpaRepo = cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
        
        TypeWrapper typeWrapper = new TypeWrapper(jpaRepo);
        
        assertTrue(RepositoryAnalyzer.isJpaRepository(typeWrapper));
    }

    @Test
    void testIsJpaRepositoryWithCustomRepository() {
        // Create a custom repository that extends JpaRepository
        String customRepositoryCode = """
                package com.example.repository;
                
                import org.springframework.data.jpa.repository.JpaRepository;
                
                public interface UserRepository extends JpaRepository<User, Long> {
                    User findByEmail(String email);
                }
                """;
        
        CompilationUnit cu = javaParser.parse(customRepositoryCode).getResult().orElseThrow();
        ClassOrInterfaceDeclaration customRepo = cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
        
        TypeWrapper typeWrapper = new TypeWrapper(customRepo);
        
        assertTrue(RepositoryAnalyzer.isJpaRepository(typeWrapper));
    }

    @Test
    void testIsJpaRepositoryWithNonRepository() {
        // Create a regular interface that's not a repository
        String regularInterfaceCode = """
                package com.example.service;
                
                public interface UserService {
                    void saveUser(User user);
                }
                """;
        
        CompilationUnit cu = javaParser.parse(regularInterfaceCode).getResult().orElseThrow();
        ClassOrInterfaceDeclaration regularInterface = cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
        
        TypeWrapper typeWrapper = new TypeWrapper(regularInterface);
        
        assertFalse(RepositoryAnalyzer.isJpaRepository(typeWrapper));
    }

    @Test
    void testIsJpaRepositoryWithCrudRepository() {
        // Test with CrudRepository (should also be recognized)
        String crudRepositoryCode = """
                package com.example.repository;
                
                import org.springframework.data.repository.CrudRepository;
                
                public interface OrderRepository extends CrudRepository<Order, Long> {
                    List<Order> findByUserId(Long userId);
                }
                """;
        
        CompilationUnit cu = javaParser.parse(crudRepositoryCode).getResult().orElseThrow();
        ClassOrInterfaceDeclaration crudRepo = cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
        
        TypeWrapper typeWrapper = new TypeWrapper(crudRepo);
        
        assertTrue(RepositoryAnalyzer.isJpaRepository(typeWrapper));
    }

    @Test
    void testExtractRepositoryMethods() {
        // Create a repository with various method types
        String repositoryCode = """
                package com.example.repository;
                
                import org.springframework.data.jpa.repository.JpaRepository;
                import org.springframework.data.jpa.repository.Query;
                import org.springframework.data.jpa.repository.Modifying;
                
                public interface UserRepository extends JpaRepository<User, Long> {
                    User findByEmail(String email);
                    
                    List<User> findByAgeGreaterThan(int age);
                    
                    @Query("SELECT u FROM User u WHERE u.active = true")
                    List<User> findActiveUsers();
                    
                    @Modifying
                    @Query("UPDATE User u SET u.active = false WHERE u.id = ?1")
                    void deactivateUser(Long id);
                    
                    boolean existsByEmail(String email);
                }
                """;
        
        CompilationUnit cu = javaParser.parse(repositoryCode).getResult().orElseThrow();
        ClassOrInterfaceDeclaration repo = cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
        
        TypeWrapper typeWrapper = new TypeWrapper(repo);
        List<RepositoryMethod> methods = RepositoryAnalyzer.extractRepositoryMethods(typeWrapper);
        
        assertEquals(5, methods.size());
        
        // Check derived query methods
        RepositoryMethod findByEmail = methods.stream()
            .filter(m -> "findByEmail".equals(m.getMethodName()))
            .findFirst().orElseThrow();
        assertTrue(findByEmail.isDerivedQuery());
        assertTrue(findByEmail.getAnnotations().isEmpty());
        
        // Check annotated methods
        RepositoryMethod findActiveUsers = methods.stream()
            .filter(m -> "findActiveUsers".equals(m.getMethodName()))
            .findFirst().orElseThrow();
        assertFalse(findActiveUsers.isDerivedQuery());
        assertFalse(findActiveUsers.getAnnotations().isEmpty());
        
        RepositoryMethod deactivateUser = methods.stream()
            .filter(m -> "deactivateUser".equals(m.getMethodName()))
            .findFirst().orElseThrow();
        assertFalse(deactivateUser.isDerivedQuery());
        assertEquals(2, deactivateUser.getAnnotations().size()); // @Query and @Modifying
    }

    @Test
    void testExtractQueryAnnotations() {
        // Create a method with query annotations
        String methodCode = """
                @Query(value = "SELECT * FROM users WHERE active = true", nativeQuery = true)
                @Modifying
                public List<User> findActiveUsersNative();
                """;
        
        String classCode = """
                package com.example.repository;
                
                import org.springframework.data.jpa.repository.Query;
                import org.springframework.data.jpa.repository.Modifying;
                
                public interface TestRepository {
                    %s
                }
                """.formatted(methodCode);
        
        CompilationUnit cu = javaParser.parse(classCode).getResult().orElseThrow();
        ClassOrInterfaceDeclaration testRepo = cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
        MethodDeclaration method = testRepo.findFirst(MethodDeclaration.class).orElseThrow();
        
        List<QueryAnnotation> annotations = RepositoryAnalyzer.extractQueryAnnotations(method);
        
        assertEquals(2, annotations.size());
        
        // Check @Query annotation
        QueryAnnotation queryAnnotation = annotations.stream()
            .filter(a -> "Query".equals(a.getAnnotationName()))
            .findFirst().orElseThrow();
        assertTrue(queryAnnotation.getValue().contains("SELECT * FROM users"));
        assertTrue(queryAnnotation.isNative());
        
        // Check @Modifying annotation
        QueryAnnotation modifyingAnnotation = annotations.stream()
            .filter(a -> "Modifying".equals(a.getAnnotationName()))
            .findFirst().orElseThrow();
        assertFalse(modifyingAnnotation.isNative());
    }

    @Test
    void testAnalyzeRepositoryComprehensive() {
        // Create a comprehensive repository for full analysis
        String repositoryCode = """
                package com.example.repository;
                
                import org.springframework.data.jpa.repository.JpaRepository;
                import org.springframework.data.jpa.repository.Query;
                
                public interface ComprehensiveRepository extends JpaRepository<Entity, Long> {
                    Entity findByName(String name);
                    
                    @Query("SELECT e FROM Entity e WHERE e.active = true")
                    List<Entity> findActiveEntities();
                }
                """;
        
        CompilationUnit cu = javaParser.parse(repositoryCode).getResult().orElseThrow();
        ClassOrInterfaceDeclaration repo = cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
        
        TypeWrapper typeWrapper = new TypeWrapper(repo);
        RepositoryMetadata metadata = RepositoryAnalyzer.analyzeRepository(
            "com.example.repository.ComprehensiveRepository", typeWrapper);
        
        assertNotNull(metadata);
        assertEquals("com.example.repository.ComprehensiveRepository", metadata.getFullyQualifiedName());
        assertTrue(metadata.isJpaRepository());
        assertEquals(1, metadata.getExtendedInterfaces().size());
        assertTrue(metadata.getExtendedInterfaces().get(0).contains("JpaRepository"));
        assertEquals(2, metadata.getMethods().size());
        assertEquals(typeWrapper, metadata.getTypeWrapper());
    }

    @Test
    void testAnalyzeRepositoryWithNullFQN() {
        // Test analyzeRepository with just FQN (should return null as documented)
        RepositoryMetadata metadata = RepositoryAnalyzer.analyzeRepository("com.example.NonExistentRepository");
        assertNull(metadata);
    }

    @Test
    void testIsDerivedQueryMethod() {
        // Test various derived query method patterns
        assertTrue(RepositoryAnalyzer.isDerivedQueryMethod("findByEmail"));
        assertTrue(RepositoryAnalyzer.isDerivedQueryMethod("findByNameAndAge"));
        assertTrue(RepositoryAnalyzer.isDerivedQueryMethod("countByStatus"));
        assertTrue(RepositoryAnalyzer.isDerivedQueryMethod("deleteByAge"));
        assertTrue(RepositoryAnalyzer.isDerivedQueryMethod("existsByEmail"));
        assertTrue(RepositoryAnalyzer.isDerivedQueryMethod("readByName"));
        assertTrue(RepositoryAnalyzer.isDerivedQueryMethod("queryByStatus"));
        assertTrue(RepositoryAnalyzer.isDerivedQueryMethod("getByEmail"));
        
        // Test non-derived query methods
        assertFalse(RepositoryAnalyzer.isDerivedQueryMethod("save"));
        assertFalse(RepositoryAnalyzer.isDerivedQueryMethod("delete"));
        assertFalse(RepositoryAnalyzer.isDerivedQueryMethod("customMethod"));
        assertFalse(RepositoryAnalyzer.isDerivedQueryMethod("processData"));
    }

    @Test
    void testIsQueryRelatedAnnotation() {
        // Test query-related annotations
        assertTrue(RepositoryAnalyzer.isQueryRelatedAnnotation("Query"));
        assertTrue(RepositoryAnalyzer.isQueryRelatedAnnotation("Modifying"));
        assertTrue(RepositoryAnalyzer.isQueryRelatedAnnotation("Procedure"));
        assertTrue(RepositoryAnalyzer.isQueryRelatedAnnotation("NamedQuery"));
        assertTrue(RepositoryAnalyzer.isQueryRelatedAnnotation("NamedQueries"));
        
        // Test non-query annotations
        assertFalse(RepositoryAnalyzer.isQueryRelatedAnnotation("Override"));
        assertFalse(RepositoryAnalyzer.isQueryRelatedAnnotation("Transactional"));
        assertFalse(RepositoryAnalyzer.isQueryRelatedAnnotation("Service"));
        assertFalse(RepositoryAnalyzer.isQueryRelatedAnnotation("Repository"));
    }

    @Test
    void testHasQueryAnnotation() {
        // Create method with @Query annotation
        String methodWithQuery = """
                @Query("SELECT u FROM User u")
                public List<User> findAllUsers();
                """;
        
        String classCode = """
                package com.example;
                import org.springframework.data.jpa.repository.Query;
                public interface TestRepo {
                    %s
                }
                """.formatted(methodWithQuery);
        
        CompilationUnit cu = javaParser.parse(classCode).getResult().orElseThrow();
        MethodDeclaration method = cu.findFirst(MethodDeclaration.class).orElseThrow();
        
        assertTrue(RepositoryAnalyzer.hasQueryAnnotation(method));
        
        // Create method without @Query annotation
        String methodWithoutQuery = """
                public List<User> findByName(String name);
                """;
        
        String classCode2 = """
                package com.example;
                public interface TestRepo {
                    %s
                }
                """.formatted(methodWithoutQuery);
        
        CompilationUnit cu2 = javaParser.parse(classCode2).getResult().orElseThrow();
        MethodDeclaration method2 = cu2.findFirst(MethodDeclaration.class).orElseThrow();
        
        assertFalse(RepositoryAnalyzer.hasQueryAnnotation(method2));
    }

    @Test
    void testHasModifyingAnnotation() {
        // Create method with @Modifying annotation
        String methodWithModifying = """
                @Modifying
                @Query("UPDATE User u SET u.active = false")
                public void deactivateAllUsers();
                """;
        
        String classCode = """
                package com.example;
                import org.springframework.data.jpa.repository.Modifying;
                import org.springframework.data.jpa.repository.Query;
                public interface TestRepo {
                    %s
                }
                """.formatted(methodWithModifying);
        
        CompilationUnit cu = javaParser.parse(classCode).getResult().orElseThrow();
        MethodDeclaration method = cu.findFirst(MethodDeclaration.class).orElseThrow();
        
        assertTrue(RepositoryAnalyzer.hasModifyingAnnotation(method));
        
        // Create method without @Modifying annotation
        String methodWithoutModifying = """
                @Query("SELECT u FROM User u")
                public List<User> findAllUsers();
                """;
        
        String classCode2 = """
                package com.example;
                import org.springframework.data.jpa.repository.Query;
                public interface TestRepo {
                    %s
                }
                """.formatted(methodWithoutModifying);
        
        CompilationUnit cu2 = javaParser.parse(classCode2).getResult().orElseThrow();
        MethodDeclaration method2 = cu2.findFirst(MethodDeclaration.class).orElseThrow();
        
        assertFalse(RepositoryAnalyzer.hasModifyingAnnotation(method2));
    }

    @Test
    void testRepositoryMethodClass() {
        // Test RepositoryMethod class functionality
        String methodCode = """
                @Query("SELECT u FROM User u WHERE u.active = true")
                public List<User> findActiveUsers();
                """;
        
        String classCode = """
                package com.example;
                import org.springframework.data.jpa.repository.Query;
                public interface TestRepo {
                    %s
                }
                """.formatted(methodCode);
        
        CompilationUnit cu = javaParser.parse(classCode).getResult().orElseThrow();
        MethodDeclaration method = cu.findFirst(MethodDeclaration.class).orElseThrow();
        
        List<QueryAnnotation> annotations = RepositoryAnalyzer.extractQueryAnnotations(method);
        RepositoryMethod repoMethod = new RepositoryMethod(method, "findActiveUsers", annotations, false);
        
        assertEquals(method, repoMethod.getMethod());
        assertEquals("findActiveUsers", repoMethod.getMethodName());
        assertEquals(annotations, repoMethod.getAnnotations());
        assertFalse(repoMethod.isDerivedQuery());
    }

    @Test
    void testQueryAnnotationClass() {
        // Test QueryAnnotation class functionality
        String methodCode = """
                @Query(value = "SELECT * FROM users", nativeQuery = true)
                public List<User> findAllUsersNative();
                """;
        
        String classCode = """
                package com.example;
                import org.springframework.data.jpa.repository.Query;
                public interface TestRepo {
                    %s
                }
                """.formatted(methodCode);
        
        CompilationUnit cu = javaParser.parse(classCode).getResult().orElseThrow();
        MethodDeclaration method = cu.findFirst(MethodDeclaration.class).orElseThrow();
        
        List<QueryAnnotation> annotations = RepositoryAnalyzer.extractQueryAnnotations(method);
        assertEquals(1, annotations.size());
        
        QueryAnnotation queryAnnotation = annotations.get(0);
        assertEquals("Query", queryAnnotation.getAnnotationName());
        assertTrue(queryAnnotation.getValue().contains("SELECT * FROM users"));
        assertTrue(queryAnnotation.isNative());
        assertNotNull(queryAnnotation.getOriginalAnnotation());
    }

    @Test
    void testRepositoryMetadataClass() {
        // Test RepositoryMetadata class functionality
        String repositoryCode = """
                package com.example.repository;
                
                import org.springframework.data.jpa.repository.JpaRepository;
                
                public interface TestRepository extends JpaRepository<Entity, Long> {
                    Entity findByName(String name);
                }
                """;
        
        CompilationUnit cu = javaParser.parse(repositoryCode).getResult().orElseThrow();
        ClassOrInterfaceDeclaration repo = cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
        
        TypeWrapper typeWrapper = new TypeWrapper(repo);
        List<String> extendedInterfaces = List.of("JpaRepository<Entity, Long>");
        List<RepositoryMethod> methods = RepositoryAnalyzer.extractRepositoryMethods(typeWrapper);
        
        RepositoryMetadata metadata = new RepositoryMetadata(
            "com.example.repository.TestRepository", true, extendedInterfaces, methods, typeWrapper);
        
        assertEquals("com.example.repository.TestRepository", metadata.getFullyQualifiedName());
        assertTrue(metadata.isJpaRepository());
        assertEquals(extendedInterfaces, metadata.getExtendedInterfaces());
        assertEquals(methods, metadata.getMethods());
        assertEquals(typeWrapper, metadata.getTypeWrapper());
    }

    @Test
    void testInheritanceHierarchyHandling() {
        // Test repository with multiple inheritance levels
        String repositoryCode = """
                package com.example.repository;
                
                import org.springframework.data.jpa.repository.JpaRepository;
                import org.springframework.data.repository.PagingAndSortingRepository;
                
                public interface ComplexRepository extends JpaRepository<Entity, Long>, PagingAndSortingRepository<Entity, Long> {
                    Entity findByName(String name);
                }
                """;
        
        CompilationUnit cu = javaParser.parse(repositoryCode).getResult().orElseThrow();
        ClassOrInterfaceDeclaration repo = cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
        
        TypeWrapper typeWrapper = new TypeWrapper(repo);
        
        assertTrue(RepositoryAnalyzer.isJpaRepository(typeWrapper));
        
        RepositoryMetadata metadata = RepositoryAnalyzer.analyzeRepository(
            "com.example.repository.ComplexRepository", typeWrapper);
        
        assertEquals(2, metadata.getExtendedInterfaces().size());
        assertTrue(metadata.getExtendedInterfaces().stream()
            .anyMatch(iface -> iface.contains("JpaRepository")));
        assertTrue(metadata.getExtendedInterfaces().stream()
            .anyMatch(iface -> iface.contains("PagingAndSortingRepository")));
    }

    @Test
    void testEdgeCasesAndErrorHandling() {
        // Test with empty repository interface
        String emptyRepositoryCode = """
                package com.example.repository;
                
                import org.springframework.data.jpa.repository.JpaRepository;
                
                public interface EmptyRepository extends JpaRepository<Entity, Long> {
                }
                """;
        
        CompilationUnit cu = javaParser.parse(emptyRepositoryCode).getResult().orElseThrow();
        ClassOrInterfaceDeclaration repo = cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
        
        TypeWrapper typeWrapper = new TypeWrapper(repo);
        
        assertTrue(RepositoryAnalyzer.isJpaRepository(typeWrapper));
        
        List<RepositoryMethod> methods = RepositoryAnalyzer.extractRepositoryMethods(typeWrapper);
        assertTrue(methods.isEmpty());
        
        List<QueryAnnotation> annotations = RepositoryAnalyzer.extractQueryAnnotations(typeWrapper);
        assertTrue(annotations.isEmpty());
    }
}