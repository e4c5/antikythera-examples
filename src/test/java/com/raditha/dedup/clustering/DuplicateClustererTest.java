package com.raditha.dedup.clustering;

import com.raditha.dedup.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DuplicateClusterer.
 */
class DuplicateClustererTest {

    private DuplicateClusterer clusterer;

    @BeforeEach
    void setUp() {
        clusterer = new DuplicateClusterer();
    }

    @Test
    void testEmptyPairs() {
        List<DuplicateCluster> clusters = clusterer.cluster(List.of());
        assertTrue(clusters.isEmpty());
    }

    @Test
    void testSinglePair() {
        SimilarityPair pair = createPair(10, 20, 0.90);

        List<DuplicateCluster> clusters = clusterer.cluster(List.of(pair));

        assertEquals(1, clusters.size());
        DuplicateCluster cluster = clusters.get(0);

        // Primary should be the earlier sequence (line 10)
        assertEquals(10, cluster.primary().range().startLine());
        assertEquals(1, cluster.duplicates().size());
    }

    @Test
    void testMultiplePairsSamePrimary() {
        // 3 duplicates of the same code
        SimilarityPair pair1 = createPair(10, 20, 0.95);
        SimilarityPair pair2 = createPair(10, 30, 0.92);
        SimilarityPair pair3 = createPair(10, 40, 0.90);

        List<DuplicateCluster> clusters = clusterer.cluster(List.of(pair1, pair2, pair3));

        assertEquals(1, clusters.size());
        DuplicateCluster cluster = clusters.get(0);

        assertEquals(10, cluster.primary().range().startLine());
        assertEquals(3, cluster.duplicates().size());
        assertTrue(cluster.estimatedLOCReduction() > 0);
    }

    @Test
    void testMultipleClusters() {
        // Two separate duplicate groups
        SimilarityPair pair1 = createPair(10, 20, 0.95); // Group 1
        SimilarityPair pair2 = createPair(10, 25, 0.92); // Group 1
        SimilarityPair pair3 = createPair(50, 60, 0.90); // Group 2

        List<DuplicateCluster> clusters = clusterer.cluster(List.of(pair1, pair2, pair3));

        assertEquals(2, clusters.size());
    }

    @Test
    void testClustersSortedByLOCReduction() {
        // Create pairs with different LOC potentials
        SimilarityPair smallPair = createPairWithSize(10, 20, 0.95, 3);
        SimilarityPair largePair = createPairWithSize(50, 60, 0.90, 10);

        List<DuplicateCluster> clusters = clusterer.cluster(List.of(smallPair, largePair));

        // Should be sorted by LOC reduction (largest first)
        assertTrue(clusters.get(0).estimatedLOCReduction() >= clusters.get(1).estimatedLOCReduction());
    }

    // Helper methods

    private SimilarityPair createPair(int startLine1, int startLine2, double similarity) {
        return createPairWithSize(startLine1, startLine2, similarity, 5);
    }

    private SimilarityPair createPairWithSize(int startLine1, int startLine2, double similarity, int size) {
        StatementSequence seq1 = createSequence(startLine1, size);
        StatementSequence seq2 = createSequence(startLine2, size);

        SimilarityResult result = new SimilarityResult(
                similarity, // lcsScore
                similarity, // levenshteinScore
                similarity, // structuralScore
                similarity, // overallScore
                0, // normalizedLength1
                0, // normalizedLength2
                new VariationAnalysis(List.of(), false),
                new TypeCompatibility(true, java.util.Map.of(), null, List.of()),
                false // hasControlFlowDifferences
        );
        return new SimilarityPair(seq1, seq2, result);
    }

    private StatementSequence createSequence(int startLine, int size) {
        List<com.github.javaparser.ast.stmt.Statement> statements = new java.util.ArrayList<>();
        for (int i = 0; i < size; i++) {
            statements.add(new com.github.javaparser.ast.stmt.EmptyStmt());
        }

        return new StatementSequence(
                statements,
                new Range(startLine, startLine + size - 1, 1, 10),
                0,
                null,
                null,
                Paths.get("Test.java"));
    }
}
