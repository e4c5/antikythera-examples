package sa.com.cloudsolutions.antikythera.examples.util;

import sa.com.cloudsolutions.antikythera.examples.SchemaNormalizationAnalyzer.DataMigrationPlan;
import sa.com.cloudsolutions.antikythera.examples.SchemaNormalizationAnalyzer.EntityProfile;
import sa.com.cloudsolutions.antikythera.examples.util.InsteadOfTriggerGenerator.ForeignKey;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates a {@link DataMigrationPlan} before artifact generation.
 *
 * <p>Performs four checks:</p>
 * <ol>
 *   <li><strong>Table consistency</strong> – every {@code targetTable} in column mappings and
 *       every {@code fromTable}/{@code toTable} in foreign keys must be in {@code plan.newTables()}.</li>
 *   <li><strong>Base table</strong> – {@code plan.baseTable()} must be in {@code plan.newTables()}.</li>
 *   <li><strong>DAG check</strong> – the foreign keys must form a directed acyclic graph (no cycles).</li>
 *   <li><strong>Column coverage</strong> (warning only) – all columns from the source
 *       {@link EntityProfile} should appear in the column mappings.</li>
 * </ol>
 */
public class DataMigrationPlanValidator {

    private DataMigrationPlanValidator() {
        // intentional private constructor to prevent instantiation
    }

    /**
     * Validates the given {@link DataMigrationPlan} against the source {@link EntityProfile}.
     * Fails fast by throwing an exception on the first error encountered.
     *
     * @param plan          the migration plan to validate
     * @param sourceProfile the entity profile for the source table; may be {@code null} to skip
     *                      column-coverage check
     * @throws IllegalArgumentException if the plan is invalid
     */
    public static void validate(DataMigrationPlan plan, EntityProfile sourceProfile) {
        Set<String> newTableSet = new HashSet<>(plan.newTables());

        validateReferences(plan, newTableSet);

        // 2. Base table check
        if (plan.baseTable() == null || plan.baseTable().isBlank()) {
            throw new IllegalArgumentException("baseTable is null or blank");
        } else if (!newTableSet.contains(plan.baseTable())) {
            throw new IllegalArgumentException("baseTable '" + plan.baseTable() + "' is not in newTables");
        }

        // 3. DAG check via topological sort (already throws IllegalArgumentException on cycles)
        List<ForeignKey> fkList = toFkList(plan);
        TopologicalSorter.sort(plan.newTables(), fkList);

        // 4. Column coverage warning (optional) - log warnings for unmapped columns
        if (sourceProfile != null) {
            Set<String> profileColumns = new HashSet<>();
            sourceProfile.fields().forEach(f -> profileColumns.add(f.columnName()));
            sourceProfile.relationships().forEach(r -> {
                if (r.joinColumn() != null && !r.joinColumn().isBlank()) {
                    profileColumns.add(r.joinColumn());
                }
            });

            Set<String> mappedViewColumns = plan.columnMappings().stream()
                    .map(DataMigrationPlan.ColumnMappingEntry::viewColumn)
                    .collect(Collectors.toSet());

            for (String profileCol : profileColumns) {
                if (!mappedViewColumns.contains(profileCol)) {
                    throw new IllegalArgumentException("WARNING: Column '" + profileCol + "' from source profile is not mapped in columnMappings");
                }
            }
        }
    }

    private static void validateReferences(DataMigrationPlan plan, Set<String> newTableSet) {
        // 1. Table consistency: every targetTable in column mappings must be in newTables
        for (DataMigrationPlan.ColumnMappingEntry cm : plan.columnMappings()) {
            if (!newTableSet.contains(cm.targetTable())) {
                throw new IllegalArgumentException("Column mapping references unknown table: " + cm.targetTable());
            }
        }

        // Table consistency: every fromTable and toTable in foreignKeys must be in newTables
        for (DataMigrationPlan.ForeignKeyEntry fk : plan.foreignKeys()) {
            if (!newTableSet.contains(fk.fromTable())) {
                throw new IllegalArgumentException("FK fromTable references unknown table: " + fk.fromTable());
            }
            if (!newTableSet.contains(fk.toTable())) {
                throw new IllegalArgumentException("FK toTable references unknown table: " + fk.toTable());
            }
        }
    }

    /**
     * Converts the {@link DataMigrationPlan.ForeignKeyEntry} list to
     * {@link InsteadOfTriggerGenerator.ForeignKey} list for use with {@link TopologicalSorter}.
     */
    private static List<ForeignKey> toFkList(DataMigrationPlan plan) {
        return plan.foreignKeys().stream()
                .map(fk -> new ForeignKey(fk.fromTable(), fk.fromColumn(), fk.toTable(), fk.toColumn()))
                .toList();
    }
}
