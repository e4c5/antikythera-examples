package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import sa.com.cloudsolutions.antikythera.generator.QueryMethodParameter;

import java.util.*;

/**
 * Applies standardization changes to repository methods:
 * - For @Query annotated methods, reorders WHERE clause conditions so HIGH cardinality come first
 *   and LOW cardinality go to the end.
 * - For derived (non-annotated) methods, reorders method parameters to align with recommended
 *   condition order (HIGH->MEDIUM->LOW) and returns the new parameter order for usage update.
 *
 * This class intentionally uses simple heuristics for WHERE-clause rewriting (split by AND)
 * to keep changes minimal for this exercise.
 */
@SuppressWarnings({"java:S1192", "java:S106"})
public class CodeStandardizer {
    public static class SignatureUpdate {
        public final String repositoryClassFqn;
        public final String methodName;
        private final List<Integer> mapping;
        public String newMethodName;
        public final List<String> oldParamNames;

        public SignatureUpdate(String fqn, String methodName, List<String> oldParamNames, List<Integer> mapping) {
            this.repositoryClassFqn = fqn;
            this.methodName = methodName;
            this.oldParamNames = oldParamNames;
            this.mapping = mapping;
        }
    }

    /**
     * Standardize a single repository method based on analysis result.
     * Returns an optional SignatureUpdate for derived methods whose parameter order was changed.
     */
    public Optional<SignatureUpdate> standardize(QueryOptimizationResult result) throws ReflectiveOperationException {
        String repositoryClassFqn = result.getRepositoryClass();
        for (OptimizationIssue issue : result.getOptimizationIssues()) {
            if (!issue.currentFirstColumn().equals(issue.recommendedFirstColumn())) {
                CallableDeclaration<?> method = result.getMethod().getCallableDeclaration();
                Optional<AnnotationExpr> q = method.getAnnotationByName("Query");
                if (q.isPresent()) {
                    rewriteQueryAnnotation(result, q.get());
                    return Optional.empty();
                } else {
                    return reorderDerivedMethodParams(repositoryClassFqn, method, result);
                }
            }
        }
        return Optional.empty();
    }

