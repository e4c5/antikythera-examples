package com.raditha.dedup.analyzer;

import com.github.javaparser.ast.CompilationUnit;
import com.raditha.dedup.config.DuplicationConfig;
import com.raditha.dedup.detection.TokenNormalizer;
import com.raditha.dedup.analysis.VariationTracker;
import com.raditha.dedup.analysis.TypeAnalyzer;
import com.raditha.dedup.extraction.StatementExtractor;
import com.raditha.dedup.filter.PreFilterChain;
import com.raditha.dedup.clustering.DuplicateClusterer;
import com.raditha.dedup.clustering.RefactoringRecommendationGenerator;
import com.raditha.dedup.model.*;
import com.raditha.dedup.similarity.SimilarityCalculator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static java.util.stream.IntStream.range;

/**
 * Main orchestrator for duplicate detection.
 * Coordinates extraction, filtering, similarity calculation, and result
 * aggregation.
 * 
 * Performance characteristics:
 * - Uses parallel streams for O(N²) pair comparisons (ForkJoinPool)
 * - Pre-normalization of sequences (avoid redundant tokenization)
 * - Two-stage pre-filtering (Size + Structural filters)
 * - Expected: 8x speedup on 8-core machines for large codebases
 */
public class DuplicationAnalyzer {

    private final DuplicationConfig config;
    private final StatementExtractor extractor;
    private final PreFilterChain preFilter;
    private final TokenNormalizer normalizer;
    private final VariationTracker variationTracker;
    private final SimilarityCalculator similarityCalculator;
    private final TypeAnalyzer typeAnalyzer;
    private final DuplicateClusterer clusterer;
    private final RefactoringRecommendationGenerator recommendationGenerator;

    /**
     * Create analyzer with default configuration.
     */
    public DuplicationAnalyzer() {
        this(DuplicationConfig.moderate());
    }

    /**
     * Create analyzer with custom configuration.
     */
    public DuplicationAnalyzer(DuplicationConfig config) {
        this.config = config;
        this.extractor = new StatementExtractor(config.minLines());
        this.preFilter = new PreFilterChain();
        this.normalizer = new TokenNormalizer();
        this.variationTracker = new VariationTracker();
        this.similarityCalculator = new SimilarityCalculator();
        this.typeAnalyzer = new TypeAnalyzer();
        this.clusterer = new DuplicateClusterer(config.threshold());
        this.recommendationGenerator = new RefactoringRecommendationGenerator();
    }

    /**
     * Analyze a single file for duplicates.
     * 
     * @param cu         Compilation unit to analyze
     * @param sourceFile Path to source file
     * @return Analysis report with clustered duplicates and refactoring
     *         recommendations
     */
    public DuplicationReport analyzeFile(CompilationUnit cu, Path sourceFile) {
        // Step 1: Extract all statement sequences
        List<StatementSequence> sequences = extractor.extractSequences(cu, sourceFile);

        // Step 1.5: PRE-NORMALIZE ALL SEQUENCES ONCE (major performance optimization)
        // This avoids normalizing the same sequence multiple times during comparisons
        List<NormalizedSequence> normalizedSequences = sequences.stream()
                .map(seq -> new NormalizedSequence(
                        seq,
                        normalizer.normalizeStatements(seq.statements())))
                .toList();

        // Step 2: Compare all pairs (with pre-filtering)
        List<SimilarityPair> candidates = findCandidates(normalizedSequences);

        // Step 3: Filter by similarity threshold
        List<SimilarityPair> duplicates = filterByThreshold(candidates);

        // Step 4: Cluster duplicates and generate recommendations
        List<DuplicateCluster> clusters = clusterer.cluster(duplicates);

        // Step 5: Add refactoring recommendations to clusters
        List<DuplicateCluster> clustersWithRecommendations = clusters.stream()
                .map(cluster -> {
                    // Get a representative similarity result from the first pair
                    if (!cluster.duplicates().isEmpty()) {
                        SimilarityResult similarity = cluster.duplicates().get(0).similarity();
                        RefactoringRecommendation recommendation = recommendationGenerator
                                .generateRecommendation(cluster, similarity);

                        // Create new cluster with recommendation
                        return new DuplicateCluster(
                                cluster.primary(),
                                cluster.duplicates(),
                                recommendation,
                                cluster.estimatedLOCReduction());
                    }
                    return cluster;
                })
                .toList();

        // Step 6: Create report
        return new DuplicationReport(
                sourceFile,
                duplicates,
                clustersWithRecommendations,
                sequences.size(),
                candidates.size(),
                config);
    }

