package com.raditha.dedup.clustering;

import com.raditha.dedup.model.*;

import java.util.*;

/**
 * Clusters duplicate detection results by grouping related duplicates together.
 */
public class DuplicateClusterer {

    private final double similarityThreshold;

    public DuplicateClusterer() {
        this(0.75);
    }

    public DuplicateClusterer(double similarityThreshold) {
        this.similarityThreshold = similarityThreshold;
    }

    /**
     * Cluster similarity pairs into groups.
     */
    public List<DuplicateCluster> cluster(List<SimilarityPair> pairs) {
        if (pairs.isEmpty()) {
            return List.of();
        }

        // Group pairs by primary sequence
        Map<StatementSequence, List<SimilarityPair>> groups = groupByPrimary(pairs);

        // Convert to clusters
        List<DuplicateCluster> clusters = new ArrayList<>();
        for (Map.Entry<StatementSequence, List<SimilarityPair>> entry : groups.entrySet()) {
            StatementSequence primary = entry.getKey();
            List<SimilarityPair> groupPairs = entry.getValue();

            // Calculate LOC reduction
            int primaryLines = primary.statements().size();
            int totalDuplicateLines = groupPairs.stream()
                    .mapToInt(p -> getPrimary(p).equals(primary) ? p.seq2().statements().size()
                            : p.seq1().statements().size())
                    .sum();
            int locReduction = Math.max(0, totalDuplicateLines - groupPairs.size() - 1);

            DuplicateCluster cluster = new DuplicateCluster(
                    primary,
                    groupPairs,
                    null, // Recommendation added later
                    locReduction);

            clusters.add(cluster);
        }

        // Sort by LOC reduction
        return clusters.stream()
                .sorted((a, b) -> Integer.compare(b.estimatedLOCReduction(), a.estimatedLOCReduction()))
                .toList();
    }

    private Map<StatementSequence, List<SimilarityPair>> groupByPrimary(List<SimilarityPair> pairs) {
        Map<StatementSequence, List<SimilarityPair>> groups = new HashMap<>();

        for (SimilarityPair pair : pairs) {
            StatementSequence primary = getPrimary(pair);
            groups.computeIfAbsent(primary, k -> new ArrayList<>()).add(pair);
        }

        return groups;
    }

    private StatementSequence getPrimary(SimilarityPair pair) {
        int line1 = pair.seq1().range().startLine();
        int line2 = pair.seq2().range().startLine();
        return line1 < line2 ? pair.seq1() : pair.seq2();
    }
}