    List<Integer> prioritize(QueryOptimizationResult result) {
        // Only handle simple AND-only derived cases: ensure result has conditions with parameters
        List<WhereCondition> conditions = new ArrayList<>(result.getWhereConditions());

        // Build mapping from parameter name to desired priority based on associated WhereCondition
        List<Map.Entry<Integer, Integer>> nameToPriority = new ArrayList<>();
        for (int i = 0 ; i < conditions.size() ; i++) {
            WhereCondition wc = conditions.get(i);
            QueryMethodParameter qp = wc.parameter();
            if (qp != null) {
                String pname = null;
                if (qp.getParameter() != null) {
                    pname = qp.getParameter().getNameAsString();
                }
                if (pname == null || pname.isBlank()) {
                    pname = qp.getPlaceHolderName();
                }
                if (pname != null && !pname.isBlank()) {
                    nameToPriority.add( new AbstractMap.SimpleImmutableEntry<>(i, priority(wc.cardinality())));
                }
            }
        }
        nameToPriority.sort(Comparator.comparingInt(Map.Entry::getValue));
        List<Integer> newWorldOrder = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : nameToPriority) {
            newWorldOrder.add(entry.getKey());
        }
        return newWorldOrder;
    }

    private Optional<SignatureUpdate> reorderDerivedMethodParams(String repositoryClassFqn,
                                                                 CallableDeclaration<?> method,
                                                                 QueryOptimizationResult result) {
        List<Integer> nameToPriority = prioritize(result);
        // Skip if method has no parameters or only one
        if (method.getParameters().size() < 2) {
            return Optional.empty();
        }

        // Current parameter names in declaration order
        List<Parameter> current = new ArrayList<>(method.getParameters());
        List<String> oldNames = current.stream().map(p -> p.getNameAsString()).toList();

        // Apply new parameter order
        NodeList<Parameter> parameters = new NodeList<>();
        for (int i = 0 ; i < current.size() ; i++) {
            int j = nameToPriority.get(i);
            Parameter p = current.get(j);
            parameters.add(p);
        }

        method.setParameters(parameters);

        // Attempt to also rename derived method so that property order in the name matches new parameter order
        String originalName = method.getNameAsString();

        int byIdx = originalName.indexOf("By");
        if (byIdx > 0 && byIdx + 2 <= originalName.length()) {
            String prefix = originalName.substring(0, byIdx + 2); // includes "By"
            String[] columns = originalName.substring(prefix.length()).split("And");
            List<String> remapped = new ArrayList<>();
            for (int i = 0 ; i < columns.length ; i++) {
                int j = nameToPriority.get(i);
                remapped.add(columns[j]);
            }
            String updatedName = prefix + String.join("And", remapped);

            SignatureUpdate su = new SignatureUpdate(repositoryClassFqn, originalName, oldNames, nameToPriority);
            if (!Objects.equals(updatedName, originalName)) {
                su.newMethodName = updatedName;
                method.setName(updatedName);
                return Optional.of(su);
            }
        }
        return Optional.empty();
    }

    private int priority(CardinalityLevel level) {
        return switch (level) {
            case HIGH -> 0;
            case MEDIUM -> 1;
            case LOW -> 2;
        };
    }

    /**
     * Rewrite the @Query annotation value by reordering conditions in the WHERE clause.
     * Heuristic: split by case-insensitive " and ", match each part to a WhereCondition by column name,
     * then sort parts by HIGH, MEDIUM, LOW cardinality.
     */
    @SuppressWarnings("java:S1130")
    private boolean rewriteQueryAnnotation(QueryOptimizationResult result, AnnotationExpr ann) throws ReflectiveOperationException {
        String queryText = result.getQuery().toString();

        String[] split = queryText.split("(?i)\\bwhere\\b", 2);
        if (split.length < 2) return false;
        String head = split[0];
        String tail = split[1];

        // Separate WHERE body from trailing clauses like ORDER BY / GROUP BY / HAVING / LIMIT / OFFSET / FETCH / FOR UPDATE
        int suffixStart = indexOfRegex(tail, "(?i)\\b(order\\s+by|group\\s+by|having|limit|offset|fetch|for\\s+update)\\b");
        String whereBody = suffixStart >= 0 ? tail.substring(0, suffixStart) : tail;
        String suffix = suffixStart >= 0 ? tail.substring(suffixStart) : "";

        // Ignore OR cases in WHERE body for safety
        if (java.util.regex.Pattern.compile("(?i)\\bor\\b").matcher(whereBody).find()) return false;

        // Split body by AND
        List<String> parts = Arrays.stream(whereBody.split("(?i)\\band\\b"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (parts.size() < 2) return false;

        // Map part to condition by matching column names
        List<WhereCondition> conditions = new ArrayList<>(result.getWhereConditions());
        // Sort conditions high->low then by original position
        conditions.sort(Comparator
                .comparing(WhereCondition::cardinality, Comparator.comparingInt(this::priority))
                .thenComparingInt(WhereCondition::position));

        List<String> ordered = new ArrayList<>();
        Set<Integer> usedIdx = new HashSet<>();
        for (WhereCondition wc : conditions) {
            int matchIdx = matchPartIndex(parts, wc.columnName());
            if (matchIdx >= 0 && !usedIdx.contains(matchIdx)) {
                ordered.add(parts.get(matchIdx));
                usedIdx.add(matchIdx);
            }
        }
        // add any leftover parts at the end preserving original order
        for (int i = 0; i < parts.size(); i++) {
            if (!usedIdx.contains(i)) ordered.add(parts.get(i));
        }

        String newQuery = head + " WHERE " + String.join(" AND ", ordered) + suffix;
        updateAnnotation(ann, newQuery);
        return true;
    }

    private static void updateAnnotation(AnnotationExpr ann, String newQuery) {
        if (ann.isSingleMemberAnnotationExpr()) {
            SingleMemberAnnotationExpr s = ann.asSingleMemberAnnotationExpr();
            s.setMemberValue(new StringLiteralExpr(newQuery));
        } else if (ann.isNormalAnnotationExpr()) {
            NormalAnnotationExpr na = ann.asNormalAnnotationExpr();
            for (MemberValuePair p : na.getPairs()) {
                if (p.getNameAsString().equals("value")) {
                    p.setValue(new StringLiteralExpr(newQuery));
                    break;
                }
            }
        }
    }

    private static int indexOfRegex(String input, String regex) {
        if (input == null || input.isEmpty()) return -1;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(input);
        return m.find() ? m.start() : -1;
    }

    private int matchPartIndex(List<String> parts, String columnName) {
        for (int i = 0; i < parts.size(); i++) {
            String p = parts.get(i).toLowerCase(Locale.ROOT);
            if (p.contains(columnName.toLowerCase(Locale.ROOT))) return i;
        }
        return -1;
    }
}
