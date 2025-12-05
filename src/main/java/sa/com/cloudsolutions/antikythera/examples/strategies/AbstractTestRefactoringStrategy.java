package sa.com.cloudsolutions.antikythera.examples.strategies;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.examples.TestRefactorer;
import sa.com.cloudsolutions.antikythera.examples.TestRefactoringStrategy;

import java.util.Set;

/**
 * Abstract base for framework-specific refactoring strategies.
 *
 * For the first wiring step, we delegate to the existing
 * TestRefactorer.applyRefactoring
 * to keep behavior unchanged, while enabling per-framework strategies to be
 * plugged in.
 */
public abstract class AbstractTestRefactoringStrategy implements TestRefactoringStrategy {
    private static final Logger logger = LoggerFactory.getLogger(AbstractTestRefactoringStrategy.class);
    public static final String CONVERTED = "CONVERTED";
    public static final String REVERTED = "REVERTED";

    protected CompilationUnit currentCu;

    protected abstract boolean isJunit5();

    /**
     * Framework-specific hook to apply unit-test style (runner/extension) when
     * converting
     * a Spring test to a pure unit test.
     */
    protected abstract boolean applyFrameworkUnitStyle(ClassOrInterfaceDeclaration decl, boolean isMockito1,
            String currentAnnotation);

