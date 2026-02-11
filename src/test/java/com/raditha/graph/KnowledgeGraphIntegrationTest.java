package com.raditha.graph;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
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
import java.util.Set;

import org.testcontainers.containers.Neo4jContainer;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import sa.com.cloudsolutions.antikythera.parser.MavenHelper;

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
    
    private Neo4jContainer<?> neo4jContainer;
    
    private KnowledgeGraphBuilder builder;

    @BeforeEach
    void setUp() throws IOException {
        // Initialize Settings for DependencyAnalyzer
        File generatorConfig = new File("src/test/resources/graph-test.yml");
        assertTrue(generatorConfig.exists(), "Test configuration graph-test.yml must exist");
        Settings.loadConfigMap(generatorConfig);
        
        // Initialize AbstractCompiler (required by DependencyAnalyzer)
        AbstractCompiler.reset();
        
        if (Boolean.getBoolean("test.live.neo4j")) {
            System.out.println("🚀 Running against LIVE Neo4j Container");
            neo4jContainer = new Neo4jContainer<>("neo4j:5.18.0")
                .withAdminPassword("password");
            neo4jContainer.start();
            
            graphStore = Neo4jGraphStore.builder()
                .uri(neo4jContainer.getBoltUrl())
                .password("password")
                .build();
        } else {
            System.out.println("🛡️ Running against MOCK GraphStore");
            // graphStore is already injected by Mockito
        }
        
        builder = new KnowledgeGraphBuilder(graphStore);
        Graph.getNodes().clear(); // Clear Antikythera in-memory headers
    }

    @AfterEach
    void tearDown() {
        if (builder != null) {
            builder.close();
        }
        if (neo4jContainer != null) {
            neo4jContainer.stop();
        }
    }



    @Test
    void testSpringPetClinicAnalysis() throws IOException, XmlPullParserException {
        // Pre-load dependencies using MavenHelper
        // This ensures external jars (Spring Boot etc) are available to the parser
        MavenHelper mavenHelper = new MavenHelper();
        mavenHelper.readPomFile();
        mavenHelper.buildJarPaths();

        // Use Antikythera framework to pre-process (parse & resolve) the testbed
        AbstractCompiler.preProcess();
        
        List<CompilationUnit> units = new ArrayList<>();
        
        // Retrieve parsed units directly from AntikytheraRunTime
        for (CompilationUnit cu : AntikytheraRunTime.getResolvedCompilationUnits().values()) {
            units.add(cu);
        }


        assertTrue(units.size() > 0, "Should have found compilation units in testbed");

        // Build Graph
        builder.build(units);

        if (neo4jContainer != null) {
            // Live verification against Real DB
            assertTrue(graphStore.getEdgeCount() > 100, "Should generate a significant number of edges for PetClinic");
            System.out.println("✅ Verified " + graphStore.getEdgeCount() + " edges in Live Neo4j");
        } else {
             // Mock verification using ArgumentCaptor to inspect generated edges
            ArgumentCaptor<KnowledgeGraphEdge> edgeCaptor = ArgumentCaptor.forClass(KnowledgeGraphEdge.class);
            verify(graphStore, atLeastOnce()).persistEdge(edgeCaptor.capture());
            
            List<KnowledgeGraphEdge> capturedEdges = edgeCaptor.getAllValues();
            
            assertTrue(capturedEdges.size() > 100, "Should generate a significant number of edges for PetClinic");

            Set<EdgeType> types = capturedEdges.stream()
                .map(KnowledgeGraphEdge::type)
                .collect(java.util.stream.Collectors.toSet());

            assertTrue(types.contains(EdgeType.CONTAINS), "Should include structural CONTAINS edges");
            assertTrue(types.contains(EdgeType.CALLS), "Should include behavioral CALLS edges");
            assertTrue(types.contains(EdgeType.EXTENDS) || types.contains(EdgeType.IMPLEMENTS),
                    "Should include inheritance edges");

            // Check for specific expected patterns (OwnerController calling something)
            boolean foundControllerInteraction = capturedEdges.stream()
                .anyMatch(e -> e.sourceId().contains("OwnerController") && 
                              (e.targetId().contains("Repository") || e.targetId().contains("save")));
                              
            assertTrue(foundControllerInteraction, "Should find OwnerController interaction with Repository/save");
        }
        

    }
}
