package sa.com.cloudsolutions.antikythera.examples.util;

import sa.com.cloudsolutions.antikythera.examples.SchemaNormalizationAnalyzer.EntityProfile;
import sa.com.cloudsolutions.antikythera.examples.util.InsteadOfTriggerGenerator.ColumnMapping;
import sa.com.cloudsolutions.antikythera.examples.util.InsteadOfTriggerGenerator.ForeignKey;
import sa.com.cloudsolutions.antikythera.examples.util.InsteadOfTriggerGenerator.ViewDescriptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates CREATE TABLE DDL (raw SQL or Liquibase changeSet XML) for each normalized table
 * described in a {@link ViewDescriptor}, in topological FK dependency order.
 *
 * <p>Column types are resolved from the source {@link EntityProfile} by matching view column
 * names to profile field column names and then converting the Java type to a SQL/Liquibase type.
 * Unknown types default to {@code VARCHAR(255)} / {@code varchar(255)}.
 */
public class NormalizedTableDDLGenerator {

    /** DDL mode: emit raw ANSI SQL CREATE TABLE statements. */
    public static final String MODE_RAW_SQL = "raw_sql";

    /** DDL mode: emit Liquibase {@code <changeSet>} XML fragments with {@code <createTable>}. */
    public static final String MODE_LIQUIBASE = "liquibase";

    // Java type → ANSI SQL type
    private static final Map<String, String> JAVA_TO_SQL = new HashMap<>();
    // Java type → Liquibase type name
    private static final Map<String, String> JAVA_TO_LIQUIBASE = new HashMap<>();

    static {
        JAVA_TO_SQL.put("Long",          "BIGINT");
        JAVA_TO_SQL.put("long",          "BIGINT");
        JAVA_TO_SQL.put("Integer",       "INTEGER");
        JAVA_TO_SQL.put("int",           "INTEGER");
        JAVA_TO_SQL.put("String",        "VARCHAR(255)");
        JAVA_TO_SQL.put("BigDecimal",    "NUMERIC(19,2)");
        JAVA_TO_SQL.put("Boolean",       "BOOLEAN");
        JAVA_TO_SQL.put("boolean",       "BOOLEAN");
        JAVA_TO_SQL.put("Double",        "DOUBLE PRECISION");
        JAVA_TO_SQL.put("double",        "DOUBLE PRECISION");
        JAVA_TO_SQL.put("Float",         "REAL");
        JAVA_TO_SQL.put("float",         "REAL");
        JAVA_TO_SQL.put("Short",         "SMALLINT");
        JAVA_TO_SQL.put("short",         "SMALLINT");
        JAVA_TO_SQL.put("Date",          "DATE");
        JAVA_TO_SQL.put("LocalDate",     "DATE");
        JAVA_TO_SQL.put("LocalDateTime", "TIMESTAMP");
        JAVA_TO_SQL.put("Timestamp",     "TIMESTAMP");

        JAVA_TO_LIQUIBASE.put("Long",          "bigint");
        JAVA_TO_LIQUIBASE.put("long",          "bigint");
        JAVA_TO_LIQUIBASE.put("Integer",       "int");
        JAVA_TO_LIQUIBASE.put("int",           "int");
        JAVA_TO_LIQUIBASE.put("String",        "varchar(255)");
        JAVA_TO_LIQUIBASE.put("BigDecimal",    "decimal(19,2)");
        JAVA_TO_LIQUIBASE.put("Boolean",       "boolean");
        JAVA_TO_LIQUIBASE.put("boolean",       "boolean");
        JAVA_TO_LIQUIBASE.put("Double",        "double");
        JAVA_TO_LIQUIBASE.put("double",        "double");
        JAVA_TO_LIQUIBASE.put("Float",         "float");
        JAVA_TO_LIQUIBASE.put("float",         "float");
        JAVA_TO_LIQUIBASE.put("Short",         "smallint");
        JAVA_TO_LIQUIBASE.put("short",         "smallint");
        JAVA_TO_LIQUIBASE.put("Date",          "date");
        JAVA_TO_LIQUIBASE.put("LocalDate",     "date");
        JAVA_TO_LIQUIBASE.put("LocalDateTime", "timestamp");
        JAVA_TO_LIQUIBASE.put("Timestamp",     "timestamp");
    }

