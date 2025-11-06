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
    
    /**
     * Writes content to a file using UTF-8 encoding, creating the file if it doesn't exist
     * and replacing it if it does exist.
     * 
     * @param filePath the path to the file to write
     * @param content the content to write
     * @throws IOException if an I/O error occurs
     */
    public static void writeFileContent(Path filePath, String content) throws IOException {
        // Ensure parent directories exist
        createDirectories(filePath.getParent());
        Files.writeString(filePath, content, StandardCharsets.UTF_8);
    }
    
    /**
     * Writes content to a file as bytes, creating the file if it doesn't exist
     * and replacing it if it does exist.
     * 
     * @param filePath the path to the file to write
     * @param content the content to write as bytes
     * @throws IOException if an I/O error occurs
     */
    public static void writeFileBytes(Path filePath, byte[] content) throws IOException {
        // Ensure parent directories exist
        createDirectories(filePath.getParent());
        Files.write(filePath, content);
    }
    
    /**
     * Writes lines to a file using UTF-8 encoding, creating the file if it doesn't exist
     * and replacing it if it does exist.
     * 
     * @param filePath the path to the file to write
     * @param lines the lines to write
     * @throws IOException if an I/O error occurs
     */
    public static void writeLines(Path filePath, List<String> lines) throws IOException {
        // Ensure parent directories exist
        createDirectories(filePath.getParent());
        Files.write(filePath, lines, StandardCharsets.UTF_8);
    }
    
    /**
     * Appends content to the end of a file using UTF-8 encoding.
     * Creates the file if it doesn't exist.
     * 
     * @param filePath the path to the file to append to
     * @param content the content to append
     * @throws IOException if an I/O error occurs
     */
    public static void appendToFile(Path filePath, String content) throws IOException {
        // Ensure parent directories exist
        createDirectories(filePath.getParent());
        Files.writeString(filePath, content, StandardCharsets.UTF_8, 
                         StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    
    /**
     * Appends lines to the end of a file using UTF-8 encoding.
     * Creates the file if it doesn't exist.
     * 
     * @param filePath the path to the file to append to
     * @param lines the lines to append
     * @throws IOException if an I/O error occurs
     */
    public static void appendLines(Path filePath, List<String> lines) throws IOException {
        // Ensure parent directories exist
        createDirectories(filePath.getParent());
        Files.write(filePath, lines, StandardCharsets.UTF_8, 
                   StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * Creates directories for the given path if they don't exist.
     * This method is null-safe and will do nothing if the path is null.
     * 
     * @param dirPath the directory path to create
     * @throws IOException if an I/O error occurs
     */
    public static void createDirectories(Path dirPath) throws IOException {
        if (dirPath != null && !Files.exists(dirPath)) {
            Files.createDirectories(dirPath);
        }
    }
    
    /**
     * Copies a file from source to target, replacing the target if it exists.
     * Creates parent directories of the target if they don't exist.
     * 
     * @param source the source file path
     * @param target the target file path
     * @throws IOException if an I/O error occurs
     */
    public static void copyFile(Path source, Path target) throws IOException {
        // Ensure parent directories exist
        createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
    
}
