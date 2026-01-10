package com.raditha.spring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Migrates Hibernate code from Spring Boot 2.1 to 2.2.
 *
 * Main changes:
 * - Detects @TypeDef annotations
 * - Generates AttributeConverter stub classes
 * - Replaces @Type with @Convert annotations
 * - Marks generated stubs for manual completion
 */
public class HibernateCodeMigrator extends AbstractCodeMigrator {


    public HibernateCodeMigrator(boolean dryRun) {
        super(dryRun);
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
        Map<String, CompilationUnit> modifiedUnits = new HashMap<>();

        for (Map.Entry<String, CompilationUnit> entry : units.entrySet()) {
            String className = entry.getKey();
            CompilationUnit cu = entry.getValue();

            // Map typedef name -> converter FQCN for this CU
            Map<String, String> typedefToConverter = new HashMap<>();

            // Find @TypeDef annotations
            List<AnnotationExpr> annotations = cu.findAll(AnnotationExpr.class,
                    ann -> ann.getNameAsString().equals("TypeDef") ||
                            ann.getNameAsString().equals("org.hibernate.annotations.TypeDef"));

            for (AnnotationExpr annotation : annotations) {
                typeDefCount++;
                // Extract type name from @TypeDef
                String typeName = extractTypeDefName(annotation);
                if (typeName != null) {
                    if (!dryRun) {
                        String converterClassName = generateAttributeConverter(className, typeName);
                        typedefToConverter.put(typeName, converterClassName);
                        generatedConverters.add(converterClassName);
                        result.addChange(className + ": Generated AttributeConverter stub for @TypeDef(name=\"" + typeName + "\")");
                    } else {
                        // Dry-run
                        String converterClassName = deriveConverterFqcnFromClassName(className, typeName);
                        typedefToConverter.put(typeName, converterClassName);
                        result.addChange(className + ": Would generate AttributeConverter for @TypeDef(name=\"" + typeName + "\")");
                    }
                }
            }

            // If we have typedefs, attempt to replace @Type annotations on fields
            boolean cuModified = false;
            if (!typedefToConverter.isEmpty()) {
                List<FieldDeclaration> fields = cu.findAll(FieldDeclaration.class);
                for (FieldDeclaration field : fields) {
                    NodeList<AnnotationExpr> fieldAnns = field.getAnnotations();
                    for (int i = 0; i < fieldAnns.size(); i++) {
                        AnnotationExpr fieldAnnotation = fieldAnns.get(i);
                        String annName = fieldAnnotation.getNameAsString();
                        if (annName.equals("Type") || annName.equals("org.hibernate.annotations.Type")) {
                            String referencedTypeName = extractTypeAnnotationValue(fieldAnnotation);
                            if (referencedTypeName != null && typedefToConverter.containsKey(referencedTypeName)) {
                                String converterFqcn = typedefToConverter.get(referencedTypeName);

                                if (dryRun) {
                                    result.addChange(className + ": Would replace @Type(type=\"" + referencedTypeName + "\") with @Convert(converter="
                                            + simpleName(converterFqcn) + ".class) on field " + field.getVariable(0).getNameAsString());
                                } else {
                                    // Replace annotation
                                    NormalAnnotationExpr convertAnn = new NormalAnnotationExpr();
                                    convertAnn.setName("Convert");
                                    ClassExpr classExpr = new ClassExpr(new ClassOrInterfaceType(null, simpleName(converterFqcn)));
                                    MemberValuePair pair = new MemberValuePair("converter", classExpr);
                                    convertAnn.setPairs(new NodeList<>(pair));
                                    fieldAnns.set(i, convertAnn);

                                    // Ensure imports
                                    ensureImport(cu, "javax.persistence.Convert");
                                    ensureImport(cu, converterFqcn);
                                    // Optionally remove Hibernate Type import if present
                                    removeImportIfPresent(cu, "org.hibernate.annotations.Type");

                                    cuModified = true;
                                    result.addChange(className + ": Replaced @Type(type=\"" + referencedTypeName + "\") with @Convert(converter="
                                            + simpleName(converterFqcn) + ".class) on field " + field.getVariable(0).getNameAsString());
                                    result.addWarning(className + "." + field.getVariable(0).getNameAsString() +
                                            ": Replace @Type annotation with @Convert(converter=" + simpleName(converterFqcn) + ".class)");
                                }
                            } else if (!dryRun) {
                                // Could not resolve mapping automatically
                                result.addWarning(className + "." + field.getVariable(0).getNameAsString()
                                        + ": @Type references unknown typedef '" + referencedTypeName
                                        + "' - manual migration may be required");
                            }
                        }
                    }
                }
            }

            if (cuModified) {
                modifiedUnits.put(className, cu);
                result.addModifiedClass(className);
            } else if (!annotations.isEmpty()) {
                // Mark class as relevant even if only generator stubs
                result.addModifiedClass(className);
            }
        }

        if (!modifiedUnits.isEmpty()) {
            writeModifiedFiles(modifiedUnits, result);
        }

        if (typeDefCount == 0) {
            result.addChange("No Hibernate @TypeDef annotations found");
        } else {
            result.addChange(String.format(
                    "Detected %d @TypeDef annotation(s) and generated %d AttributeConverter stub(s)",
                    typeDefCount, generatedConverters.size()));

            if (!generatedConverters.isEmpty()) {
                result.addManualReviewItem(String.format(
                        "Complete conversion logic in %d generated AttributeConverter stub(s) (marked with TODO comments)",
                        generatedConverters.size()));
                result.addManualReviewItem("Verify removed/replaced @Type annotations and consider removing obsolete @TypeDef if unused");
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
    private String generateAttributeConverter(String entityClassName, String typeName) throws IOException {
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

    // Helpers
    private String deriveConverterFqcnFromClassName(String entityClassName, String typeName) {
        String packageName = entityClassName.contains(".") ?
                entityClassName.substring(0, entityClassName.lastIndexOf('.')) : "";
        String converterPackage = packageName.isEmpty() ? "converters" : packageName + ".converters";
        return converterPackage + "." + capitalizeFirst(typeName) + "AttributeConverter";
    }

    private String extractTypeAnnotationValue(AnnotationExpr typeAnnotation) {
        if (typeAnnotation instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair p : normal.getPairs()) {
                if (p.getNameAsString().equals("type")) {
                    Expression v = p.getValue();
                    if (v.isStringLiteralExpr()) {
                        return v.asStringLiteralExpr().asString();
                    }
                    return v.toString().replace("\"", "");
                }
            }
        } else if (typeAnnotation instanceof SingleMemberAnnotationExpr single) {
            Expression v = single.getMemberValue();
            if (v.isStringLiteralExpr()) {
                return v.asStringLiteralExpr().asString();
            }
            return v.toString().replace("\"", "");
        }
        return null;
    }

    private String simpleName(String fqcn) {
        int idx = fqcn.lastIndexOf('.');
        return idx >= 0 ? fqcn.substring(idx + 1) : fqcn;
    }

    private void ensureImport(CompilationUnit cu, String fqcn) {
        for (ImportDeclaration imp : cu.getImports()) {
            if (imp.getNameAsString().equals(fqcn)) {
                return;
            }
        }
        cu.addImport(fqcn);
    }

    private void removeImportIfPresent(CompilationUnit cu, String fqcn) {
        NodeList<ImportDeclaration> imports = cu.getImports();
        for (int i = 0; i < imports.size(); i++) {
            if (imports.get(i).getNameAsString().equals(fqcn)) {
                imports.remove(i);
                return;
            }
        }
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
