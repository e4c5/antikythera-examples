package com.raditha.spring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;

import java.util.Map;

/**
 * Migrates Kafka code from Spring Boot 2.1 to 2.2.
 * 
 * Main change:
 * - TopicPartitionInitialOffset → TopicPartitionOffset
 */
public class KafkaCodeMigrator implements MigrationPhase {
    private static final Logger logger = LoggerFactory.getLogger(KafkaCodeMigrator.class);

    private final boolean dryRun;

    public KafkaCodeMigrator(boolean dryRun) {
        this.dryRun = dryRun;
    }

    /**
     * Migrate Kafka code.
     */
    public MigrationPhaseResult migrate() {
        MigrationPhaseResult result = new MigrationPhaseResult();

        Map<String, CompilationUnit> units = AntikytheraRunTime.getResolvedCompilationUnits();
        int changeCount = 0;

        for (Map.Entry<String, CompilationUnit> entry : units.entrySet()) {
            String className = entry.getKey();
            CompilationUnit cu = entry.getValue();

            if (cu == null) {
                continue;
            }

            boolean modified = false;

            // Replace imports
            for (ImportDeclaration imp : cu.findAll(ImportDeclaration.class)) {
                if (imp.getNameAsString().contains("TopicPartitionInitialOffset")) {
                    imp.setName("org.springframework.kafka.support.TopicPartitionOffset");
                    result.addChange(className + ": Updated import TopicPartitionInitialOffset → TopicPartitionOffset");
                    modified = true;
                }
            }

            // Replace type references
            for (ClassOrInterfaceType type : cu.findAll(ClassOrInterfaceType.class)) {
                if (type.getNameAsString().equals("TopicPartitionInitialOffset")) {
                    type.setName("TopicPartitionOffset");
                    if (!modified) {
                        result.addChange(className
                                + ": Updated type reference TopicPartitionInitialOffset → TopicPartitionOffset");
                    }
                    modified = true;
                }
            }

            if (modified) {
                result.addModifiedClass(className);
                changeCount++;
            }
        }

        if (changeCount == 0) {
            result.addChange("No Kafka migrations needed");
        } else {
            logger.info("Kafka migration complete: {} classes updated", changeCount);
        }

        return result;
    }

    @Override
    public String getPhaseName() {
        return "Kafka Migration";
    }

    @Override
    public int getPriority() {
        return 30;
    }
}
