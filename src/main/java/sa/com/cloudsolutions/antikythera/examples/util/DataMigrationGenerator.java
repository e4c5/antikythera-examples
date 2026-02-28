package sa.com.cloudsolutions.antikythera.examples.util;

import sa.com.cloudsolutions.antikythera.examples.util.InsteadOfTriggerGenerator.ColumnMapping;
import sa.com.cloudsolutions.antikythera.examples.util.InsteadOfTriggerGenerator.ForeignKey;
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
 * <p>FK columns in child tables are automatically synthesised from the {@code foreignKeys}
 * list: for a FK {@code address.customer_id → customer.id}, the generator adds
 * {@code customer_id} to the child table's INSERT column list and selects the corresponding
 * view column (e.g. {@code id}) from the source table.  Callers must therefore
 * <strong>not</strong> include FK columns in {@code columnMappings}.
 *
 * <p>Example — {@code customer} → {@code customer} + {@code address}:
 * <pre>
 *   INSERT INTO customer (id, name) SELECT id, name FROM customer;
 *   INSERT INTO address (customer_id, street, city, zip) SELECT id, street, city, zip FROM customer;
 * </pre>
 */
public class DataMigrationGenerator {

    /**
     * Generates one INSERT-SELECT SQL statement per normalized table described in the view,
     * ordered by FK dependency (parent before child, respecting FKs on insert).
     *
     * <p>Each statement copies rows from the old denormalized table ({@code view.viewName()})
     * into the corresponding normalized table, mapping view columns to their source columns.
     * FK columns in child tables are auto-injected from the {@code foreignKeys} list and must
     * not be present in {@code columnMappings}.
     *
     * @param view descriptor of the view and its column-to-table mappings
     * @return list of SQL strings, one per table; empty if no mappings exist
     */
    public List<String> generateMigrationSql(ViewDescriptor view) {
        Map<String, List<ColumnMapping>> byTable = groupByTable(view);

        List<String> order = TopologicalSorter.sort(view.tables(), view.foreignKeys());
        List<String> statements = new ArrayList<>();
        for (String table : order) {
            List<ColumnMapping> cols = new ArrayList<>(byTable.getOrDefault(table, List.of()));
            if (cols.isEmpty()) continue;

            // Auto-inject FK columns: for each FK where this table is the child, derive the
            // SELECT expression from the parent PK's view-column and INSERT it as the FK column.
            for (ForeignKey fk : view.foreignKeys()) {
                if (!fk.fromTable().equals(table)) continue;
                String parentViewCol = findViewColumnForParentPk(view, fk);
                if (parentViewCol != null) {
                    cols.add(new ColumnMapping(parentViewCol, table, fk.fromColumn()));
                }
            }

            List<String> insertCols = cols.stream()
                    .map(ColumnMapping::sourceColumn)
                    .collect(Collectors.toList());
            List<String> selectCols = cols.stream()
                    .map(ColumnMapping::viewColumn)
                    .collect(Collectors.toList());

            String sql = "INSERT INTO " + table
                    + " (" + String.join(", ", insertCols) + ")"
                    + " SELECT " + String.join(", ", selectCols)
                    + " FROM " + view.viewName() + ";";

            statements.add(sql);
        }
        return statements;
    }

    /**
     * Finds the view-column (i.e. the column name in the old source table) that corresponds
     * to the referenced PK column of the parent table in a FK relationship.
     *
     * <p>For example, if the FK is {@code address.customer_id → customer.id}, this looks in
     * {@code columnMappings} for a mapping whose {@code targetTable = "customer"} and
     * {@code targetColumn = "id"}, and returns its {@code viewColumn} (e.g. {@code "id"}).
     *
     * @param view the view descriptor containing all column mappings
     * @param fk   the foreign key whose parent PK view-column is needed
     * @return the view-column name for the parent PK, or {@code null} if not found
     */
    private String findViewColumnForParentPk(ViewDescriptor view, ForeignKey fk) {
        return view.columnMappings().stream()
                .filter(cm -> cm.sourceTable().equals(fk.toTable())
                        && cm.sourceColumn().equals(fk.toColumn()))
                .map(ColumnMapping::viewColumn)
                .findFirst()
                .orElse(null);
    }

    private Map<String, List<ColumnMapping>> groupByTable(ViewDescriptor view) {
        Map<String, List<ColumnMapping>> result = new LinkedHashMap<>();
        for (ColumnMapping cm : view.columnMappings()) {
            result.computeIfAbsent(cm.sourceTable(), k -> new ArrayList<>()).add(cm);
        }
        return result;
    }
}
