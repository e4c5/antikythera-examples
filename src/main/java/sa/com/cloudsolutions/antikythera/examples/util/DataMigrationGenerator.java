package sa.com.cloudsolutions.antikythera.examples.util;

import sa.com.cloudsolutions.antikythera.examples.util.InsteadOfTriggerGenerator.ColumnMapping;
import sa.com.cloudsolutions.antikythera.examples.util.InsteadOfTriggerGenerator.ViewDescriptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Generates data migration SQL for schema normalization.
 *
 * <p>When a denormalized table is split into multiple normalized tables, existing data
 * must be copied into the new tables before the compatibility view is created.
 * This class produces one {@code INSERT INTO ... SELECT ... FROM old_table} statement
 * per normalized table, in FK dependency order (base table first), ready to pass to
 * {@link LiquibaseGenerator#createRawSqlChangeset}.
 *
 * <p>The generated SQL is standard ANSI and works on both PostgreSQL and Oracle.
 *
 * <p>Example — {@code old_patient} → {@code patient} + {@code address}:
 * <pre>
 *   INSERT INTO patient (id, name) SELECT id, name FROM old_patient;
 *   INSERT INTO address (patient_id, street) SELECT patient_id, street FROM old_patient;
 * </pre>
 */
public class DataMigrationGenerator {

    /**
     * Generates one INSERT-SELECT SQL statement per normalized table described in the view,
     * ordered by FK dependency (parent before child, respecting FKs on insert).
     *
     * <p>Each statement copies rows from the old denormalized table ({@code view.viewName()})
     * into the corresponding normalized table, mapping view columns to their source columns.
     *
     * @param view descriptor of the view and its column-to-table mappings
     * @return list of SQL strings, one per table; empty if no mappings exist
     */
    public List<String> generateMigrationSql(ViewDescriptor view) {
        Map<String, List<ColumnMapping>> byTable = groupByTable(view);

        List<String> order = TopologicalSorter.sort(view.tables(), view.foreignKeys());
        List<String> statements = new ArrayList<>();
        for (String table : order) {
            List<ColumnMapping> cols = byTable.getOrDefault(table, List.of());
            if (cols.isEmpty()) continue;

            String insertCols = cols.stream()
                    .map(ColumnMapping::sourceColumn)
                    .collect(Collectors.joining(", "));
            String selectCols = cols.stream()
                    .map(ColumnMapping::viewColumn)
                    .collect(Collectors.joining(", "));

            String sql = "INSERT INTO " + table
                    + " (" + insertCols + ")"
                    + " SELECT " + selectCols
                    + " FROM " + view.viewName() + ";";

            statements.add(sql);
        }
        return statements;
    }

    private Map<String, List<ColumnMapping>> groupByTable(ViewDescriptor view) {
        Map<String, List<ColumnMapping>> result = new LinkedHashMap<>();
        for (ColumnMapping cm : view.columnMappings()) {
            result.computeIfAbsent(cm.sourceTable(), k -> new ArrayList<>()).add(cm);
        }
        return result;
    }
}
