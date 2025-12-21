package com.raditha.maven;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.io.DefaultModelReader;
import org.apache.maven.model.io.DefaultModelWriter;
import org.apache.maven.model.io.ModelReader;
import org.apache.maven.model.io.ModelWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the complete parent POM flattening workflow
 */
class PomFlattenerIntegrationTest {

    private ModelReader modelReader;
    private ModelWriter modelWriter;
    private Path testResourcesPath;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        modelReader = new DefaultModelReader();
        modelWriter = new DefaultModelWriter();
        testResourcesPath = Paths.get("src/test/resources/integration-poms");
    }

    @Test
    void testCompleteWorkflowWithCorporateParent() throws Exception {
        // Setup: Copy POMs to temp directory with proper repository structure
        setupLocalRepo("corporate-parent.xml", "com.example.corp", "corporate-parent", "2.0.0");

        // Load child POM
        Model child = modelReader.read(
                testResourcesPath.resolve("corporate-child.xml").toFile(),
                Collections.emptyMap());

        assertNotNull(child.getParent(), "Child should have parent before flattening");

        // Execute: Resolve and flatten
        ParentPomResolver resolver = new ParentPomResolver(tempDir);
        Parent parent = child.getParent();
        List<Model> parentChain = resolver.resolveParentChain(parent, null);

        assertEquals(1, parentChain.size(), "Should have 1 parent level");
        assertEquals("corporate-parent", parentChain.get(0).getArtifactId());

        InheritanceFlattener flattener = new InheritanceFlattener();
        Model flattened = flattener.flattenInheritance(child, parentChain);

        // Verify: Parent removed
        assertNull(flattened.getParent(), "Parent should be removed after flattening");

        // Verify: Properties inherited
        assertNotNull(flattened.getProperties());
        assertEquals("17", flattened.getProperties().getProperty("java.version"));
        assertEquals("UTF-8", flattened.getProperties().getProperty("project.build.sourceEncoding"));
        assertEquals("5.10.0", flattened.getProperties().getProperty("junit.version"));

        // Verify: Dependencies have versions
        assertEquals(5, flattened.getDependencies().size());
        for (Dependency dep : flattened.getDependencies()) {
            assertNotNull(dep.getVersion(),
                    "Dependency " + dep.getArtifactId() + " should have version");
        }

        // Verify: Dependency management merged
        assertNotNull(flattened.getDependencyManagement());
        assertTrue(flattened.getDependencyManagement().getDependencies().size() >= 5,
                "Should have dependency management from parent");

        // Verify: Can write flattened POM
        Path outputPath = tempDir.resolve("flattened-corporate-child.xml");
        try (FileWriter fw = new FileWriter(outputPath.toFile())) {
            modelWriter.write(fw, Collections.emptyMap(), flattened);
        }

        assertTrue(Files.exists(outputPath), "Flattened POM should be written");
        assertTrue(Files.size(outputPath) > 100, "Flattened POM should have content");

        // Verify: Can read back flattened POM
        Model reloaded = modelReader.read(outputPath.toFile(), Collections.emptyMap());
        assertNull(reloaded.getParent(), "Reloaded POM should not have parent");
        assertEquals(flattened.getDependencies().size(), reloaded.getDependencies().size());
    }

    @Test
    void testMultiLevelHierarchyIntegration() throws Exception {
        // Setup 3-level hierarchy from unit test resources
        Path unitTestPoms = Paths.get("src/test/resources/test-poms");

        setupLocalRepo("grandparent.xml", "com.example.test", "grandparent", "1.0.0", unitTestPoms);
        setupLocalRepo("middle-parent.xml", "com.example.test", "middle-parent", "1.0.0", unitTestPoms);

        Model child = modelReader.read(
                unitTestPoms.resolve("multilevel-child.xml").toFile(),
                Collections.emptyMap());

        // Execute complete workflow
        ParentPomResolver resolver = new ParentPomResolver(tempDir);
        List<Model> parentChain = resolver.resolveParentChain(child.getParent(), null);

        assertEquals(2, parentChain.size(), "Should resolve 2-level parent chain");
        assertEquals("grandparent", parentChain.get(0).getArtifactId());
        assertEquals("middle-parent", parentChain.get(1).getArtifactId());

        InheritanceFlattener flattener = new InheritanceFlattener();
        Model flattened = flattener.flattenInheritance(child, parentChain);

        // Verify property override chain
        assertEquals("from-grandparent", flattened.getProperties().getProperty("root.property"));
        assertEquals("from-middle", flattened.getProperties().getProperty("middle.property"));
        assertEquals("from-child", flattened.getProperties().getProperty("child.property"));
        assertEquals("child-value", flattened.getProperties().getProperty("override.property"),
                "Child should win 3-level override");

        // Verify dependencies from all levels have versions
        assertAllDependenciesHaveVersions(flattened);
    }

    @Test
    void testProfileHandlingIntegration() throws Exception {
        setupLocalRepo("corporate-parent.xml", "com.example.corp", "corporate-parent", "2.0.0");

        Model child = modelReader.read(
                testResourcesPath.resolve("corporate-child.xml").toFile(),
                Collections.emptyMap());

        ParentPomResolver resolver = new ParentPomResolver(tempDir);
        List<Model> parentChain = resolver.resolveParentChain(child.getParent(), null);

        // Test with profiles
        InheritanceFlattener flattener = new InheritanceFlattener(false);
        Model flattened = flattener.flattenInheritance(child, parentChain);

        assertNotNull(flattened.getProfiles());
        assertTrue(flattened.getProfiles().size() > 0, "Should inherit parent profiles");

        boolean hasCorporateCi = flattened.getProfiles().stream()
                .anyMatch(p -> "corporate-ci".equals(p.getId()));
        assertTrue(hasCorporateCi, "Should have corporate-ci profile from parent");

        // Test without profiles
        InheritanceFlattener skipProfilesFlattener = new InheritanceFlattener(true);
        Model flattenedNoProfiles = skipProfilesFlattener.flattenInheritance(child, parentChain);

        assertTrue(flattenedNoProfiles.getProfiles() == null ||
                flattenedNoProfiles.getProfiles().isEmpty(),
                "Should skip profiles when flag is set");
    }

    @Test
    void testPomSizeIncrease() throws Exception {
        setupLocalRepo("corporate-parent.xml", "com.example.corp", "corporate-parent", "2.0.0");

        Path childPath = testResourcesPath.resolve("corporate-child.xml");
        Model child = modelReader.read(childPath.toFile(), Collections.emptyMap());

        long originalSize = Files.size(childPath);

        // Flatten
        ParentPomResolver resolver = new ParentPomResolver(tempDir);
        List<Model> parentChain = resolver.resolveParentChain(child.getParent(), null);
        InheritanceFlattener flattener = new InheritanceFlattener();
        Model flattened = flattener.flattenInheritance(child, parentChain);

        // Write flattened
        Path flattenedPath = tempDir.resolve("flattened.xml");
        try (FileWriter fw = new FileWriter(flattenedPath.toFile())) {
            modelWriter.write(fw, Collections.emptyMap(), flattened);
        }

        long flattenedSize = Files.size(flattenedPath);

        assertTrue(flattenedSize > originalSize,
                String.format("Flattened POM (%d bytes) should be larger than original (%d bytes)",
                        flattenedSize, originalSize));
    }

    /**
     * Helper to set up a POM in the fake local repository
     */
    private void setupLocalRepo(String sourceFile, String groupId, String artifactId, String version)
            throws IOException {
        setupLocalRepo(sourceFile, groupId, artifactId, version, testResourcesPath);
    }

    private void setupLocalRepo(String sourceFile, String groupId, String artifactId, String version, Path sourcePath)
            throws IOException {
        String groupPath = groupId.replace('.', '/');
        Path repoPath = tempDir
                .resolve(groupPath)
                .resolve(artifactId)
                .resolve(version);
        Files.createDirectories(repoPath);

        Path targetPom = repoPath.resolve(artifactId + "-" + version + ".pom");
        Files.copy(sourcePath.resolve(sourceFile), targetPom);
    }

    /**
     * Helper to verify all dependencies have versions
     */
    private void assertAllDependenciesHaveVersions(Model model) {
        if (model.getDependencies() != null) {
            for (Dependency dep : model.getDependencies()) {
                assertNotNull(dep.getVersion(),
                        "Dependency " + dep.getGroupId() + ":" + dep.getArtifactId() + " missing version");
            }
        }
    }
}
