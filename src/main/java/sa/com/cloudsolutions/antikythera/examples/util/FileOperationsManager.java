package sa.com.cloudsolutions.antikythera.examples.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Centralized utility class for file operations with consistent UTF-8 encoding,
 * atomic operations, and proper error handling.
 * 
 * Consolidates file operation patterns from:
 * - QueryOptimizer.writeFile()
 * - RepoProcessor.updateGeneratorYml()
 * - AnalysisInfrastructureRunner file operations
 * - JaCoCoTestCoverageAnalyzer file operations
 */
public class FileOperationsManager {
    
    /**
     * Reads the entire content of a file as a string using UTF-8 encoding.
     * 
     * @param filePath the path to the file to read
     * @return the file content as a string
     * @throws IOException if an I/O error occurs
     */
    public static String readFileContent(Path filePath) throws IOException {
        return Files.readString(filePath, StandardCharsets.UTF_8);
    }
    
    /**
     * Reads all lines from a file using UTF-8 encoding.
     * 
     * @param filePath the path to the file to read
     * @return a list containing all lines from the file
     * @throws IOException if an I/O error occurs
     */
    public static List<String> readLines(Path filePath) throws IOException {
        return Files.readAllLines(filePath, StandardCharsets.UTF_8);
    }

}
