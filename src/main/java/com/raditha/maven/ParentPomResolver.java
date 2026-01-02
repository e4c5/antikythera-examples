package com.raditha.maven;

import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.io.DefaultModelReader;
import org.apache.maven.model.io.ModelReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Resolves parent POMs from Maven repositories and local file system.
 * 
 * <p>
 * This class handles the resolution of Maven parent POMs, supporting both:
 * <ul>
 * <li>Local repository resolution (default: ~/.m2/repository)</li>
 * <li>Relative path resolution for multi-module projects</li>
 * <li>Recursive parent chain resolution (parent of parent)</li>
 * </ul>
 * 
 * <p>
 * <strong>Usage Example:</strong>
 * 
 * <pre>{@code
 * ParentPomResolver resolver = new ParentPomResolver();
 * Parent parent = new Parent();
 * parent.setGroupId("org.springframework.boot");
 * parent.setArtifactId("spring-boot-starter-parent");
 * parent.setVersion("2.7.14");
 * 
 * Model resolved = resolver.resolveParent(parent, Paths.get("pom.xml"));
 * List<Model> chain = resolver.resolveParentChain(parent, Paths.get("pom.xml"));
 * }</pre>
 * 
 * @author Maven Parent POM Converter
 * @since 1.0
 */
public class ParentPomResolver {
    private static final Logger logger = LoggerFactory.getLogger(ParentPomResolver.class);

    private final Path localRepository;
    private final ModelReader modelReader;

    /**
     * Creates a resolver using the default Maven local repository
     * (~/.m2/repository).
     */
    public ParentPomResolver() {
        this(getDefaultLocalRepository());
    }

    /**
     * Create resolver with custom local repository path
     * 
     * @param localRepository path to Maven local repository
     */
    public ParentPomResolver(Path localRepository) {
        this.localRepository = localRepository;
        this.modelReader = new DefaultModelReader();
    }

    /**
     * Get default Maven local repository location
     */
    private static Path getDefaultLocalRepository() {
        String userHome = System.getProperty("user.home");
        return Paths.get(userHome, ".m2", "repository");
    }

    /**
     * Resolve a single parent POM
     * 
     * @param parent       parent declaration from child POM
     * @param childPomPath path to child POM file (for relativePath resolution)
     * @return resolved parent Model
     * @throws ParentResolutionException if parent cannot be resolved
     */
    public Model resolveParent(Parent parent, Path childPomPath) throws ParentResolutionException {
        logger.info("Resolving parent {}:{}:{}",
                parent.getGroupId(), parent.getArtifactId(), parent.getVersion());

        // Try relativePath first if specified
        if (parent.getRelativePath() != null && !parent.getRelativePath().isEmpty()) {
            try {
                Model relativeModel = resolveWithRelativePath(parent, childPomPath);
                if (relativeModel != null) {
                    logger.info("Resolved parent using relativePath: {}", parent.getRelativePath());

                    // Warn if version doesn't match
                    if (!parent.getVersion().equals(relativeModel.getVersion())) {
                        logger.warn("Parent version mismatch: declared={}, found={}",
                                parent.getVersion(), relativeModel.getVersion());
                    }
                    return relativeModel;
                }
            } catch (Exception e) {
                logger.debug("Failed to resolve via relativePath, falling back to repository", e);
            }
        }

        // Fall back to local repository resolution
        Model repoModel = resolveFromLocalRepository(parent);
        if (repoModel != null) {
            logger.info("Resolved parent from local repository");
            return repoModel;
        }

        throw new ParentResolutionException(
                "Unable to resolve parent POM from relativePath or local repository",
                parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
    }

    /**
     * Recursively resolve entire parent chain
     * 
     * @param parent       immediate parent declaration
     * @param childPomPath path to child POM file
     * @return list of parent Models from root to immediate parent
     * @throws ParentResolutionException if any parent cannot be resolved
     */
    public List<Model> resolveParentChain(Parent parent, Path childPomPath) throws ParentResolutionException {
        List<Model> chain = new ArrayList<>();
        Model currentParent = resolveParent(parent, childPomPath);
        chain.add(currentParent);

        // Recursively resolve parent of parent
        while (currentParent.getParent() != null) {
            Parent parentOfParent = currentParent.getParent();

            // Use effective values if not explicitly set in Parent declaration
            String effectiveGroupId = parentOfParent.getGroupId() != null ? parentOfParent.getGroupId()
                    : currentParent.getGroupId();
            String effectiveArtifactId = parentOfParent.getArtifactId() != null ? parentOfParent.getArtifactId()
                    : currentParent.getArtifactId();
            String effectiveVersion = parentOfParent.getVersion() != null ? parentOfParent.getVersion()
                    : currentParent.getVersion();

            logger.info("Resolving parent of parent: {}:{}:{}",
                    effectiveGroupId, effectiveArtifactId, effectiveVersion);

            // For parent of parent, use the current parent's location for relativePath
            Path parentPomPath = findPomPathInRepository(
                    effectiveGroupId, effectiveArtifactId, effectiveVersion);

            currentParent = resolveParent(parentOfParent, parentPomPath);
            chain.add(currentParent);
        }

        // Reverse to get root to immediate parent order
        Collections.reverse(chain);
        logger.info("Resolved parent chain with {} levels", chain.size());
        return chain;
    }

    /**
     * Resolve parent using relativePath from child POM directory
     */
    private Model resolveWithRelativePath(Parent parent, Path childPomPath) throws ParentResolutionException {
        if (childPomPath == null) {
            return null;
        }

        Path childDir = childPomPath.getParent();
        if (childDir == null) {
            return null;
        }

        Path relativePomPath = childDir.resolve(parent.getRelativePath()).normalize();

        // If relativePath points to directory, look for pom.xml inside
        if (Files.isDirectory(relativePomPath)) {
            relativePomPath = relativePomPath.resolve("pom.xml");
        }

        if (!Files.exists(relativePomPath)) {
            logger.debug("relativePath does not exist: {}", relativePomPath);
            return null;
        }

        try {
            return modelReader.read(relativePomPath.toFile(), Collections.emptyMap());
        } catch (IOException e) {
            throw new ParentResolutionException(
                    "Failed to read parent POM from relativePath: " + relativePomPath,
                    parent.getGroupId(), parent.getArtifactId(), parent.getVersion(), e);
        }
    }

    /**
     * Resolve parent from local Maven repository
     */
    private Model resolveFromLocalRepository(Parent parent) throws ParentResolutionException {
        Path pomPath = findPomPathInRepository(
                parent.getGroupId(), parent.getArtifactId(), parent.getVersion());

        if (!Files.exists(pomPath)) {
            return null;
        }

        try {
            return modelReader.read(pomPath.toFile(), Collections.emptyMap());
        } catch (IOException e) {
            throw new ParentResolutionException(
                    "Failed to read parent POM from local repository: " + pomPath,
                    parent.getGroupId(), parent.getArtifactId(), parent.getVersion(), e);
        }
    }

    /**
     * Find POM file path in local repository
     */
    private Path findPomPathInRepository(String groupId, String artifactId, String version) {
        String groupPath = groupId.replace('.', '/');
        return localRepository
                .resolve(groupPath)
                .resolve(artifactId)
                .resolve(version)
                .resolve(artifactId + "-" + version + ".pom");
    }
}
