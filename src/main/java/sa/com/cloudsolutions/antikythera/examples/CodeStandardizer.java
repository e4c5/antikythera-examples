package sa.com.cloudsolutions.antikythera.examples;

import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import sa.com.cloudsolutions.antikythera.generator.QueryMethodParameter;

import java.util.*;
import java.util.stream.Collectors;

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
        public final List<String> oldParamNames;
        public final List<String> newParamNames;

        public SignatureUpdate(String fqn, String methodName, List<String> oldParamNames, List<String> newParamNames) {
            this.repositoryClassFqn = fqn;
            this.methodName = methodName;
            this.oldParamNames = oldParamNames;
            this.newParamNames = newParamNames;
        }
    }

    /**
     * Standardize a single repository method based on analysis result.
     * Returns an optional SignatureUpdate for derived methods whose parameter order was changed.
     */
    public Optional<SignatureUpdate> standardize(QueryOptimizationResult result)  {
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

    private Optional<SignatureUpdate> reorderDerivedMethodParams(String repositoryClassFqn,
                                                                 CallableDeclaration method,
                                                                 QueryOptimizationResult result) {
        try {
            // Skip if method has no parameters or only one
            if (method.getParameters().size() < 2) return Optional.empty();

            // Only handle simple AND-only derived cases: ensure result has conditions with parameters
            List<WhereCondition> conditions = new ArrayList<>(result.getWhereConditions());
            if (conditions.isEmpty()) return Optional.empty();

            // Build mapping from parameter name to desired priority based on associated WhereCondition
            Map<String, Integer> nameToPriority = new HashMap<>();
            for (WhereCondition wc : conditions) {
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
                        nameToPriority.put(pname, priority(wc.cardinality()));
                    }
                }
            }
            if (nameToPriority.isEmpty()) return Optional.empty();

            // Current parameter names in declaration order
            List<com.github.javaparser.ast.body.Parameter> current = new ArrayList<>(method.getParameters());
            List<String> oldNames = current.stream().map(p -> p.getNameAsString()).toList();

            // Sort parameters by priority, then keep original relative order for ties
            List<com.github.javaparser.ast.body.Parameter> sorted = new ArrayList<>(current);
            sorted.sort(Comparator.comparingInt((com.github.javaparser.ast.body.Parameter p) ->
                    nameToPriority.getOrDefault(p.getNameAsString(), Integer.MAX_VALUE))
                    .thenComparingInt(p -> oldNames.indexOf(p.getNameAsString())));

            // If no real change, bail
            boolean changed = false;
            for (int i = 0; i < current.size(); i++) {
                if (!current.get(i).getNameAsString().equals(sorted.get(i).getNameAsString())) { changed = true; break; }
            }
            if (!changed) return Optional.empty();

            // Apply new parameter order
            method.getParameters().clear();
            for (var p : sorted) method.addParameter(p);

            List<String> newNames = sorted.stream().map(p -> p.getNameAsString()).toList();
            return Optional.of(new SignatureUpdate(repositoryClassFqn, method.getNameAsString(), oldNames, newNames));
        } catch (Exception ex) {
            return Optional.empty();
        }
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
    private boolean rewriteQueryAnnotation(QueryOptimizationResult result, AnnotationExpr ann) {
        String queryText = null;
        if (ann.isSingleMemberAnnotationExpr()) {
            var s = ann.asSingleMemberAnnotationExpr().getMemberValue();
            if (s.isStringLiteralExpr()) queryText = s.asStringLiteralExpr().getValue();
        } else if (ann.isNormalAnnotationExpr()) {
            NormalAnnotationExpr na = ann.asNormalAnnotationExpr();
            for (MemberValuePair p : na.getPairs()) {
                if (p.getNameAsString().equals("value") && p.getValue().isStringLiteralExpr()) {
                    queryText = p.getValue().asStringLiteralExpr().getValue();
                    break;
                }
            }
        }
        if (queryText == null || !queryText.toLowerCase(Locale.ROOT).contains(" where ")) return false;

        String[] split = queryText.split("(?i)\\bwhere\\b", 2);
        if (split.length < 2) return false;
        String head = split[0];
        String tail = split[1];

        // Split tail by AND (ignore OR cases for safety: leave them unchanged)
        if (tail.toLowerCase(Locale.ROOT).contains(" or ")) return false; // do not touch OR for safety
        List<String> parts = Arrays.stream(tail.split("(?i)\\band\\b"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
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
        if (ordered.equals(parts)) return false;

        String newQuery = head + " WHERE " + String.join(" AND ", ordered);
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
        return true;
    }

    private int matchPartIndex(List<String> parts, String columnName) {
        for (int i = 0; i < parts.size(); i++) {
            String p = parts.get(i).toLowerCase(Locale.ROOT);
            if (p.contains(columnName.toLowerCase(Locale.ROOT))) return i;
        }
        return -1;
    }
}
