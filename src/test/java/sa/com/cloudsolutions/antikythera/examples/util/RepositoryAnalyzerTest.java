package sa.com.cloudsolutions.antikythera.examples.util;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import sa.com.cloudsolutions.antikythera.generator.TypeWrapper;


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
}
