package com.raditha.maven;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Profile;
import org.apache.maven.model.io.DefaultModelReader;
import org.apache.maven.model.io.ModelReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InheritanceFlattenerTest {

    private InheritanceFlattener flattener;
    private ModelReader modelReader;
    private Path testResourcesPath;

    @BeforeEach
    void setUp() {
        flattener = new InheritanceFlattener();
        modelReader = new DefaultModelReader();
        testResourcesPath = Paths.get("src/test/resources/test-poms");
    }

    @Test
    void testPropertyMerging() throws Exception {
        Model parent = loadModel("simple-parent.xml");
        Model child = loadModel("simple-child.xml");

        List<Model> parentChain = Collections.singletonList(parent);
        Model flattened = flattener.flattenInheritance(child, parentChain);

        // Child should override parent java.version
        assertEquals("21", flattened.getProperties().getProperty("java.version"));

        // Parent properties should be inherited
        assertEquals("UTF-8", flattened.getProperties().getProperty("project.build.sourceEncoding"));
        assertEquals("5.9.3", flattened.getProperties().getProperty("junit.version"));

        // Child-only property should be preserved
        assertEquals("child-value", flattened.getProperties().getProperty("custom.property"));
    }

    @Test
    void testMultiLevelPropertyInheritance() throws Exception {
        Model grandparent = loadModel("grandparent.xml");
        Model middleParent = loadModel("middle-parent.xml");
        Model child = loadModel("multilevel-child.xml");

        List<Model> parentChain = new ArrayList<>();
        parentChain.add(grandparent);
        parentChain.add(middleParent);

        Model flattened = flattener.flattenInheritance(child, parentChain);

        // Properties from all levels should be present
        assertEquals("from-grandparent", flattened.getProperties().getProperty("root.property"));
        assertEquals("from-middle", flattened.getProperties().getProperty("middle.property"));
        assertEquals("from-child", flattened.getProperties().getProperty("child.property"));

        // Child should win override chain
        assertEquals("child-value", flattened.getProperties().getProperty("override.property"));
    }

    @Test
    void testDependencyVersionFlattening() throws Exception {
        Model parent = loadModel("simple-parent.xml");
        Model child = loadModel("simple-child.xml");

        List<Model> parentChain = Collections.singletonList(parent);
        Model flattened = flattener.flattenInheritance(child, parentChain);

        // Dependencies should have explicit versions from parent (may be property
        // expressions)
        assertNotNull(flattened.getDependencies());
        assertEquals(2, flattened.getDependencies().size());

        Dependency junitDep = findDependency(flattened, "junit-jupiter");
        assertNotNull(junitDep);
        // Version might be property expression: ${junit.version}
        assertNotNull(junitDep.getVersion());
        assertTrue(junitDep.getVersion().equals("${junit.version}") || junitDep.getVersion().equals("5.9.3"));

        Dependency slf4jDep = findDependency(flattened, "slf4j-api");
        assertNotNull(slf4jDep);
        assertNotNull(slf4jDep.getVersion());
        assertTrue(slf4jDep.getVersion().equals("${slf4j.version}") || slf4jDep.getVersion().equals("2.0.0"));

        // Verify properties are merged so expressions can resolve
        assertEquals("5.9.3", flattened.getProperties().getProperty("junit.version"));
        assertEquals("2.0.0", flattened.getProperties().getProperty("slf4j.version"));
    }

    @Test
    void testMultiLevelDependencyVersions() throws Exception {
        Model grandparent = loadModel("grandparent.xml");
        Model middleParent = loadModel("middle-parent.xml");
        Model child = loadModel("multilevel-child.xml");

        List<Model> parentChain = new ArrayList<>();
        parentChain.add(grandparent);
        parentChain.add(middleParent);

        Model flattened = flattener.flattenInheritance(child, parentChain);

        // Dependencies should get versions from appropriate parent level
        Dependency commonsIo = findDependency(flattened, "commons-io");
        assertNotNull(commonsIo);
        assertNotNull(commonsIo.getVersion()); // From grandparent

        Dependency guava = findDependency(flattened, "guava");
        assertNotNull(guava);
        assertNotNull(guava.getVersion()); // From middle parent
    }

    @Test
    void testParentRemoval() throws Exception {
        Model parent = loadModel("simple-parent.xml");
        Model child = loadModel("simple-child.xml");

        // Verify child has parent before flattening
        assertNotNull(child.getParent());

        List<Model> parentChain = Collections.singletonList(parent);
        Model flattened = flattener.flattenInheritance(child, parentChain);

        // Parent should be removed after flattening
        assertNull(flattened.getParent());
    }

    @Test
    void testDependencyManagementMerging() throws Exception {
        Model grandparent = loadModel("grandparent.xml");
        Model middleParent = loadModel("middle-parent.xml");

        List<Model> parentChain = new ArrayList<>();
        parentChain.add(grandparent);
        parentChain.add(middleParent);

        Model child = new Model();
        child.setGroupId("com.example.test");
        child.setArtifactId("test-child");
        child.setVersion("1.0.0");

        Model flattened = flattener.flattenInheritance(child, parentChain);

        // Dependency management from all parents should be merged
        assertNotNull(flattened.getDependencyManagement());
        assertTrue(flattened.getDependencyManagement().getDependencies().size() >= 2);

        // Should have dependencyManagement from both parents
        boolean hasCommonsIo = flattened.getDependencyManagement().getDependencies().stream()
                .anyMatch(d -> "commons-io".equals(d.getArtifactId()));
        boolean hasGuava = flattened.getDependencyManagement().getDependencies().stream()
                .anyMatch(d -> "guava".equals(d.getArtifactId()));

        assertTrue(hasCommonsIo);
        assertTrue(hasGuava);
    }

    @Test
    void testSkipProfiles() throws Exception {
        Model parent = new Model();
        parent.setGroupId("com.example");
        parent.setArtifactId("parent");
        parent.setVersion("1.0");

        Profile parentProfile = new Profile();
        parentProfile.setId("parent-profile");
        parent.addProfile(parentProfile);

        Model child = new Model();
        child.setGroupId("com.example");
        child.setArtifactId("child");
        child.setVersion("1.0");

        // Test with skipProfiles = true
        InheritanceFlattener skipProfilesFlattener = new InheritanceFlattener(true);
        Model flattened = skipProfilesFlattener.flattenInheritance(child, Collections.singletonList(parent));

        // Profiles should not be merged
        assertTrue(flattened.getProfiles() == null || flattened.getProfiles().isEmpty());
    }

    @Test
    void testProfileMerging() throws Exception {
        Model parent = new Model();
        parent.setGroupId("com.example");
        parent.setArtifactId("parent");
        parent.setVersion("1.0");

        Profile parentProfile = new Profile();
        parentProfile.setId("parent-profile");
        parent.addProfile(parentProfile);

        Model child = new Model();
        child.setGroupId("com.example");
        child.setArtifactId("child");
        child.setVersion("1.0");

        Profile childProfile = new Profile();
        childProfile.setId("child-profile");
        child.addProfile(childProfile);

        // Test with skipProfiles = false (default)
        Model flattened = flattener.flattenInheritance(child, Collections.singletonList(parent));

        // Both profiles should be present
        assertNotNull(flattened.getProfiles());
        assertEquals(2, flattened.getProfiles().size());

        boolean hasParentProfile = flattened.getProfiles().stream()
                .anyMatch(p -> "parent-profile".equals(p.getId()));
        boolean hasChildProfile = flattened.getProfiles().stream()
                .anyMatch(p -> "child-profile".equals(p.getId()));

        assertTrue(hasParentProfile);
        assertTrue(hasChildProfile);
    }

    /**
     * Helper to load a model from test resources
     */
    private Model loadModel(String filename) throws Exception {
        return modelReader.read(testResourcesPath.resolve(filename).toFile(), Collections.emptyMap());
    }

    /**
     * Helper to find a dependency by artifactId
     */
    private Dependency findDependency(Model model, String artifactId) {
        if (model.getDependencies() == null) {
            return null;
        }
        return model.getDependencies().stream()
                .filter(d -> artifactId.equals(d.getArtifactId()))
                .findFirst()
                .orElse(null);
    }
}