    /**
     * Generates one DDL string per normalized table, in FK dependency order (parent before child).
     *
     * @param view          descriptor of the view/tables and column mappings
     * @param sourceProfile entity profile for the original denormalized table; used to resolve
     *                      Java types; may be {@code null} (all columns default to VARCHAR)
     * @param ddlMode       {@value #MODE_RAW_SQL} or {@value #MODE_LIQUIBASE}
     * @return list of DDL strings in topological order; one entry per table that has column mappings
     */
    public List<String> generate(ViewDescriptor view, EntityProfile sourceProfile, String ddlMode) {
        // Group column mappings by their source (normalized) table
        Map<String, List<ColumnMapping>> byTable = new HashMap<>();
        for (ColumnMapping cm : view.columnMappings()) {
            byTable.computeIfAbsent(cm.sourceTable(), k -> new ArrayList<>()).add(cm);
        }

        // Build lookup maps from the source profile
        Map<String, String> columnJavaType = new HashMap<>();
        Map<String, Boolean> columnNullable = new HashMap<>();
        Map<String, Boolean> columnIsId    = new HashMap<>();
        if (sourceProfile != null) {
            for (var field : sourceProfile.fields()) {
                columnJavaType.put(field.columnName(), field.columnType());
                columnNullable.put(field.columnName(), field.isNullable());
                columnIsId.put(field.columnName(), field.isId());
            }
        }

        List<String> order = TopologicalSorter.sort(view.tables(), view.foreignKeys());
        List<String> result = new ArrayList<>();

        for (String table : order) {
            List<ColumnMapping> cols = byTable.getOrDefault(table, List.of());
            if (cols.isEmpty()) continue;

            if (MODE_RAW_SQL.equals(ddlMode)) {
                result.add(buildRawSql(table, cols, columnJavaType, columnNullable, columnIsId));
            } else {
                result.add(buildLiquibaseChangeset(table, cols, columnJavaType, columnNullable, columnIsId));
            }
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // Raw SQL
    // -------------------------------------------------------------------------

    private String buildRawSql(String table, List<ColumnMapping> cols,
                                Map<String, String> typeMap,
                                Map<String, Boolean> nullableMap,
                                Map<String, Boolean> isIdMap) {
        List<String> colDefs = new ArrayList<>();
        List<String> pkCols  = new ArrayList<>();

        for (ColumnMapping cm : cols) {
            String sqlType = toSqlType(typeMap.get(cm.viewColumn()));
            boolean isId   = Boolean.TRUE.equals(isIdMap.get(cm.viewColumn()));
            boolean nullable = !Boolean.FALSE.equals(nullableMap.getOrDefault(cm.viewColumn(), true));

            StringBuilder def = new StringBuilder("    ").append(cm.sourceColumn()).append(" ").append(sqlType);
            if (isId || !nullable) {
                def.append(" NOT NULL");
            }
            colDefs.add(def.toString());

            if (isId) {
                pkCols.add(cm.sourceColumn());
            }
        }

        StringBuilder sb = new StringBuilder("CREATE TABLE ").append(table).append(" (\n");
        sb.append(String.join(",\n", colDefs));
        if (!pkCols.isEmpty()) {
            sb.append(",\n    PRIMARY KEY (").append(String.join(", ", pkCols)).append(")");
        }
        sb.append("\n)");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Liquibase changeset XML
    // -------------------------------------------------------------------------

    private String buildLiquibaseChangeset(String table, List<ColumnMapping> cols,
                                            Map<String, String> typeMap,
                                            Map<String, Boolean> nullableMap,
                                            Map<String, Boolean> isIdMap) {
        StringBuilder sb = new StringBuilder();
        sb.append("<changeSet id=\"create_table_").append(table).append("_")
          .append(System.currentTimeMillis()).append("\" author=\"antikythera\">\n");
        sb.append("    <createTable tableName=\"").append(table).append("\">\n");

        for (ColumnMapping cm : cols) {
            String lbType = toLiquibaseType(typeMap.get(cm.viewColumn()));
            boolean isId  = Boolean.TRUE.equals(isIdMap.get(cm.viewColumn()));
            boolean nullable = !Boolean.FALSE.equals(nullableMap.getOrDefault(cm.viewColumn(), true));

            sb.append("        <column name=\"").append(cm.sourceColumn())
              .append("\" type=\"").append(lbType).append("\"");

            if (isId || !nullable) {
                sb.append(">\n");
                sb.append("            <constraints");
                if (isId) sb.append(" primaryKey=\"true\"");
                sb.append(" nullable=\"false\"");
                if (isId) sb.append(" autoIncrement=\"true\"");
                sb.append("/>\n");
                sb.append("        </column>\n");
            } else {
                sb.append("/>\n");
            }
        }

        sb.append("    </createTable>\n");
        sb.append("    <rollback><dropTable tableName=\"").append(table).append("\"/></rollback>\n");
        sb.append("</changeSet>");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Type mapping helpers
    // -------------------------------------------------------------------------

    /** Maps a Java type name to an ANSI SQL type. Defaults to {@code VARCHAR(255)} if unknown. */
    public static String toSqlType(String javaType) {
        if (javaType == null) return "VARCHAR(255)";
        return JAVA_TO_SQL.getOrDefault(javaType, "VARCHAR(255)");
    }

    /** Maps a Java type name to a Liquibase type name. Defaults to {@code varchar(255)} if unknown. */
    public static String toLiquibaseType(String javaType) {
        if (javaType == null) return "varchar(255)";
        return JAVA_TO_LIQUIBASE.getOrDefault(javaType, "varchar(255)");
    }
}
