package sa.com.cloudsolutions.antikythera.examples;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.parser.MavenHelper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class to detect the Spring Boot version used in a Maven project.
 * This class analyzes pom.xml files to determine which version of Spring Boot
 * is being used, checking both parent declarations and dependencies.
 */
@SuppressWarnings("java:S106")
public class SpringBootVersionDetector {
    private static final Logger logger = LoggerFactory.getLogger(SpringBootVersionDetector.class);
    private static final String SPRING_BOOT_GROUP_ID = "org.springframework.boot";
    private static final String SPRING_BOOT_STARTER_PARENT = "spring-boot-starter-parent";
    private static final Pattern PROPERTY_PATTERN = Pattern.compile("\\$\\{(.+?)\\}");

    private final MavenHelper mavenHelper;
    private final Model pomModel;

    /**
     * Creates a new SpringBootVersionDetector for the given pom.xml file path.
     *
     * @param pomPath the path to the pom.xml file
     * @throws IOException            if the pom file cannot be read
     * @throws XmlPullParserException if the pom file cannot be parsed
     */
    public SpringBootVersionDetector(Path pomPath) throws IOException, XmlPullParserException {
        this.mavenHelper = new MavenHelper();
        this.mavenHelper.readPomFile(pomPath);
        this.pomModel = this.mavenHelper.getPomModel();
    }

    /**
     * Creates a new SpringBootVersionDetector using the default base path from Settings.
     *
     * @throws IOException            if the pom file cannot be read
     * @throws XmlPullParserException if the pom file cannot be parsed
     */
    public SpringBootVersionDetector() throws IOException, XmlPullParserException {
        this.mavenHelper = new MavenHelper();
        this.pomModel = this.mavenHelper.readPomFile();
    }

    /**
     * Detects and returns the Spring Boot version used in the project.
     * This method checks multiple sources in the following order:
     * <ol>
     *   <li>Parent pom declaration (spring-boot-starter-parent)</li>
     *   <li>Spring Boot dependencies with explicit versions</li>
     *   <li>Properties (spring-boot.version or spring.boot.version)</li>
     *   <li>Dependency management section</li>
     * </ol>
     *
     * @return the Spring Boot version string, or null if not found
     */
    public String detectSpringBootVersion() {
        if (pomModel == null) {
            logger.warn("POM model is null, cannot detect Spring Boot version");
            return null;
        }

        // Check parent pom first - most common pattern
        String versionFromParent = checkParentPom();
        if (versionFromParent != null) {
            logger.info("Spring Boot version detected from parent: {}", versionFromParent);
            return versionFromParent;
        }

        // Check dependencies
        String versionFromDependencies = checkDependencies();
        if (versionFromDependencies != null) {
            logger.info("Spring Boot version detected from dependencies: {}", versionFromDependencies);
            return versionFromDependencies;
        }

        // Check properties
        String versionFromProperties = checkProperties();
        if (versionFromProperties != null) {
            logger.info("Spring Boot version detected from properties: {}", versionFromProperties);
            return versionFromProperties;
        }

        // Check dependency management
        String versionFromDependencyManagement = checkDependencyManagement();
        if (versionFromDependencyManagement != null) {
            logger.info("Spring Boot version detected from dependency management: {}", versionFromDependencyManagement);
            return versionFromDependencyManagement;
        }

        logger.info("No Spring Boot version found in pom.xml");
        return null;
    }

    /**
     * Checks the parent pom for spring-boot-starter-parent declaration.
     *
     * @return the version if found, null otherwise
     */
    private String checkParentPom() {
        Parent parent = pomModel.getParent();
        if (parent != null &&
            SPRING_BOOT_GROUP_ID.equals(parent.getGroupId()) &&
            SPRING_BOOT_STARTER_PARENT.equals(parent.getArtifactId())) {
            return resolvePropertyOrVersion(parent.getVersion());
        }
        return null;
    }

    /**
     * Checks the dependencies for Spring Boot artifacts.
     *
     * @return the version if found, null otherwise
     */
    private String checkDependencies() {
        List<Dependency> dependencies = pomModel.getDependencies();
        if (dependencies == null) {
            return null;
        }

        for (Dependency dependency : dependencies) {
            if (SPRING_BOOT_GROUP_ID.equals(dependency.getGroupId())) {
                String version = dependency.getVersion();
                if (version != null && !version.isEmpty()) {
                    return resolvePropertyOrVersion(version);
                }
            }
        }
        return null;
    }

    /**
     * Checks the dependency management section for Spring Boot artifacts.
     *
     * @return the version if found, null otherwise
     */
    private String checkDependencyManagement() {
        if (pomModel.getDependencyManagement() == null ||
            pomModel.getDependencyManagement().getDependencies() == null) {
            return null;
        }

        List<Dependency> managedDependencies = pomModel.getDependencyManagement().getDependencies();
        for (Dependency dependency : managedDependencies) {
            if (SPRING_BOOT_GROUP_ID.equals(dependency.getGroupId())) {
                String version = dependency.getVersion();
                if (version != null && !version.isEmpty()) {
                    return resolvePropertyOrVersion(version);
                }
            }
        }
        return null;
    }

    /**
     * Checks properties for Spring Boot version declarations.
     *
     * @return the version if found, null otherwise
     */
    private String checkProperties() {
        Properties properties = pomModel.getProperties();
        if (properties == null) {
            return null;
        }

        // Common property names for Spring Boot version
        String[] propertyNames = {
            "spring-boot.version",
            "spring.boot.version",
            "springboot.version",
            "version.spring.boot",
            "version.springboot"
        };

        for (String propertyName : propertyNames) {
            String version = properties.getProperty(propertyName);
            if (version != null && !version.isEmpty()) {
                return resolvePropertyOrVersion(version);
            }
        }
        return null;
    }

    /**
     * Resolves a version string that may contain property references.
     * If the version is a property reference (${property.name}), it will be resolved
     * from the pom properties.
     *
     * @param version the version string to resolve
     * @return the resolved version, or the original string if not a property reference
     */
    private String resolvePropertyOrVersion(String version) {
        if (version == null || version.isEmpty()) {
            return null;
        }

        Matcher matcher = PROPERTY_PATTERN.matcher(version);
        if (matcher.matches()) {
            String propertyName = matcher.group(1);
            Properties properties = pomModel.getProperties();
            if (properties != null) {
                String resolvedVersion = properties.getProperty(propertyName);
                if (resolvedVersion != null && !resolvedVersion.isEmpty()) {
                    // Recursively resolve in case of nested properties
                    return resolvePropertyOrVersion(resolvedVersion);
                }
            }
            logger.warn("Unable to resolve property: {}", propertyName);
            return null;
        }

        return version;
    }

    /**
     * Main method for command-line usage.
     * Usage: java SpringBootVersionDetector <path-to-pom.xml>
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) throws XmlPullParserException, IOException {
        if (args.length < 1) {
            System.err.println("Usage: java SpringBootVersionDetector <path-to-pom.xml>");
            System.exit(1);
        }

        Path pomPath = Path.of(args[0]);
        SpringBootVersionDetector detector = new SpringBootVersionDetector(pomPath);
        String version = detector.detectSpringBootVersion();

        if (version != null) {
            System.out.println(version);
        } else {
            System.err.println("Spring Boot version not found in: " + pomPath);
            System.exit(1);
        }

    }
}
