package sa.com.cloudsolutions.antikythera.examples.util;

import sa.com.cloudsolutions.antikythera.examples.util.InsteadOfTriggerGenerator.ForeignKey;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * Topological sort for a set of tables connected by foreign-key relationships.
 * Uses Kahn's algorithm (BFS-based), which detects cycles.
 */
public class TopologicalSorter {

    /**
     * Returns tables in FK dependency order: parents before children.
     * Safe for INSERT and CREATE TABLE. Reverse the result for DELETE / DROP TABLE.
     *
     * <p>Edge semantics: a FK {@code fromTable → toTable} means "fromTable depends on toTable",
     * so {@code toTable} (parent) must appear before {@code fromTable} (child) in the output.
     *
     * @param tables      all tables to include in the result
     * @param foreignKeys FK edges describing dependencies between tables
     * @return tables sorted so that every parent appears before its dependent child
     * @throws IllegalArgumentException if a circular FK dependency is detected
     */
    public static List<String> sort(List<String> tables, List<ForeignKey> foreignKeys) {
        // Build adjacency (parent → children) and in-degree map
        Map<String, List<String>> children = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();

        for (String table : tables) {
            children.putIfAbsent(table, new ArrayList<>());
            inDegree.putIfAbsent(table, 0);
        }

        for (ForeignKey fk : foreignKeys) {
            String parent = fk.toTable();
            String child  = fk.fromTable();
            // Only consider edges between tables in our set
            if (!inDegree.containsKey(parent) || !inDegree.containsKey(child)) continue;
            children.computeIfAbsent(parent, k -> new ArrayList<>()).add(child);
            inDegree.merge(child, 1, Integer::sum);
        }

        // Seed queue with tables that have no dependencies (in-degree 0)
        Queue<String> queue = new ArrayDeque<>();
        for (String table : tables) {
            if (inDegree.get(table) == 0) {
                queue.add(table);
            }
        }

        // Kahn's BFS
        List<String> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            result.add(current);
            for (String child : children.getOrDefault(current, List.of())) {
                int newDegree = inDegree.merge(child, -1, Integer::sum);
                if (newDegree == 0) {
                    queue.add(child);
                }
            }
        }

        if (result.size() < tables.size()) {
            throw new IllegalArgumentException(
                    "Circular FK dependency detected among tables: " + tables);
        }

        return result;
    }
}
