package com.raditha.spring;

import com.github.javaparser.ast.CompilationUnit;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Abstract base class for code migrators that perform AST-based
 * transformations.
 * 
 * <p>
 * Provides common functionality for migrators that:
 * <ul>
 * <li>Analyze Java source code using JavaParser AST</li>
 * <li>Transform imports, method calls, annotations, etc.</li>
 * <li>Write modified compilation units back to files</li>
 * </ul>
 * 
 * <p>
 * Example migrators:
 * <ul>
 * <li>CassandraCodeMigrator - Package import migration</li>
 * <li>RedisCodeMigrator - Method call transformations</li>
 * <li>ElasticsearchCodeMigrator - API migration detection</li>
 * </ul>
 * 
 * @see MigrationPhase
 */
public abstract class AbstractCodeMigrator extends MigrationPhase {

    protected AbstractCodeMigrator(boolean dryRun) {
        super(dryRun);
    }

    /**
     * Get all resolved compilation units from the current project.
     * 
     * <p>
     * This retrieves all Java files that have been parsed and resolved by
     * Antikythera.
     * 
     * @return map of class names to compilation units
     */
    protected Map<String, CompilationUnit> getCompilationUnits() {
        return AntikytheraRunTime.getResolvedCompilationUnits();
    }

    /**
     * Write modified compilation units back to their source files.
     * 
     * <p>
     * Handles file writing with proper error handling and logging.
     * Respects dry-run mode.
     * 
     * @param modifiedUnits map of class names to modified compilation units
     * @param result        migration result for logging changes/errors
     */
    protected void writeModifiedFiles(Map<String, CompilationUnit> modifiedUnits,
            MigrationPhaseResult result) {
        if (dryRun) {
            result.addChange(String.format("Would modify %d files (dry-run mode)",
                    modifiedUnits.size()));
            return;
        }

        for (Map.Entry<String, CompilationUnit> entry : modifiedUnits.entrySet()) {
            String className = entry.getKey();
            CompilationUnit cu = entry.getValue();

            try {
                // Try to use storage from parser
                if (cu.getStorage().isPresent()) {
                    Path filePath = cu.getStorage().get().getPath();
                    Files.writeString(filePath, cu.toString());
                    logger.info("Updated file: {}", filePath);
                    continue;
                }

                // Fallback: Try to find source file by class name
                Path filePath = findSourceFile(className);
                if (filePath != null) {
                    Files.writeString(filePath, cu.toString());
                    logger.info("Updated file: {}", filePath);
                } else {
                    result.addWarning("Could not determine file path for: " + className);
                }

            } catch (IOException e) {
                result.addError("Failed to write " + className + ": " + e.getMessage());
                logger.error("Error writing file for {}", className, e);
            }
        }
    }

    /**
     * Find source file path for a fully qualified class name.
     * 
     * <p>
     * Converts class name to file path and searches in src/main/java.
     * 
     * @param className fully qualified class name (e.g., "com.example.MyClass")
     * @return path to source file, or null if not found
     */
    protected Path findSourceFile(String className) {
        try {
            String basePath = sa.com.cloudsolutions.antikythera.configuration.Settings.getBasePath();
            if (basePath == null) {
                return null;
            }

            String relativePath = className.replace('.', '/') + ".java";
            Path filePath = Paths.get(basePath, "src", "main", "java", relativePath);

            if (Files.exists(filePath)) {
                return filePath;
            }

            // Try test sources as fallback
            filePath = Paths.get(basePath, "src", "test", "java", relativePath);
            if (Files.exists(filePath)) {
                return filePath;
            }

        } catch (Exception e) {
            logger.warn("Error finding source file for {}: {}", className, e.getMessage());
        }

        return null;
    }
}
