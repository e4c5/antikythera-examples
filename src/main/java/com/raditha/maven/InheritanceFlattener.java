package com.raditha.maven;

import org.apache.maven.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Flattens Maven parent POM inheritance by merging all inherited configuration
 * into the child POM. Implements complete expansion (FULL strategy).
 * 
 * <p>
 * This class performs the following operations:
 * <ul>
 * <li>Merges properties from parent chain (child overrides parent)</li>
 * <li>Converts managed dependencies to explicit versions</li>
 * <li>Flattens plugin configurations</li>
 * <li>Merges profiles from parent chain (optional)</li>
 * <li>Removes the {@code <parent>} element entirely</li>
 * </ul>
 * 
 * <p>
 * <strong>Usage Example:</strong>
 * 
 * <pre>{@code
 * InheritanceFlattener flattener = new InheritanceFlattener();
 * Model child = ...; // Load child POM
 * List<Model> parentChain = ...; // Resolve parent chain
 * 
 * Model flattened = flattener.flattenInheritance(child, parentChain);
 * // flattened now contains all inherited configuration, no parent reference
 * }</pre>
 * 
 * <p>
 * <strong>Property Expression Handling:</strong><br>
 * Dependency versions from {@code <dependencyManagement>} may contain property
 * expressions (e.g., {@code ${junit.version}}). These are preserved in the
 * flattened POM along with the properties, maintaining Maven's property
 * resolution.
 * 
 * @author Maven Parent POM Converter
 * @since 1.0
 */
public class InheritanceFlattener {
    private static final Logger logger = LoggerFactory.getLogger(InheritanceFlattener.class);

    private final boolean skipProfiles;

    /**
     * Creates a flattener that includes profiles from parent chain.
     */
    public InheritanceFlattener() {
        this(false);
    }

    public InheritanceFlattener(boolean skipProfiles) {
        this.skipProfiles = skipProfiles;
    }

    /**
     * Flatten entire parent chain into child POM
     * 
     * @param child       child POM model
     * @param parentChain list of parent Models from root to immediate parent
     * @return flattened Model with all inheritance resolved
     */
    public Model flattenInheritance(Model child, List<Model> parentChain) {
        logger.info("Flattening inheritance for {}:{}:{}",
                child.getGroupId(), child.getArtifactId(), child.getVersion());

        Model flattened = child.clone();

        // Remove parent reference - we're making this standalone
        flattened.setParent(null);

        // Merge properties
        Properties mergedProperties = mergeProperties(child, parentChain);
        flattened.setProperties(mergedProperties);

        // Flatten dependencies (add explicit versions from dependencyManagement)
        List<Dependency> flattenedDeps = flattenDependencies(child, parentChain);
        flattened.setDependencies(flattenedDeps);

        // Merge dependency management
        DependencyManagement mergedDepMgmt = mergeDependencyManagement(child, parentChain);
        if (mergedDepMgmt != null && !mergedDepMgmt.getDependencies().isEmpty()) {
            flattened.setDependencyManagement(mergedDepMgmt);
        }

        // Merge build configuration (plugins, plugin management, etc.)
        Build mergedBuild = mergePluginConfigurations(child, parentChain);
        flattened.setBuild(mergedBuild);

        // Merge profiles
        if (!skipProfiles) {
            List<Profile> mergedProfiles = mergeProfiles(child, parentChain);
            flattened.setProfiles(mergedProfiles);
        }

        logger.info("Flattening complete");
        return flattened;
    }

    /**
     * Merge properties from parent chain, child overrides parent
     */
    private Properties mergeProperties(Model child, List<Model> parentChain) {
        Properties merged = new Properties();

        // Start with root parent and work down
        for (Model parent : parentChain) {
            if (parent.getProperties() != null) {
                merged.putAll(parent.getProperties());
            }
        }

        // Child properties override
        if (child.getProperties() != null) {
            merged.putAll(child.getProperties());
        }

        logger.debug("Merged {} properties", merged.size());
        return merged;
    }

    /**
     * Flatten dependencies by adding explicit versions from dependencyManagement
     */
    private List<Dependency> flattenDependencies(Model child, List<Model> parentChain) {
        List<Dependency> flattened = new ArrayList<>();

        // Build complete dependencyManagement map
        Map<String, String> versionMap = buildDependencyManagementMap(child, parentChain);

        if (child.getDependencies() == null) {
            return flattened;
        }

        for (Dependency dep : child.getDependencies()) {
            Dependency flatDep = dep.clone();

            // If dependency doesn't have explicit version, get from management
            if (flatDep.getVersion() == null || flatDep.getVersion().isEmpty()) {
                String key = dep.getGroupId() + ":" + dep.getArtifactId();
                String version = versionMap.get(key);
                if (version != null) {
                    flatDep.setVersion(version);
                    logger.debug("Added version {} to {}:{}",
                            version, dep.getGroupId(), dep.getArtifactId());
                }
            }

            flattened.add(flatDep);
        }

        return flattened;
    }

