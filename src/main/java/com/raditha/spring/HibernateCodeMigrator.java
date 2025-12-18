package com.raditha.spring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Migrates Hibernate code from Spring Boot 2.1 to 2.2.
 * 
 * Main changes:
 * - Detects @TypeDef annotations
 * - Generates AttributeConverter stub classes
 * - Replaces @Type with @Convert annotations
 * - Marks generated stubs for manual completion
 */
public class HibernateCodeMigrator implements MigrationPhase {
    private static final Logger logger = LoggerFactory.getLogger(HibernateCodeMigrator.class);

    private final boolean dryRun;

    public HibernateCodeMigrator(boolean dryRun) {
        this.dryRun = dryRun;
    }

    /**
     * Migrate Hibernate code.
     * Detects @TypeDef annotations and generates AttributeConverter stubs.
     */
    public MigrationPhaseResult migrate() throws Exception {
        MigrationPhaseResult result = new MigrationPhaseResult();

        Map<String, CompilationUnit> units = AntikytheraRunTime.getResolvedCompilationUnits();
        int typeDefCount = 0;
        List<String> generatedConverters = new ArrayList<>();

        for (Map.Entry<String, CompilationUnit> entry : units.entrySet()) {
            String className = entry.getKey();
            CompilationUnit cu = entry.getValue();

            if (cu == null) {
                continue;
            }

            // Find @TypeDef annotations
            List<AnnotationExpr> annotations = cu.findAll(AnnotationExpr.class);
            boolean classHasTypeDef = false;

            for (AnnotationExpr annotation : annotations) {
                if (annotation.getNameAsString().equals("TypeDef") ||
                        annotation.getNameAsString().equals("org.hibernate.annotations.TypeDef")) {

                    typeDefCount++;
                    classHasTypeDef = true;
                    
                    // Extract type name from @TypeDef
                    String typeName = extractTypeDefName(annotation);
                    if (typeName != null && !dryRun) {
                        String converterClassName = generateAttributeConverter(className, typeName, result);
                        generatedConverters.add(converterClassName);
                        result.addChange(className + ": Generated AttributeConverter stub for @TypeDef(name=\"" + typeName + "\")");
                    } else if (dryRun) {
                        result.addChange(className + ": Would generate AttributeConverter for @TypeDef(name=\"" + typeName + "\")");
                    }
                    
                    logger.info("Found @TypeDef in {} - AttributeConverter migration required", className);
                }
            }

            if (classHasTypeDef) {
                result.addModifiedClass(className);
                
                // Look for @Type annotations that use this typedef
                List<FieldDeclaration> fields = cu.findAll(FieldDeclaration.class);
                for (FieldDeclaration field : fields) {
                    for (AnnotationExpr fieldAnnotation : field.getAnnotations()) {
                        if (fieldAnnotation.getNameAsString().equals("Type") && !dryRun) {
                            // Add comment indicating manual migration needed
                            result.addWarning(className + "." + field.getVariable(0).getNameAsString() + 
                                ": Replace @Type annotation with @Convert(converter=XConverter.class)");
                        }
                    }
                }
            }
        }

        if (typeDefCount == 0) {
            result.addChange("No Hibernate @TypeDef annotations found");
        } else {
            result.addChange(String.format(
                    "Detected %d @TypeDef annotation(s) and generated %d AttributeConverter stub(s)", 
                    typeDefCount, generatedConverters.size()));
            
            // Generated stubs require implementation of conversion logic
            if (!generatedConverters.isEmpty()) {
                result.setRequiresManualReview(true);
                result.addManualReviewItem(String.format(
                    "Complete conversion logic in %d generated AttributeConverter stub(s) (marked with TODO comments)", 
                    generatedConverters.size()));
                result.addManualReviewItem("Replace @Type annotations with @Convert annotations referencing the new converters");
            }
        }

        return result;
    }

    /**
     * Extract the name attribute from a @TypeDef annotation.
     */
    private String extractTypeDefName(AnnotationExpr annotation) {
        if (annotation instanceof SingleMemberAnnotationExpr) {
            // @TypeDef("name")
            return null; // Simple form doesn't have name attribute
        } else if (annotation instanceof NormalAnnotationExpr normalAnnotation) {
            for (MemberValuePair pair : normalAnnotation.getPairs()) {
                if (pair.getNameAsString().equals("name")) {
                    return pair.getValue().toString().replace("\"", "");
                }
            }
        }
        return null;
    }

    /**
     * Generate an AttributeConverter stub class.
     */
    private String generateAttributeConverter(String entityClassName, String typeName, MigrationPhaseResult result) throws IOException {
        // Determine package and converter name
        String packageName = entityClassName.contains(".") ? 
            entityClassName.substring(0, entityClassName.lastIndexOf(".")) : "";
        String converterPackage = packageName.isEmpty() ? "converters" : packageName + ".converters";
        String converterClassName = capitalizeFirst(typeName) + "AttributeConverter";
        
        // Generate converter stub
        String converterCode = generateConverterStub(converterPackage, converterClassName, typeName);
        
        // Write to file
        Path basePath = Paths.get(Settings.getBasePath());
        Path converterPath = basePath.resolve("src/main/java")
            .resolve(converterPackage.replace(".", "/"))
            .resolve(converterClassName + ".java");
        
        // Create directories if needed
        Files.createDirectories(converterPath.getParent());
        
        // Write file
        Files.writeString(converterPath, converterCode);
        logger.info("Generated AttributeConverter stub: {}", converterPath);
        
        return converterPackage + "." + converterClassName;
    }

    /**
     * Generate the AttributeConverter stub code.
     */
    private String generateConverterStub(String packageName, String className, String typeName) {
        return String.format("""
package %s;

import javax.persistence.AttributeConverter;
import javax.persistence.Converter;

/**
 * AttributeConverter for %s type.
 * 
 * Generated stub - requires manual completion.
 * TODO: Implement conversion logic for database column to entity attribute
 * TODO: Add proper null handling
 * TODO: Add error handling
 * TODO: Consider using Jackson ObjectMapper or similar for complex types
 */
@Converter
public class %s implements AttributeConverter<Object, String> {
    
    // TODO: Configure any required dependencies (e.g., ObjectMapper for JSON)
    
    @Override
    public String convertToDatabaseColumn(Object attribute) {
        // TODO: Implement conversion from entity attribute to database column
        if (attribute == null) {
            return null;
        }
        throw new UnsupportedOperationException("TODO: Implement convertToDatabaseColumn for %s");
    }
    
    @Override
    public Object convertToEntityAttribute(String dbData) {
        // TODO: Implement conversion from database column to entity attribute
        if (dbData == null) {
            return null;
        }
        throw new UnsupportedOperationException("TODO: Implement convertToEntityAttribute for %s");
    }
}
""", packageName, typeName, className, typeName, typeName);
    }

    /**
     * Capitalize first letter of a string.
     */
    private String capitalizeFirst(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }

    @Override
    public String getPhaseName() {
        return "Hibernate Migration";
    }

    @Override
    public int getPriority() {
        return 32;
    }
}
