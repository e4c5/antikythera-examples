package com.raditha.spring;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Utility class for property file operations.
 * 
 * <p>
 * Provides common functionality for finding and working with Spring Boot
 * property files (application*.yml, application*.properties).
 * 
 * <p>
 * This class centralizes duplicate logic that was previously scattered across
 * multiple migrator classes.
 */
public final class PropertyFileUtils {

    private PropertyFileUtils() {
        // Utility class - prevent instantiation
    }

    /**
     * Find property files matching the given patterns.
     * 
     * <p>
     * Searches recursively in the given base path for files that match the
     * patterns and start with "application".
     * 
     * @param basePath base path to search
     * @param patterns file name patterns (e.g., "*.yml", "*.properties")
     * @return list of matching property file paths
     * @throws IOException if I/O error occurs during file walking
     */
    public static List<Path> findPropertyFiles(Path basePath, String... patterns) throws IOException {
        List<Path> files = new ArrayList<>();

        if (!Files.exists(basePath)) {
            return files;
        }

        try (Stream<Path> paths = Files.walk(basePath)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> matchesPattern(path, patterns))
                    .forEach(files::add);
        }

        return files;
    }

    /**
     * Check if a file path matches any of the given patterns.
     * 
     * @param path     file path to check
     * @param patterns glob patterns to match
     * @return true if file matches any pattern
     */
    private static boolean matchesPattern(Path path, String[] patterns) {
        String fileName = path.getFileName().toString();

        // Must start with "application"
        if (!fileName.startsWith("application")) {
            return false;
        }

        for (String pattern : patterns) {
            String regex = pattern.replace("*", ".*");
            if (fileName.matches(regex)) {
                return true;
            }
        }

        return false;
    }
}