    /**
     * Helper record to hold a sequence with its pre-computed token list.
     * Avoids redundant normalization during comparisons.
     */
    private record NormalizedSequence(StatementSequence sequence, List<Token> tokens) {
    }

    /**
     * Find candidate duplicate pairs using pre-filtering.
     * Uses parallel streams for performance on large codebases.
     * Memory-efficient: generates pairs lazily using IntStream without materializing all pairs upfront.
     */
    private List<SimilarityPair> findCandidates(List<NormalizedSequence> normalizedSequences) {
        int n = normalizedSequences.size();
        int totalComparisons = n * (n - 1) / 2;
        
        // Generate pair indices lazily and process in parallel
        // This avoids creating O(N²) intermediate objects in memory
        List<SimilarityPair> candidates = range(0, n)
                .parallel()
                .boxed()
                .flatMap(i -> range(i + 1, n)
                        .mapToObj(j -> {
                            // Retrieve sequences once to avoid redundant list lookups
                            NormalizedSequence norm1 = normalizedSequences.get(i);
                            NormalizedSequence norm2 = normalizedSequences.get(j);
                            return new SequencePair(i, j, norm1, norm2);
                        }))
                // Filter: same-method check (cheap)
                .filter(pair -> {
                    StatementSequence seq1 = pair.norm1.sequence();
                    StatementSequence seq2 = pair.norm2.sequence();
                    return seq1.containingMethod() == null ||
                           !seq1.containingMethod().equals(seq2.containingMethod());
                })
                // Filter: size and structural pre-filtering (cheap)
                .filter(pair -> preFilter.shouldCompare(
                        pair.norm1.sequence(), 
                        pair.norm2.sequence()))
                // Calculate similarity for remaining pairs (expensive)
                .map(pair -> analyzePair(pair.norm1, pair.norm2))
                .toList();
        
        int filteredOut = totalComparisons - candidates.size();
        System.out.printf("Pre-filtering: %d/%d comparisons filtered (%.1f%%)%n",
                filteredOut, totalComparisons, 100.0 * filteredOut / totalComparisons);

        return candidates;
    }
    
    /**
     * Helper record for pair with cached normalized sequences.
     * Avoids redundant list lookups during filtering.
     */
    private record SequencePair(int i, int j, NormalizedSequence norm1, NormalizedSequence norm2) {}

    /**
     * Analyze a pair of sequences for similarity using pre-computed tokens.
     */
    private SimilarityPair analyzePair(NormalizedSequence norm1, NormalizedSequence norm2) {
        // Use PRE-COMPUTED tokens (no normalization needed!)
        List<Token> tokens1 = norm1.tokens();
        List<Token> tokens2 = norm2.tokens();

        // Track variations
        VariationAnalysis variations = variationTracker.trackVariations(tokens1, tokens2);

        // Analyze type compatibility (Phase 7 implementation)
        TypeCompatibility typeCompat = typeAnalyzer.analyzeTypeCompatibility(variations);

        SimilarityResult similarity = similarityCalculator.calculate(
                tokens1,
                tokens2,
                config.weights(),
                variations,
                typeCompat);

        return new SimilarityPair(norm1.sequence(), norm2.sequence(), similarity);
    }

    /**
     * Filter pairs by similarity threshold.
     */
    private List<SimilarityPair> filterByThreshold(List<SimilarityPair> candidates) {
        return candidates.stream()
                .filter(pair -> pair.similarity().overallScore() >= config.threshold())
                .sorted((a, b) -> Double.compare(
                        b.similarity().overallScore(),
                        a.similarity().overallScore()))
                .toList();
    }

    /**
     * Get the configuration used by this analyzer.
     */
    public DuplicationConfig getConfig() {
        return config;
    }
}
