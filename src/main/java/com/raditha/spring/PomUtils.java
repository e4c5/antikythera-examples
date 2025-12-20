package com.raditha.spring;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Utility class for common POM operations.
 * 
 * <p>
 * Provides reusable methods for reading, writing, and finding POM files.
 * Used by migrators that don't extend AbstractPomMigrator.
 */
public final class PomUtils {

    private PomUtils() {
        // Utility class - no instantiation
    }

    /**
     * Resolve path to pom.xml file.
     * 
     * @return path to pom.xml, or null if not found
     */
    public static Path resolvePomPath() {
        String basePathStr = Settings.getBasePath();
        if (basePathStr == null) {
            return null;
        }

        Path basePath = Paths.get(basePathStr);
        Path pomPath = basePath.resolve("pom.xml");

        if (!pomPath.toFile().exists()) {
            pomPath = basePath.getParent().resolve("pom.xml");
        }

        return pomPath;
    }

    /**
     * Read Maven POM model from file.
     * 
     * @param pomPath path to pom.xml file
     * @return parsed Maven model
     * @throws Exception if reading or parsing fails
     */
    public static Model readPomModel(Path pomPath) throws Exception {
        MavenXpp3Reader reader = new MavenXpp3Reader();
        try (FileReader fileReader = new FileReader(pomPath.toFile())) {
            return reader.read(fileReader);
        }
    }

    /**
     * Write Maven POM model to file.
     * 
     * @param pomPath path to pom.xml file
     * @param model   Maven model to write
     * @throws IOException if writing fails
     */
    public static void writePomModel(Path pomPath, Model model) throws IOException {
        MavenXpp3Writer writer = new MavenXpp3Writer();
        try (FileWriter fileWriter = new FileWriter(pomPath.toFile())) {
            writer.write(fileWriter, model);
        }
    }
}
