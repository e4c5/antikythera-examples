package com.raditha.maven;

import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.io.DefaultModelReader;
import org.apache.maven.model.io.DefaultModelWriter;
import org.apache.maven.model.io.ModelReader;
import org.apache.maven.model.io.ModelWriter;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Command-line interface for flattening Maven parent POM inheritance.
 * 
 * <p>
 * This CLI tool allows users to convert Maven POMs with parent inheritance
 * into completely standalone POMs with all configuration explicitly declared.
 * 
 * <p>
 * <strong>Features:</strong>
 * <ul>
 * <li>Automatic backup creation (timestamped)</li>
 * <li>Dry-run mode to preview changes</li>
 * <li>Optional profile flattening</li>
 * <li>Detailed summary reporting</li>
 * </ul>
 * 
 * <p>
 * <strong>Usage Examples:</strong>
 * 
 * <pre>
 * # Flatten current directory's pom.xml
 * mvn exec:java -Dexec.mainClass="com.raditha.maven.PomFlattenerCLI"
 * 
 * # Dry run to preview changes
 * mvn exec:java -Dexec.mainClass="com.raditha.maven.PomFlattenerCLI" \
 *   -Dexec.args="--dry-run"
 * 
 * # Flatten specific POM file
 * mvn exec:java -Dexec.mainClass="com.raditha.maven.PomFlattenerCLI" \
 *   -Dexec.args="--pom /path/to/pom.xml"
 * 
 * # Skip profile merging
 * mvn exec:java -Dexec.mainClass="com.raditha.maven.PomFlattenerCLI" \
 *   -Dexec.args="--skip-profiles"
 * 
 * # No backup creation
 * mvn exec:java -Dexec.mainClass="com.raditha.maven.PomFlattenerCLI" \
 *   -Dexec.args="--no-backup"
 * </pre>
 * 
 * @author Maven Parent POM Converter
 * @since 1.0
 */
@Command(name = "pom-flattener", mixinStandardHelpOptions = true, version = "1.0", description = "Flattens Maven parent POM inheritance by expanding all inherited configuration")
public class PomFlattenerCLI implements Callable<Integer> {
    private static final Logger logger = LoggerFactory.getLogger(PomFlattenerCLI.class);

    @Option(names = { "--pom" }, description = "Path to POM file (default: ./pom.xml)")
    private Path pomPath = Paths.get("pom.xml");

    @Option(names = { "--dry-run" }, description = "Show changes without modifying files")
    private boolean dryRun = false;

    @Option(names = { "--no-backup" }, description = "Skip creating backup file")
    private boolean noBackup = false;

    @Option(names = { "--skip-profiles" }, description = "Skip merging profiles from parent chain")
    private boolean skipProfiles = false;

    private final ModelReader modelReader = new DefaultModelReader();
    private final ModelWriter modelWriter = new DefaultModelWriter();

