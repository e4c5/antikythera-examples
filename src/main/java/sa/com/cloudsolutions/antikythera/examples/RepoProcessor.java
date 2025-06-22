package sa.com.cloudsolutions.antikythera.examples;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class RepoProcessor {
    private static BufferedWriter csvWriter;
    private static Path currentProject;
    private static Path currentRepo;
    @SuppressWarnings("java:S106")
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: java RepoProcessor <root_folder>");
            System.exit(1);
        }
        Path root = Paths.get(args[0]);
        if (!Files.isDirectory(root)) {
            System.err.println("Provided path is not a directory: " + root);
            System.exit(1);
        }
        // Open deletes.csv for appending at the start
        Path csvPath = Paths.get("deletes.csv");
        try (BufferedWriter writer = Files.newBufferedWriter(csvPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            csvWriter = writer;
            processProjects(root);
        }
    }

    private static void processProjects(Path root) throws IOException, InterruptedException {
        try (DirectoryStream<Path> projects = Files.newDirectoryStream(root)) {
            for (Path project : projects) {
                if (!Files.isDirectory(project)) continue;
                currentProject = project;
                processRepos(project);
            }
        }
    }

    private static void processRepos(Path project) throws IOException, InterruptedException {
        try (DirectoryStream<Path> repos = Files.newDirectoryStream(project)) {
            for (Path repo : repos) {
                if (!Files.isDirectory(repo)) continue;
                Path pom = repo.resolve("pom.xml");
                if (!Files.exists(pom)) continue;
                currentRepo = repo;
                String branch = findAndCheckoutBranch(repo);
                if (branch == null) {
                    System.out.println("No develop/Develop/development branch in " + repo);
                    continue;
                }
                runGitCommand(repo, "git pull");
                updateGeneratorYml(project.getFileName().toString(), repo.getFileName().toString());
                captureAndPrintHardDeleteOutput();
            }
        }
    }

    @SuppressWarnings("java:S106")
    private static void captureAndPrintHardDeleteOutput() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        try (PrintStream ps = new PrintStream(baos)) {
            System.setOut(ps);
            System.setErr(ps);
            sa.com.cloudsolutions.antikythera.examples.HardDelete.main(new String[]{});
        } catch (Exception e) {
            e.printStackTrace(originalOut);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        String hardDeleteOutput = baos.toString();
        System.out.println("Output for repo: " + currentRepo);
        System.out.println(hardDeleteOutput);
        // Write to deletes.csv with project and repo prefix
        String projectName = currentProject.getFileName().toString();
        String repoName = currentRepo.getFileName().toString();
        try {
            for (String line : hardDeleteOutput.split(System.lineSeparator())) {
                if (!line.trim().isEmpty()) {
                    csvWriter.write(projectName + "," + repoName + "," + line);
                    csvWriter.newLine();
                }
            }
            csvWriter.flush();
        } catch (IOException ioe) {
            System.err.println("Failed to write to deletes.csv: " + ioe.getMessage());
        }
    }

    private static String findAndCheckoutBranch(Path repo) throws IOException, InterruptedException {
        String[] branches = {"develop", "Develop", "development"};
        for (String branch : branches) {
            if (branchExists(repo, branch)) {
                // Discard changes and checkout
                runGitCommand(repo, "git reset --hard");
                runGitCommand(repo, "git checkout " + branch);
                return branch;
            }
        }
        return null;
    }

    private static boolean branchExists(Path repo, String branch) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("git", "show-ref", "--verify", "--quiet", "refs/heads/" + branch);
        pb.directory(repo.toFile());
        Process p = pb.start();
        int exit = p.waitFor();
        if (exit == 0) return true;
        // Try remote branch
        pb = new ProcessBuilder("git", "ls-remote", "--heads", "origin", branch);
        pb.directory(repo.toFile());
        p = pb.start();
        exit = p.waitFor();
        return exit == 0;
    }

    private static void runGitCommand(Path repo, String command) throws IOException, InterruptedException {
        String[] cmd = {"bash", "-c", command};
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(repo.toFile());
        pb.inheritIO();
        Process p = pb.start();
        int exit = p.waitFor();
        if (exit != 0) throw new IOException("Git command failed: " + command);
    }

    private static void updateGeneratorYml(String projectName, String repoName) throws IOException {
        Path ymlPath = Paths.get("src/main/resources/generator.yml");
        List<String> lines = Files.readAllLines(ymlPath);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.trim().startsWith("base_path")) {
                // Replace project and repo in base_path
                line = line.replaceAll("/[^/]+/[^/]+/src/main/java/", "/" + projectName + "/" + repoName + "/src/main/java/");
                lines.set(i, line);
            }
        }
        Files.write(ymlPath, lines);
    }
}
