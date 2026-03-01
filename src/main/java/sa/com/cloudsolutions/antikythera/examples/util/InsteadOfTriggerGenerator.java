package sa.com.cloudsolutions.antikythera.examples.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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
    // Rollback DDL (Phase 7)
    // -------------------------------------------------------------------------

    /**
     * Generates rollback DDL for the INSERT trigger.
     * PostgreSQL drops both the trigger and the backing function.
     * Oracle drops only the trigger.
     *
     * @param view descriptor of the view
     * @return map from dialect to rollback SQL
     */
    public Map<LiquibaseGenerator.DatabaseDialect, String> generateInsertRollback(ViewDescriptor view) {
        return buildTriggerRollback(view.viewName(), "insert");
    }

    /**
     * Generates rollback DDL for the UPDATE trigger.
     *
     * @param view descriptor of the view
     * @return map from dialect to rollback SQL
     */
    public Map<LiquibaseGenerator.DatabaseDialect, String> generateUpdateRollback(ViewDescriptor view) {
        return buildTriggerRollback(view.viewName(), "update");
    }

    /**
     * Generates rollback DDL for the DELETE trigger.
     *
     * @param view descriptor of the view
     * @return map from dialect to rollback SQL
     */
    public Map<LiquibaseGenerator.DatabaseDialect, String> generateDeleteRollback(ViewDescriptor view) {
        return buildTriggerRollback(view.viewName(), "delete");
    }

    private Map<LiquibaseGenerator.DatabaseDialect, String> buildTriggerRollback(String viewName, String suffix) {
        Map<LiquibaseGenerator.DatabaseDialect, String> result =
                new EnumMap<>(LiquibaseGenerator.DatabaseDialect.class);

        // PostgreSQL: drop trigger then drop function
        result.put(LiquibaseGenerator.DatabaseDialect.POSTGRESQL,
                "DROP TRIGGER IF EXISTS trig_" + viewName + "_" + suffix + " ON " + viewName + ";\n"
                + "DROP FUNCTION IF EXISTS fn_" + viewName + "_" + suffix + "();");

        // Oracle: drop trigger only (no separate function)
        result.put(LiquibaseGenerator.DatabaseDialect.ORACLE,
                "DROP TRIGGER trig_" + viewName + "_" + suffix + ";");

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
            String colNames = cols.stream().map(ColumnMapping::sourceColumn).collect(Collectors.joining(", "));
            String values = cols.stream().map(cm -> "NEW." + cm.viewColumn()).collect(Collectors.joining(", "));
            sb.append("    INSERT INTO ").append(table)
              .append(" (").append(colNames).append(") VALUES (").append(values).append(");\n");
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

            List<ColumnMapping> setCols = deriveSetColumns(table, view.baseTable(), cols);
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
            String colNames = cols.stream().map(ColumnMapping::sourceColumn).collect(Collectors.joining(", "));
            String values = cols.stream().map(cm -> ":NEW." + cm.viewColumn()).collect(Collectors.joining(", "));
            sb.append("    INSERT INTO ").append(table)
              .append(" (").append(colNames).append(") VALUES (").append(values).append(");\n");
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

            List<ColumnMapping> setCols = deriveSetColumns(table, view.baseTable(), cols);
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
     * Returns the SET columns for an UPDATE statement:
     * - For the base table: all columns except the first (PK).
     * - For child tables: all columns except the first (FK to base table).
     */
    private List<ColumnMapping> deriveSetColumns(String table, String baseTable, List<ColumnMapping> cols) {
        // Skip the first column (PK for base table, FK for child tables)
        return cols.size() > 1 ? cols.subList(1, cols.size()) : List.of();
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
