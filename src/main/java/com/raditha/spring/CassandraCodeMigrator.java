package com.raditha.spring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Detects Cassandra Driver v3 patterns and generates migration guide for v4.
 * 
 * <p>
 * This migrator focuses on <b>detection and guidance</b> rather than automatic
 * transformation
 * due to the complexity of Cassandra driver v4 breaking changes.
 * 
 * <p>
 * Major breaking changes in Cassandra Driver v4:
 * <ul>
 * <li>Package rename: {@code com.datastax.driver.core} →
 * {@code com.datastax.oss.driver.api.core}</li>
 * <li>{@code Cluster} replaced with {@code CqlSession}</li>
 * <li>{@code Session} methods changed significantly</li>
 * <li>ResultSet iteration API changed</li>
 * <li>QueryOptions and other configuration classes redesigned</li>
 * </ul>
 * 
 * @see MigrationPhase
 */
public class CassandraCodeMigrator implements MigrationPhase {
    private static final Logger logger = LoggerFactory.getLogger(CassandraCodeMigrator.class);

    private final boolean dryRun;

    public CassandraCodeMigrator(boolean dryRun) {
        this.dryRun = dryRun;
    }

    @Override
    public MigrationPhaseResult migrate() {
        MigrationPhaseResult result = new MigrationPhaseResult();

        Map<String, CompilationUnit> units = AntikytheraRunTime.getResolvedCompilationUnits();
        List<String> filesWithCassandraV3 = new ArrayList<>();

        for (Map.Entry<String, CompilationUnit> entry : units.entrySet()) {
            String className = entry.getKey();
            CompilationUnit cu = entry.getValue();

            if (cu == null) {
                continue;
            }

            // Check for Cassandra driver v3 imports
            for (ImportDeclaration imp : cu.findAll(ImportDeclaration.class)) {
                String importName = imp.getNameAsString();

                // Old driver package pattern
                if (importName.startsWith("com.datastax.driver.core")) {
                    filesWithCassandraV3.add(className);
                    break;
                }
            }
        }

        if (filesWithCassandraV3.isEmpty()) {
            result.addChange("No Cassandra Driver v3 usage detected");
            logger.info("No Cassandra driver v3 imports found");
            return result;
        }

        // Generate migration guide
        result.addWarning(String.format(
                "CASSANDRA: Detected Cassandra Driver v3 usage in %d files",
                filesWithCassandraV3.size()));

        result.addWarning(
                "Cassandra Driver v4 Migration Required - Major breaking changes detected");

        // Add specific files that need attention
        for (String className : filesWithCassandraV3) {
            result.addChange("Cassandra v3 detected in: " + className);
        }

        // Generate detailed migration guide
        StringBuilder guide = new StringBuilder();
        guide.append("\n=== CASSANDRA DRIVER V4 MIGRATION GUIDE ===\n\n");
        guide.append("Spring Boot 2.3 upgrades to Cassandra Driver v4, which introduces BREAKING CHANGES:\n\n");
        guide.append("1. PACKAGE RENAME:\n");
        guide.append("   OLD: com.datastax.driver.core.*\n");
        guide.append("   NEW: com.datastax.oss.driver.api.core.*\n\n");
        guide.append("2. CLUSTER → CQLSESSION:\n");
        guide.append("   OLD: Cluster cluster = Cluster.builder()...build();\n");
        guide.append("        Session session = cluster.connect();\n");
        guide.append("   NEW: CqlSession session = CqlSession.builder()...build();\n\n");
        guide.append("3. QUERY EXECUTION:\n");
        guide.append("   OLD: ResultSet rs = session.execute(query);\n");
        guide.append("        for (Row row : rs) { ... }\n");
        guide.append("   NEW: ResultSet rs = session.execute(query);\n");
        guide.append("        for (Row row : rs.all()) { ... }\n\n");
        guide.append("4. PREPARED STATEMENTS:\n");
        guide.append("   OLD: PreparedStatement ps = session.prepare(query);\n");
        guide.append("        BoundStatement bound = ps.bind(params);\n");
        guide.append("   NEW: PreparedStatement ps = session.prepare(query);\n");
        guide.append("        BoundStatement bound = ps.bind(params); // Same\n\n");
        guide.append("FILES REQUIRING CHANGES:\n");
        for (String className : filesWithCassandraV3) {
            guide.append("  - ").append(className).append("\n");
        }
        guide.append("\nREFERENCE: https://docs.datastax.com/en/developer/java-driver/4.0/upgrade_guide/\n");

        logger.warn("\n{}", guide);
        result.addChange(guide.toString());

        return result;
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
