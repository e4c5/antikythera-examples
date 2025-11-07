package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.evaluator.mock.MockingRegistry;
import sa.com.cloudsolutions.antikythera.generator.TypeWrapper;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;
import sa.com.cloudsolutions.antikythera.parser.MavenHelper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Comprehensive tests for HardDelete analyzer to verify it correctly identifies
 * hard delete operations in JPA repositories while distinguishing them from soft deletes.
 */
class HardDeleteTest {
    protected final PrintStream standardOut = System.out;
    protected final ByteArrayOutputStream outContent = new ByteArrayOutputStream();

    @BeforeAll
    static void beforeClass() throws IOException, XmlPullParserException {
        Settings.loadConfigMap(new File("src/test/resources/generator2.yml"));
        MockingRegistry.reset();
        MavenHelper mavenHelper = new MavenHelper();
        mavenHelper.readPomFile();
        mavenHelper.buildJarPaths();
        AbstractCompiler.reset();
        AbstractCompiler.preProcess();
    }

    @BeforeEach
    void before() {
        System.setOut(new PrintStream(outContent));
    }

    @Test
    void testNoHardDeleteDetection() {
        TypeDeclaration<?> decl = AntikytheraRunTime.getTypeDeclaration(
                "sa.com.cloudsolutions.antikythera.testhelper.repository.FakeRepository").orElseThrow();
        MethodDeclaration method = decl.getMethodsByName("deleteByNothing").get(0);
        NodeList<AnnotationExpr> ann = method.getAnnotations();
        method.setAnnotations(new NodeList<>());
        HardDelete.detectHardDeletes();
        method.setAnnotations(ann);
        assertEquals("""
                sa.com.cloudsolutions.antikythera.testhelper.service.FakeService,deleteAll,fakeRepository.deleteAll()
                sa.com.cloudsolutions.antikythera.testhelper.service.FakeService,deleteById,fakeRepository.deleteById(10)
                sa.com.cloudsolutions.antikythera.testhelper.service.FakeService,deleteNoDelete,fakeRepository.deleteByNothing(10)
                """, outContent.toString());
    }

    @Test
    void testHardDeleteDetection() {

        HardDelete.detectHardDeletes();
        assertEquals("""
                sa.com.cloudsolutions.antikythera.testhelper.service.FakeService,deleteAll,fakeRepository.deleteAll()
                sa.com.cloudsolutions.antikythera.testhelper.service.FakeService,deleteById,fakeRepository.deleteById(10)
                """, outContent.toString());
    }

}
