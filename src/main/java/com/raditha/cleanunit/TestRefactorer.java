package com.raditha.cleanunit;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.raditha.cleanunit.strategies.AbstractTestRefactoringStrategy;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.parser.MavenHelper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

@SuppressWarnings("java:S106")
public class TestRefactorer {
    private static final Logger logger = LoggerFactory.getLogger(TestRefactorer.class);

    public enum ResourceType {
        DATABASE_JPA, JDBC, REDIS, KAFKA, WEB, WEBFLUX, REST_CLIENT, JSON, GRAPHQL, NONE
    }

    private CompilationUnit currentCu;
    private boolean isJUnit5 = false;
    private String springBootVersion = "2.0.0"; // Default to 2.x as requested
    private boolean isMockito1 = false;
    private boolean hasSliceTestSupport = false;
    private boolean dryRun = false;
    private final MavenHelper mavenHelper = new MavenHelper();

    public TestRefactorer(boolean dryRun) throws Exception {
        this.dryRun = dryRun;
        detectVersions();
    }

    private void detectVersions() throws IOException, XmlPullParserException {
        Model model = mavenHelper.readPomFile();

        detectSpringBootVersion(model);
        detectTestFrameworks(model);
        detectSliceTestSupport(model);
        detectVersionFromParentOrDependency(model);
        inferMockitoVersion();

        logger.info("Final Spring Boot Version: {}", springBootVersion);

        ensureSliceTestSupport(model, mavenHelper.getPomPath().toFile());
        validateTestAutoConfigureVersion(model, mavenHelper.getPomPath().toFile());
    }

    private void detectSpringBootVersion(Model model) {
        java.util.Properties props = model.getProperties();
        if (props != null) {
            if (props.containsKey("spring.boot.version")) {
                springBootVersion = props.getProperty("spring.boot.version");
                logger.debug("Found spring.boot.version property: {}", springBootVersion);
            } else if (props.containsKey("spring-boot.version")) {
                springBootVersion = props.getProperty("spring-boot.version");
                logger.debug("Found spring-boot.version property: {}", springBootVersion);
            }
        }
    }

    private void detectTestFrameworks(Model model) {
        for (Dependency dep : model.getDependencies()) {
            if ("junit-jupiter-api".equals(dep.getArtifactId())
                    || "junit-jupiter-engine".equals(dep.getArtifactId())
                    || "junit-jupiter".equals(dep.getArtifactId())) {
                isJUnit5 = true;
                logger.debug("Detected JUnit 5");
            }

            if ("mockito-core".equals(dep.getArtifactId()) || "mockito-all".equals(dep.getArtifactId())) {
                String version = resolveVersion(model, dep.getVersion());
                if (version != null && version.startsWith("1.")) {
                    isMockito1 = true;
                    logger.debug("Detected Mockito 1.x");
                }
            }
        }
    }

    private void detectSliceTestSupport(Model model) {
        for (Dependency dep : model.getDependencies()) {
            if ("spring-boot-starter-test".equals(dep.getArtifactId())
                    || "spring-boot-test-autoconfigure".equals(dep.getArtifactId())) {
                hasSliceTestSupport = true;
                logger.debug(
                        "Detected Slice Test Support (spring-boot-starter-test or spring-boot-test-autoconfigure)");
                break;
            }
        }
    }

