package com.raditha.spring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;

import java.util.Map;

/**
 * Detector for error view message attribute usage.
 * 
 * <p>
 * Spring Boot 2.5 changed the default error view to <b>remove</b> (not blank)
 * the message attribute when not displayed. This can break JSON parsing code
 * that expects the field to always be present.
 * 
 * <p>
 * This detector searches for:
 * <ul>
 * <li>JSON parsing code expecting "message" field in error responses</li>
 * <li>Error response DTOs with non-optional message fields</li>
 * <li>Code that doesn't handle missing "message" field</li>
 * </ul>
 * 
 * <p>
 * <b>Mitigation</b>: Configure
 * {@code server.error.include-message=always} if compatibility required.
 * 
 * @see AbstractCodeMigrator
 */
public class ErrorMessageAttributeDetector extends AbstractCodeMigrator {
    private static final Logger logger = LoggerFactory.getLogger(ErrorMessageAttributeDetector.class);

    public ErrorMessageAttributeDetector(boolean dryRun) {
        super(dryRun);
    }

    @Override
    public MigrationPhaseResult migrate() {
        logger.info("Detecting error message attribute usage...");
        MigrationPhaseResult result = new MigrationPhaseResult();

        Map<String, CompilationUnit> allUnits = AntikytheraRunTime.getResolvedCompilationUnits();

        for (Map.Entry<String, CompilationUnit> entry : allUnits.entrySet()) {
            String filePath = entry.getKey();
            CompilationUnit cu = entry.getValue();

            // Detect JSON parsing of "message" field
            detectMessageFieldParsing(cu, filePath, result);

            // Detect error response DTOs
            detectErrorResponseDTOs(cu, filePath, result);
        }

        if (result.getChangeCount() == 0) {
            result.addChange("No potential error message attribute issues detected");
        } else {
            result.addWarning("⚠️  ERROR MESSAGE ATTRIBUTE CHANGE DETECTED");
            result.addWarning("Spring Boot 2.5 removes (not blanks) message field from error responses");
            result.addWarning("Consider adding: server.error.include-message=always");
            result.addWarning("Or update code to handle missing 'message' field");
        }

        return result;
    }

    /**
     * Detect JSON parsing code that expects "message" field.
     */
    private void detectMessageFieldParsing(CompilationUnit cu, String filePath, MigrationPhaseResult result) {
        // Look for .get("message") or similar patterns
        cu.findAll(MethodCallExpr.class).stream()
                .filter(call -> "get".equals(call.getNameAsString()))
                .filter(call -> call.getArguments().size() == 1)
                .filter(call -> call.getArguments().get(0) instanceof StringLiteralExpr)
                .filter(call -> "message".equals(((StringLiteralExpr) call.getArguments().get(0)).getValue()))
                .forEach(call -> {
                    result.addChange(filePath + ": Found .get(\"message\") call at line "
                            + call.getBegin().map(pos -> String.valueOf(pos.line)).orElse("unknown"));
                    result.addWarning("May need to handle missing 'message' field: " + filePath);
                    logger.info("Found message field access in {}", filePath);
                });

        // Look for field access like error.message or errorResponse.message
        cu.findAll(FieldAccessExpr.class).stream()
                .filter(field -> "message".equals(field.getNameAsString()))
                .forEach(field -> {
                    result.addChange(filePath + ": Found .message field access at line "
                            + field.getBegin().map(pos -> String.valueOf(pos.line)).orElse("unknown"));
                    result.addWarning("May need null check for message field: " + filePath);
                    logger.info("Found message field access in {}", filePath);
                });
    }

    /**
     * Detect error response DTO classes with required message field.
     */
    private void detectErrorResponseDTOs(CompilationUnit cu, String filePath, MigrationPhaseResult result) {
        cu.findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class).stream()
                .filter(clazz -> clazz.getNameAsString().toLowerCase().contains("error"))
                .forEach(clazz -> {
                    // Check if class has a message field
                    clazz.getFieldByName("message").ifPresent(messageField -> {
                        // Check if field is non-optional (not Optional<String> or @Nullable)
                        boolean isOptional = messageField.getVariable(0).getType().asString().contains("Optional");
                        boolean hasNullable = messageField.getAnnotations().stream()
                                .anyMatch(ann -> ann.getNameAsString().contains("Nullable"));

                        if (!isOptional && !hasNullable) {
                            result.addChange(filePath + ": Error DTO '" + clazz.getNameAsString()
                                    + "' has required message field");
                            result.addWarning("Consider making message field optional in: " + clazz.getNameAsString());
                            logger.info("Found error DTO with required message field: {}", clazz.getNameAsString());
                        }
                    });
                });
    }

    @Override
    public String getPhaseName() {
        return "Error Message Attribute Detection";
    }

    @Override
    public int getPriority() {
        return 60;
    }
}
