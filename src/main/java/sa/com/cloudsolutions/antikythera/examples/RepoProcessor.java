package sa.com.cloudsolutions.antikythera.examples;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * RepoProcessor automates the process of scanning multiple Bitbucket repositories organized by project folders.
 * It uses the presence of a maven pom.xml to identifying java projects. Once a java project is detected it
 * switches to the development branch, updating configuration files, and running a static analysis.
 *
 * At the moment the code runs the HardDelete class which tries to determine whether there is any code that
 * will carry out a hard delete operation on a JPA repository. However the class can be repurposed to run
 * other tasks on a collection of repositories. All
 *
 * <p>Workflow:</p>
 * <ol>
 *   <li>Accepts a root directory as a command-line argument. Each subdirectory is considered a project folder.</li>
 *   <li>Within each project, iterates over all subdirectories (repositories).</li>
 *   <li>For each repository containing a pom.xml (Maven project):
 *     <ul>
 *       <li>Attempts to checkout the 'develop', 'Develop', or 'development' branch (in that order), discarding local changes.</li>
 *       <li>Updates the repository by pulling from remote after checkout.</li>
 *       <li>Updates the base_path in generator.yml to reflect the current project and repository.</li>
 *       <li>Copies the updated generator.yml to the build output directory to ensure it is picked up by downstream processes.</li>
 *       <li>Executes the HardDelete analysis as a separate Java process, capturing its output.</li>
 *       <li>Prefixes each line of HardDelete output with the project and repository name, and appends it to deletes.csv.</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>Design notes:</p>
 * <ul>
 *   <li>Uses static fields to track the current project, repository, and CSV writer for simplicity.</li>
 *   <li>Runs git commands via the shell to leverage shell features and simplify command chaining.</li>
 *   <li>Executes HardDelete as a separate process to ensure configuration changes are picked up on each run.</li>
 *   <li>All output is captured and written to a single CSV file for easy analysis in spreadsheet tools.</li>
 * </ul>
 */
@SuppressWarnings("java:S106")
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
                if (Files.isDirectory(repo)) {
                    Path pom = repo.resolve("pom.xml");
                    if (!Files.exists(pom)) continue;
                    currentRepo = repo;
                    String branch = findAndCheckoutBranch(repo);
                    if (branch == null) {
                        System.out.println("No develop/Develop/development branch in " + repo);
                    } else {
                        updateGeneratorYml(project.getFileName().toString(), repo.getFileName().toString());
                        captureAndPrintHardDeleteOutput();
                    }
                }
            }
        }
    }

    @SuppressWarnings("java:S106")
    private static void captureAndPrintHardDeleteOutput() {
        PrintStream originalOut = System.out;
        String projectName = currentProject.getFileName().toString();
        String repoName = currentRepo.getFileName().toString();
        try {
            // Run HardDelete as a separate process
            ProcessBuilder pb = new ProcessBuilder(
                "java",
                "-cp",
                System.getProperty("java.class.path"),
                "sa.com.cloudsolutions.antikythera.examples.HardDelete"
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("Output for repo: " + currentRepo);
                    System.out.println(line);
                    if (!line.trim().isEmpty()) {
                        csvWriter.write(projectName + "," + repoName + "," + line);
                        csvWriter.newLine();
                    }
                }
            }
            process.waitFor();
            csvWriter.flush();
        } catch (Exception e) {
            e.printStackTrace(originalOut);
        }
    }

    private static String findAndCheckoutBranch(Path repo) throws IOException, InterruptedException {
        System.out.println(repo);
        String[] branches = {"develop", "Develop", "development"};
        for (String branch : branches) {
            try {
                runGitCommand(repo, "git reset --hard");
                runGitCommand(repo, "git checkout " + branch);
                runGitCommand(repo, "git pull"); // Always pull after successful checkout
                return branch;
            } catch (IOException | InterruptedException e) {
                // Ignore and try next branch
            }
        }
        return null;
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
        // Copy to target/classes/generator.yml so classpath resource is updated
        Path targetYml = Paths.get("target/classes/generator.yml");
        Files.createDirectories(targetYml.getParent());
        Files.copy(ymlPath, targetYml, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
