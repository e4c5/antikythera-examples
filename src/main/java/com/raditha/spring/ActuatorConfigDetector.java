package com.raditha.spring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;

import java.util.List;
import java.util.Map;

/**
 * Detects usage of Actuator features that are disabled by default in Spring Boot 2.2.
 * 
 * Specifically checks for:
 * - HTTP trace endpoint usage (/actuator/httptrace)
 * - Audit event listeners
 * 
 * These features were auto-configured in Spring Boot 2.1 but require explicit
 * configuration in Spring Boot 2.2.
 */
public class ActuatorConfigDetector extends MigrationPhase {


    public ActuatorConfigDetector(boolean dryRun) {
        super(dryRun);
    }

    /**
     * Detect Actuator features requiring configuration.
     */
    public MigrationPhaseResult migrate() {
        MigrationPhaseResult result = new MigrationPhaseResult();

        Map<String, CompilationUnit> units = AntikytheraRunTime.getResolvedCompilationUnits();
        
        boolean httpTraceDetected = false;
        boolean auditEventsDetected = false;

        for (Map.Entry<String, CompilationUnit> entry : units.entrySet()) {
            String className = entry.getKey();
            CompilationUnit cu = entry.getValue();

            if (cu == null) {
                continue;
            }

            // Check for HttpTraceRepository implementations or references
            if (detectHttpTrace(cu, className, result)) {
                httpTraceDetected = true;
            }

            // Check for AuditEventRepository implementations or audit listeners
            if (detectAuditEvents(cu, className, result)) {
                auditEventsDetected = true;
            }
        }

        // Provide configuration guidance
        if (httpTraceDetected) {
            addHttpTraceConfigGuidance(result);
        }

        if (auditEventsDetected) {
            addAuditEventsConfigGuidance(result);
        }

        if (!httpTraceDetected && !auditEventsDetected) {
            result.addChange("No Actuator HTTP trace or audit event usage detected");
        }

        return result;
    }

    /**
     * Detect HTTP trace usage.
     */
    private boolean detectHttpTrace(CompilationUnit cu, String className, MigrationPhaseResult result) {
        // Check for HttpTraceRepository implementations
        List<ClassOrInterfaceDeclaration> classes = cu.findAll(ClassOrInterfaceDeclaration.class);
        
        for (ClassOrInterfaceDeclaration clazz : classes) {
            // Check if class implements HttpTraceRepository
            if (clazz.getImplementedTypes().stream()
                    .anyMatch(type -> type.getNameAsString().contains("HttpTraceRepository"))) {
                result.addChange(className + ": Found HttpTraceRepository implementation");
                return true;
            }

            // Check for @Bean methods returning HttpTraceRepository
            for (MethodDeclaration method : clazz.getMethods()) {
                if (method.getAnnotations().stream()
                        .anyMatch(a -> a.getNameAsString().equals("Bean"))) {
                    if (method.getType().asString().contains("HttpTraceRepository")) {
                        result.addChange(className + "." + method.getNameAsString() + 
                            ": Found @Bean returning HttpTraceRepository");
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Detect audit event usage.
     */
    private boolean detectAuditEvents(CompilationUnit cu, String className, MigrationPhaseResult result) {
        // Check for AuditEventRepository implementations
        List<ClassOrInterfaceDeclaration> classes = cu.findAll(ClassOrInterfaceDeclaration.class);
        
        for (ClassOrInterfaceDeclaration clazz : classes) {
            // Check if class implements AuditEventRepository
            if (clazz.getImplementedTypes().stream()
                    .anyMatch(type -> type.getNameAsString().contains("AuditEventRepository"))) {
                result.addChange(className + ": Found AuditEventRepository implementation");
                return true;
            }

            // Check for @Bean methods returning AuditEventRepository
            for (MethodDeclaration method : clazz.getMethods()) {
                if (method.getAnnotations().stream()
                        .anyMatch(a -> a.getNameAsString().equals("Bean"))) {
                    if (method.getType().asString().contains("AuditEventRepository")) {
                        result.addChange(className + "." + method.getNameAsString() + 
                            ": Found @Bean returning AuditEventRepository");
                        return true;
                    }
                }
            }

            // Check for AbstractAuditListener extensions
            if (clazz.getExtendedTypes().stream()
                    .anyMatch(type -> type.getNameAsString().contains("AbstractAuditListener") ||
                                     type.getNameAsString().contains("AuditListener"))) {
                result.addChange(className + ": Found AuditListener implementation");
                return true;
            }
        }

        return false;
    }

    /**
     * Add HTTP trace configuration guidance.
     */
    private void addHttpTraceConfigGuidance(MigrationPhaseResult result) {
        result.addWarning("⚠️  HTTP trace is disabled by default in Spring Boot 2.2");
        result.addWarning("   You must provide an HttpTraceRepository bean to enable /actuator/httptrace");
        result.addWarning("");
        result.addWarning("   Example configuration:");
        result.addWarning("   @Bean");
        result.addWarning("   public HttpTraceRepository httpTraceRepository() {");
        result.addWarning("       return new InMemoryHttpTraceRepository();");
        result.addWarning("   }");
        result.addWarning("");
        result.addWarning("   Also expose the endpoint in application.yml:");
        result.addWarning("   management.endpoints.web.exposure.include: httptrace");
    }

    /**
     * Add audit events configuration guidance.
     */
    private void addAuditEventsConfigGuidance(MigrationPhaseResult result) {
        result.addWarning("⚠️  Audit events require explicit configuration in Spring Boot 2.2");
        result.addWarning("   You must provide an AuditEventRepository bean");
        result.addWarning("");
        result.addWarning("   Example configuration:");
        result.addWarning("   @Bean");
        result.addWarning("   public AuditEventRepository auditEventRepository() {");
        result.addWarning("       return new InMemoryAuditEventRepository();");
        result.addWarning("   }");
        result.addWarning("");
        result.addWarning("   Also expose the endpoint in application.yml:");
        result.addWarning("   management.endpoints.web.exposure.include: auditevents");
    }

    @Override
    public String getPhaseName() {
        return "Actuator Configuration Detection";
    }

    @Override
    public int getPriority() {
        return 50;
    }
}
