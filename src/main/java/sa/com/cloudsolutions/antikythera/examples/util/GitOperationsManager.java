package sa.com.cloudsolutions.antikythera.examples.util;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Centralized utility class for Git operations with retry logic, graceful error handling,
 * and repository state validation.
 * 
 * Consolidates Git operation patterns from:
 * - RepoProcessor.findAndCheckoutBranch()
 * - RepoProcessor.runGitCommand()
 */
public class    GitOperationsManager {
    
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long DEFAULT_RETRY_DELAY_MS = 1000;
    
    /**
     * Exception thrown when Git operations fail.
     */
    public static class GitException extends Exception {
        public GitException(String message) {
            super(message);
        }
        
        public GitException(String message, Throwable cause) {
            super(message, cause);
        }
    }
    
    /**
     * Represents the result of a Git operation.
     */
    public static class GitResult {
        private final boolean success;
        private final String output;
        private final int exitCode;
        
        public GitResult(boolean success, String output, int exitCode) {
            this.success = success;
            this.output = output;
            this.exitCode = exitCode;
        }
        
        public boolean isSuccess() { return success; }
        public String getOutput() { return output; }
        public int getExitCode() { return exitCode; }
    }
    
    /**
     * Checks out the specified branch in the given repository.
     * 
     * @param repoPath the path to the Git repository
     * @param branchName the name of the branch to checkout
     * @throws GitException if the checkout operation fails
     */
    public static void checkoutBranch(Path repoPath, String branchName) throws GitException {
        executeGitCommand(repoPath, "git checkout " + branchName);
    }
    
    /**
     * Pulls the latest changes from the remote repository.
     * 
     * @param repoPath the path to the Git repository
     * @throws GitException if the pull operation fails
     */
    public static void pullLatest(Path repoPath) throws GitException {
        executeGitCommand(repoPath, "git pull");
    }
    
    /**
     * Performs a hard reset to discard all local changes.
     * 
     * @param repoPath the path to the Git repository
     * @throws GitException if the reset operation fails
     */
    public static void resetHard(Path repoPath) throws GitException {
        executeGitCommand(repoPath, "git reset --hard");
    }
    
    /**
     * Lists all branches in the repository.
     * 
     * @param repoPath the path to the Git repository
     * @return a list of branch names
     * @throws GitException if the operation fails
     */
    public static List<String> listBranches(Path repoPath) throws GitException {
        GitResult result = executeGitCommandWithOutput(repoPath, "git branch -a");
        List<String> branches = new ArrayList<>();
        
        if (result.isSuccess()) {
            String[] lines = result.getOutput().split("\n");
            for (String line : lines) {
                String branch = line.trim();
                if (!branch.isEmpty()) {
                    // Remove the current branch indicator (*)
                    if (branch.startsWith("* ")) {
                        branch = branch.substring(2);
                    }
                    // Remove remote prefix for remote branches
                    if (branch.startsWith("remotes/origin/")) {
                        branch = branch.substring("remotes/origin/".length());
                    }
                    if (!branch.equals("HEAD") && !branches.contains(branch)) {
                        branches.add(branch);
                    }
                }
            }
        }
        
        return branches;
    }
    
    /**
     * Checks if the specified branch exists in the repository.
     * 
     * @param repoPath the path to the Git repository
     * @param branchName the name of the branch to check
     * @return true if the branch exists, false otherwise
     */
    public static boolean branchExists(Path repoPath, String branchName) {
        try {
            List<String> branches = listBranches(repoPath);
            return branches.contains(branchName);
        } catch (GitException e) {
            return false;
        }
    }
    
    /**
     * Gets the name of the currently checked out branch.
     * 
     * @param repoPath the path to the Git repository
     * @return the current branch name
     * @throws GitException if the operation fails
     */
    public static String getCurrentBranch(Path repoPath) throws GitException {
        GitResult result = executeGitCommandWithOutput(repoPath, "git branch --show-current");
        if (result.isSuccess()) {
            return result.getOutput().trim();
        } else {
            throw new GitException("Failed to get current branch: " + result.getOutput());
        }
    }
    
