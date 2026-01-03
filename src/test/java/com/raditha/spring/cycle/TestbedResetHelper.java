package com.raditha.spring.cycle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Helper class to reset the testbed to a clean git state.
 * This ensures tests run against unmodified source files.
 */
public class TestbedResetHelper {
    
    private static final String UNKNOWN_JAVA = "spring-boot-cycles/src/main/java/com/example/cycles/extraction/Unknown.java";
    private static Path unknownJavaPath;
    private static boolean unknownJavaBackedUp = false;
    private static Path unknownJavaBackup;
    
    /**
     * Reset the testbed to a clean git state.
     * This ensures tests run against unmodified source files.
     */
    public static void resetTestbed() throws IOException, InterruptedException {
        Path workspaceRoot = Paths.get(System.getProperty("user.dir"));
        if (workspaceRoot.toString().contains("antikythera-examples")) {
            workspaceRoot = workspaceRoot.getParent();
        }
        Path testbedRoot = workspaceRoot.resolve("spring-boot-cycles");

        if (Files.exists(testbedRoot.resolve(".git"))) {
            ProcessBuilder pb = new ProcessBuilder("git", "restore", ".");
            pb.directory(testbedRoot.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor();

            pb = new ProcessBuilder("git", "clean", "-fd");
            pb.directory(testbedRoot.toFile());
            pb.redirectErrorStream(true);
            p = pb.start();
            p.waitFor();
        }

        // Reset unknownJavaPath
        unknownJavaPath = workspaceRoot.resolve(UNKNOWN_JAVA);
        unknownJavaBackedUp = false;
    }
    
    /**
     * Temporarily remove Unknown.java to avoid duplicate class definition errors.
     * This should be called before preProcess() in tests that don't test duplicate detection.
     */
    public static void removeUnknownJava() throws IOException {
        if (unknownJavaPath == null) {
            Path workspaceRoot = Paths.get(System.getProperty("user.dir"));
            if (workspaceRoot.toString().contains("antikythera-examples")) {
                workspaceRoot = workspaceRoot.getParent();
            }
            unknownJavaPath = workspaceRoot.resolve(UNKNOWN_JAVA);
        }
        
        // If file exists and hasn't been backed up yet, back it up
        if (Files.exists(unknownJavaPath) && !unknownJavaBackedUp) {
            unknownJavaBackup = unknownJavaPath.resolveSibling("Unknown.java.bak");
            Files.move(unknownJavaPath, unknownJavaBackup);
            unknownJavaBackedUp = true;
        } else if (Files.exists(unknownJavaPath) && unknownJavaBackedUp) {
            // File was restored (e.g., by git restore or writeChanges), delete it directly
            Files.delete(unknownJavaPath);
        }
    }
    
    /**
     * Restore Unknown.java if it was removed.
     * This should be called in tearDown() for tests that removed it.
     */
    public static void restoreUnknownJava() throws IOException {
        if (unknownJavaBackedUp && unknownJavaBackup != null && Files.exists(unknownJavaBackup)) {
            // If the file already exists (e.g., from git restore), delete it first
            if (Files.exists(unknownJavaPath)) {
                Files.delete(unknownJavaPath);
            }
            Files.move(unknownJavaBackup, unknownJavaPath);
            unknownJavaBackedUp = false;
        }
    }
}
