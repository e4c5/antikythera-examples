package sa.com.cloudsolutions.antikythera.examples;

import sa.com.cloudsolutions.antikythera.examples.util.GitOperationsManager;

import java.io.*;
import java.nio.file.*;
import java.util.*;

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
        return GitOperationsManager.findAndCheckoutBranch(repo, "develop", "Develop", "development");
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
