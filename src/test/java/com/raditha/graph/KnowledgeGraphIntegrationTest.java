package com.raditha.graph;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;


import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.depsolver.Graph;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * Integration test running against the spring-petclinic testbed.
 * Uses real parsing of testbed code but mocks the Neo4j persistence layer
 * to ensure stability in CI/CD environments.
 */
@Tag("integration")
@ExtendWith(MockitoExtension.class)
class KnowledgeGraphIntegrationTest {

    @Mock
    private Neo4jGraphStore graphStore;
    
    private KnowledgeGraphBuilder builder;

    @BeforeEach
    void setUp() throws IOException {
        // Initialize Settings for DependencyAnalyzer
        File generatorConfig = new File("src/test/resources/graph-test.yml");
        assertTrue(generatorConfig.exists(), "Test configuration graph-test.yml must exist");
        Settings.loadConfigMap(generatorConfig);
        
        // Initialize AbstractCompiler (required by DependencyAnalyzer)
        AbstractCompiler.reset();
        
        // We use the @Mock graphStore injected by MockitoExtension
        builder = new KnowledgeGraphBuilder(graphStore);
        Graph.getNodes().clear(); // Clear Antikythera in-memory headers
    }

    @AfterEach
    void tearDown() {
        if (builder != null) {
            builder.close();
        }
    }



    @Test
    void testSpringPetClinicAnalysis() throws IOException {
        // Use Antikythera framework to pre-process (parse & resolve) the testbed
        AbstractCompiler.preProcess();
        
        List<MethodDeclaration> allMethods = new ArrayList<>();
        
        // Retrieve parsed units directly from AntikytheraRunTime
        for (CompilationUnit cu : AntikytheraRunTime.getResolvedCompilationUnits().values()) {
            allMethods.addAll(cu.findAll(MethodDeclaration.class));
        }


        assertTrue(allMethods.size() > 0, "Should have found methods in testbed");

        // Build Graph
        builder.build(allMethods);

        // Verification using ArgumentCaptor to inspect generated edges
        ArgumentCaptor<KnowledgeGraphEdge> edgeCaptor = ArgumentCaptor.forClass(KnowledgeGraphEdge.class);
        verify(graphStore, atLeastOnce()).persistEdge(edgeCaptor.capture());
        
        List<KnowledgeGraphEdge> capturedEdges = edgeCaptor.getAllValues();

        
        assertTrue(capturedEdges.size() > 100, "Should generate a significant number of edges for PetClinic");

        // Check for specific expected patterns (OwnerController calling something)
        // Signature formats are complex to predict exactly without full resolution, 
        // but we can check for partial matches that indicate correct logic.
        boolean foundControllerInteraction = capturedEdges.stream()
            .anyMatch(e -> e.sourceId().contains("OwnerController") && 
                          (e.targetId().contains("Repository") || e.targetId().contains("save")));
                          
        assertTrue(foundControllerInteraction, "Should find OwnerController interaction with Repository/save");
        

    }
}
