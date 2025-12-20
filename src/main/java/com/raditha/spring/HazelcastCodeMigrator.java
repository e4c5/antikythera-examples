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
 * Detects Hazelcast usage for Spring Boot 2.4.
 * 
 * <p>
 * Spring Boot 2.4 upgrades Hazelcast from 3.x to 4.x, which introduced breaking
 * API changes.
 * 
 * <p>
 * This migrator:
 * <ul>
 * <li>Detects Hazelcast imports and usage in source code</li>
 * <li>Flags files that may require changes for Hazelcast 4.x compatibility</li>
 * <li>Provides migration guidance and reference documentation</li>
 * </ul>
 * 
 * <p>
 * Key Hazelcast 4.x changes:
 * <ul>
 * <li>Package names mostly unchanged (com.hazelcast.*)</li>
 * <li>Configuration API changes for some classes</li>
 * <li>Deprecated methods removed</li>
 * <li>Network configuration changes</li>
 * </ul>
 * 
 * @see MigrationPhase
 */
public class HazelcastCodeMigrator implements MigrationPhase {
    private static final Logger logger = LoggerFactory.getLogger(HazelcastCodeMigrator.class);

    private final boolean dryRun;

    public HazelcastCodeMigrator(boolean dryRun) {
        this.dryRun = dryRun;
    }

    @Override
    public MigrationPhaseResult migrate() {
        MigrationPhaseResult result = new MigrationPhaseResult();

        Map<String, CompilationUnit> units = AntikytheraRunTime.getResolvedCompilationUnits();
        List<String> filesWithHazelcast = new ArrayList<>();

        for (Map.Entry<String, CompilationUnit> entry : units.entrySet()) {
            String className = entry.getKey();
            CompilationUnit cu = entry.getValue();

            if (cu == null) {
                continue;
            }

            // Check for Hazelcast imports
            for (ImportDeclaration imp : cu.findAll(ImportDeclaration.class)) {
                String importName = imp.getNameAsString();

                // Hazelcast core packages
                if (importName.startsWith("com.hazelcast")) {
                    filesWithHazelcast.add(className);
                    break;
                }
            }
        }

        if (filesWithHazelcast.isEmpty()) {
            result.addChange("No Hazelcast usage detected");
            logger.info("No Hazelcast imports found");
            return result;
        }

        // Generate migration guide
        result.setRequiresManualReview(true);
        result.addWarning(String.format(
                "HAZELCAST: Hazelcast 4.x detected or will be upgraded - check for breaking changes in %d files",
                filesWithHazelcast.size()));

        result.addWarning("Hazelcast 4.x introduces breaking API changes from 3.x");

        // Add specific files that need attention
        for (String className : filesWithHazelcast) {
            result.addChange("Hazelcast usage in: " + className);
        }

        // Generate detailed migration guide
        StringBuilder guide = new StringBuilder();
        guide.append("\n=== HAZELCAST 4.x MIGRATION GUIDE ===\n\n");
        guide.append("Spring Boot 2.4 upgrades Hazelcast from 3.x to 4.x with BREAKING CHANGES:\n\n");

        guide.append("1. CONFIGURATION CHANGES:\n");
        guide.append("   Review configuration classes for deprecated methods\n");
        guide.append("   Check NetworkConfig and other config builders\n\n");

        guide.append("2. CLUSTER FORMATION:\n");
        guide.append("   Network configuration may require updates\n");
        guide.append("   Join configuration changes for discovery mechanisms\n\n");

        guide.append("3. CLIENT CONFIGURATION:\n");
        guide.append("   HazelcastClient configuration API may have changed\n");
        guide.append("   Review client credentials and authentication setup\n\n");

        guide.append("4. MAP AND CACHE CONFIGURATION:\n");
        guide.append("   MapConfig and CacheConfig may have new options\n");
        guide.append("   Eviction policies and expiration settings\n\n");

        guide.append("5. COMMON ISSUES:\n");
        guide.append("   - Deprecated methods removed\n");
        guide.append("   - Some configuration defaults changed\n");
        guide.append("   - Serialization configuration updates\n\n");

        guide.append("FILES REQUIRING REVIEW:\n");
        for (String className : filesWithHazelcast) {
            guide.append("  - ").append(className).append("\n");
        }

        guide.append("\nACTIONS:\n");
        guide.append("1. Review Hazelcast configuration classes\n");
        guide.append("2. Test all Hazelcast-related functionality\n");
        guide.append("3. Verify cache operations work correctly\n");
        guide.append("4. Check cluster formation in distributed environments\n\n");

        guide.append("REFERENCE: https://docs.hazelcast.com/hazelcast/4.0/migration-guides/4.0\n");

        logger.warn("\n{}", guide);
        result.addChange(guide.toString());

        return result;
    }

    @Override
    public String getPhaseName() {
        return "Hazelcast 4.x Detection";
    }

    @Override
    public int getPriority() {
        return 55;
    }
}
