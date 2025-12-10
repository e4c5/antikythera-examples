package com.raditha.dedup.analyzer;

import com.github.javaparser.ast.CompilationUnit;
import com.raditha.dedup.config.DuplicationConfig;
import com.raditha.dedup.detection.TokenNormalizer;
import com.raditha.dedup.analysis.VariationTracker;
import com.raditha.dedup.extraction.StatementExtractor;
import com.raditha.dedup.filter.PreFilterChain;
import com.raditha.dedup.model.*;
import com.raditha.dedup.similarity.SimilarityCalculator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Main orchestrator for duplicate detection.
 * Coordinates extraction, filtering, similarity calculation, and result
 * aggregation.
 */
public class DuplicationAnalyzer {

    private final DuplicationConfig config;
    private final StatementExtractor extractor;
    private final PreFilterChain preFilter;
    private final TokenNormalizer normalizer;
    private final VariationTracker variationTracker;
    private final SimilarityCalculator similarityCalculator;

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
    }

    /**
     * Analyze a single file for duplicates.
     * 
     * @param cu         Compilation unit to analyze
     * @param sourceFile Path to source file
     * @return Analysis report with all detected duplicates
     */
    public DuplicationReport analyzeFile(CompilationUnit cu, Path sourceFile) {
        // Step 1: Extract all statement sequences
        List<StatementSequence> sequences = extractor.extractSequences(cu, sourceFile);

        // Step 2: Compare all pairs (with pre-filtering)
        List<SimilarityPair> candidates = findCandidates(sequences);

        // Step 3: Filter by similarity threshold
        List<SimilarityPair> duplicates = filterByThreshold(candidates);

        // Step 4: Create report
        return new DuplicationReport(
                sourceFile,
                duplicates,
                sequences.size(),
                candidates.size(),
                config);
    }

    /**
     * Find candidate duplicate pairs using pre-filtering.
     */
    private List<SimilarityPair> findCandidates(List<StatementSequence> sequences) {
        List<SimilarityPair> candidates = new ArrayList<>();
        int totalComparisons = 0;
        int filteredOut = 0;

        // Compare all pairs
        for (int i = 0; i < sequences.size(); i++) {
            for (int j = i + 1; j < sequences.size(); j++) {
                totalComparisons++;

                StatementSequence seq1 = sequences.get(i);
                StatementSequence seq2 = sequences.get(j);

                // Skip sequences from the same method (overlapping windows)
                if (seq1.containingMethod() != null && 
                    seq1.containingMethod().equals(seq2.containingMethod())) {
                    filteredOut++;
                    continue;
                }

                // Pre-filter to skip unlikely matches
                if (!preFilter.shouldCompare(seq1, seq2)) {
                    filteredOut++;
                    continue;
                }

                // Calculate similarity
                SimilarityPair pair = analyzePair(seq1, seq2);
                candidates.add(pair);
            }
        }

        System.out.printf("Pre-filtering: %d/%d comparisons filtered (%.1f%%)%n",
                filteredOut, totalComparisons, 100.0 * filteredOut / totalComparisons);

        return candidates;
    }

    /**
     * Analyze a pair of sequences for similarity.
     */
    private SimilarityPair analyzePair(StatementSequence seq1, StatementSequence seq2) {
        // Normalize to tokens
        List<Token> tokens1 = normalizer.normalizeStatements(seq1.statements());
        List<Token> tokens2 = normalizer.normalizeStatements(seq2.statements());

        // Track variations
        VariationAnalysis variations = variationTracker.trackVariations(tokens1, tokens2);

        // Calculate similarity
        TypeCompatibility typeCompat = new TypeCompatibility(
                true, // Assume feasible for now (Phase 7 will implement proper analysis)
                java.util.Map.of(),
                null,
                List.of());

        SimilarityResult similarity = similarityCalculator.calculate(
                tokens1,
                tokens2,
                config.weights(),
                variations,
                typeCompat);

        return new SimilarityPair(seq1, seq2, similarity);
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