    /**
     * New shared decision pipeline (Template Method). Mirrors
     * TestRefactorer.applyRefactoring behavior.
     */
    @Override
    public TestRefactorer.RefactorOutcome refactor(ClassOrInterfaceDeclaration decl,
            Set<TestRefactorer.ResourceType> resources,
            boolean hasSliceSupport,
            String springBootVersion,
            boolean isMockito1,
            CompilationUnit cu,
            TestRefactorer refactorer) {
        this.currentCu = cu;
        String currentAnnotation = determineCurrentAnnotation(decl);
        TestRefactorer.RefactorOutcome outcome = new TestRefactorer.RefactorOutcome(decl.getNameAsString());
        if (currentAnnotation == null) {
            outcome.action = "SKIPPED";
            outcome.reason = "No recognized class-level test annotation";
            outcome.modified = false;
            return outcome;
        }
        outcome.originalAnnotation = currentAnnotation;

        boolean modified = false;
        String className = decl.getNameAsString();

        if (requiresRunningServer(decl)) {
            if (!"SpringBootTest".equals(currentAnnotation)) {
                outcome.action = REVERTED;
                outcome.newAnnotation = "@SpringBootTest(webEnvironment = RANDOM_PORT)";
                outcome.reason = "Requires running server (TestRestTemplate/LocalServerPort)";
                logger.info("Reverting {} to @SpringBootTest (Requires running server)", className);
                modified = replaceAnnotation(decl, currentAnnotation, "SpringBootTest",
                        "org.springframework.boot.test.context.SpringBootTest");
                addRandomPortConfig(decl);
            } else {
                outcome.action = "KEPT";
                outcome.newAnnotation = "@SpringBootTest(webEnvironment = RANDOM_PORT)";
                outcome.reason = "Requires running server";
                logger.info("Keeping {} as @SpringBootTest (Requires running server)", className);
                addRandomPortConfig(decl);
            }
        } else if (resources.isEmpty()) {
            outcome.action = CONVERTED;
            outcome.newAnnotation = "Unit Test";
            outcome.reason = "No resources detected (all mocked)";
            logger.info("Converting {} to Unit Test", className);
            modified = convertToUnitTest(decl, currentAnnotation, isMockito1);
        } else if (isSpringBootAtLeast(springBootVersion, "1.4.0") && hasSliceSupport) {
            // JPA slice
            if (resources.contains(TestRefactorer.ResourceType.DATABASE_JPA)
                    && !resources.contains(TestRefactorer.ResourceType.JDBC)
                    && !resources.contains(TestRefactorer.ResourceType.REDIS)
                    && !resources.contains(TestRefactorer.ResourceType.KAFKA)
                    && !resources.contains(TestRefactorer.ResourceType.WEB)
                    && !resources.contains(TestRefactorer.ResourceType.WEBFLUX)
                    && !resources.contains(TestRefactorer.ResourceType.REST_CLIENT)
                    && !resources.contains(TestRefactorer.ResourceType.JSON)
                    && !resources.contains(TestRefactorer.ResourceType.GRAPHQL)) {
                if (!"DataJpaTest".equals(currentAnnotation)) {
                    outcome.action = CONVERTED;
                    outcome.newAnnotation = "@DataJpaTest";
                    outcome.reason = "Only DATABASE_JPA resource detected";
                    logger.info("Converting {} to @DataJpaTest", className);
                    modified = replaceAnnotation(decl, currentAnnotation, "DataJpaTest",
                            "org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest");
                } else {
                    outcome.action = "KEPT";
                    outcome.newAnnotation = "@DataJpaTest";
                    outcome.reason = "Already optimal";
                }
                // JDBC slice
            } else if (resources.contains(TestRefactorer.ResourceType.JDBC)
                    && !resources.contains(TestRefactorer.ResourceType.DATABASE_JPA)
                    && !resources.contains(TestRefactorer.ResourceType.REDIS)
                    && !resources.contains(TestRefactorer.ResourceType.KAFKA)
                    && !resources.contains(TestRefactorer.ResourceType.WEB)
                    && !resources.contains(TestRefactorer.ResourceType.WEBFLUX)
                    && !resources.contains(TestRefactorer.ResourceType.REST_CLIENT)
                    && !resources.contains(TestRefactorer.ResourceType.JSON)
                    && !resources.contains(TestRefactorer.ResourceType.GRAPHQL)) {
                if (!"JdbcTest".equals(currentAnnotation)) {
                    outcome.action = CONVERTED;
                    outcome.newAnnotation = "@JdbcTest";
                    outcome.reason = "Only JDBC resource detected";
                    logger.info("Converting {} to @JdbcTest", className);
                    modified = replaceAnnotation(decl, currentAnnotation, "JdbcTest",
                            "org.springframework.boot.test.autoconfigure.jdbc.JdbcTest");
                } else {
                    outcome.action = "KEPT";
                    outcome.newAnnotation = "@JdbcTest";
                    outcome.reason = "Already optimal";
                }
                // Web MVC slice (JSON allowed)
            } else if (resources.contains(TestRefactorer.ResourceType.WEB)
                    && !resources.contains(TestRefactorer.ResourceType.DATABASE_JPA)
                    && !resources.contains(TestRefactorer.ResourceType.JDBC)
                    && !resources.contains(TestRefactorer.ResourceType.REDIS)
                    && !resources.contains(TestRefactorer.ResourceType.KAFKA)
                    && !resources.contains(TestRefactorer.ResourceType.REST_CLIENT)
                    && !resources.contains(TestRefactorer.ResourceType.WEBFLUX)) {
                if (!"WebMvcTest".equals(currentAnnotation)) {
                    outcome.action = CONVERTED;
                    outcome.newAnnotation = "@WebMvcTest";
                    outcome.reason = "Only WEB resource detected (JSON allowed)";
                    logger.info("Converting {} to @WebMvcTest", className);
                    modified = replaceAnnotation(decl, currentAnnotation, "WebMvcTest",
                            "org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest");
                } else {
                    outcome.action = "KEPT";
                    outcome.newAnnotation = "@WebMvcTest";
                    outcome.reason = "Already optimal";
                }
                // WebFlux slice
            } else if (resources.contains(TestRefactorer.ResourceType.WEBFLUX)
                    && !resources.contains(TestRefactorer.ResourceType.DATABASE_JPA)
                    && !resources.contains(TestRefactorer.ResourceType.JDBC)
                    && !resources.contains(TestRefactorer.ResourceType.REDIS)
                    && !resources.contains(TestRefactorer.ResourceType.KAFKA)
                    && !resources.contains(TestRefactorer.ResourceType.WEB)
                    && !resources.contains(TestRefactorer.ResourceType.REST_CLIENT)) {
                if (!"WebFluxTest".equals(currentAnnotation)) {
                    outcome.action = CONVERTED;
                    outcome.newAnnotation = "@WebFluxTest";
                    outcome.reason = "Only WEBFLUX resource detected";
                    logger.info("Converting {} to @WebFluxTest", className);
                    modified = replaceAnnotation(decl, currentAnnotation, "WebFluxTest",
                            "org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest");
                } else {
                    outcome.action = "KEPT";
                    outcome.newAnnotation = "@WebFluxTest";
                    outcome.reason = "Already optimal";
                }
                // REST client slice
            } else if (resources.contains(TestRefactorer.ResourceType.REST_CLIENT)
                    && !resources.contains(TestRefactorer.ResourceType.DATABASE_JPA)
                    && !resources.contains(TestRefactorer.ResourceType.JDBC)
                    && !resources.contains(TestRefactorer.ResourceType.REDIS)
                    && !resources.contains(TestRefactorer.ResourceType.KAFKA)
                    && !resources.contains(TestRefactorer.ResourceType.WEB)
                    && !resources.contains(TestRefactorer.ResourceType.WEBFLUX)
                    && !resources.contains(TestRefactorer.ResourceType.JSON)) {
                if (!"RestClientTest".equals(currentAnnotation)) {
                    outcome.action = CONVERTED;
                    outcome.newAnnotation = "@RestClientTest";
                    outcome.reason = "Only REST_CLIENT resource detected";
                    logger.info("Converting {} to @RestClientTest", className);
                    modified = replaceAnnotation(decl, currentAnnotation, "RestClientTest",
                            "org.springframework.boot.test.autoconfigure.web.client.RestClientTest");
                } else {
                    outcome.action = "KEPT";
                    outcome.newAnnotation = "@RestClientTest";
                    outcome.reason = "Already optimal";
                }
                // JSON slice
            } else if (resources.contains(TestRefactorer.ResourceType.JSON)
                    && !resources.contains(TestRefactorer.ResourceType.DATABASE_JPA)
                    && !resources.contains(TestRefactorer.ResourceType.JDBC)
                    && !resources.contains(TestRefactorer.ResourceType.REDIS)
                    && !resources.contains(TestRefactorer.ResourceType.KAFKA)
                    && !resources.contains(TestRefactorer.ResourceType.WEB)
                    && !resources.contains(TestRefactorer.ResourceType.WEBFLUX)
                    && !resources.contains(TestRefactorer.ResourceType.REST_CLIENT)) {
                if (!"JsonTest".equals(currentAnnotation)) {
                    outcome.action = CONVERTED;
                    outcome.newAnnotation = "@JsonTest";
                    outcome.reason = "Only JSON resource detected";
                    logger.info("Converting {} to @JsonTest", className);
                    modified = replaceAnnotation(decl, currentAnnotation, "JsonTest",
                            "org.springframework.boot.test.autoconfigure.json.JsonTest");
                } else {
                    outcome.action = "KEPT";
                    outcome.newAnnotation = "@JsonTest";
                    outcome.reason = "Already optimal";
                }
                // GraphQL slice
            } else if (resources.contains(TestRefactorer.ResourceType.GRAPHQL)
                    && !resources.contains(TestRefactorer.ResourceType.DATABASE_JPA)
                    && !resources.contains(TestRefactorer.ResourceType.JDBC)
                    && !resources.contains(TestRefactorer.ResourceType.REDIS)
                    && !resources.contains(TestRefactorer.ResourceType.KAFKA)
                    && !resources.contains(TestRefactorer.ResourceType.WEB)
                    && !resources.contains(TestRefactorer.ResourceType.WEBFLUX)
                    && !resources.contains(TestRefactorer.ResourceType.REST_CLIENT)
                    && !resources.contains(TestRefactorer.ResourceType.JSON)) {
                if (!"GraphQlTest".equals(currentAnnotation)) {
                    outcome.action = CONVERTED;
                    outcome.newAnnotation = "@GraphQlTest";
                    outcome.reason = "Only GRAPHQL resource detected";
                    logger.info("Converting {} to @GraphQlTest", className);
                    modified = replaceAnnotation(decl, currentAnnotation, "GraphQlTest",
                            "org.springframework.boot.test.autoconfigure.graphql.GraphQlTest");
                } else {
                    outcome.action = "KEPT";
                    outcome.newAnnotation = "@GraphQlTest";
                    outcome.reason = "Already optimal";
                }
            } else {
                if (!"SpringBootTest".equals(currentAnnotation)) {
                    outcome.action = REVERTED;
                    outcome.newAnnotation = "@SpringBootTest";
                    outcome.reason = "Complex resources found: " + resources;
                    logger.info("Reverting {} to @SpringBootTest (Complex resources found)", className);
                    modified = replaceAnnotation(decl, currentAnnotation, "SpringBootTest",
                            "org.springframework.boot.test.context.SpringBootTest");
                } else {
                    outcome.action = "KEPT";
                    outcome.newAnnotation = "@SpringBootTest";
                    outcome.reason = "Complex resources found: " + resources;
                    logger.debug("Keeping {} as @SpringBootTest", className);
                }
            }
        } else {
            if (!"SpringBootTest".equals(currentAnnotation)) {
                outcome.action = REVERTED;
                outcome.newAnnotation = "@SpringBootTest";
                outcome.reason = "Slice tests not supported (version/deps)";
                logger.info("Reverting {} to @SpringBootTest (Slice tests not supported)", className);
                modified = replaceAnnotation(decl, currentAnnotation, "SpringBootTest",
                        "org.springframework.boot.test.context.SpringBootTest");
            } else {
                outcome.action = "KEPT";
                outcome.newAnnotation = "@SpringBootTest";
                outcome.reason = hasSliceSupport ? "Spring Boot < 1.4.0" : "Slice test dependencies not found";
                if (!hasSliceSupport) {
                    logger.debug("Keeping {} as @SpringBootTest (Slice test dependencies not found)", className);
                } else {
                    logger.debug("Keeping {} as @SpringBootTest (Spring Boot < 1.4.0 does not support slice tests)",
                            className);
                }
            }
        }
        outcome.modified = modified;
        return outcome;
    }

