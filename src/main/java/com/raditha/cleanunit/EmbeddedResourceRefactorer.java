package com.raditha.cleanunit;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Main orchestrator for embedded resource conversion.
 * 
 * Detects test containers and live connections, applies conversions,
 * and manages POM dependencies.
 * Follows simple method patterns with clear separation of concerns.
 */
public class EmbeddedResourceRefactorer {
    private static final Logger logger = LoggerFactory.getLogger(EmbeddedResourceRefactorer.class);

    private final boolean dryRun;
    private final TestContainerDetector containerDetector;
    private final LiveConnectionDetector connectionDetector;
    private final List<EmbeddedResourceConverter> converters;
    private final Path projectRoot;

    public EmbeddedResourceRefactorer(boolean dryRun) {
        this.dryRun = dryRun;
        this.containerDetector = new TestContainerDetector();
        this.connectionDetector = new LiveConnectionDetector();
        this.converters = initializeConverters();
        this.projectRoot = resolveProjectRoot();
    }

    /**
     * Initialize all available converters.
     */
    private List<EmbeddedResourceConverter> initializeConverters() {
        List<EmbeddedResourceConverter> list = new ArrayList<>();
        list.add(new DatabaseToEmbeddedConverter());
        list.add(new KafkaToEmbeddedConverter());
        list.add(new RedisToEmbeddedConverter());
        list.add(new MongoToEmbeddedConverter());
        return list;
    }

    /**
     * Resolve project root directory.
     */
    private Path resolveProjectRoot() {
        String basePath = sa.com.cloudsolutions.antikythera.configuration.Settings.getBasePath();
        if (basePath.contains("/src/main/java")) {
            return Paths.get(basePath.substring(0, basePath.indexOf("/src/main/java")));
        }
        if (basePath.contains("/src/test/java")) {
            return Paths.get(basePath.substring(0, basePath.indexOf("/src/test/java")));
        }
        return Paths.get(basePath);
    }

    /**
     * Refactor all test classes in a compilation unit.
     * 
     * @param cu the compilation unit
     * @return list of conversion outcomes
     */
    public List<ConversionOutcome> refactorAll(CompilationUnit cu) {
        List<ConversionOutcome> outcomes = new ArrayList<>();
        for (ClassOrInterfaceDeclaration testClass : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            ConversionOutcome outcome = processTestClass(testClass, cu);
            if (outcome != null) {
                outcomes.add(outcome);
            }
        }

        // Update POM if any conversions were successful
        if (hasSuccessfulConversions(outcomes) && !dryRun) {
            updatePomDependencies(outcomes);
        }

        return outcomes;
    }

    /**
     * Process a single test class for conversion.
     */
    private ConversionOutcome processTestClass(ClassOrInterfaceDeclaration testClass, CompilationUnit cu) {
        if (!isTestClass(testClass)) {
            return null;
        }

        ConversionOutcome outcome = new ConversionOutcome(testClass.getNameAsString());

        // Detect containers and live connections
        Set<TestContainerDetector.ContainerType> containers = containerDetector.detectContainers(testClass);
        Set<LiveConnectionDetector.LiveConnectionType> connections = connectionDetector.detectLiveConnections(testClass,
                projectRoot);

        outcome.containersRemoved = containers;
        outcome.connectionsReplaced = connections;

        if (containers.isEmpty() && connections.isEmpty()) {
            outcome.action = "SKIPPED";
            outcome.reason = "No containers or live connections detected";
            outcome.modified = false;
            return outcome;
        }

        // Apply appropriate converter
        return applyConversion(testClass, cu, containers, connections, outcome);
    }

    /**
     * Check if class is a test class.
     */
    private boolean isTestClass(ClassOrInterfaceDeclaration decl) {
        return decl.getNameAsString().endsWith("Test")
                || decl.getNameAsString().endsWith("Tests")
                || decl.getAnnotationByName("SpringBootTest").isPresent()
                || decl.getAnnotationByName("DataJpaTest").isPresent()
                || decl.getAnnotationByName("WebMvcTest").isPresent();
    }

    /**
     * Apply conversion using appropriate converter.
     */
    private ConversionOutcome applyConversion(ClassOrInterfaceDeclaration testClass,
            CompilationUnit cu,
            Set<TestContainerDetector.ContainerType> containers,
            Set<LiveConnectionDetector.LiveConnectionType> connections,
            ConversionOutcome outcome) {
        for (EmbeddedResourceConverter converter : converters) {
            if (converter.canConvert(containers, connections)) {
                EmbeddedResourceConverter.ConversionResult result = converter.convert(testClass, cu, containers,
                        connections, projectRoot);

                outcome.modified = result.modified;
                outcome.embeddedAlternative = result.embeddedAlternative;
                outcome.reason = result.reason;
                outcome.action = result.modified ? "CONVERTED" : "SKIPPED";

                if (dryRun && result.modified) {
                    logger.info("[DRY RUN] Would convert {} to {}",
                            testClass.getNameAsString(), result.embeddedAlternative);
                }

                return outcome;
            }
        }

        outcome.action = "SKIPPED";
        outcome.reason = "No suitable converter found";
        outcome.modified = false;
        return outcome;
    }

