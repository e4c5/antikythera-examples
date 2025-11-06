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
}
