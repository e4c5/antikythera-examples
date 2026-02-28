package sa.com.cloudsolutions.antikythera.examples.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Generates INSTEAD OF trigger DDL for INSERT, UPDATE, and DELETE operations on a view,
 * for both PostgreSQL and Oracle, from a pure data structure. No AI or external service is involved.
 *
 * <p>The trigger DDL is derived entirely from the {@link ViewDescriptor}, which maps view columns
 * to their underlying normalized tables. The generated SQL can be passed directly to
 * {@link LiquibaseGenerator#createDialectSqlChangeset}.
 */
public class InsteadOfTriggerGenerator {

    /**
     * Maps a single view column to its source table and column in the normalized schema.
     *
     * @param viewColumn   name of the column as it appears in the view (and the old table)
     * @param sourceTable  normalized table that owns this column
     * @param sourceColumn column name in the normalized table
     */
    public record ColumnMapping(String viewColumn, String sourceTable, String sourceColumn) {}

    /**
     * Describes a foreign-key relationship between two normalized tables.
     *
     * @param fromTable   child table that holds the FK column
     * @param fromColumn  FK column in the child table
     * @param toTable     parent table being referenced
     * @param toColumn    referenced column in the parent table (usually PK)
     */
    public record ForeignKey(String fromTable, String fromColumn, String toTable, String toColumn) {}

    /**
     * Descriptor for a view that was created over one or more normalized tables.
     *
     * @param viewName       name of the view (equivalent to the old denormalized table name)
     * @param baseTable      the primary normalized table that owns the primary key
     * @param tables         all normalized tables (order is irrelevant; topological sort derives it)
     * @param columnMappings one entry per view column describing which source table/column it maps to.
     *                       The first mapping for the base table is treated as the PK column.
     * @param foreignKeys    explicit FK edges used to derive correct table order and WHERE columns
     */
    public record ViewDescriptor(
            String viewName,
            String baseTable,
            List<String> tables,
            List<ColumnMapping> columnMappings,
            List<ForeignKey> foreignKeys) {}

    /**
     * Generates INSTEAD OF INSERT trigger DDL for both PostgreSQL and Oracle.
     *
     * @param view descriptor of the view and its column mappings
     * @return map from dialect to trigger DDL string
     */
    public Map<LiquibaseGenerator.DatabaseDialect, String> generateInsert(ViewDescriptor view) {
        Map<LiquibaseGenerator.DatabaseDialect, String> result = new EnumMap<>(LiquibaseGenerator.DatabaseDialect.class);
        result.put(LiquibaseGenerator.DatabaseDialect.POSTGRESQL, generateInsertPostgresql(view));
        result.put(LiquibaseGenerator.DatabaseDialect.ORACLE, generateInsertOracle(view));
        return result;
    }

    /**
     * Generates INSTEAD OF UPDATE trigger DDL for both PostgreSQL and Oracle.
     * Emits one UPDATE statement per normalized table using only the columns that map to it.
     * The WHERE clause uses the base table's PK for the base table and the FK column for child tables.
     *
     * @param view descriptor of the view and its column mappings
     * @return map from dialect to trigger DDL string
     */
    public Map<LiquibaseGenerator.DatabaseDialect, String> generateUpdate(ViewDescriptor view) {
        Map<LiquibaseGenerator.DatabaseDialect, String> result = new EnumMap<>(LiquibaseGenerator.DatabaseDialect.class);
        result.put(LiquibaseGenerator.DatabaseDialect.POSTGRESQL, generateUpdatePostgresql(view));
        result.put(LiquibaseGenerator.DatabaseDialect.ORACLE, generateUpdateOracle(view));
        return result;
    }

    /**
     * Generates INSTEAD OF DELETE trigger DDL for both PostgreSQL and Oracle.
     * Tables are deleted in reverse FK dependency order to respect FK constraints
     * (child tables before parent/base table).
     *
     * @param view descriptor of the view and its column mappings
     * @return map from dialect to trigger DDL string
     */
    public Map<LiquibaseGenerator.DatabaseDialect, String> generateDelete(ViewDescriptor view) {
        Map<LiquibaseGenerator.DatabaseDialect, String> result = new EnumMap<>(LiquibaseGenerator.DatabaseDialect.class);
        result.put(LiquibaseGenerator.DatabaseDialect.POSTGRESQL, generateDeletePostgresql(view));
        result.put(LiquibaseGenerator.DatabaseDialect.ORACLE, generateDeleteOracle(view));
        return result;
    }

    // -------------------------------------------------------------------------
    // PostgreSQL implementations
    // -------------------------------------------------------------------------

    private String generateInsertPostgresql(ViewDescriptor view) {
        String viewName = view.viewName();
        Map<String, List<ColumnMapping>> byTable = groupByTable(view);
        List<String> order = TopologicalSorter.sort(view.tables(), view.foreignKeys());

        StringBuilder sb = new StringBuilder();
        sb.append("CREATE OR REPLACE FUNCTION fn_").append(viewName).append("_insert() RETURNS TRIGGER AS $$\n");
        sb.append("BEGIN\n");

        for (String table : order) {
            List<ColumnMapping> cols = byTable.getOrDefault(table, List.of());
            if (cols.isEmpty()) continue;
            appendInsertStatement(table, cols, view, "NEW.", sb);
        }

        sb.append("    RETURN NEW;\n");
        sb.append("END;\n");
        sb.append("$$ LANGUAGE plpgsql;\n\n");
        sb.append("CREATE OR REPLACE TRIGGER trig_").append(viewName).append("_insert\n");
        sb.append("INSTEAD OF INSERT ON ").append(viewName).append("\n");
        sb.append("FOR EACH ROW EXECUTE FUNCTION fn_").append(viewName).append("_insert();");
        return sb.toString();
    }

    private String generateUpdatePostgresql(ViewDescriptor view) {
        String viewName = view.viewName();
        Map<String, List<ColumnMapping>> byTable = groupByTable(view);
        ColumnMapping pkMapping = findBasePk(view, byTable);
        if (pkMapping == null) return "";
        List<String> order = TopologicalSorter.sort(view.tables(), view.foreignKeys());

        StringBuilder sb = new StringBuilder();
        sb.append("CREATE OR REPLACE FUNCTION fn_").append(viewName).append("_update() RETURNS TRIGGER AS $$\n");
        sb.append("BEGIN\n");

        for (String table : order) {
            List<ColumnMapping> cols = byTable.getOrDefault(table, List.of());
            if (cols.isEmpty()) continue;

            List<ColumnMapping> setCols = deriveSetColumns(table, view.baseTable(), cols, view);
            if (setCols.isEmpty()) continue;

            String whereCol = deriveWhereSourceCol(table, view, pkMapping);
            String setClause = setCols.stream()
                    .map(cm -> cm.sourceColumn() + " = NEW." + cm.viewColumn())
                    .collect(Collectors.joining(", "));
            sb.append("    UPDATE ").append(table)
              .append(" SET ").append(setClause)
              .append(" WHERE ").append(whereCol).append(" = NEW.").append(pkMapping.viewColumn()).append(";\n");
        }

        sb.append("    RETURN NEW;\n");
        sb.append("END;\n");
        sb.append("$$ LANGUAGE plpgsql;\n\n");
        sb.append("CREATE OR REPLACE TRIGGER trig_").append(viewName).append("_update\n");
        sb.append("INSTEAD OF UPDATE ON ").append(viewName).append("\n");
        sb.append("FOR EACH ROW EXECUTE FUNCTION fn_").append(viewName).append("_update();");
        return sb.toString();
    }

    private String generateDeletePostgresql(ViewDescriptor view) {
        String viewName = view.viewName();
        Map<String, List<ColumnMapping>> byTable = groupByTable(view);
        ColumnMapping pkMapping = findBasePk(view, byTable);
        if (pkMapping == null) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("CREATE OR REPLACE FUNCTION fn_").append(viewName).append("_delete() RETURNS TRIGGER AS $$\n");
        sb.append("BEGIN\n");

        appendDeleteStatements(view, byTable, pkMapping, sb, "OLD.");

        sb.append("    RETURN OLD;\n");
        sb.append("END;\n");
        sb.append("$$ LANGUAGE plpgsql;\n\n");
        sb.append("CREATE OR REPLACE TRIGGER trig_").append(viewName).append("_delete\n");
        sb.append("INSTEAD OF DELETE ON ").append(viewName).append("\n");
        sb.append("FOR EACH ROW EXECUTE FUNCTION fn_").append(viewName).append("_delete();");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Oracle implementations
    // -------------------------------------------------------------------------

    private String generateInsertOracle(ViewDescriptor view) {
        String viewName = view.viewName();
        Map<String, List<ColumnMapping>> byTable = groupByTable(view);
        List<String> order = TopologicalSorter.sort(view.tables(), view.foreignKeys());

        StringBuilder sb = new StringBuilder();
        sb.append("CREATE OR REPLACE TRIGGER trig_").append(viewName).append("_insert\n");
        sb.append("INSTEAD OF INSERT ON ").append(viewName).append("\n");
        sb.append("FOR EACH ROW\n");
        sb.append("BEGIN\n");

        for (String table : order) {
            List<ColumnMapping> cols = byTable.getOrDefault(table, List.of());
            if (cols.isEmpty()) continue;
            appendInsertStatement(table, cols, view, ":NEW.", sb);
        }

        sb.append("END;\n");
        sb.append("/");
        return sb.toString();
    }

    private String generateUpdateOracle(ViewDescriptor view) {
        String viewName = view.viewName();
        Map<String, List<ColumnMapping>> byTable = groupByTable(view);
        ColumnMapping pkMapping = findBasePk(view, byTable);
        if (pkMapping == null) return "";
        List<String> order = TopologicalSorter.sort(view.tables(), view.foreignKeys());

        StringBuilder sb = new StringBuilder();
        sb.append("CREATE OR REPLACE TRIGGER trig_").append(viewName).append("_update\n");
        sb.append("INSTEAD OF UPDATE ON ").append(viewName).append("\n");
        sb.append("FOR EACH ROW\n");
        sb.append("BEGIN\n");

        for (String table : order) {
            List<ColumnMapping> cols = byTable.getOrDefault(table, List.of());
            if (cols.isEmpty()) continue;

            List<ColumnMapping> setCols = deriveSetColumns(table, view.baseTable(), cols, view);
            if (setCols.isEmpty()) continue;

            String whereCol = deriveWhereSourceCol(table, view, pkMapping);
            String setClause = setCols.stream()
                    .map(cm -> cm.sourceColumn() + " = :NEW." + cm.viewColumn())
                    .collect(Collectors.joining(", "));
            sb.append("    UPDATE ").append(table)
              .append(" SET ").append(setClause)
              .append(" WHERE ").append(whereCol).append(" = :NEW.").append(pkMapping.viewColumn()).append(";\n");
        }

        sb.append("END;\n");
        sb.append("/");
        return sb.toString();
    }

    private String generateDeleteOracle(ViewDescriptor view) {
        String viewName = view.viewName();
        Map<String, List<ColumnMapping>> byTable = groupByTable(view);
        ColumnMapping pkMapping = findBasePk(view, byTable);
        if (pkMapping == null) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("CREATE OR REPLACE TRIGGER trig_").append(viewName).append("_delete\n");
        sb.append("INSTEAD OF DELETE ON ").append(viewName).append("\n");
        sb.append("FOR EACH ROW\n");
        sb.append("BEGIN\n");

        appendDeleteStatements(view, byTable, pkMapping, sb, ":OLD.");

        sb.append("END;\n");
        sb.append("/");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    /** Groups column mappings by their source table, preserving insertion order. */
    private Map<String, List<ColumnMapping>> groupByTable(ViewDescriptor view) {
        Map<String, List<ColumnMapping>> result = new java.util.LinkedHashMap<>();
        for (ColumnMapping cm : view.columnMappings()) {
            result.computeIfAbsent(cm.sourceTable(), k -> new ArrayList<>()).add(cm);
        }
        return result;
    }

    /**
     * Returns the first ColumnMapping of the base table, which is treated as the PK column.
     * Returns {@code null} if the base table has no mappings.
     */
    private ColumnMapping findBasePk(ViewDescriptor view, Map<String, List<ColumnMapping>> byTable) {
        List<ColumnMapping> baseMappings = byTable.getOrDefault(view.baseTable(), List.of());
        return baseMappings.isEmpty() ? null : baseMappings.get(0);
    }

    /**
     * Returns the SET columns for an UPDATE statement on the given table.
     *
     * <ul>
     *   <li>For the <em>base table</em>: all columns except the first (treated as PK).</li>
     *   <li>For <em>child tables</em>: all columns except FK columns to the base table
     *       (determined from {@code view.foreignKeys()}, not by position). This is safe even when
     *       FK columns are absent from {@code columnMappings}.</li>
     * </ul>
     */
    private List<ColumnMapping> deriveSetColumns(String table, String baseTable,
                                                  List<ColumnMapping> cols, ViewDescriptor view) {
        if (table.equals(baseTable)) {
            // Base table: skip the first column (PK)
            return cols.size() > 1 ? cols.subList(1, cols.size()) : List.of();
        }
        // Child table: exclude any FK columns declared in foreignKeys (they appear in WHERE, not SET)
        Set<String> fkColumns = view.foreignKeys().stream()
                .filter(fk -> fk.fromTable().equals(table))
                .map(ForeignKey::fromColumn)
                .collect(Collectors.toSet());
        return cols.stream()
                .filter(cm -> !fkColumns.contains(cm.sourceColumn()))
                .collect(Collectors.toList());
    }

    /**
     * Appends a single INSERT statement for {@code table} to {@code sb}.
     *
     * <p>Columns and values come from {@code cols} (the mapped view columns). In addition,
     * FK columns that are declared in {@code view.foreignKeys()} but are <em>absent</em> from
     * {@code cols} are auto-injected: their value is taken from the view column that maps to
     * the parent PK (e.g. {@code NEW.id} populates {@code address.customer_id}).
     * This preserves backward compatibility when the old application code does not supply
     * FK column values explicitly.
     *
     * @param table     target normalized table
     * @param cols      column mappings for this table (from {@link #groupByTable})
     * @param view      full view descriptor (provides foreignKeys and columnMappings)
     * @param newPrefix {@code "NEW."} for PostgreSQL or {@code ":NEW."} for Oracle
     * @param sb        builder to append the INSERT statement to
     */
    private void appendInsertStatement(String table, List<ColumnMapping> cols,
                                        ViewDescriptor view, String newPrefix, StringBuilder sb) {
        List<String> colNames = new ArrayList<>(
                cols.stream().map(ColumnMapping::sourceColumn).collect(Collectors.toList()));
        List<String> values = new ArrayList<>(
                cols.stream().map(cm -> newPrefix + cm.viewColumn()).collect(Collectors.toList()));

        // Auto-inject FK columns that are not already present in columnMappings
        Set<String> existingCols = new HashSet<>(colNames);
        for (ForeignKey fk : view.foreignKeys()) {
            if (!fk.fromTable().equals(table)) continue;
            if (existingCols.contains(fk.fromColumn())) continue; // already provided
            String parentViewCol = findViewColumnForParentPk(view, fk);
            if (parentViewCol != null) {
                colNames.add(fk.fromColumn());
                values.add(newPrefix + parentViewCol);
            }
        }

        sb.append("    INSERT INTO ").append(table)
          .append(" (").append(String.join(", ", colNames)).append(")")
          .append(" VALUES (").append(String.join(", ", values)).append(");\n");
    }

    /**
     * Finds the view-column (i.e. the column name in the view/old source table) that corresponds
     * to the referenced PK column of the parent table in a FK relationship.
     *
     * <p>For example, if the FK is {@code address.customer_id → customer.id}, this looks in
     * {@code columnMappings} for a mapping whose {@code sourceTable = "customer"} and
     * {@code sourceColumn = "id"}, and returns its {@code viewColumn} (e.g. {@code "id"}).
     *
     * @param view the view descriptor containing all column mappings
     * @param fk   the FK whose parent PK view-column is needed
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

    /**
     * Returns the source column name used in the WHERE clause of an UPDATE or DELETE statement.
     * For the base table, returns the PK source column.
     * For child tables, looks up the explicit FK mapping to the base table.
     *
     * @throws IllegalArgumentException if no FK from the child table to the base table is declared
     */
    private String deriveWhereSourceCol(String table, ViewDescriptor view, ColumnMapping pkMapping) {
        if (table.equals(view.baseTable())) return pkMapping.sourceColumn();
        return view.foreignKeys().stream()
                .filter(fk -> fk.fromTable().equals(table) && fk.toTable().equals(view.baseTable()))
                .map(ForeignKey::fromColumn)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No FK from " + table + " to " + view.baseTable() + " found in foreignKeys"));
    }

    /**
     * Appends DELETE statements in reverse FK dependency order (child tables first, base table last).
     * The {@code oldPrefix} is "OLD." for PostgreSQL and ":OLD." for Oracle.
     */
    private void appendDeleteStatements(ViewDescriptor view,
                                        Map<String, List<ColumnMapping>> byTable,
                                        ColumnMapping pkMapping,
                                        StringBuilder sb,
                                        String oldPrefix) {
        List<String> reverseOrder = new ArrayList<>(TopologicalSorter.sort(view.tables(), view.foreignKeys()));
        Collections.reverse(reverseOrder);

        for (String table : reverseOrder) {
            List<ColumnMapping> cols = byTable.getOrDefault(table, List.of());
            if (cols.isEmpty()) continue;

            String whereCol = deriveWhereSourceCol(table, view, pkMapping);
            sb.append("    DELETE FROM ").append(table)
              .append(" WHERE ").append(whereCol)
              .append(" = ").append(oldPrefix).append(pkMapping.viewColumn()).append(";\n");
        }
    }
}