    /**
     * Build map of dependency versions from dependencyManagement sections
     */
    private Map<String, String> buildDependencyManagementMap(Model child, List<Model> parentChain) {
        Map<String, String> versionMap = new HashMap<>();

        // Start with root parent
        for (Model parent : parentChain) {
            if (parent.getDependencyManagement() != null &&
                    parent.getDependencyManagement().getDependencies() != null) {
                for (Dependency dep : parent.getDependencyManagement().getDependencies()) {
                    String key = dep.getGroupId() + ":" + dep.getArtifactId();
                    if (dep.getVersion() != null) {
                        versionMap.put(key, dep.getVersion());
                    }
                }
            }
        }

        // Child dependencyManagement overrides
        if (child.getDependencyManagement() != null &&
                child.getDependencyManagement().getDependencies() != null) {
            for (Dependency dep : child.getDependencyManagement().getDependencies()) {
                String key = dep.getGroupId() + ":" + dep.getArtifactId();
                if (dep.getVersion() != null) {
                    versionMap.put(key, dep.getVersion());
                }
            }
        }

        return versionMap;
    }

    /**
     * Merge dependencyManagement from parent chain
     */
    private DependencyManagement mergeDependencyManagement(Model child, List<Model> parentChain) {
        List<Dependency> allManaged = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // Child dependencyManagement first (highest priority)
        if (child.getDependencyManagement() != null &&
                child.getDependencyManagement().getDependencies() != null) {
            for (Dependency dep : child.getDependencyManagement().getDependencies()) {
                String key = dep.getGroupId() + ":" + dep.getArtifactId();
                if (seen.add(key)) {
                    allManaged.add(dep.clone());
                }
            }
        }

        // Then parent chain (reverse order - immediate parent to root)
        for (int i = parentChain.size() - 1; i >= 0; i--) {
            Model parent = parentChain.get(i);
            if (parent.getDependencyManagement() != null &&
                    parent.getDependencyManagement().getDependencies() != null) {
                for (Dependency dep : parent.getDependencyManagement().getDependencies()) {
                    String key = dep.getGroupId() + ":" + dep.getArtifactId();
                    if (seen.add(key)) {
                        allManaged.add(dep.clone());
                    }
                }
            }
        }

        if (allManaged.isEmpty()) {
            return null;
        }

        DependencyManagement depMgmt = new DependencyManagement();
        depMgmt.setDependencies(allManaged);
        return depMgmt;
    }

    /**
     * Merge plugin configurations from parent chain
     */
    private Build mergePluginConfigurations(Model child, List<Model> parentChain) {
        Build merged = child.getBuild() != null ? child.getBuild().clone() : new Build();

        // Merge plugins from parent chain
        Map<String, Plugin> pluginMap = new LinkedHashMap<>();

        // Start with root parent
        for (Model parent : parentChain) {
            if (parent.getBuild() != null && parent.getBuild().getPlugins() != null) {
                for (Plugin plugin : parent.getBuild().getPlugins()) {
                    String key = plugin.getGroupId() + ":" + plugin.getArtifactId();
                    pluginMap.put(key, plugin.clone());
                }
            }
        }

        // Child plugins override
        if (child.getBuild() != null && child.getBuild().getPlugins() != null) {
            for (Plugin plugin : child.getBuild().getPlugins()) {
                String key = plugin.getGroupId() + ":" + plugin.getArtifactId();
                pluginMap.put(key, plugin.clone());
            }
        }

        merged.setPlugins(new ArrayList<>(pluginMap.values()));

        // Merge pluginManagement
        PluginManagement mergedPluginMgmt = mergePluginManagement(child, parentChain);
        if (mergedPluginMgmt != null) {
            merged.setPluginManagement(mergedPluginMgmt);
        }

        return merged;
    }

    /**
     * Merge pluginManagement from parent chain
     */
    private PluginManagement mergePluginManagement(Model child, List<Model> parentChain) {
        Map<String, Plugin> pluginMap = new LinkedHashMap<>();

        // Start with root parent
        for (Model parent : parentChain) {
            if (parent.getBuild() != null &&
                    parent.getBuild().getPluginManagement() != null &&
                    parent.getBuild().getPluginManagement().getPlugins() != null) {
                for (Plugin plugin : parent.getBuild().getPluginManagement().getPlugins()) {
                    String key = plugin.getGroupId() + ":" + plugin.getArtifactId();
                    pluginMap.put(key, plugin.clone());
                }
            }
        }

        // Child pluginManagement overrides
        if (child.getBuild() != null &&
                child.getBuild().getPluginManagement() != null &&
                child.getBuild().getPluginManagement().getPlugins() != null) {
            for (Plugin plugin : child.getBuild().getPluginManagement().getPlugins()) {
                String key = plugin.getGroupId() + ":" + plugin.getArtifactId();
                pluginMap.put(key, plugin.clone());
            }
        }

        if (pluginMap.isEmpty()) {
            return null;
        }

        PluginManagement pluginMgmt = new PluginManagement();
        pluginMgmt.setPlugins(new ArrayList<>(pluginMap.values()));
        return pluginMgmt;
    }

    /**
     * Merge profiles from parent chain
     */
    private List<Profile> mergeProfiles(Model child, List<Model> parentChain) {
        Map<String, Profile> profileMap = new LinkedHashMap<>();

        // Start with root parent
        for (Model parent : parentChain) {
            if (parent.getProfiles() != null) {
                for (Profile profile : parent.getProfiles()) {
                    profileMap.put(profile.getId(), profile.clone());
                }
            }
        }

        // Child profiles override
        if (child.getProfiles() != null) {
            for (Profile profile : child.getProfiles()) {
                profileMap.put(profile.getId(), profile.clone());
            }
        }

        return new ArrayList<>(profileMap.values());
    }
}
