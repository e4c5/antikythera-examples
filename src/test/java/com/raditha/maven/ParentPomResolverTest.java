package com.raditha.maven;

import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.io.DefaultModelReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ParentPomResolverTest {

    private ParentPomResolver resolver;
    private Path testResourcesPath;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        // Point to test resources
        testResourcesPath = Paths.get("src/test/resources/test-poms");

        // Use temp directory as fake local repository
        resolver = new ParentPomResolver(tempDir);
    }

    @Test
    void testResolveSimpleParent() throws Exception {
        // Copy parent to "local repository"
        setupLocalRepo("simple-parent.xml", "com.example.test", "simple-parent", "1.0.0");

        Parent parent = new Parent();
        parent.setGroupId("com.example.test");
        parent.setArtifactId("simple-parent");
        parent.setVersion("1.0.0");

        Model resolved = resolver.resolveParent(parent, null);

        assertNotNull(resolved);
        assertEquals("com.example.test", resolved.getGroupId());
        assertEquals("simple-parent", resolved.getArtifactId());
        assertEquals("1.0.0", resolved.getVersion());
        assertNotNull(resolved.getProperties());
        assertEquals("17", resolved.getProperties().getProperty("java.version"));
    }

    @Test
    void testResolveWithRelativePath() throws Exception {
        // Copy parent to a relative location
        Path parentPath = tempDir.resolve("parent-dir").resolve("pom.xml");
        Files.createDirectories(parentPath.getParent());
        Files.copy(testResourcesPath.resolve("simple-parent.xml"), parentPath);

        // Create child POM path
        Path childPath = tempDir.resolve("child-dir").resolve("pom.xml");
        Files.createDirectories(childPath.getParent());

        Parent parent = new Parent();
        parent.setGroupId("com.example.test");
        parent.setArtifactId("simple-parent");
        parent.setVersion("1.0.0");
        parent.setRelativePath("../parent-dir/pom.xml");

        Model resolved = resolver.resolveParent(parent, childPath);

        assertNotNull(resolved);
        assertEquals("simple-parent", resolved.getArtifactId());
    }

    @Test
    void testResolveParentChain() throws Exception {
        // Setup 3-level hierarchy in local repo
        setupLocalRepo("grandparent.xml", "com.example.test", "grandparent", "1.0.0");
        setupLocalRepo("middle-parent.xml", "com.example.test", "middle-parent", "1.0.0");

        Parent parent = new Parent();
        parent.setGroupId("com.example.test");
        parent.setArtifactId("middle-parent");
        parent.setVersion("1.0.0");

        List<Model> chain = resolver.resolveParentChain(parent, null);

        assertNotNull(chain);
        assertEquals(2, chain.size());

        // Chain should be from root to immediate parent
        assertEquals("grandparent", chain.get(0).getArtifactId());
        assertEquals("middle-parent", chain.get(1).getArtifactId());
    }

    @Test
    void testMissingParentThrowsException() {
        Parent parent = new Parent();
        parent.setGroupId("com.example.missing");
        parent.setArtifactId("non-existent");
        parent.setVersion("1.0.0");

        assertThrows(ParentResolutionException.class, () -> {
            resolver.resolveParent(parent, null);
        });
    }

    @Test
    void testParentResolutionFallback() throws Exception {
        // Setup parent in local repo
        setupLocalRepo("simple-parent.xml", "com.example.test", "simple-parent", "1.0.0");

        Parent parent = new Parent();
        parent.setGroupId("com.example.test");
        parent.setArtifactId("simple-parent");
        parent.setVersion("1.0.0");
        parent.setRelativePath("../nonexistent/pom.xml"); // This will fail

        // Should fall back to local repository
        Model resolved = resolver.resolveParent(parent, tempDir.resolve("child/pom.xml"));

        assertNotNull(resolved);
        assertEquals("simple-parent", resolved.getArtifactId());
    }

    /**
     * Helper to set up a POM in the fake local repository
     */
    private void setupLocalRepo(String sourceFile, String groupId, String artifactId, String version)
            throws IOException {
        String groupPath = groupId.replace('.', '/');
        Path repoPath = tempDir
                .resolve(groupPath)
                .resolve(artifactId)
                .resolve(version);
        Files.createDirectories(repoPath);

        Path targetPom = repoPath.resolve(artifactId + "-" + version + ".pom");
        Files.copy(testResourcesPath.resolve(sourceFile), targetPom);
    }
}
