package sa.com.cloudsolutions.antikythera.mcp;

import liquibase.change.AbstractSQLChange;
import liquibase.change.Change;
import liquibase.change.core.CreateViewChange;
import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.OfflineConnection;
import liquibase.exception.LiquibaseException;
import liquibase.parser.ChangeLogParser;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.DirectoryResourceAccessor;
import sa.com.cloudsolutions.liquibase.LiquibaseResourceUtil;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates Liquibase XML changelog files for syntax and structural
 * correctness.
 */
@SuppressWarnings("java:S106")
public class LiquibaseValidator {

    /**
     * Result of a validation operation.
     */
    public static class ValidationResult {
        private final boolean valid;
        private final List<String> errors;
        private final List<String> warnings;

        public ValidationResult(boolean valid, List<String> errors, List<String> warnings) {
            this.valid = valid;
            this.errors = errors != null ? errors : new ArrayList<>();
            this.warnings = warnings != null ? warnings : new ArrayList<>();
        }

        public boolean isValid() {
            return valid;
        }

        public List<String> getErrors() {
            return errors;
        }

        public List<String> getWarnings() {
            return warnings;
        }

        public String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"valid\":").append(valid).append(",");
            sb.append("\"errors\":[");
            for (int i = 0; i < errors.size(); i++) {
                if (i > 0)
                    sb.append(",");
                sb.append("\"").append(escapeJson(errors.get(i))).append("\"");
            }
            sb.append("],");
            sb.append("\"warnings\":[");
            for (int i = 0; i < warnings.size(); i++) {
                if (i > 0)
                    sb.append(",");
                sb.append("\"").append(escapeJson(warnings.get(i))).append("\"");
            }
            sb.append("]");
            sb.append("}");
            return sb.toString();
        }

        private String escapeJson(String str) {
            if (str == null)
                return "";
            return str.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
        }
    }

    /**
     * Validates a Liquibase changelog file.
     *
     * @param changelogPath Path to the changelog XML file
     * @return ValidationResult containing validation status and any errors/warnings
     */
    public ValidationResult validate(String changelogPath) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        try {
            File changelogFile = new File(changelogPath);

            if (!changelogFile.exists()) {
                errors.add("Changelog file not found: " + changelogPath);
                return new ValidationResult(false, errors, warnings);
            }

            if (!changelogFile.canRead()) {
                errors.add("Changelog file is not readable: " + changelogPath);
                return new ValidationResult(false, errors, warnings);
            }

            // Set up resource accessor - use intelligent path resolution for Spring Boot
            // projects
            Path resourceRoot = LiquibaseResourceUtil.determineResourceRoot(changelogFile);
            String relativeChangelogPath = LiquibaseResourceUtil.getRelativeChangelogPath(changelogFile, resourceRoot);

            try (DirectoryResourceAccessor resourceAccessor = new DirectoryResourceAccessor(resourceRoot)) {

                // Parse the changelog
                ChangeLogParserFactory parserFactory = ChangeLogParserFactory.getInstance();
                ChangeLogParser parser = parserFactory.getParser(relativeChangelogPath, resourceAccessor);

                if (parser == null) {
                    errors.add("No parser found for file: " + relativeChangelogPath);
                    return new ValidationResult(false, errors, warnings);
                }

                Path tempHistoryPath = Files.createTempFile("liquibase-history", ".csv");
                File tempHistoryFile = tempHistoryPath.toFile();
                tempHistoryFile.deleteOnExit();
                String offlineUri = "offline:postgresql?changeLogFile=" + tempHistoryFile.getAbsolutePath();

                try (Database database = DatabaseFactory.getInstance()
                        .findCorrectDatabaseImplementation(new OfflineConnection(offlineUri, resourceAccessor))) {

                    // Parse the changelog
                    DatabaseChangeLog changeLog = parser.parse(
                            relativeChangelogPath,
                            new ChangeLogParameters(database),
                            resourceAccessor);

                    if (changeLog == null) {
                        errors.add("Failed to parse changelog file");
                        return new ValidationResult(false, errors, warnings);
                    }

                    // Validate the changelog
                    validateChangeLog(changeLog, database, errors);

                    // Validate Raw SQL if any
                    validateRawSql(changeLog, errors);

                    // Additional checks
                    if (changeLog.getChangeSets().isEmpty()) {
                        warnings.add("Changelog contains no change sets");
                    }

                    // Count change sets
                    int changeSetCount = changeLog.getChangeSets().size();
                    warnings.add("Changelog contains " + changeSetCount + " change set(s)");
                }
            }

        } catch (LiquibaseException e) {
            errors.add("Liquibase error: " + e.getMessage());
            if (e.getCause() != null) {
                errors.add("Caused by: " + e.getCause().getMessage());
            }
        } catch (Exception e) {
            errors.add("Unexpected error: " + e.getMessage());
            if (e.getCause() != null) {
                errors.add("Caused by: " + e.getCause().getMessage());
            }
        }

        return new ValidationResult(errors.isEmpty(), errors, warnings);
    }

    /**
     * Validates a changelog against a database.
     */
    private void validateChangeLog(DatabaseChangeLog changeLog, Database database, List<String> errors) {
        try {
            changeLog.validate(database);
        } catch (LiquibaseException e) {
            errors.add("Validation error: " + e.getMessage());
        }
    }

    /**
     * Validates raw SQL changes using JSQLParser.
     */
    private void validateRawSql(DatabaseChangeLog changeLog, List<String> errors) {
        for (ChangeSet changeSet : changeLog.getChangeSets()) {
            for (Change change : changeSet.getChanges()) {
                String sql = null;
                if (change instanceof AbstractSQLChange sqlChange) {
                    sql = sqlChange.getSql();
                } else if (change instanceof CreateViewChange viewChange) {
                    sql = viewChange.getSelectQuery();
                }

                if (sql != null && !sql.trim().isEmpty()) {
                    try {
                        CCJSqlParserUtil.parseStatements(sql);
                    } catch (JSQLParserException e) {
                        errors.add("SQL Syntax error in ChangeSet '" + changeSet.getId() +
                                "' by " + changeSet.getAuthor() + ": " + e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Main method for standalone execution.
     * Usage: java LiquibaseValidator <path-to-liquibase-xml>
     */
    public static void main(String[] args) {
        if (args == null || args.length != 1) {
            System.err.println("Usage: java sa.com.cloudsolutions.antikythera.mcp.LiquibaseValidator <path-to-liquibase-xml>");
            System.exit(1);
        }

        File file = new File(args[0]);
        if (!file.exists() || !file.isFile()) {
            System.err.println("Error: File not found: " + file.getAbsolutePath());
            System.exit(2);
        }

        LiquibaseValidator validator = new LiquibaseValidator();
        ValidationResult result = validator.validate(args[0]);

        // Print JSON output
        System.out.println(result.toJson());

        // Exit with appropriate code
        System.exit(result.isValid() ? 0 : 1);
    }
}
