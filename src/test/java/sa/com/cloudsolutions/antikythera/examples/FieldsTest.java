package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.generator.TypeWrapper;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FieldsTest {

    @BeforeEach
    void setUp() {
        AntikytheraRunTime.resetAll();
        // Clear private static map in Fields using reflection since it's not exposed
        try {
            java.lang.reflect.Field field = Fields.class.getDeclaredField("fieldDependencies");
            field.setAccessible(true);
            ((Map<?, ?>) field.get(null)).clear();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void testBuildDependenciesWithInheritance() {
        // Mock a repository
        String repoFqn = "com.example.Repo";
        CompilationUnit repoCu = new CompilationUnit();
        ClassOrInterfaceDeclaration repoDecl = repoCu.addClass("Repo").setInterface(true);
        TypeWrapper repoTw = new TypeWrapper(repoDecl);
        AntikytheraRunTime.addType(repoFqn, repoTw);
        AntikytheraRunTime.addCompilationUnit(repoFqn, repoCu);

        // Mock a base service with the repository field
        String baseServiceFqn = "com.example.BaseService";
        CompilationUnit baseCu = new CompilationUnit();
        ClassOrInterfaceDeclaration baseDecl = baseCu.addClass("BaseService");
        FieldDeclaration field = baseDecl.addField("Repo", "repo");
        TypeWrapper baseTw = new TypeWrapper(baseDecl);
        AntikytheraRunTime.addType(baseServiceFqn, baseTw);
        AntikytheraRunTime.addCompilationUnit(baseServiceFqn, baseCu);

        // Mock a subclass service
        String subServiceFqn = "com.example.SubService";
        CompilationUnit subCu = new CompilationUnit();
        ClassOrInterfaceDeclaration subDecl = subCu.addClass("SubService");
        subDecl.addExtendedType("BaseService");
        TypeWrapper subTw = new TypeWrapper(subDecl);
        AntikytheraRunTime.addType(subServiceFqn, subTw);
        AntikytheraRunTime.addCompilationUnit(subServiceFqn, subCu);

        // Set up inheritance in runtime
        AntikytheraRunTime.addSubClass(baseServiceFqn, subServiceFqn);

        // Run buildDependencies
        Fields.buildDependencies();

        // Verify dependencies
        Map<String, Set<String>> repoDeps = Fields.getFieldDependencies(repoFqn);
        assertNotNull(repoDeps);

        // BaseService should have the field
        assertTrue(repoDeps.containsKey(baseServiceFqn));
        assertTrue(repoDeps.get(baseServiceFqn).contains("repo"));

        // SubService should INHERIT the field dependency
        assertTrue(repoDeps.containsKey(subServiceFqn), "SubService should have inherited dependencies");
        assertTrue(repoDeps.get(subServiceFqn).contains("repo"), "SubService should inherit 'repo' field");
    }
}
