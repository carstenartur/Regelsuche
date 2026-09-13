package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/** Necessary root constraints only. No value projection, normalization or recursive matching. */
final class NativeRuleShapeIndex {
    private enum Kind { NUMBER, VARIABLE, BINARY, FUNCTION }
    private record Shape(Kind kind, String symbol, int arity) { }
    private record ChildConstraint(int index, Shape required) { }
    private record Plan(int ruleIndex, List<ChildConstraint> children) { }
    private final Map<Shape, List<Plan>> buckets;
    private final List<Plan> wildcards;
    private final int ruleCount;
    private final BiConsumer<TransformationCursor.Operation, Long> work;

    NativeRuleShapeIndex(List<RewriteRule> rules, BiConsumer<TransformationCursor.Operation, Long> work) {
        this.work = work;
        this.ruleCount = rules.size();
        var mutableBuckets = new HashMap<Shape, List<Plan>>();
        var mutableWildcards = new ArrayList<Plan>();
        for (int index = 0; index < rules.size(); index++) {
            charge(TransformationCursor.Operation.SHAPE_INDEX_RULE_COMPILE);
            if (rules.get(index).getClass() != PatternRewriteRule.class)
                throw new IllegalArgumentException("shape index requires exact PatternRewriteRule instances");
            var rule = (PatternRewriteRule) rules.get(index);
            var profile = rule.recognitionProfile();
            // Algebraic inference can change root shape; recognition-rule profiles stay conservative too.
            if (profile.inferAlgebraicBindings() || !profile.recognitionRuleIds().isEmpty()
                    || profile.maxEquivalenceDepth() != 0) {
                mutableWildcards.add(new Plan(index, List.of()));
                continue;
            }
            Shape root = patternShape(rule.source());
            var constraints = new ArrayList<ChildConstraint>();
            if (root != null && profile.associativeOperators().isEmpty() && profile.commutativeOperators().isEmpty()) {
                for (int childIndex = 0; childIndex < Math.min(2, root.arity()); childIndex++) {
                    Shape child = patternShape(patternChild(rule.source(), childIndex));
                    if (child != null) constraints.add(new ChildConstraint(childIndex, child));
                }
            }
            var plan = new Plan(index, List.copyOf(constraints));
            if (root == null) mutableWildcards.add(plan);
            else mutableBuckets.computeIfAbsent(root, ignored -> new ArrayList<>()).add(plan);
        }
        var frozen = new HashMap<Shape, List<Plan>>();
        mutableBuckets.forEach((shape, plans) -> frozen.put(shape, List.copyOf(plans)));
        buckets = Map.copyOf(frozen);
        wildcards = List.copyOf(mutableWildcards);
    }

    Features features(Expr expression) { return new Features(expression); }

    Candidates candidates(Features features) {
        Shape root = features.root();
        charge(TransformationCursor.Operation.SHAPE_INDEX_LOOKUP);
        return new Candidates(buckets.getOrDefault(root, List.of()));
    }

    /** Two already ordered lists merge lazily; no later candidate is selected by an early pull. */
    final class Candidates {
        private final List<Plan> bucket;
        private int bucketIndex, wildcardIndex;
        private Plan current;
        Candidates(List<Plan> bucket) { this.bucket = bucket; }
        int rootExcludedRules() { return ruleCount - bucket.size() - wildcards.size(); }
        boolean hasNext() { return bucketIndex < bucket.size() || wildcardIndex < wildcards.size(); }
        int nextRuleIndex() {
            if (!hasNext()) throw new java.util.NoSuchElementException();
            charge(TransformationCursor.Operation.SHAPE_CANDIDATE_SELECT);
            if (wildcardIndex == wildcards.size() || (bucketIndex < bucket.size()
                    && bucket.get(bucketIndex).ruleIndex() < wildcards.get(wildcardIndex).ruleIndex()))
                current = bucket.get(bucketIndex++);
            else current = wildcards.get(wildcardIndex++);
            return current.ruleIndex();
        }
        boolean currentShapeAdmits(Features features) {
            if (current == null) throw new IllegalStateException("no selected rule");
            for (var constraint : current.children()) {
                Shape actual = features.child(constraint.index());
                charge(TransformationCursor.Operation.SHAPE_PREDICATE_CHECK);
                if (!constraint.required().equals(actual)) return false;
            }
            return true;
        }
    }

    /** Only root and the first two immediate children can be inspected; all are read lazily. */
    final class Features {
        private final Expr expression;
        private Shape root;
        private final Shape[] children = new Shape[2];
        Features(Expr expression) { this.expression = expression; }
        private Shape root() {
            if (root == null) root = expressionShape(expression);
            else charge(TransformationCursor.Operation.SHAPE_FEATURE_REUSE);
            return root;
        }
        private Shape child(int index) {
            if (children[index] == null) {
                Expr child = expression instanceof BinaryExpr binary ? (index == 0 ? binary.left() : binary.right())
                    : ((FunctionExpr) expression).arguments().get(index);
                children[index] = expressionShape(child);
            } else charge(TransformationCursor.Operation.SHAPE_FEATURE_REUSE);
            return children[index];
        }
    }

    private Shape expressionShape(Expr expression) {
        charge(TransformationCursor.Operation.SHAPE_FEATURE_READ);
        return switch (expression) {
            case NumberExpr ignored -> new Shape(Kind.NUMBER, "", 0);
            case VariableExpr variable -> new Shape(Kind.VARIABLE, variable.name(), 0);
            case BinaryExpr binary -> new Shape(Kind.BINARY, binary.operator().name(), 2);
            case FunctionExpr function -> new Shape(Kind.FUNCTION, function.name(), function.arguments().size());
        };
    }

    private Shape patternShape(PatternExpr pattern) {
        charge(TransformationCursor.Operation.SHAPE_PATTERN_VISIT);
        return switch (pattern) {
            case PatternExpr.Placeholder ignored -> null;
            case PatternExpr.LiteralNumber ignored -> new Shape(Kind.NUMBER, "", 0);
            case PatternExpr.LiteralVariable variable -> new Shape(Kind.VARIABLE, variable.name(), 0);
            case PatternExpr.Operation operation -> new Shape(Kind.BINARY, operation.operator().name(), 2);
            case PatternExpr.Function function -> new Shape(Kind.FUNCTION, function.name(), function.arguments().size());
        };
    }

    private static PatternExpr patternChild(PatternExpr pattern, int index) {
        return pattern instanceof PatternExpr.Operation operation ? (index == 0 ? operation.left() : operation.right())
            : ((PatternExpr.Function) pattern).arguments().get(index);
    }
    private void charge(TransformationCursor.Operation operation) { work.accept(operation, 1L); }
}
