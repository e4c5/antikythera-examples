package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.MarkerAnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;
import sa.com.cloudsolutions.antikythera.generator.TypeWrapper;

import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public class TestRefactorer {

    enum ResourceType {
        DATABASE, REDIS, KAFKA, WEB, NONE
    }

    private CompilationUnit currentCu;
    private boolean isJUnit5 = false;
    private String springBootVersion = "2.0.0"; // Default to 2.x as requested
    private boolean isMockito1 = false;
    private boolean hasSliceTestSupport = false;
    private boolean dryRun = false;

    public TestRefactorer(boolean dryRun) {
        this.dryRun = dryRun;
        detectVersions();
    }

    public TestRefactorer() {
        this(false);
    }

    private void detectVersions() {
        try {
            MavenXpp3Reader reader = new MavenXpp3Reader();
            String basePath = sa.com.cloudsolutions.antikythera.configuration.Settings.getBasePath();
            Path p;
            if (basePath.contains("src/main/java")) {
                p = Paths.get(basePath.replace("/src/main/java", ""), "pom.xml");
            } else if (basePath.contains("src/test/java")) {
                p = Paths.get(basePath.replace("/src/test/java", ""), "pom.xml");
            } else {
                p = Paths.get(basePath, "pom.xml");
            }

            if (p.toFile().exists()) {
                System.out.println("Reading POM from: " + p);
                Model model = reader.read(new FileReader(p.toFile()));

                // Check properties for version hints
                java.util.Properties props = model.getProperties();
                if (props != null) {
                    if (props.containsKey("spring.boot.version")) {
                        springBootVersion = props.getProperty("spring.boot.version");
                        System.out.println("Found spring.boot.version property: " + springBootVersion);
                    } else if (props.containsKey("spring-boot.version")) {
                        springBootVersion = props.getProperty("spring-boot.version");
                        System.out.println("Found spring-boot.version property: " + springBootVersion);
                    }
                }

                // Detect JUnit 5 and Slice Test Support
                for (Dependency dep : model.getDependencies()) {
                    if ("junit-jupiter-api".equals(dep.getArtifactId())
                            || "junit-jupiter-engine".equals(dep.getArtifactId())
                            || "junit-jupiter".equals(dep.getArtifactId())) {
                        isJUnit5 = true;
                        System.out.println("Detected JUnit 5");
                    }
                    // Detect Mockito 1.x
                    if ("mockito-core".equals(dep.getArtifactId()) || "mockito-all".equals(dep.getArtifactId())) {
                        String version = resolveVersion(model, dep.getVersion());
                        if (version != null && version.startsWith("1.")) {
                            isMockito1 = true;
                            System.out.println("Detected Mockito 1.x");
                        }
                    }
                    // Detect Slice Test Support
                    if ("spring-boot-starter-test".equals(dep.getArtifactId())
                            || "spring-boot-test-autoconfigure".equals(dep.getArtifactId())) {
                        hasSliceTestSupport = true;
                        System.out.println(
                                "Detected Slice Test Support (spring-boot-starter-test or spring-boot-test-autoconfigure)");
                    }
                }

                // Detect Spring Boot Version if not found in properties
                if ("2.0.0".equals(springBootVersion)) {
                    String detectedVersion = null;
                    if (model.getParent() != null
                            && "spring-boot-starter-parent".equals(model.getParent().getArtifactId())) {
                        detectedVersion = resolveVersion(model, model.getParent().getVersion());
                    } else {
                        for (Dependency dep : model.getDependencies()) {
                            if ("spring-boot-starter".equals(dep.getArtifactId())) {
                                detectedVersion = resolveVersion(model, dep.getVersion());
                                break;
                            }
                        }
                    }

                    if (detectedVersion != null && !detectedVersion.startsWith("${")) {
                        springBootVersion = detectedVersion;
                        System.out.println("Detected Spring Boot Version: " + springBootVersion);
                    }
                }

                // Fallback for Mockito detection if not explicitly found
                if (compareVersions(springBootVersion, "2.0.0") < 0) {
                    // Spring Boot 1.x usually uses Mockito 1.x
                    if (!isMockito1 && compareVersions(springBootVersion, "1.0.0") > 0) {
                        isMockito1 = true;
                        System.out.println("Inferred Mockito 1.x from Spring Boot version");
                    }
                }

                System.out.println("Final Spring Boot Version: " + springBootVersion);

                // Add missing slice test dependency if needed
                if (!hasSliceTestSupport && compareVersions(springBootVersion, "2.0.0") >= 0) {
                    System.out.println("Slice test support missing. Adding spring-boot-starter-test dependency...");
                    addDependencyToPom(model, p.toFile());
                    hasSliceTestSupport = true;
                }

            } else {
                System.out.println("POM file not found at: " + p);
            }
        } catch (Exception e) {
            System.err.println("Failed to detect versions: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void addDependencyToPom(Model model, java.io.File pomFile) {
        try {
            Dependency dep = new Dependency();
            dep.setGroupId("org.springframework.boot");
            dep.setArtifactId("spring-boot-starter-test");
            dep.setScope("test");
            // Version is usually managed by parent, but if not we might need to add it.
            // For now assuming managed or inherited from parent.

            model.addDependency(dep);

            if (dryRun) {
                System.out.println("[DRY RUN] Would add spring-boot-starter-test to pom.xml");
            } else {
                MavenXpp3Writer writer = new MavenXpp3Writer();
                try (FileWriter fileWriter = new FileWriter(pomFile)) {
                    writer.write(fileWriter, model);
                }
                System.out.println("Added spring-boot-starter-test to pom.xml");
            }
        } catch (Exception e) {
            System.err.println("Failed to add dependency to POM: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private String resolveVersion(Model model, String version) {
        if (version != null && version.startsWith("${") && version.endsWith("}")) {
            String propertyName = version.substring(2, version.length() - 1);
            String resolved = model.getProperties().getProperty(propertyName);
            if (resolved != null) {
                return resolved;
            }
        }
        return version;
    }

    private boolean isSpringBootAtLeast(String version) {
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

    public boolean refactor(CompilationUnit cu) {
        this.currentCu = cu;
        boolean modified = false;
        for (ClassOrInterfaceDeclaration decl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (analyzeClass(decl)) {
                modified = true;
            }
        }
        return modified;
    }

    private boolean analyzeClass(ClassOrInterfaceDeclaration decl) {
        String annotationName = null;
        if (decl.getAnnotationByName("SpringBootTest").isPresent()) {
            annotationName = "SpringBootTest";
        } else if (decl.getAnnotationByName("DataJpaTest").isPresent()) {
            annotationName = "DataJpaTest";
        } else if (decl.getAnnotationByName("WebMvcTest").isPresent()) {
            annotationName = "WebMvcTest";
        }

        if (annotationName != null) {
            Set<ResourceType> resources = new HashSet<>();

            // Analyze all test methods
            for (MethodDeclaration method : decl.getMethods()) {
                if (method.getAnnotationByName("Test").isPresent()) {
                    resources.addAll(analyzeInteractions(method, new HashSet<>(), decl));
                }
            }

            return applyRefactoring(decl, resources, annotationName);
        }
        return false;
    }

    private Set<ResourceType> analyzeInteractions(MethodDeclaration method, Set<String> visitedMethods,
            ClassOrInterfaceDeclaration testClass) {
        Set<ResourceType> resources = EnumSet.noneOf(ResourceType.class);
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
                    resolvedMethod.ifPresent(methodDeclaration -> resources
                            .addAll(analyzeInteractions(methodDeclaration, visitedMethods, testClass)));
                }
                super.visit(n, arg);
            }
        }, null);

        return resources;
    }

    private Optional<MethodDeclaration> resolveMethod(MethodCallExpr n, MethodDeclaration context) {
        if (n.getScope().isEmpty() || n.getScope().get().isThisExpr()) {
            return context.findAncestor(ClassOrInterfaceDeclaration.class)
                    .flatMap(c -> c.getMethodsByName(n.getNameAsString()).stream()
                            .filter(m -> m.getParameters().size() == n.getArguments().size())
                            .findFirst());
        }

        if (n.getScope().isPresent()) {
            String scopeName = n.getScope().get().toString();
            Optional<ClassOrInterfaceDeclaration> testClass = context.findAncestor(ClassOrInterfaceDeclaration.class);
            if (testClass.isPresent()) {
                for (FieldDeclaration field : testClass.get().getFields()) {
                    if (field.getVariable(0).getNameAsString().equals(scopeName)) {
                        Type type = field.getElementType();
                        TypeWrapper wrapper = AbstractCompiler.findType(currentCu, type);
                        if (wrapper != null && wrapper.getType() != null
                                && wrapper.getType().isClassOrInterfaceDeclaration()) {
                            return wrapper.getType().asClassOrInterfaceDeclaration()
                                    .getMethodsByName(n.getNameAsString()).stream()
                                    .filter(m -> m.getParameters().size() == n.getArguments().size())
                                    .findFirst();
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    private boolean isResourceCall(MethodCallExpr n, ClassOrInterfaceDeclaration testClass) {
        return getResourceType(n, testClass) != ResourceType.NONE;
    }

    private ResourceType getResourceType(MethodCallExpr n, ClassOrInterfaceDeclaration testClass) {
        if (n.getScope().isPresent()) {
            String scopeName = n.getScope().get().toString();
            // The class where the method call is happening (could be SUT)
            Optional<ClassOrInterfaceDeclaration> currentClass = n.findAncestor(ClassOrInterfaceDeclaration.class);

            if (currentClass.isPresent()) {
                for (FieldDeclaration field : currentClass.get().getFields()) {
                    if (field.getVariable(0).getNameAsString().equals(scopeName)) {

                        // 1. Check if the field itself is mocked (in current class)
                        if (field.getAnnotationByName("Mock").isPresent()
                                || field.getAnnotationByName("MockBean").isPresent()
                                || field.getAnnotationByName("SpyBean").isPresent()) {
                            return ResourceType.NONE;
                        }

                        Type type = field.getElementType();

                        // 2. Check if this type is mocked in the Test Class
                        // This handles the case where we are in SUT, and SUT uses a dependency,
                        // but that dependency is mocked in the Test Class.
                        if (testClass != null) {
                            for (FieldDeclaration testField : testClass.getFields()) {
                                if (testField.getElementType().asString().equals(type.asString())) {
                                    if (testField.getAnnotationByName("Mock").isPresent()
                                            || testField.getAnnotationByName("MockBean").isPresent()
                                            || testField.getAnnotationByName("SpyBean").isPresent()) {
                                        return ResourceType.NONE;
                                    }
                                }
                            }
                        }

                        return identifyResourceType(type);
                    }
                }
            }
        }
        return ResourceType.NONE;
    }

    private ResourceType identifyResourceType(Type type) {
        String typeName = type.asString();

        if (typeName.endsWith("Repository") || typeName.equals("JdbcTemplate") || typeName.equals("EntityManager")
                || typeName.equals("DataSource")) {
            TypeWrapper wrapper = AbstractCompiler.findType(currentCu, type);
            if (wrapper != null && wrapper.getType() != null) {
                if (wrapper.getType().isClassOrInterfaceDeclaration()) {
                    ClassOrInterfaceDeclaration decl = wrapper.getType().asClassOrInterfaceDeclaration();
                    if (decl.getExtendedTypes().stream().anyMatch(t -> t.getNameAsString().contains("Repository"))) {
                        return ResourceType.DATABASE;
                    }
                }
            }
            if (typeName.endsWith("Repository"))
                return ResourceType.DATABASE;
            if (typeName.equals("JdbcTemplate") || typeName.equals("EntityManager") || typeName.equals("DataSource"))
                return ResourceType.DATABASE;
        }

        if (typeName.contains("RedisTemplate")) {
            return ResourceType.REDIS;
        }

        if (typeName.contains("KafkaTemplate")) {
            return ResourceType.KAFKA;
        }

        if (typeName.equals("MockMvc") || typeName.equals("TestRestTemplate")) {
            return ResourceType.WEB;
        }

        return ResourceType.NONE;
    }

    private boolean applyRefactoring(ClassOrInterfaceDeclaration decl, Set<ResourceType> resources,
            String currentAnnotation) {
        String className = decl.getNameAsString();
        boolean modified = false;

        if (resources.isEmpty()) {
            System.out.println("Converting " + className + " to Unit Test");
            modified = convertToUnitTest(decl, currentAnnotation);
        } else if (isSpringBootAtLeast("1.4.0") && hasSliceTestSupport) {
            if (resources.contains(ResourceType.DATABASE) && !resources.contains(ResourceType.REDIS)
                    && !resources.contains(ResourceType.KAFKA) && !resources.contains(ResourceType.WEB)) {
                if (!"DataJpaTest".equals(currentAnnotation)) {
                    System.out.println("Converting " + className + " to @DataJpaTest");
                    modified = replaceAnnotation(decl, currentAnnotation, "DataJpaTest",
                            "org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest");
                }
            } else if (resources.contains(ResourceType.WEB) && !resources.contains(ResourceType.DATABASE)
                    && !resources.contains(ResourceType.REDIS) && !resources.contains(ResourceType.KAFKA)) {
                if (!"WebMvcTest".equals(currentAnnotation)) {
                    System.out.println("Converting " + className + " to @WebMvcTest");
                    modified = replaceAnnotation(decl, currentAnnotation, "WebMvcTest",
                            "org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest");
                }
            } else {
                if (!"SpringBootTest".equals(currentAnnotation)) {
                    System.out.println("Reverting " + className + " to @SpringBootTest (Complex resources found)");
                    modified = replaceAnnotation(decl, currentAnnotation, "SpringBootTest",
                            "org.springframework.boot.test.context.SpringBootTest");
                } else {
                    System.out.println("Keeping " + className + " as @SpringBootTest");
                }
            }
        } else {
            if (!"SpringBootTest".equals(currentAnnotation)) {
                System.out.println("Reverting " + className + " to @SpringBootTest (Slice tests not supported)");
                modified = replaceAnnotation(decl, currentAnnotation, "SpringBootTest",
                        "org.springframework.boot.test.context.SpringBootTest");
            } else {
                if (!hasSliceTestSupport) {
                    System.out.println(
                            "Keeping " + className + " as @SpringBootTest (Slice test dependencies not found)");
                } else {
                    System.out.println("Keeping " + className
                            + " as @SpringBootTest (Spring Boot < 1.4.0 does not support slice tests)");
                }
            }
        }
        return modified;
    }

    private boolean convertToUnitTest(ClassOrInterfaceDeclaration decl, String currentAnnotation) {
        boolean modified = false;

        // 1. Replace @SpringBootTest (or other) with appropriate runner/extension
        Optional<AnnotationExpr> testAnnotation = decl.getAnnotationByName(currentAnnotation);
        if (testAnnotation.isPresent()) {
            if (isJUnit5) {
                SingleMemberAnnotationExpr extendWith = new SingleMemberAnnotationExpr(
                        new Name("ExtendWith"),
                        new ClassExpr(new ClassOrInterfaceType(null, "MockitoExtension")));
                testAnnotation.get().replace(extendWith);
                decl.findCompilationUnit().ifPresent(cu -> cu.addImport("org.junit.jupiter.api.extension.ExtendWith"));
                decl.findCompilationUnit().ifPresent(cu -> cu.addImport("org.mockito.junit.jupiter.MockitoExtension"));
            } else {
                SingleMemberAnnotationExpr runWith = new SingleMemberAnnotationExpr(
                        new Name("RunWith"),
                        new ClassExpr(new ClassOrInterfaceType(null, "MockitoJUnitRunner")));
                testAnnotation.get().replace(runWith);
                decl.findCompilationUnit().ifPresent(cu -> cu.addImport("org.junit.runner.RunWith"));
                if (isMockito1) {
                    decl.findCompilationUnit().ifPresent(cu -> cu.addImport("org.mockito.runners.MockitoJUnitRunner"));
                } else {
                    decl.findCompilationUnit().ifPresent(cu -> cu.addImport("org.mockito.junit.MockitoJUnitRunner"));
                }
            }
            modified = true;
        }

        // 3. Convert @Autowired to @Mock / @InjectMocks
        String testClassName = decl.getNameAsString();
        String sutName = testClassName.endsWith("Test") ? testClassName.substring(0, testClassName.length() - 4)
                : testClassName.endsWith("Tests") ? testClassName.substring(0, testClassName.length() - 5) : "";

        for (FieldDeclaration field : decl.getFields()) {
            Optional<AnnotationExpr> autowired = field.getAnnotationByName("Autowired");
            Optional<AnnotationExpr> inject = field.getAnnotationByName("Inject");

            if (autowired.isPresent() || inject.isPresent()) {
                AnnotationExpr annotationToReplace = autowired.orElseGet(inject::get);

                String fieldType = field.getElementType().asString();
                if (!sutName.isEmpty() && fieldType.equals(sutName)) {
                    annotationToReplace.replace(new MarkerAnnotationExpr("InjectMocks"));
                    decl.findCompilationUnit().ifPresent(cu -> cu.addImport("org.mockito.InjectMocks"));
                } else {
                    annotationToReplace.replace(new MarkerAnnotationExpr("Mock"));
                    decl.findCompilationUnit().ifPresent(cu -> cu.addImport("org.mockito.Mock"));
                }
                modified = true;
            }
            // Also handle @MockBean -> @Mock
            Optional<AnnotationExpr> mockBean = field.getAnnotationByName("MockBean");
            if (mockBean.isPresent()) {
                mockBean.get().replace(new MarkerAnnotationExpr("Mock"));
                decl.findCompilationUnit().ifPresent(cu -> cu.addImport("org.mockito.Mock"));
                modified = true;
            }
        }

        return modified;
    }

    private boolean replaceAnnotation(ClassOrInterfaceDeclaration decl, String oldName, String newName,
            String newImport) {
        Optional<AnnotationExpr> oldAnnotation = decl.getAnnotationByName(oldName);
        if (oldAnnotation.isPresent()) {
            oldAnnotation.get().replace(new MarkerAnnotationExpr(newName));
            decl.findCompilationUnit().ifPresent(cu -> cu.addImport(newImport));
            return true;
        }
        return false;
    }
}