    protected String determineCurrentAnnotation(ClassOrInterfaceDeclaration decl) {
        if (decl.getAnnotationByName("SpringBootTest").isPresent())
            return "SpringBootTest";
        if (decl.getAnnotationByName("DataJpaTest").isPresent())
            return "DataJpaTest";
        if (decl.getAnnotationByName("JdbcTest").isPresent())
            return "JdbcTest";
        if (decl.getAnnotationByName("WebMvcTest").isPresent())
            return "WebMvcTest";
        if (decl.getAnnotationByName("WebFluxTest").isPresent())
            return "WebFluxTest";
        if (decl.getAnnotationByName("RestClientTest").isPresent())
            return "RestClientTest";
        if (decl.getAnnotationByName("JsonTest").isPresent())
            return "JsonTest";
        if (decl.getAnnotationByName("GraphQlTest").isPresent())
            return "GraphQlTest";
        return null;
    }

    private boolean isSpringBootAtLeast(String springBootVersion, String version) {
        return compareVersions(springBootVersion, version) >= 0;
    }

    private int compareVersions(String v1, String v2) {
        if (v1 == null)
            return -1;
        if (v2 == null)
            return 1;
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        int length = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < length; i++) {
            int part1 = i < parts1.length ? parseVersionPart(parts1[i]) : 0;
            int part2 = i < parts2.length ? parseVersionPart(parts2[i]) : 0;
            if (part1 != part2) {
                return Integer.compare(part1, part2);
            }
        }
        return 0;
    }

    private int parseVersionPart(String part) {
        try {
            return Integer.parseInt(part.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Convert to pure unit test using Mockito annotations and framework-specific
     * unit style.
     */
    protected boolean convertToUnitTest(ClassOrInterfaceDeclaration decl, String currentAnnotation,
            boolean isMockito1) {
        boolean modified = false;
        // Replace class-level annotation with Mockito runner/extension
        modified |= applyFrameworkUnitStyle(decl, isMockito1, currentAnnotation);

        // Convert field annotations @Autowired/@Inject to @Mock/@InjectMocks; @MockBean
        // -> @Mock
        String testClassName = decl.getNameAsString();
        String sutName = testClassName.endsWith("Test") ? testClassName.substring(0, testClassName.length() - 4)
                : testClassName.endsWith("Tests") ? testClassName.substring(0, testClassName.length() - 5) : "";

        String sutFieldName = null;

        for (com.github.javaparser.ast.body.FieldDeclaration field : decl.getFields()) {
            java.util.Optional<com.github.javaparser.ast.expr.AnnotationExpr> autowired = field
                    .getAnnotationByName("Autowired");
            java.util.Optional<com.github.javaparser.ast.expr.AnnotationExpr> inject = field
                    .getAnnotationByName("Inject");

            if (autowired.isPresent() || inject.isPresent()) {
                com.github.javaparser.ast.expr.AnnotationExpr annotationToReplace = autowired.orElseGet(inject::get);

                String fieldType = field.getElementType().asString();
                if (!sutName.isEmpty() && fieldType.equals(sutName)) {
                    annotationToReplace.replace(new com.github.javaparser.ast.expr.MarkerAnnotationExpr("InjectMocks"));
                    decl.findCompilationUnit().ifPresent(cu2 -> cu2.addImport("org.mockito.InjectMocks"));
                    sutFieldName = field.getVariables().get(0).getNameAsString();
                } else {
                    annotationToReplace.replace(new com.github.javaparser.ast.expr.MarkerAnnotationExpr("Mock"));
                    decl.findCompilationUnit().ifPresent(cu2 -> cu2.addImport("org.mockito.Mock"));
                }
                modified = true;
            }
            java.util.Optional<com.github.javaparser.ast.expr.AnnotationExpr> mockBean = field
                    .getAnnotationByName("MockBean");
            if (mockBean.isPresent()) {
                mockBean.get().replace(new com.github.javaparser.ast.expr.MarkerAnnotationExpr("Mock"));
                decl.findCompilationUnit().ifPresent(cu2 -> cu2.addImport("org.mockito.Mock"));
                modified = true;
            }
        }

        if (sutFieldName != null) {
            injectValueFields(decl, sutName, sutFieldName);
        }

        return modified;
    }

    // ===== Shared helper methods extracted/mirrored from TestRefactorer (available
    // for strategies) =====

    protected boolean replaceAnnotation(ClassOrInterfaceDeclaration decl, String oldName, String newName,
            String newImport) {
        return decl.getAnnotationByName(oldName).map(oldAnnotation -> {
            oldAnnotation.replace(new com.github.javaparser.ast.expr.MarkerAnnotationExpr(newName));
            decl.findCompilationUnit().ifPresent(cu -> cu.addImport(newImport));

            // Remove @RunWith annotation when converting to slice tests
            // Slice tests (@DataJpaTest, @WebMvcTest, etc.) have built-in test runners
            // and are incompatible with @RunWith(SpringJUnit4ClassRunner.class)
            if (isSliceTestAnnotation(newName)) {
                decl.getAnnotationByName("RunWith").ifPresent(runWith -> {
                    runWith.remove();
                    logger.info("  Removed @RunWith annotation (not needed with {})", newName);
                });
            }

            return true;
        }).orElse(false);
    }

    /**
     * Check if the annotation is a Spring Boot slice test annotation.
     * These annotations have built-in test configuration and don't need @RunWith.
     */
    private boolean isSliceTestAnnotation(String annotationName) {
        return annotationName.equals("DataJpaTest")
                || annotationName.equals("JdbcTest")
                || annotationName.equals("WebMvcTest")
                || annotationName.equals("WebFluxTest")
                || annotationName.equals("RestClientTest")
                || annotationName.equals("JsonTest")
                || annotationName.equals("GraphQlTest");
    }

    protected boolean requiresRunningServer(ClassOrInterfaceDeclaration decl) {
        for (com.github.javaparser.ast.body.FieldDeclaration field : decl.getFields()) {
            if (field.getElementType().asString().equals("TestRestTemplate")) {
                return true;
            }
            if (field.getAnnotationByName("LocalServerPort").isPresent()) {
                return true;
            }
            if (field.getAnnotationByName("Value").isPresent()) {
                com.github.javaparser.ast.expr.AnnotationExpr annotation = field.getAnnotationByName("Value").get();
                String value = "";
                if (annotation.isSingleMemberAnnotationExpr()) {
                    value = annotation.asSingleMemberAnnotationExpr().getMemberValue().toString();
                } else if (annotation.isNormalAnnotationExpr()) {
                    for (com.github.javaparser.ast.expr.MemberValuePair pair : annotation.asNormalAnnotationExpr()
                            .getPairs()) {
                        if (pair.getNameAsString().equals("value")) {
                            value = pair.getValue().toString();
                            break;
                        }
                    }
                }

                if (value.contains("local.server.port")) {
                    return true;
                }
            }
        }
        return false;
    }

    protected void addRandomPortConfig(ClassOrInterfaceDeclaration decl) {
        java.util.Optional<com.github.javaparser.ast.expr.AnnotationExpr> springBootTest = decl
                .getAnnotationByName("SpringBootTest");
        if (springBootTest.isPresent()) {
            if (springBootTest.get().isNormalAnnotationExpr()) {
                com.github.javaparser.ast.expr.NormalAnnotationExpr normal = springBootTest.get()
                        .asNormalAnnotationExpr();
                boolean hasWebEnv = normal.getPairs().stream()
                        .anyMatch(p -> p.getNameAsString().equals("webEnvironment"));
                if (!hasWebEnv) {
                    normal.addPair("webEnvironment", "WebEnvironment.RANDOM_PORT");
                    decl.findCompilationUnit().ifPresent(
                            cu -> cu.addImport("org.springframework.boot.test.context.SpringBootTest.WebEnvironment"));
                }
            } else if (springBootTest.get().isMarkerAnnotationExpr()) {
                com.github.javaparser.ast.expr.NormalAnnotationExpr normal = new com.github.javaparser.ast.expr.NormalAnnotationExpr();
                normal.setName("SpringBootTest");
                normal.addPair("webEnvironment", "WebEnvironment.RANDOM_PORT");
                springBootTest.get().replace(normal);
                decl.findCompilationUnit().ifPresent(
                        cu -> cu.addImport("org.springframework.boot.test.context.SpringBootTest.WebEnvironment"));
            }
        }
        fixContextConfiguration(decl);
    }

    protected void fixContextConfiguration(ClassOrInterfaceDeclaration decl) {
        java.util.Optional<com.github.javaparser.ast.expr.AnnotationExpr> contextConfig = decl
                .getAnnotationByName("ContextConfiguration");
        if (contextConfig.isPresent() && contextConfig.get().isNormalAnnotationExpr()) {
            com.github.javaparser.ast.expr.NormalAnnotationExpr normal = contextConfig.get().asNormalAnnotationExpr();
            for (com.github.javaparser.ast.expr.MemberValuePair pair : normal.getPairs()) {
                if (pair.getNameAsString().equals("classes")) {
                    // Extract classes
                    com.github.javaparser.ast.expr.Expression value = pair.getValue();
                    java.util.List<String> classNames = new java.util.ArrayList<>();
                    if (value.isArrayInitializerExpr()) {
                        for (com.github.javaparser.ast.expr.Expression expr : value.asArrayInitializerExpr()
                                .getValues()) {
                            classNames.add(expr.toString());
                        }
                    } else if (value.isClassExpr()) {
                        classNames.add(value.toString());
                    }

                    // Remove 'classes' from ContextConfiguration
                    pair.remove();

                    // Add to @Import
                    addToImport(decl, classNames);
                    break;
                }
            }
            if (normal.getPairs().isEmpty()) {
                normal.remove();
            }
        }
    }

    protected void addToImport(ClassOrInterfaceDeclaration decl, java.util.List<String> classNames) {
        if (classNames.isEmpty())
            return;

        java.util.Optional<com.github.javaparser.ast.expr.AnnotationExpr> importAnnotation = decl
                .getAnnotationByName("Import");
        if (importAnnotation.isPresent()) {
            if (importAnnotation.get().isSingleMemberAnnotationExpr()) {
                com.github.javaparser.ast.expr.Expression existing = importAnnotation.get()
                        .asSingleMemberAnnotationExpr().getMemberValue();
                com.github.javaparser.ast.expr.ArrayInitializerExpr array;
                if (existing.isArrayInitializerExpr()) {
                    array = existing.asArrayInitializerExpr();
                } else {
                    array = new com.github.javaparser.ast.expr.ArrayInitializerExpr();
                    array.getValues().add(existing);
                    importAnnotation.get().asSingleMemberAnnotationExpr().setMemberValue(array);
                }
                for (String className : classNames) {
                    if (array.getValues().stream().noneMatch(v -> v.toString().equals(className))) {
                        array.getValues().add(new com.github.javaparser.ast.expr.NameExpr(className));
                    }
                }
            } else if (importAnnotation.get().isNormalAnnotationExpr()) {
                for (com.github.javaparser.ast.expr.MemberValuePair pair : importAnnotation.get()
                        .asNormalAnnotationExpr().getPairs()) {
                    if (pair.getNameAsString().equals("value")) {
                        com.github.javaparser.ast.expr.Expression existing = pair.getValue();
                        com.github.javaparser.ast.expr.ArrayInitializerExpr array;
                        if (existing.isArrayInitializerExpr()) {
                            array = existing.asArrayInitializerExpr();
                        } else {
                            array = new com.github.javaparser.ast.expr.ArrayInitializerExpr();
                            array.getValues().add(existing);
                            pair.setValue(array);
                        }
                        for (String className : classNames) {
                            if (array.getValues().stream().noneMatch(v -> v.toString().equals(className))) {
                                array.getValues().add(new com.github.javaparser.ast.expr.NameExpr(className));
                            }
                        }
                    }
                }
            }
        } else {
            com.github.javaparser.ast.expr.ArrayInitializerExpr array = new com.github.javaparser.ast.expr.ArrayInitializerExpr();
            for (String className : classNames) {
                array.getValues().add(new com.github.javaparser.ast.expr.NameExpr(className));
            }
            decl.addAnnotation(new com.github.javaparser.ast.expr.SingleMemberAnnotationExpr(
                    new com.github.javaparser.ast.expr.Name("Import"),
                    array));

            decl.findCompilationUnit().ifPresent(cu -> cu.addImport("org.springframework.context.annotation.Import"));
        }
    }

    protected void injectValueFields(ClassOrInterfaceDeclaration decl, String sutClassName, String sutFieldName) {
        java.util.Optional<sa.com.cloudsolutions.antikythera.generator.TypeWrapper> sutType = java.util.Optional
                .ofNullable(sa.com.cloudsolutions.antikythera.parser.AbstractCompiler.findType(currentCu,
                        new com.github.javaparser.ast.type.ClassOrInterfaceType(null, sutClassName)));

        if (sutType.isPresent() && sutType.get().getType().isClassOrInterfaceDeclaration()) {
            com.github.javaparser.ast.body.ClassOrInterfaceDeclaration sutDecl = sutType.get().getType()
                    .asClassOrInterfaceDeclaration();
            java.util.List<String> injectionStatements = new java.util.ArrayList<>();

            for (com.github.javaparser.ast.body.FieldDeclaration field : sutDecl.getFields()) {
                if (field.getAnnotationByName("Value").isPresent()) {
                    String fieldName = field.getVariables().get(0).getNameAsString();
                    String fieldType = field.getElementType().asString();
                    String valueToInject = getValueForType(fieldType);

                    injectionStatements.add("ReflectionTestUtils.setField(" + sutFieldName + ", \"" + fieldName + "\", "
                            + valueToInject + ");");
                }
            }

            if (!injectionStatements.isEmpty()) {
                decl.findCompilationUnit()
                        .ifPresent(cu -> cu.addImport("org.springframework.test.util.ReflectionTestUtils"));

                com.github.javaparser.ast.body.MethodDeclaration beforeEach = decl.getMethods().stream()
                        .filter(m -> m.getAnnotationByName("BeforeEach").isPresent()
                                || m.getAnnotationByName("Before").isPresent())
                        .findFirst()
                        .orElseGet(() -> {
                            com.github.javaparser.ast.body.MethodDeclaration m = decl.addMethod("setUp",
                                    com.github.javaparser.ast.Modifier.Keyword.PUBLIC);
                            if (isJunit5()) {
                                m.addAnnotation("BeforeEach");
                                decl.findCompilationUnit()
                                        .ifPresent(cu -> cu.addImport("org.junit.jupiter.api.BeforeEach"));
                            } else {
                                m.addAnnotation("Before");
                                decl.findCompilationUnit().ifPresent(cu -> cu.addImport("org.junit.Before"));
                            }
                            return m;
                        });

                com.github.javaparser.ast.stmt.BlockStmt body = beforeEach.getBody().orElseGet(() -> {
                    com.github.javaparser.ast.stmt.BlockStmt b = new com.github.javaparser.ast.stmt.BlockStmt();
                    beforeEach.setBody(b);
                    return b;
                });

                for (String stmt : injectionStatements) {
                    if (body.getStatements().stream().noneMatch(s -> s.toString().trim().equals(stmt))) {
                        body.addStatement(stmt);
                    }
                }
            }
        }
    }

    protected String getValueForType(String type) {
        switch (type) {
            case "String":
                return "\"test-value\"";
            case "boolean":
            case "Boolean":
                return "false";
            case "int":
            case "Integer":
                return "0";
            case "long":
            case "Long":
                return "0L";
            case "double":
            case "Double":
                return "0.0";
            default:
                return "null";
        }
    }
}
