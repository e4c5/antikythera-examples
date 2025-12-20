package com.raditha.spring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.NodeList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Migrates Cassandra Driver v3 code to v4 format.
 * 
 * <p>
 * This migrator performs actual AST-based code transformations for Cassandra
 * Driver v4 migration:
 * 
 * <p>
 * <b>Automated Transformations:</b>
 * <ul>
 * <li>Package imports: {@code com.datastax.driver.core.*} →
 * {@code com.datastax.oss.driver.api.core.*}</li>
 * <li>Package imports: {@code com.datastax.driver.mapping.*} →
 * {@code com.datastax.oss.driver.api.mapper.*}</li>
 * </ul>
 * 
 * <p>
 * <b>Detection & Manual Review:</b>
 * <ul>
 * <li>Cluster → CqlSession migration (requires manual intervention)</li>
 * <li>ResultSet iteration patterns (flagged for review)</li>
 * <li>Configuration API changes (flagged for review)</li>
 * </ul>
 * 
 * @see MigrationPhase
 */
public class CassandraCodeMigrator extends AbstractCodeMigrator {

    // Package mapping: v3 → v4
    private static final Map<String, String> PACKAGE_MAPPINGS = Map.of(
            "com.datastax.driver.core", "com.datastax.oss.driver.api.core",
            "com.datastax.driver.mapping", "com.datastax.oss.driver.api.mapper",
            "com.datastax.driver.extras", "com.datastax.oss.driver.api.core.cql");

    public CassandraCodeMigrator(boolean dryRun) {
        super(dryRun);
    }

    @Override
    public MigrationPhaseResult migrate() throws IOException {
        MigrationPhaseResult result = new MigrationPhaseResult();

        Map<String, CompilationUnit> units = getCompilationUnits();
        Map<String, CompilationUnit> modifiedUnits = new HashMap<>();

        for (Map.Entry<String, CompilationUnit> entry : units.entrySet()) {
            String className = entry.getKey();
            CompilationUnit cu = entry.getValue();

            if (cu == null) {
                continue;
            }

            // Transform imports
            boolean modified = transformImports(cu, className, result);

            if (modified) {
                modifiedUnits.put(className, cu);
                result.setRequiresManualReview(true);
            }
        }

        if (modifiedUnits.isEmpty()) {
            result.addChange("No Cassandra Driver v3 usage detected");
            return result;
        }

        // Write back modified files
        writeModifiedFiles(modifiedUnits, result);

        // Add migration guidance for complex changes
        addManualMigrationGuidance(result, modifiedUnits.size());

        return result;
    }

    /**
     * Transform Cassandra v3 imports to v4.
     * 
     * @param cu        compilation unit
     * @param className class name for logging
     * @param result    migration result
     * @return true if imports were modified
     */
    private boolean transformImports(CompilationUnit cu, String className, MigrationPhaseResult result) {
        boolean modified = false;
        NodeList<ImportDeclaration> imports = cu.getImports();
        List<ImportDeclaration> toReplace = new ArrayList<>();
        List<String> newImports = new ArrayList<>();

        for (ImportDeclaration imp : imports) {
            String importName = imp.getNameAsString();

            for (Map.Entry<String, String> mapping : PACKAGE_MAPPINGS.entrySet()) {
                String oldPackage = mapping.getKey();
                String newPackage = mapping.getValue();

                if (importName.equals(oldPackage) || importName.startsWith(oldPackage + ".")) {
                    // Replace package prefix
                    String newImportName = importName.replace(oldPackage, newPackage);

                    toReplace.add(imp);
                    newImports.add(newImportName);

                    result.addChange(String.format("%s: %s → %s",
                            className,
                            importName,
                            newImportName));

                    modified = true;
                    break;
                }
            }
        }

        // Apply transformations
        if (modified) {
            // Remove old imports
            for (ImportDeclaration oldImport : toReplace) {
                imports.remove(oldImport);
            }

            // Add new imports
            for (String newImportName : newImports) {
                cu.addImport(newImportName);
            }
        }

        return modified;
    }

    /**
     * Add manual migration guidance for complex changes.
     * 
     * @param result    migration result
     * @param fileCount number of files modified
     */
    private void addManualMigrationGuidance(MigrationPhaseResult result, int fileCount) {
        result.addWarning(String.format(
                "CASSANDRA: Migrated imports in %d files - Manual review required for API changes",
                fileCount));

        StringBuilder guidance = new StringBuilder();
        guidance.append("\n=== CASSANDRA DRIVER V4 - MANUAL MIGRATION REQUIRED ===\n\n");
        guidance.append("✅ AUTOMATED: Package imports updated\n\n");
        guidance.append("⚠️  MANUAL REVIEW NEEDED:\n\n");
        guidance.append("1. CLUSTER → CQLSESSION:\n");
        guidance.append("   Replace: Cluster cluster = Cluster.builder()...build();\n");
        guidance.append("            Session session = cluster.connect();\n");
        guidance.append("   With:    CqlSession session = CqlSession.builder()...build();\n\n");
        guidance.append("2. RESULTSET ITERATION:\n");
        guidance.append("   If you have: for (Row row : resultSet) { ... }\n");
        guidance.append("   Change to:   for (Row row : resultSet.all()) { ... }\n\n");
        guidance.append("3. CONFIGURATION:\n");
        guidance.append("   QueryOptions, PoolingOptions → Use ExecutionProfile\n\n");
        guidance.append("REFERENCE: https://docs.datastax.com/en/developer/java-driver/4.0/upgrade_guide/\n");

        result.addChange(guidance.toString());
    }

    @Override
    public String getPhaseName() {
        return "Cassandra Driver v4 Migration";
    }

    @Override
    public int getPriority() {
        return 40;
    }
}
