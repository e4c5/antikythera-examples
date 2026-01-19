package sa.com.cloudsolutions.liquibase;

import java.io.File;
import java.nio.file.Path;

/**
 * Utility class for Liquibase resource path resolution.
 * Provides common functionality for determining resource roots and relative paths
 * in Spring Boot projects.
 */
public final class LiquibaseResourceUtil {

    private LiquibaseResourceUtil() {
        // Utility class - prevent instantiation
    }

    /**
     * Determines the resource root directory for Liquibase file resolution.
     * For Spring Boot projects, this should be src/main/resources or
     * src/test/resources.
     * Otherwise, falls back to the file's parent directory.
     *
     * @param file the changelog file
     * @return the root path for resource resolution
     */
    public static Path determineResourceRoot(File file) {
        String absolutePath = file.getAbsolutePath();

        // Check if this is in a Spring Boot resources directory
        if (absolutePath.contains("/src/main/resources/")) {
            int idx = absolutePath.indexOf("/src/main/resources/");
            String rootDir = absolutePath.substring(0, idx + "/src/main/resources".length());
            return new File(rootDir).toPath().toAbsolutePath().normalize();
        } else if (absolutePath.contains("/src/test/resources/")) {
            int idx = absolutePath.indexOf("/src/test/resources/");
            String rootDir = absolutePath.substring(0, idx + "/src/test/resources".length());
            return new File(rootDir).toPath().toAbsolutePath().normalize();
        }

        // Fallback: use parent directory
        return file.getParentFile().toPath().toAbsolutePath().normalize();
    }

    /**
     * Gets the changelog file path relative to the resource root.
     * This is necessary for Liquibase to properly resolve include directives.
     *
     * @param file     the changelog file
     * @param rootPath the resource root path
     * @return the relative path from root to the changelog file
     */
    public static String getRelativeChangelogPath(File file, Path rootPath) {
        Path filePath = file.toPath().toAbsolutePath().normalize();
        Path relativePath = rootPath.relativize(filePath);
        return relativePath.toString().replace(File.separatorChar, '/');
    }
}