    /**
     * Finds and checks out one of the specified development branches.
     * This consolidates the logic from RepoProcessor.findAndCheckoutBranch().
     * 
     * @param repoPath the path to the Git repository
     * @param branchNames the branch names to try in order of preference
     * @return the name of the successfully checked out branch, or null if none found
     */
    public static String findAndCheckoutBranch(Path repoPath, String... branchNames) {
        String[] defaultBranches = {"develop", "Develop", "development"};
        String[] branches = branchNames.length > 0 ? branchNames : defaultBranches;
        
        for (String branch : branches) {
            try {
                resetHard(repoPath);
                checkoutBranch(repoPath, branch);
                pullLatest(repoPath);
                return branch;
            } catch (GitException e) {
                // Ignore and try next branch
            }
        }
        return null;
    }
    
    /**
     * Executes a Git command in the specified repository with retry logic.
     * 
     * @param repoPath the path to the Git repository
     * @param command the Git command to execute
     * @throws GitException if the command fails after all retries
     */
    public static void executeGitCommand(Path repoPath, String command) throws GitException {
        GitResult result = executeGitCommandWithRetry(repoPath, command, DEFAULT_MAX_RETRIES);
        if (!result.isSuccess()) {
            throw new GitException("Git command failed: " + command + " (exit code: " + result.getExitCode() + ")");
        }
    }
    
    /**
     * Executes a Git command and returns the result with output.
     * 
     * @param repoPath the path to the Git repository
     * @param command the Git command to execute
     * @return the result of the command execution
     * @throws GitException if the command fails after all retries
     */
    public static GitResult executeGitCommandWithOutput(Path repoPath, String command) throws GitException {
        return executeGitCommandWithRetry(repoPath, command, DEFAULT_MAX_RETRIES);
    }
    
    /**
     * Executes a Git command with retry logic and returns the result.
     * 
     * @param repoPath the path to the Git repository
     * @param command the Git command to execute
     * @param maxRetries the maximum number of retry attempts
     * @return the result of the command execution
     * @throws GitException if the command fails after all retries
     */
    private static GitResult executeGitCommandWithRetry(Path repoPath, String command, int maxRetries) throws GitException {
        Exception lastException = null;
        
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return executeGitCommandInternal(repoPath, command);
            } catch (Exception e) {
                lastException = e;
                
                if (attempt < maxRetries) {
                    // Wait before retrying
                    try {
                        Thread.sleep(DEFAULT_RETRY_DELAY_MS * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new GitException("Git command interrupted: " + command, ie);
                    }
                }
            }
        }
        
        throw new GitException("Git command failed after " + (maxRetries + 1) + " attempts: " + command, lastException);
    }
    
    /**
     * Internal method to execute a Git command.
     * 
     * @param repoPath the path to the Git repository
     * @param command the Git command to execute
     * @return the result of the command execution
     * @throws IOException if an I/O error occurs
     * @throws InterruptedException if the process is interrupted
     */
    private static GitResult executeGitCommandInternal(Path repoPath, String command) throws IOException, InterruptedException {
        String[] cmd = {"bash", "-c", command};
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(repoPath.toFile());
        
        // Capture output for commands that need it
        boolean captureOutput = command.contains("branch") || command.contains("status") || command.contains("log");
        if (!captureOutput) {
            pb.inheritIO();
        }
        
        Process process = pb.start();
        
        // Wait for process completion with timeout
        boolean finished = process.waitFor(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Git command timed out: " + command);
        }
        
        int exitCode = process.exitValue();
        String output = "";
        
        if (captureOutput && exitCode == 0) {
            try {
                output = new String(process.getInputStream().readAllBytes());
            } catch (IOException e) {
                // If we can't read output, that's okay for most commands
                output = "";
            }
        }
        
        return new GitResult(exitCode == 0, output, exitCode);
    }
    
    /**
     * Validates that the given path is a Git repository.
     * 
     * @param repoPath the path to validate
     * @return true if the path is a Git repository, false otherwise
     */
    public static boolean isGitRepository(Path repoPath) {
        try {
            executeGitCommandInternal(repoPath, "git rev-parse --git-dir");
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Gets the repository status (modified files, staged files, etc.).
     * 
     * @param repoPath the path to the Git repository
     * @return the status output
     * @throws GitException if the operation fails
     */
    public static String getRepositoryStatus(Path repoPath) throws GitException {
        GitResult result = executeGitCommandWithOutput(repoPath, "git status --porcelain");
        if (result.isSuccess()) {
            return result.getOutput();
        } else {
            throw new GitException("Failed to get repository status: " + result.getOutput());
        }
    }
}