    private void detectVersionFromParentOrDependency(Model model) {
        if (!"2.0.0".equals(springBootVersion)) {
            return; // Already detected from properties
        }

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
            logger.debug("Detected Spring Boot Version: {}", springBootVersion);
        }
    }

    private void inferMockitoVersion() {
        if (AbstractTestRefactoringStrategy.compareVersions(springBootVersion, "2.0.0") < 0) {
            if (!isMockito1 && AbstractTestRefactoringStrategy.compareVersions(springBootVersion, "1.0.0") > 0) {
                isMockito1 = true;
                logger.debug("Inferred Mockito 1.x from Spring Boot version");
            }
        }
    }

    private void ensureSliceTestSupport(Model model, java.io.File pomFile) throws IOException {
        if (!hasSliceTestSupport && AbstractTestRefactoringStrategy.compareVersions(springBootVersion, "2.0.0") >= 0) {
            logger.info("Slice test support missing. Adding spring-boot-starter-test dependency...");
            addDependencyToPom(model, pomFile);
            hasSliceTestSupport = true;
        }
    }

    private void validateTestAutoConfigureVersion(Model model, java.io.File pomFile) throws IOException {
        if (!hasSliceTestSupport) {
            return;
        }

        String testAutoConfigureVersion = findTestAutoConfigureVersion(model);
        if (testAutoConfigureVersion == null) {
            return;
        }

        boolean needsUpdate = false;
        String updateReason = "";

        if (AbstractTestRefactoringStrategy.compareVersions(testAutoConfigureVersion, "1.4.1") < 0) {
            needsUpdate = true;
            updateReason = "Version " + testAutoConfigureVersion
                    + " is too old (< 1.4.1) and missing EmbeddedDatabaseConnection class";
        } else if (!isVersionCompatible(springBootVersion, testAutoConfigureVersion)) {
            needsUpdate = true;
            updateReason = "Version mismatch: " + testAutoConfigureVersion
                    + " is incompatible with Spring Boot " + springBootVersion;
        }

        if (needsUpdate) {
            updateDependencyVersion(model, pomFile,
                    "org.springframework.boot",
                    "spring-boot-test-autoconfigure",
                    updateReason);
        } else {
            logger.debug("spring-boot-test-autoconfigure version {} is compatible with Spring Boot {}",
                    testAutoConfigureVersion, springBootVersion);
        }
    }

    private String findTestAutoConfigureVersion(Model model) {
        for (Dependency dep : model.getDependencies()) {
            if ("spring-boot-test-autoconfigure".equals(dep.getArtifactId())) {
                return resolveVersion(model, dep.getVersion());
            }
        }
        return null;
    }

    private void addDependencyToPom(Model model, java.io.File pomFile) throws IOException {
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
            mavenHelper.writePomModel(model);
            System.out.println("Added spring-boot-starter-test to pom.xml");
        }
    }

    /**
     * Update the version of an existing dependency in the POM file.
     * If the version is explicitly set, it will be removed to inherit from parent.
     * 
     * @param model      the Maven model
     * @param pomFile    the POM file
     * @param groupId    the dependency groupId
     * @param artifactId the dependency artifactId
     * @param reason     explanation for the update
     */
    private void updateDependencyVersion(Model model, java.io.File pomFile, String groupId, String artifactId,
            String reason) throws IOException {
        Dependency targetDep = null;
        for (Dependency dep : model.getDependencies()) {
            if (groupId.equals(dep.getGroupId()) && artifactId.equals(dep.getArtifactId())) {
                targetDep = dep;
                break;
            }
        }

        if (targetDep != null) {
            String oldVersion = targetDep.getVersion();

            if (dryRun) {
                System.out.println("[DRY RUN] Would update " + artifactId + " version from "
                        + oldVersion + " to inherit from parent");
                System.out.println("           Reason: " + reason);
            } else {
                // Remove explicit version to inherit from parent
                targetDep.setVersion(null);

                mavenHelper.writePomModel(model);
                System.out.println("Updated " + artifactId + " dependency:");
                System.out.println("  - Removed explicit version: " + oldVersion);
                System.out.println("  - Will now inherit from parent POM");
                System.out.println("  - Reason: " + reason);

                // Update our cached version since we changed it
                hasSliceTestSupport = true;
            }
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

    /**
     * Check if spring-boot-test-autoconfigure version is compatible with Spring
     * Boot version.
     * They must have matching major.minor versions (e.g., 2.7.x matches 2.7.y).
     *
     * @param springBootVersion        the Spring Boot version
     * @param testAutoConfigureVersion the spring-boot-test-autoconfigure version
     * @return true if versions are compatible, false otherwise
     */
    private boolean isVersionCompatible(String springBootVersion, String testAutoConfigureVersion) {
        if (springBootVersion == null || testAutoConfigureVersion == null) {
            return false;
        }
        String sbMajorMinor = getMajorMinor(springBootVersion);
        String taMajorMinor = getMajorMinor(testAutoConfigureVersion);
        return sbMajorMinor.equals(taMajorMinor);
    }

    /**
     * Extract major.minor version from a version string.
     * For example: "2.7.5" -> "2.7", "1.4.0.RELEASE" -> "1.4"
     *
     * @param version the version string
     * @return major.minor version string
     */
    private String getMajorMinor(String version) {
        if (version == null) {
            return "";
        }
        String[] parts = version.split("\\.");
        if (parts.length >= 2) {
            return parts[0] + "." + parts[1];
        }
        return version;
    }

    public static class RefactorOutcome {
        public String className;
        public String originalAnnotation;
        public String newAnnotation;
        public Set<ResourceType> resourcesDetected;
        public String action; // CONVERTED, KEPT, REVERTED
        public String reason;
        public boolean modified;

        public RefactorOutcome(String className) {
            this.className = className;
            this.resourcesDetected = new HashSet<>();
            this.modified = false;
        }

        @Override
        public String toString() {
            if (action == null)
                return "";
            return String.format("%-40s | %-15s -> %-15s | %-20s | %s",
                    className,
                    originalAnnotation != null ? originalAnnotation : "None",
                    newAnnotation != null ? newAnnotation : "None",
                    action,
                    reason);
        }
    }

    public RefactorOutcome refactor(CompilationUnit cu) {
        // Backwards-compatible: return the first outcome if multiple classes
        List<RefactorOutcome> all = refactorAll(cu);
        return all.isEmpty() ? null : all.get(0);
    }

    public List<RefactorOutcome> refactorAll(CompilationUnit cu) {
        this.currentCu = cu;
        List<RefactorOutcome> outcomes = new ArrayList<>();
        for (ClassOrInterfaceDeclaration decl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            RefactorOutcome outcome = analyzeClass(decl);
            if (outcome != null) {
                outcomes.add(outcome);
            }
        }
        return outcomes;
    }

    private boolean detectJUnit5FromImports(CompilationUnit cu) {
        return cu.getImports().stream().anyMatch(i -> i.getNameAsString().startsWith("org.junit.jupiter."));
    }

    private RefactorOutcome analyzeClass(ClassOrInterfaceDeclaration decl) {
        String annotationName = null;
        if (decl.getAnnotationByName("SpringBootTest").isPresent()) {
            annotationName = "SpringBootTest";
        } else if (decl.getAnnotationByName("DataJpaTest").isPresent()) {
            annotationName = "DataJpaTest";
        } else if (decl.getAnnotationByName("WebMvcTest").isPresent()) {
            annotationName = "WebMvcTest";
        }

        if (annotationName != null) {
            // Temporarily refine JUnit 5 detection per CU imports while analyzing this
            // class
            boolean prevIsJunit5 = this.isJUnit5;
            try {
                this.isJUnit5 = this.isJUnit5 || detectJUnit5FromImports(currentCu);

                RefactorOutcome outcome = new RefactorOutcome(decl.getNameAsString());
                outcome.originalAnnotation = annotationName;

                TestResourceAnalyzer analyzer = new TestResourceAnalyzer();
                Set<ResourceType> resources = analyzer.analyzeClass(decl);
                outcome.resourcesDetected = resources;

                // Delegate to strategy based on detected test framework
                TestFrameworkDetector.TestFramework framework = TestFrameworkDetector.detect(currentCu,
                        this.isJUnit5);
                TestRefactoringStrategy strategy = TestRefactoringStrategyFactory.get(framework);
                return strategy.refactor(decl, resources, hasSliceTestSupport, springBootVersion, isMockito1,
                        currentCu,
                        this);
            } finally {
                this.isJUnit5 = prevIsJunit5;
            }
        }
        return null;
    }
}