    /**
     * Check if any conversions were successful.
     */
    private boolean hasSuccessfulConversions(List<ConversionOutcome> outcomes) {
        return outcomes.stream().anyMatch(o -> o.modified);
    }

    /**
     * Update POM dependencies based on conversions.
     */
    private void updatePomDependencies(List<ConversionOutcome> outcomes) {
        try {
            Path pomPath = projectRoot.resolve("pom.xml");
            if (!pomPath.toFile().exists()) {
                logger.warn("POM file not found at: {}", pomPath);
                return;
            }

            Model model = readPomModel(pomPath);
            if (model == null) {
                return;
            }

            boolean modified = updateDependencies(model, outcomes);

            if (modified) {
                writePomModel(pomPath, model);
                logger.info("Updated POM dependencies");
            }

        } catch (Exception e) {
            logger.error("Failed to update POM dependencies", e);
        }
    }

    /**
     * Read Maven POM model.
     */
    private Model readPomModel(Path pomPath) {
        try (FileReader reader = new FileReader(pomPath.toFile())) {
            return new MavenXpp3Reader().read(reader);
        } catch (Exception e) {
            logger.error("Failed to read POM file", e);
            return null;
        }
    }

    /**
     * Write Maven POM model.
     */
    private void writePomModel(Path pomPath, Model model) {
        try (FileWriter writer = new FileWriter(pomPath.toFile())) {
            new MavenXpp3Writer().write(writer, model);
        } catch (Exception e) {
            logger.error("Failed to write POM file", e);
        }
    }

    /**
     * Update dependencies in POM model.
     */
    private boolean updateDependencies(Model model, List<ConversionOutcome> outcomes) {
        Set<Dependency> toAdd = new HashSet<>();
        Set<Dependency> toRemove = new HashSet<>();

        collectDependencyChanges(outcomes, toAdd, toRemove);

        boolean modified = false;
        modified |= removeDependencies(model, toRemove);
        modified |= addDependencies(model, toAdd);

        return modified;
    }

    /**
     * Collect dependency changes from all converters.
     */
    private void collectDependencyChanges(List<ConversionOutcome> outcomes,
            Set<Dependency> toAdd,
            Set<Dependency> toRemove) {
        for (EmbeddedResourceConverter converter : converters) {
            toAdd.addAll(converter.getRequiredDependencies());
            toRemove.addAll(converter.getDependenciesToRemove());
        }
    }

    /**
     * Remove dependencies from POM.
     */
    private boolean removeDependencies(Model model, Set<Dependency> toRemove) {
        boolean modified = false;
        List<Dependency> deps = model.getDependencies();

        for (Dependency remove : toRemove) {
            if (removeDependency(deps, remove)) {
                logger.info("Removed dependency: {}:{}", remove.getGroupId(), remove.getArtifactId());
                modified = true;
            }
        }

        return modified;
    }

    /**
     * Remove a single dependency if it exists.
     */
    private boolean removeDependency(List<Dependency> deps, Dependency toRemove) {
        return deps.removeIf(dep -> matchesDependency(dep, toRemove.getGroupId(), toRemove.getArtifactId()));
    }

    /**
     * Check if dependency matches groupId and artifactId.
     */
    private boolean matchesDependency(Dependency dep, String groupId, String artifactId) {
        return groupId.equals(dep.getGroupId()) && artifactId.equals(dep.getArtifactId());
    }

    /**
     * Add dependencies to POM.
     */
    private boolean addDependencies(Model model, Set<Dependency> toAdd) {
        boolean modified = false;

        for (Dependency add : toAdd) {
            if (!hasDependency(model, add)) {
                model.addDependency(add);
                logger.info("Added dependency: {}:{}", add.getGroupId(), add.getArtifactId());
                modified = true;
            }
        }

        return modified;
    }

    /**
     * Check if POM already has dependency.
     */
    private boolean hasDependency(Model model, Dependency dep) {
        return model.getDependencies().stream()
                .anyMatch(existing -> matchesDependency(existing, dep.getGroupId(), dep.getArtifactId()));
    }
}
