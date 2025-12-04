package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;
import sa.com.cloudsolutions.antikythera.generator.TypeWrapper;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public class TestRefactorer {

    enum ResourceType {
        DATABASE, REDIS, KAFKA, WEB, NONE
    }

    private CompilationUnit currentCu;

    public void refactor(CompilationUnit cu) {
        this.currentCu = cu;
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(this::analyzeClass);
    }

    private void analyzeClass(ClassOrInterfaceDeclaration decl) {
        if (decl.getAnnotationByName("SpringBootTest").isPresent()) {
            Set<ResourceType> resources = new HashSet<>();

            // Analyze all test methods
            for (MethodDeclaration method : decl.getMethods()) {
                if (method.getAnnotationByName("Test").isPresent()) {
                    resources.addAll(analyzeInteractions(method, new HashSet<>()));
                }
            }

            recommendRefactoring(decl, resources);
        }
    }

    private Set<ResourceType> analyzeInteractions(MethodDeclaration method, Set<String> visitedMethods) {
        Set<ResourceType> resources = EnumSet.noneOf(ResourceType.class);
        String methodSig = method.getSignature().asString();

        if (visitedMethods.contains(methodSig)) {
            return resources;
        }
        visitedMethods.add(methodSig);

        method.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(MethodCallExpr n, Void arg) {
                if (isResourceCall(n)) {
                    resources.add(getResourceType(n));
                } else {
                    // Try to resolve the method to analyze its interactions
                    Optional<MethodDeclaration> resolvedMethod = resolveMethod(n, method);
                    if (resolvedMethod.isPresent()) {
                        resources.addAll(analyzeInteractions(resolvedMethod.get(), visitedMethods));
                    }
                }
                super.visit(n, arg);
            }
        }, null);

        return resources;
    }

    private Optional<MethodDeclaration> resolveMethod(MethodCallExpr n, MethodDeclaration context) {
        // 1. Try local resolution (method in the same class)
        if (n.getScope().isEmpty() || n.getScope().get().isThisExpr()) {
            return context.findAncestor(ClassOrInterfaceDeclaration.class)
                    .flatMap(c -> c.getMethodsByName(n.getNameAsString()).stream()
                            .filter(m -> m.getParameters().size() == n.getArguments().size())
                            .findFirst());
        }

        // 2. Try to resolve using Antikythera's type system if scope is present
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

    private boolean isResourceCall(MethodCallExpr n) {
        return getResourceType(n) != ResourceType.NONE;
    }

    private ResourceType getResourceType(MethodCallExpr n) {
        if (n.getScope().isPresent()) {
            String scopeName = n.getScope().get().toString();
            Optional<ClassOrInterfaceDeclaration> testClass = n.findAncestor(ClassOrInterfaceDeclaration.class);
            if (testClass.isPresent()) {
                for (FieldDeclaration field : testClass.get().getFields()) {
                    if (field.getVariable(0).getNameAsString().equals(scopeName)) {
                        Type type = field.getElementType();
                        return identifyResourceType(type);
                    }
                }
            }
        }
        return ResourceType.NONE;
    }

    private ResourceType identifyResourceType(Type type) {
        String typeName = type.asString();

        // Check for Database
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

        // Check for Redis
        if (typeName.contains("RedisTemplate")) {
            return ResourceType.REDIS;
        }

        // Check for Kafka
        if (typeName.contains("KafkaTemplate")) {
            return ResourceType.KAFKA;
        }

        // Check for Web
        if (typeName.equals("MockMvc") || typeName.equals("TestRestTemplate")) {
            return ResourceType.WEB;
        }

        return ResourceType.NONE;
    }

    private void recommendRefactoring(ClassOrInterfaceDeclaration decl, Set<ResourceType> resources) {
        String className = decl.getNameAsString();
        if (resources.isEmpty()) {
            System.out.println(
                    "Recommendation for " + className + ": Convert to Unit Test (@ExtendWith(MockitoExtension.class))");
        } else if (resources.contains(ResourceType.DATABASE) && !resources.contains(ResourceType.REDIS)
                && !resources.contains(ResourceType.KAFKA) && !resources.contains(ResourceType.WEB)) {
            System.out.println("Recommendation for " + className + ": Convert to Slice Test (@DataJpaTest)");
        } else if (resources.contains(ResourceType.WEB) && !resources.contains(ResourceType.DATABASE)
                && !resources.contains(ResourceType.REDIS) && !resources.contains(ResourceType.KAFKA)) {
            System.out.println("Recommendation for " + className + ": Convert to Slice Test (@WebMvcTest)");
        } else {
            System.out.println("Recommendation for " + className + ": Keep @SpringBootTest (Uses: " + resources + ")");
        }
    }
}