    @Override
    public Integer call() {
        try {
            logger.info("POM Flattener starting");
            logger.info("Target POM: {}", pomPath.toAbsolutePath());

            // Validate POM exists
            if (!Files.exists(pomPath)) {
                System.err.println("Error: POM file not found: " + pomPath);
                return 1;
            }

            // Read child POM
            Model child = modelReader.read(pomPath.toFile(), Collections.emptyMap());

            // Check if POM has a parent
            if (child.getParent() == null) {
                System.out.println("POM has no parent - nothing to flatten");
                return 0;
            }

            Parent parent = child.getParent();
            System.out.println("Found parent: " + parent.getGroupId() + ":" +
                    parent.getArtifactId() + ":" + parent.getVersion());

            // Resolve parent chain
            System.out.println("Resolving parent chain...");
            ParentPomResolver resolver = new ParentPomResolver();
            List<Model> parentChain = resolver.resolveParentChain(parent, pomPath);
            System.out.println("Resolved " + parentChain.size() + " parent level(s)");

            // Flatten inheritance
            System.out.println("Flattening inheritance...");
            InheritanceFlattener flattener = new InheritanceFlattener(skipProfiles);
            Model flattened = flattener.flattenInheritance(child, parentChain);

            // Show summary
            printSummary(child, flattened);

            if (dryRun) {
                System.out.println("\nDry run - no changes made");
                return 0;
            }

            // Create backup unless disabled
            if (!noBackup) {
                Path backup = createBackup(pomPath);
                System.out.println("\nBackup created: " + backup);
            }

            // Write flattened POM
            try (java.io.FileWriter fw = new java.io.FileWriter(pomPath.toFile())) {
                modelWriter.write(fw, Collections.emptyMap(), flattened);
            }
            System.out.println("\n✓ POM successfully flattened: " + pomPath);

            return 0;

        } catch (ParentResolutionException e) {
            System.err.println("\nError: Failed to resolve parent POM");
            System.err.println("  " + e.getFormattedCoordinates());
            System.err.println("  " + e.getMessage());
            logger.error("Parent resolution failed", e);
            return 1;

        } catch (IOException e) {
            System.err.println("\nError: Failed to read/write POM file");
            System.err.println("  " + e.getMessage());
            logger.error("I/O error", e);
            return 1;

        } catch (Exception e) {
            System.err.println("\nUnexpected error: " + e.getMessage());
            logger.error("Unexpected error", e);
            return 1;
        }
    }

    /**
     * Print summary of flattening changes
     */
    private void printSummary(Model original, Model flattened) {
        System.out.println("\n=== Flattening Summary ===");

        // Properties
        int origProps = original.getProperties() != null ? original.getProperties().size() : 0;
        int flatProps = flattened.getProperties() != null ? flattened.getProperties().size() : 0;
        System.out.println("Properties: " + origProps + " → " + flatProps +
                " (+" + (flatProps - origProps) + " from parent)");

        // Dependencies
        int origDeps = original.getDependencies() != null ? original.getDependencies().size() : 0;
        int flatDeps = flattened.getDependencies() != null ? flattened.getDependencies().size() : 0;

        // Count dependencies with explicit versions added
        int versionsAdded = 0;
        if (original.getDependencies() != null && flattened.getDependencies() != null) {
            for (int i = 0; i < Math.min(origDeps, flatDeps); i++) {
                String origVer = original.getDependencies().get(i).getVersion();
                String flatVer = flattened.getDependencies().get(i).getVersion();
                if ((origVer == null || origVer.isEmpty()) &&
                        (flatVer != null && !flatVer.isEmpty())) {
                    versionsAdded++;
                }
            }
        }
        System.out.println("Dependencies: " + origDeps + " total, " +
                versionsAdded + " versions added from dependencyManagement");

        // Plugins
        int origPlugins = 0;
        int flatPlugins = 0;
        if (original.getBuild() != null && original.getBuild().getPlugins() != null) {
            origPlugins = original.getBuild().getPlugins().size();
        }
        if (flattened.getBuild() != null && flattened.getBuild().getPlugins() != null) {
            flatPlugins = flattened.getBuild().getPlugins().size();
        }
        System.out.println("Plugins: " + origPlugins + " → " + flatPlugins +
                " (+" + (flatPlugins - origPlugins) + " from parent)");

        // Profiles
        if (!skipProfiles) {
            int origProfiles = original.getProfiles() != null ? original.getProfiles().size() : 0;
            int flatProfiles = flattened.getProfiles() != null ? flattened.getProfiles().size() : 0;
            System.out.println("Profiles: " + origProfiles + " → " + flatProfiles +
                    " (+" + (flatProfiles - origProfiles) + " from parent)");
        }
    }

    /**
     * Create timestamped backup of POM file
     */
    private Path createBackup(Path pomPath) throws IOException {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String backupName = pomPath.getFileName() + ".backup-" + timestamp;
        Path backup = pomPath.getParent() != null ? pomPath.getParent().resolve(backupName) : Paths.get(backupName);

        Files.copy(pomPath, backup, StandardCopyOption.REPLACE_EXISTING);
        return backup;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new PomFlattenerCLI()).execute(args);
        System.exit(exitCode);
    }
}
