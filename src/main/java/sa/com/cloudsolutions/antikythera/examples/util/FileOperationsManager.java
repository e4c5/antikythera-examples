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
     * Checks if a file exists.
     * 
     * @param filePath the path to check
     * @return true if the file exists, false otherwise
     */
    public static boolean fileExists(Path filePath) {
        return Files.exists(filePath);
    }
    
    /**
     * Checks if a path represents a directory.
     * 
     * @param path the path to check
     * @return true if the path is a directory, false otherwise
     */
    public static boolean isDirectory(Path path) {
        return Files.isDirectory(path);
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
    
    /**
     * Performs an atomic file write operation by writing to a temporary file first,
     * then moving it to the target location. This ensures the target file is never
     * in a partially written state.
     * 
     * @param filePath the target file path
     * @param content the content to write
     * @throws IOException if an I/O error occurs
     */
    public static void atomicWriteFileContent(Path filePath, String content) throws IOException {
        // Ensure parent directories exist
        createDirectories(filePath.getParent());
        
        // Create temporary file in the same directory
        Path tempFile = filePath.resolveSibling(filePath.getFileName() + ".tmp");
        
        try {
            // Write to temporary file first
            Files.writeString(tempFile, content, StandardCharsets.UTF_8);
            
            // Atomically move to target location
            Files.move(tempFile, filePath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Clean up temporary file if it exists
            if (Files.exists(tempFile)) {
                try {
                    Files.delete(tempFile);
                } catch (IOException deleteException) {
                    // Log but don't throw - the original exception is more important
                    e.addSuppressed(deleteException);
                }
            }
            throw e;
        }
    }
    
    /**
     * Performs an atomic file write operation for lines by writing to a temporary file first,
     * then moving it to the target location.
     * 
     * @param filePath the target file path
     * @param lines the lines to write
     * @throws IOException if an I/O error occurs
     */
    public static void atomicWriteLines(Path filePath, List<String> lines) throws IOException {
        // Ensure parent directories exist
        createDirectories(filePath.getParent());
        
        // Create temporary file in the same directory
        Path tempFile = filePath.resolveSibling(filePath.getFileName() + ".tmp");
        
        try {
            // Write to temporary file first
            Files.write(tempFile, lines, StandardCharsets.UTF_8);
            
            // Atomically move to target location
            Files.move(tempFile, filePath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Clean up temporary file if it exists
            if (Files.exists(tempFile)) {
                try {
                    Files.delete(tempFile);
                } catch (IOException deleteException) {
                    // Log but don't throw - the original exception is more important
                    e.addSuppressed(deleteException);
                }
            }
            throw e;
        }
    }
}