package sa.com.cloudsolutions.antikythera.mcp;

import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.OfflineConnection;
import liquibase.exception.LiquibaseException;
import liquibase.parser.ChangeLogParser;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.resource.CompositeResourceAccessor;
import liquibase.resource.DirectoryResourceAccessor;
import liquibase.resource.ResourceAccessor;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates Liquibase XML changelog files for syntax and structural
 * correctness.
 */
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

            // Set up resource accessor
            Path parentDir = changelogFile.getParentFile().toPath();

            try (DirectoryResourceAccessor directoryAccessor = new DirectoryResourceAccessor(parentDir)) {
                ResourceAccessor resourceAccessor = new CompositeResourceAccessor(directoryAccessor,
                        new ClassLoaderResourceAccessor());

                // Parse the changelog
                ChangeLogParserFactory parserFactory = ChangeLogParserFactory.getInstance();
                ChangeLogParser parser = parserFactory.getParser(changelogFile.getName(), resourceAccessor);

                if (parser == null) {
                    errors.add("No parser found for file: " + changelogFile.getName());
                    return new ValidationResult(false, errors, warnings);
                }

                File tempHistoryFile = File.createTempFile("liquibase-history", ".csv");
                tempHistoryFile.deleteOnExit();
                String offlineUri = "offline:postgresql?changeLogFile=" + tempHistoryFile.getAbsolutePath();

                try (Database database = DatabaseFactory.getInstance()
                        .findCorrectDatabaseImplementation(new OfflineConnection(offlineUri, resourceAccessor))) {

                    // Parse the changelog
                    DatabaseChangeLog changeLog = parser.parse(
                            changelogFile.getName(),
                            new ChangeLogParameters(database),
                            resourceAccessor);

                    if (changeLog == null) {
                        errors.add("Failed to parse changelog file");
                        return new ValidationResult(false, errors, warnings);
                    }

                    // Validate the changelog
                    validateChangeLog(changeLog, database, errors);

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
}
