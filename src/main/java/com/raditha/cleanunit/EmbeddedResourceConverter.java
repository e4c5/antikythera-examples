package com.raditha.cleanunit;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.apache.maven.model.Dependency;

import java.util.List;
import java.util.Set;

/**
 * Interface for converting test resources from containers/live connections to
 * embedded alternatives.
 * 
 * Implementations handle specific resource types (Database, Kafka, etc.).
 */
public interface EmbeddedResourceConverter {

    /**
     * Check if this converter can handle the given container/connection types.
     * 
     * @param containerTypes  detected container types
     * @param connectionTypes detected live connection types
     * @return true if this converter can handle the conversion
     */
    boolean canConvert(Set<TestContainerDetector.ContainerType> containerTypes,
            Set<LiveConnectionDetector.LiveConnectionType> connectionTypes);

    /**
     * Convert the test class to use embedded resources.
     * 
     * @param testClass       the test class declaration
     * @param cu              the compilation unit
     * @param containerTypes  detected container types
     * @param connectionTypes detected live connection types
     * @param projectRoot     the project root directory
     * @return conversion result with details
     */
    ConversionResult convert(ClassOrInterfaceDeclaration testClass,
            CompilationUnit cu,
            Set<TestContainerDetector.ContainerType> containerTypes,
            Set<LiveConnectionDetector.LiveConnectionType> connectionTypes,
            java.nio.file.Path projectRoot);

    /**
     * Get dependencies that should be added to pom.xml.
     * 
     * @return list of dependencies to add
     */
    List<Dependency> getRequiredDependencies();

    /**
     * Get dependencies that should be removed from pom.xml.
     * 
     * @return list of dependencies to remove
     */
    List<Dependency> getDependenciesToRemove();

    /**
     * Result of a conversion operation.
     */
    class ConversionResult {
        public boolean modified;
        public String embeddedAlternative;
        public String reason;

        public ConversionResult(boolean modified, String embeddedAlternative, String reason) {
            this.modified = modified;
            this.embeddedAlternative = embeddedAlternative;
            this.reason = reason;
        }

        public static ConversionResult noChange(String reason) {
            return new ConversionResult(false, null, reason);
        }

        public static ConversionResult success(String embeddedAlternative, String reason) {
            return new ConversionResult(true, embeddedAlternative, reason);
        }
    }
}
