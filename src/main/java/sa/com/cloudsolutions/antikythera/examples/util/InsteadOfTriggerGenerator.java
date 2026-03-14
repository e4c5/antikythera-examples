package sa.com.cloudsolutions.antikythera.examples.util;

import java.util.ArrayList;
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
@SuppressWarnings("java:S1192") // allow string literals in generated SQL
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

    private static final String BEGIN      = "BEGIN\n";
    private static final String END        = "END;\n";
    private static final String FOR_EACH_ROW = "FOR EACH ROW\n";
    private static final String PG_LANGUAGE  = "$$ LANGUAGE plpgsql;\n\n";

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Generates INSTEAD OF INSERT trigger DDL for both PostgreSQL and Oracle. */
    public Map<LiquibaseGenerator.DatabaseDialect, String> generateInsert(ViewDescriptor view) {
        return buildDialectMap(generateInsertPostgresql(view), generateInsertOracle(view));
    }

    /** Generates INSTEAD OF UPDATE trigger DDL for both PostgreSQL and Oracle. */
    public Map<LiquibaseGenerator.DatabaseDialect, String> generateUpdate(ViewDescriptor view) {
        return buildDialectMap(generateUpdatePostgresql(view), generateUpdateOracle(view));
    }

    /** Generates INSTEAD OF DELETE trigger DDL for both PostgreSQL and Oracle. */
    public Map<LiquibaseGenerator.DatabaseDialect, String> generateDelete(ViewDescriptor view) {
        return buildDialectMap(generateDeletePostgresql(view), generateDeleteOracle(view));
    }

    // -------------------------------------------------------------------------
    // Rollback DDL
    // -------------------------------------------------------------------------

    /** Generates rollback DDL for the INSERT trigger. */
    public Map<LiquibaseGenerator.DatabaseDialect, String> generateInsertRollback(ViewDescriptor view) {
        return buildTriggerRollback(view.viewName(), "insert");
    }

    /** Generates rollback DDL for the UPDATE trigger. */
    public Map<LiquibaseGenerator.DatabaseDialect, String> generateUpdateRollback(ViewDescriptor view) {
        return buildTriggerRollback(view.viewName(), "update");
    }

    /** Generates rollback DDL for the DELETE trigger. */
    public Map<LiquibaseGenerator.DatabaseDialect, String> generateDeleteRollback(ViewDescriptor view) {
        return buildTriggerRollback(view.viewName(), "delete");
    }

    private Map<LiquibaseGenerator.DatabaseDialect, String> buildTriggerRollback(String viewName, String suffix) {
        String pgSql = "DROP TRIGGER IF EXISTS trig_" + viewName + "_" + suffix + " ON " + viewName + ";\n"
                + "DROP FUNCTION IF EXISTS fn_" + viewName + "_" + suffix + "();";
        String oracleSql = "DROP TRIGGER trig_" + viewName + "_" + suffix + ";";
        return buildDialectMap(pgSql, oracleSql);
    }

    // -------------------------------------------------------------------------
    // PostgreSQL implementations
    // -------------------------------------------------------------------------

    private String generateInsertPostgresql(ViewDescriptor view) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE OR REPLACE FUNCTION fn_").append(view.viewName()).append("_insert() RETURNS TRIGGER AS $$\n");
        sb.append(BEGIN);
        appendInsertStatements(view, sb, "NEW.");
        sb.append("    RETURN NEW;\n");
        sb.append(END);
        sb.append(PG_LANGUAGE);
        sb.append("CREATE OR REPLACE TRIGGER trig_").append(view.viewName()).append("_insert\n");
        sb.append("INSTEAD OF INSERT ON ").append(view.viewName()).append("\n");
        sb.append("FOR EACH ROW EXECUTE FUNCTION fn_").append(view.viewName()).append("_insert();");
        return sb.toString();
    }

    private String generateUpdatePostgresql(ViewDescriptor view) {
        ColumnMapping pkMapping = findBasePk(view, groupByTable(view));
        if (pkMapping == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE OR REPLACE FUNCTION fn_").append(view.viewName()).append("_update() RETURNS TRIGGER AS $$\n");
        sb.append(BEGIN);
        appendUpdateStatements(view, sb, "NEW.", pkMapping);
        sb.append("    RETURN NEW;\n");
        sb.append(END);
        sb.append(PG_LANGUAGE);
        sb.append("CREATE OR REPLACE TRIGGER trig_").append(view.viewName()).append("_update\n");
        sb.append("INSTEAD OF UPDATE ON ").append(view.viewName()).append("\n");
        sb.append("FOR EACH ROW EXECUTE FUNCTION fn_").append(view.viewName()).append("_update();");
        return sb.toString();
    }

    private String generateDeletePostgresql(ViewDescriptor view) {
        ColumnMapping pkMapping = findBasePk(view, groupByTable(view));
        if (pkMapping == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE OR REPLACE FUNCTION fn_").append(view.viewName()).append("_delete() RETURNS TRIGGER AS $$\n");
        sb.append(BEGIN);
        appendDeleteStatements(view, groupByTable(view), pkMapping, sb, "OLD.");
        sb.append("    RETURN OLD;\n");
        sb.append(END);
        sb.append(PG_LANGUAGE);
        sb.append("CREATE OR REPLACE TRIGGER trig_").append(view.viewName()).append("_delete\n");
        sb.append("INSTEAD OF DELETE ON ").append(view.viewName()).append("\n");
        sb.append("FOR EACH ROW EXECUTE FUNCTION fn_").append(view.viewName()).append("_delete();");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Oracle implementations
    // -------------------------------------------------------------------------

    private String generateInsertOracle(ViewDescriptor view) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE OR REPLACE TRIGGER trig_").append(view.viewName()).append("_insert\n");
        sb.append("INSTEAD OF INSERT ON ").append(view.viewName()).append("\n");
        sb.append(FOR_EACH_ROW);
        sb.append(BEGIN);
        appendInsertStatements(view, sb, ":NEW.");
        sb.append(END);
        sb.append("/");
        return sb.toString();
    }

    private String generateUpdateOracle(ViewDescriptor view) {
        ColumnMapping pkMapping = findBasePk(view, groupByTable(view));
        if (pkMapping == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE OR REPLACE TRIGGER trig_").append(view.viewName()).append("_update\n");
        sb.append("INSTEAD OF UPDATE ON ").append(view.viewName()).append("\n");
        sb.append(FOR_EACH_ROW);
        sb.append(BEGIN);
        appendUpdateStatements(view, sb, ":NEW.", pkMapping);
        sb.append(END);
        sb.append("/");
        return sb.toString();
    }

    private String generateDeleteOracle(ViewDescriptor view) {
        ColumnMapping pkMapping = findBasePk(view, groupByTable(view));
        if (pkMapping == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE OR REPLACE TRIGGER trig_").append(view.viewName()).append("_delete\n");
        sb.append("INSTEAD OF DELETE ON ").append(view.viewName()).append("\n");
        sb.append(FOR_EACH_ROW);
        sb.append(BEGIN);
        appendDeleteStatements(view, groupByTable(view), pkMapping, sb, ":OLD.");
        sb.append(END);
        sb.append("/");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Shared statement body builders
    // -------------------------------------------------------------------------

    /**
     * Appends one INSERT statement per table, in topological order.
     *
     * @param newPrefix "NEW." for PostgreSQL, ":NEW." for Oracle
     */
    private void appendInsertStatements(ViewDescriptor view, StringBuilder sb, String newPrefix) {
        Map<String, List<ColumnMapping>> byTable = groupByTable(view);
        for (String table : TopologicalSorter.sort(view.tables(), view.foreignKeys())) {
            List<ColumnMapping> cols = byTable.getOrDefault(table, List.of());
            if (cols.isEmpty()) continue;
            String colNames = cols.stream().map(ColumnMapping::sourceColumn).collect(Collectors.joining(", "));
            String values   = cols.stream().map(cm -> newPrefix + cm.viewColumn()).collect(Collectors.joining(", "));
            sb.append("    INSERT INTO ").append(table)
              .append(" (").append(colNames).append(") VALUES (").append(values).append(");\n");
        }
    }

    /**
     * Appends one UPDATE statement per table, in topological order.
     *
     * @param newPrefix "NEW." for PostgreSQL, ":NEW." for Oracle
     * @param pkMapping the PK column mapping of the base table
     */
    private void appendUpdateStatements(ViewDescriptor view, StringBuilder sb, String newPrefix, ColumnMapping pkMapping) {
        Map<String, List<ColumnMapping>> byTable = groupByTable(view);
        for (String table : TopologicalSorter.sort(view.tables(), view.foreignKeys())) {
            List<ColumnMapping> cols    = byTable.getOrDefault(table, List.of());
            List<ColumnMapping> setCols = deriveSetColumns(cols);
            if (setCols.isEmpty()) continue;
            
            String whereCol;
            String viewCol;
            if (table.equals(view.baseTable())) {
                whereCol = pkMapping.sourceColumn();
                viewCol = pkMapping.viewColumn();
            } else {
                ForeignKey parentFk = findImmediateParentFk(table, view);
                whereCol = parentFk.fromColumn();
                viewCol = findViewColumnForParent(parentFk.toTable(), parentFk.toColumn(), view);
            }

            String setClause  = setCols.stream()
                    .map(cm -> cm.sourceColumn() + " = " + newPrefix + cm.viewColumn())
                    .collect(Collectors.joining(", "));
            sb.append("    UPDATE ").append(table)
              .append(" SET ").append(setClause)
              .append(" WHERE ").append(whereCol).append(" = ").append(newPrefix).append(viewCol).append(";\n");
        }
    }

    /**
     * Appends DELETE statements in reverse FK dependency order (child tables first, base table last).
     *
     * @param oldPrefix "OLD." for PostgreSQL, ":OLD." for Oracle
     */
    private void appendDeleteStatements(ViewDescriptor view,
                                        Map<String, List<ColumnMapping>> byTable,
                                        ColumnMapping pkMapping,
                                        StringBuilder sb,
                                        String oldPrefix) {
        for (String table : TopologicalSorter.sort(view.tables(), view.foreignKeys()).reversed()) {
            List<ColumnMapping> cols = byTable.getOrDefault(table, List.of());
            if (cols.isEmpty()) continue;
            
            String whereCol;
            String viewCol;
            if (table.equals(view.baseTable())) {
                whereCol = pkMapping.sourceColumn();
                viewCol = pkMapping.viewColumn();
            } else {
                ForeignKey parentFk = findImmediateParentFk(table, view);
                whereCol = parentFk.fromColumn();
                viewCol = findViewColumnForParent(parentFk.toTable(), parentFk.toColumn(), view);
            }

            sb.append("    DELETE FROM ").append(table)
              .append(" WHERE ").append(whereCol)
              .append(" = ").append(oldPrefix).append(viewCol).append(";\n");
        }
    }

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    /** Builds a dialect map from pre-generated PostgreSQL and Oracle DDL strings. */
    private Map<LiquibaseGenerator.DatabaseDialect, String> buildDialectMap(String postgresqlDdl, String oracleDdl) {
        Map<LiquibaseGenerator.DatabaseDialect, String> result = new EnumMap<>(LiquibaseGenerator.DatabaseDialect.class);
        result.put(LiquibaseGenerator.DatabaseDialect.POSTGRESQL, postgresqlDdl);
        result.put(LiquibaseGenerator.DatabaseDialect.ORACLE, oracleDdl);
        return result;
    }

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
        return baseMappings.isEmpty() ? null : baseMappings.getFirst();
    }

    /**
     * Returns the SET columns for an UPDATE statement: all columns except the first
     * (which is the PK for the base table or the FK column for child tables).
     */
    private List<ColumnMapping> deriveSetColumns(List<ColumnMapping> cols) {
        return cols.size() > 1 ? cols.subList(1, cols.size()) : List.of();
    }

    private String findViewColumnForParent(String parentTable, String parentColumn, ViewDescriptor view) {
        return view.columnMappings().stream()
                .filter(cm -> cm.sourceTable().equals(parentTable) && cm.sourceColumn().equals(parentColumn))
                .map(ColumnMapping::viewColumn)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No view column mapping for " + parentTable + "." + parentColumn));
    }

    private ForeignKey findImmediateParentFk(String table, ViewDescriptor view) {
        return view.foreignKeys().stream()
                .filter(fk -> fk.fromTable().equals(table))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No FK from " + table + " found in foreignKeys"));
    }

    /**
     * Returns the source column name used in the WHERE clause of an UPDATE or DELETE statement.
     * For the base table, returns the PK source column.
     * For child tables, looks up the explicit FK mapping to the base table.
     *
     * @deprecated Use findImmediateParentFk and findViewColumnForParent for multi-hop support.
     * @throws IllegalArgumentException if no FK from the child table to the base table is declared
     */
    @Deprecated
    private String deriveWhereSourceCol(String table, ViewDescriptor view, ColumnMapping pkMapping) {
        if (table.equals(view.baseTable())) return pkMapping.sourceColumn();
        return view.foreignKeys().stream()
                .filter(fk -> fk.fromTable().equals(table) && fk.toTable().equals(view.baseTable()))
                .map(ForeignKey::fromColumn)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No FK from " + table + " to " + view.baseTable() + " found in foreignKeys"));
    }
}
