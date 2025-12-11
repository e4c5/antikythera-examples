package com.raditha.cleanunit;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Framework-agnostic analyzer that inspects a test class to determine which
 * external resources it interacts with (DB, Web, JSON, etc.).
 *
 * This is extracted from TestRefactorer to enable reuse across strategies.
 */
public class TestResourceAnalyzer {

    /**
     * Analyze a test class and return the union of resources used by all @Test methods.
     */
    public Set<TestRefactorer.ResourceType> analyzeClass(ClassOrInterfaceDeclaration decl) {
        Set<TestRefactorer.ResourceType> resources = new HashSet<>();
        for (MethodDeclaration method : decl.getMethods()) {
            if (hasTestAnnotation(method)) {
                resources.addAll(analyzeInteractions(method, new HashSet<>(), decl));
            }
        }
        return resources;
    }

    private boolean hasTestAnnotation(MethodDeclaration method) {
        Optional<AnnotationExpr> test = method.getAnnotationByName("Test");
        return test.isPresent();
    }

    private Set<TestRefactorer.ResourceType> analyzeInteractions(MethodDeclaration method,
                                                                 Set<String> visitedMethods,
                                                                 ClassOrInterfaceDeclaration testClass) {
        Set<TestRefactorer.ResourceType> resources = EnumSet.noneOf(TestRefactorer.ResourceType.class);
        String methodSig = method.getSignature().asString();
        if (visitedMethods.contains(methodSig)) {
            return resources;
        }
        visitedMethods.add(methodSig);

        method.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(MethodCallExpr n, Void arg) {
                if (isResourceCall(n, testClass)) {
                    resources.add(getResourceType(n, testClass));
                } else {
                    Optional<MethodDeclaration> resolvedMethod = resolveMethod(n, method);
                    resolvedMethod.ifPresent(methodDeclaration -> resources.addAll(
                            analyzeInteractions(methodDeclaration, visitedMethods, testClass)));
                }
                super.visit(n, arg);
            }
        }, null);

        return resources;
    }

    private Optional<MethodDeclaration> resolveMethod(MethodCallExpr n, MethodDeclaration context) {
        Optional<Expression> scope = n.getScope();
        if (scope.isEmpty() || scope.get().isThisExpr()) {
            return context.findAncestor(ClassOrInterfaceDeclaration.class)
                    .flatMap(c -> c.getMethodsByName(n.getNameAsString()).stream()
                            .filter(m -> m.getParameters().size() == n.getArguments().size())
                            .findFirst());
        }

        if (scope.isPresent()) {
            String scopeName = scope.get().toString();
            Optional<ClassOrInterfaceDeclaration> testClass = context.findAncestor(ClassOrInterfaceDeclaration.class);
            if (testClass.isPresent()) {
                for (FieldDeclaration field : testClass.get().getFields()) {
                    if (field.getVariable(0).getNameAsString().equals(scopeName)) {
                        // Avoid heavy resolution via classloading in analyzer test context.
                        // We only need to detect resource usage, not resolve external method bodies.
                        return Optional.empty();
                    }
                }
            }
        }
        return Optional.empty();
    }

    private boolean isResourceCall(MethodCallExpr n, ClassOrInterfaceDeclaration testClass) {
        return getResourceType(n, testClass) != TestRefactorer.ResourceType.NONE;
    }

    private TestRefactorer.ResourceType getResourceType(MethodCallExpr n, ClassOrInterfaceDeclaration testClass) {
        Optional<Expression> scope = n.getScope();
        if (scope.isPresent()) {
            String scopeName = scope.get().toString();
            Optional<ClassOrInterfaceDeclaration> currentClass = n.findAncestor(ClassOrInterfaceDeclaration.class);

            if (currentClass.isPresent()) {
                for (FieldDeclaration field : currentClass.get().getFields()) {
                    if (field.getVariable(0).getNameAsString().equals(scopeName)) {
                        // If the field is a mock/spied bean in the current class, skip it.
                        if (field.getAnnotationByName("Mock").isPresent()
                                || field.getAnnotationByName("MockBean").isPresent()
                                || field.getAnnotationByName("SpyBean").isPresent()) {
                            return TestRefactorer.ResourceType.NONE;
                        }

                        Type type = field.getElementType();

                        // If this dependency is mocked in the test class, treat as NONE.
                        if (testClass != null) {
                            for (FieldDeclaration testField : testClass.getFields()) {
                                if (testField.getElementType().asString().equals(type.asString())) {
                                    if (testField.getAnnotationByName("Mock").isPresent()
                                            || testField.getAnnotationByName("MockBean").isPresent()
                                            || testField.getAnnotationByName("SpyBean").isPresent()) {
                                        return TestRefactorer.ResourceType.NONE;
                                    }
                                }
                            }
                        }

                        return identifyResourceType(type);
                    }
                }
            }
        }
        return TestRefactorer.ResourceType.NONE;
    }

    private TestRefactorer.ResourceType identifyResourceType(Type type) {
        String typeName = type.asString();
        // Normalize to simple name (support fully-qualified types in tests)
        String simple = typeName.contains(".") ? typeName.substring(typeName.lastIndexOf('.') + 1) : typeName;
        // JPA repositories / EntityManager
        if (simple.endsWith("Repository") || simple.equals("EntityManager")) {
            return TestRefactorer.ResourceType.DATABASE_JPA;
        }
        // JDBC stack
        if (simple.equals("JdbcTemplate") || simple.equals("NamedParameterJdbcTemplate")
                || simple.equals("DataSource")) {
            return TestRefactorer.ResourceType.JDBC;
        }
        if (simple.equals("RedisTemplate") || simple.equals("StringRedisTemplate")) {
            return TestRefactorer.ResourceType.REDIS;
        }
        if (simple.equals("KafkaTemplate")) {
            return TestRefactorer.ResourceType.KAFKA;
        }
        // Servlet web
        if (simple.equals("MockMvc") || simple.equals("TestRestTemplate")) {
            return TestRefactorer.ResourceType.WEB;
        }
        // Reactive WebFlux
        if (simple.equals("WebTestClient")) {
            return TestRefactorer.ResourceType.WEBFLUX;
        }
        if (simple.equals("MockRestServiceServer")) {
            return TestRefactorer.ResourceType.REST_CLIENT;
        }
        if (simple.startsWith("JacksonTester") || simple.startsWith("JsonTester")
                || simple.equals("ObjectMapper")) {
            return TestRefactorer.ResourceType.JSON;
        }
        if (simple.equals("GraphQlTester")) {
            return TestRefactorer.ResourceType.GRAPHQL;
        }
        return TestRefactorer.ResourceType.NONE;
    }
}
