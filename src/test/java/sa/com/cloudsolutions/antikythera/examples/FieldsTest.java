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
        Fields.clearFieldDependencies();
    }

    @Test
    void testBuildDependenciesWithInheritance() {
        // Mock a repository
        String repoFqn = "com.example.Repo";
        CompilationUnit repoCu = new CompilationUnit();
        repoCu.setPackageDeclaration("com.example");
        ClassOrInterfaceDeclaration repoDecl = repoCu.addClass("Repo").setInterface(true);
        TypeWrapper repoTw = new TypeWrapper(repoDecl);
        AntikytheraRunTime.addType(repoFqn, repoTw);
        AntikytheraRunTime.addCompilationUnit(repoFqn, repoCu);

        // Mock a base service with the repository field
        String baseServiceFqn = "com.example.BaseService";
        CompilationUnit baseCu = new CompilationUnit();
        baseCu.setPackageDeclaration("com.example");
        ClassOrInterfaceDeclaration baseDecl = baseCu.addClass("BaseService");
        baseDecl.addField("Repo", "repo");
        TypeWrapper baseTw = new TypeWrapper(baseDecl);
        AntikytheraRunTime.addType(baseServiceFqn, baseTw);
        AntikytheraRunTime.addCompilationUnit(baseServiceFqn, baseCu);

        // Mock a subclass service
        String subServiceFqn = "com.example.SubService";
        CompilationUnit subCu = new CompilationUnit();
        subCu.setPackageDeclaration("com.example");
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
        assertNotNull(repoDeps, "Dependencies for Repo should not be null");

        // BaseService should have the field
        assertTrue(repoDeps.containsKey(baseServiceFqn), "BaseService should be in Repo dependencies");
        assertTrue(repoDeps.get(baseServiceFqn).contains("repo"), "BaseService should have 'repo' field");

        // SubService should INHERIT the field dependency
        assertTrue(repoDeps.containsKey(subServiceFqn), "SubService should have inherited dependencies");
        assertTrue(repoDeps.get(subServiceFqn).contains("repo"), "SubService should inherit 'repo' field");
    }
}
