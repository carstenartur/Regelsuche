package de.regelsuche.benchmark;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.benchmark.CandidateIndependentMacroReplayAdapter.ReplayEvidence;
import de.regelsuche.benchmark.CandidateIndependentMacroReplayAdapter.ReplayTrace;
import de.regelsuche.benchmark.CandidateIndependentReusableMacroAdapter.FormationResult;
import de.regelsuche.benchmark.CandidateIndependentReusableMacroAdapter.FormationStatus;
import de.regelsuche.benchmark.CandidateIndependentReusableMacroAdapter.MacroCandidate;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.math.algorithms.equivalence.PolynomialNormalFormEquivalenceService;
import de.regelsuche.math.algorithms.registry.DefaultMathematicalAlgorithmRegistry;
import de.regelsuche.mining.GeneralizedPattern;
import de.regelsuche.mining.KnownRuleRepository;
import de.regelsuche.mining.PatternGeneralizer;
import de.regelsuche.mining.RulePatternCanonicalizer;
import de.regelsuche.mining.SuccessfulTransformationPath;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import de.regelsuche.validation.CandidateProofStatus;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Target-free macro formation from frozen, production-replayed TRAIN traces. */
final class CandidateIndependentMacroFormation {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\b[A-Z]\\b");
    private static final int REPLAY_MAX_DEPTH = 12;
    private static final int REPLAY_MAX_STATES = 2_000;

    private final CandidateIndependentMacroReplayAdapter replayAdapter;
    private final Map<String, Set<String>> operationsByRuleId;
    private final TransformationEngine primitiveEngine;
    private final ExpressionCanonicalizer canonicalizer =
        new ExpressionCanonicalizer();
    private final ExpressionScorer scorer = new ExpressionScorer();
    private final PatternGeneralizer generalizer = new PatternGeneralizer();
    private final ExpressionParser expressionParser = new ExpressionParser();
    private final PolynomialNormalFormEquivalenceService polynomialEquivalence =
        new PolynomialNormalFormEquivalenceService(
            new DefaultMathematicalAlgorithmRegistry());

    CandidateIndependentMacroFormation(
        Map<String, List<String>> operationRuleIds
    ) {
        replayAdapter = new CandidateIndependentMacroReplayAdapter(
            operationRuleIds);
        Map<String, List<String>> normalized = replayAdapter.operationRuleIds();
        LinkedHashMap<String, Set<String>> reverse = new LinkedHashMap<>();
        normalized.forEach((operation, rules) ->
            rules.forEach(rule -> reverse.computeIfAbsent(
                rule, ignored -> new LinkedHashSet<>()).add(operation)));
        LinkedHashMap<String, Set<String>> immutableReverse =
            new LinkedHashMap<>();
        reverse.forEach((rule, operations) ->
            immutableReverse.put(rule, Set.copyOf(operations)));
        operationsByRuleId = Map.copyOf(immutableReverse);
        Set<String> allowed = reverse.keySet();
        List<RewriteRule> rules = AstRewriteTransformationEngine.defaultRules()
            .stream()
            .filter(rule -> allowed.contains(rule.id()))
            .sorted(Comparator.comparing(RewriteRule::id))
            .toList();
        primitiveEngine = new AstRewriteTransformationEngine(rules, 16, 120);
    }

    FormationResult form(List<ReplayTrace> traces) {
        if (traces == null || traces.isEmpty()) {
            throw new IllegalArgumentException(
                "at least one TRAIN replay trace is required");
        }
        CandidateIndependentMacroReplayAdapter.BatchResult replayBatch =
            replayAdapter.replayAll(traces);
        List<ReplayEvidence> replayEvidence = replayBatch.results().stream()
            .map(CandidateIndependentMacroReplayAdapter.ReplayResult::evidence)
            .toList();
        if (replayBatch.status()
                != CandidateIndependentMacroReplayAdapter.BatchStatus.REPRODUCED) {
            return new FormationResult(
                FormationStatus.REPLAY_NOT_REPRODUCED,
                List.of(), replayEvidence, replayBatch.detail());
        }
        List<SuccessfulTransformationPath> paths = replayBatch.results().stream()
            .map(result -> result.path().orElseThrow())
            .toList();

        LinkedHashMap<List<String>, List<SuccessfulTransformationPath>> clusters =
            new LinkedHashMap<>();
        for (int index = 0; index < traces.size(); index++) {
            clusters.computeIfAbsent(traces.get(index).primitiveSteps(),
                ignored -> new ArrayList<>()).add(paths.get(index));
        }

        List<MacroCandidate> macros = new ArrayList<>();
        for (Map.Entry<List<String>, List<SuccessfulTransformationPath>> entry
                : clusters.entrySet()) {
            Optional<GeneralizedPattern> generalized = entry.getValue().size() > 1
                ? generalizeByReplay(entry.getValue(), entry.getKey())
                : generalizer.generalizeSingleExampleSchema(
                    entry.getValue().getFirst());
            if (generalized.isEmpty()) {
                return new FormationResult(
                    FormationStatus.GENERALIZATION_REJECTED,
                    List.copyOf(macros), replayEvidence,
                    "no reusable schema for operation sequence "
                        + entry.getKey());
            }
            macros.add(candidate(
                generalized.orElseThrow(), entry.getKey(), entry.getValue()));
        }
        macros.sort(Comparator.comparing(MacroCandidate::macroId));
        return new FormationResult(
            FormationStatus.SELECTED,
            List.copyOf(macros), replayEvidence,
            "all TRAIN replays reproduced and generalized into "
                + macros.size() + " reusable macros");
    }

    private Optional<GeneralizedPattern> generalizeByReplay(
        List<SuccessfulTransformationPath> paths,
        List<String> operationSequence
    ) {
        SourceGeneralization source = generalizeSources(paths);
        if (source == null || source.bindings().isEmpty()) {
            return Optional.empty();
        }
        Optional<String> targetPattern = deriveTargetPattern(
            source.pattern(), source.bindings(), paths, operationSequence);
        if (targetPattern.isEmpty()) {
            return Optional.empty();
        }
        Map<String, List<String>> expressionValues = new LinkedHashMap<>();
        for (String placeholder : source.placeholders()) {
            expressionValues.put(
                placeholder,
                source.bindings().stream()
                    .map(binding -> ExpressionFormatter.format(
                        binding.get(placeholder)))
                    .toList());
        }
        return Optional.of(new GeneralizedPattern(
            source.pattern(),
            targetPattern.orElseThrow(),
            Map.of(),
            expressionValues.entrySet().stream()
                .map(entry -> entry.getKey() + " ∈ {"
                    + String.join(", ", entry.getValue()) + "}")
                .toList(),
            expressionValues));
    }

    private SourceGeneralization generalizeSources(
        List<SuccessfulTransformationPath> paths
    ) {
        List<Expr> sources;
        try {
            sources = paths.stream()
                .map(path -> expressionParser.parseTerm(
                    path.originalExpression()))
                .toList();
        } catch (IllegalArgumentException exception) {
            return null;
        }
        AntiUnificationState state = new AntiUnificationState(paths.size());
        Expr pattern = antiUnify(sources, state);
        if (state.placeholders().isEmpty()) {
            return null;
        }
        return new SourceGeneralization(
            ExpressionFormatter.format(pattern),
            state.placeholders(),
            state.bindings());
    }

    private Expr antiUnify(List<Expr> values, AntiUnificationState state) {
        Expr first = values.getFirst();
        if (values.stream().allMatch(first::equals)) {
            return first;
        }
        if (first instanceof BinaryExpr binary
                && values.stream().allMatch(value ->
                    value instanceof BinaryExpr candidate
                        && candidate.operator() == binary.operator())) {
            return new BinaryExpr(
                antiUnify(values.stream()
                    .map(value -> ((BinaryExpr) value).left()).toList(), state),
                binary.operator(),
                antiUnify(values.stream()
                    .map(value -> ((BinaryExpr) value).right()).toList(), state));
        }
        if (first instanceof FunctionExpr function
                && values.stream().allMatch(value ->
                    value instanceof FunctionExpr candidate
                        && candidate.name().equals(function.name())
                        && candidate.arguments().size()
                            == function.arguments().size())) {
            List<Expr> arguments = new ArrayList<>();
            for (int index = 0; index < function.arguments().size(); index++) {
                final int position = index;
                arguments.add(antiUnify(values.stream()
                    .map(value -> ((FunctionExpr) value)
                        .arguments().get(position))
                    .toList(), state));
            }
            return new FunctionExpr(function.name(), arguments);
        }
        return new VariableExpr(state.placeholder(values));
    }

    private Optional<String> deriveTargetPattern(
        String sourcePattern,
        List<Map<String, Expr>> bindings,
        List<SuccessfulTransformationPath> paths,
        List<String> operationSequence
    ) {
        ArrayDeque<ReplayNode> queue = new ArrayDeque<>();
        Set<String> visited = new LinkedHashSet<>();
        queue.add(new ReplayNode(sourcePattern, 0, -1));
        visited.add(replayKey(sourcePattern, -1));
        int explored = 0;
        List<String> candidates = new ArrayList<>();
        while (!queue.isEmpty() && explored < REPLAY_MAX_STATES) {
            ReplayNode node = queue.removeFirst();
            explored++;
            if (node.operationIndex() == operationSequence.size() - 1
                    && validatesInstances(
                        node.expression(), bindings, paths)) {
                candidates.add(node.expression());
            }
            if (node.depth() >= REPLAY_MAX_DEPTH) {
                continue;
            }
            for (Transformation transformation : primitiveEngine
                    .transform(node.expression()).stream()
                    .sorted(transformationOrder()).toList()) {
                for (String operation : operationsByRuleId.getOrDefault(
                        transformation.rule(), Set.of()).stream()
                        .sorted().toList()) {
                    int nextIndex = compatibleOperationIndex(
                        operationSequence, node.operationIndex(), operation);
                    if (nextIndex < 0) {
                        continue;
                    }
                    String key = replayKey(
                        transformation.transformedExpression(), nextIndex);
                    if (!visited.add(key)) {
                        continue;
                    }
                    queue.addLast(new ReplayNode(
                        transformation.transformedExpression(),
                        node.depth() + 1,
                        nextIndex));
                }
            }
        }
        return candidates.stream()
            .distinct()
            .sorted(Comparator
                .comparingInt(canonicalizer::astNodeCount)
                .thenComparing(value -> scorer.score(value).weightedTotal())
                .thenComparing(String::compareTo))
            .findFirst();
    }

    private boolean validatesInstances(
        String targetPattern,
        List<Map<String, Expr>> bindings,
        List<SuccessfulTransformationPath> paths
    ) {
        Expr target;
        try {
            target = expressionParser.parseTerm(targetPattern);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        for (int index = 0; index < paths.size(); index++) {
            String concrete = ExpressionFormatter.format(
                substitute(target, bindings.get(index)));
            if (!same(concrete, paths.get(index).targetExpression())) {
                return false;
            }
        }
        return true;
    }

    private Expr substitute(Expr expression, Map<String, Expr> bindings) {
        if (expression instanceof VariableExpr variable) {
            return bindings.getOrDefault(variable.name(), variable);
        }
        if (expression instanceof NumberExpr) {
            return expression;
        }
        if (expression instanceof FunctionExpr function) {
            return new FunctionExpr(
                function.name(),
                function.arguments().stream()
                    .map(argument -> substitute(argument, bindings))
                    .toList());
        }
        BinaryExpr binary = (BinaryExpr) expression;
        return new BinaryExpr(
            substitute(binary.left(), bindings),
            binary.operator(),
            substitute(binary.right(), bindings));
    }

    private MacroCandidate candidate(
        GeneralizedPattern pattern,
        List<String> operationSequence,
        List<SuccessfulTransformationPath> paths
    ) {
        Set<String> leftPlaceholders = placeholders(pattern.leftPattern());
        Set<String> rightPlaceholders = placeholders(pattern.rightPattern());
        if (leftPlaceholders.isEmpty()
                || !leftPlaceholders.containsAll(rightPlaceholders)) {
            throw new IllegalArgumentException(
                "generalized macro exposes unbound target placeholders: "
                    + pattern);
        }
        if (!polynomialEquivalence.areEquivalent(
                pattern.leftPattern(), pattern.rightPattern())) {
            throw new IllegalArgumentException(
                "generalized macro failed deterministic polynomial validation: "
                    + pattern.leftPattern() + " -> " + pattern.rightPattern());
        }
        String canonicalHash = RulePatternCanonicalizer.hash(
            pattern.leftPattern(), pattern.rightPattern());
        List<String> pathIds = paths.stream()
            .map(SuccessfulTransformationPath::id).sorted().toList();
        List<String> assumptions = paths.stream()
            .flatMap(path -> path.assumptions().stream())
            .distinct().sorted().toList();
        double averageImprovement = paths.stream()
            .mapToInt(SuccessfulTransformationPath::scoreImprovement)
            .average().orElse(1.0);
        ReusableRule rule = new ReusableRule(
            "macro_candidate_independent_"
                + Integer.toUnsignedString(canonicalHash.hashCode(), 16),
            pattern.leftPattern(),
            pattern.rightPattern(),
            pattern.parameterRelations(),
            CandidateProofStatus.SYMBOLICALLY_VERIFIED,
            new KnownRuleRepository().statusFor(
                pattern.leftPattern(), pattern.rightPattern()),
            paths.size(),
            Math.max(1.0, averageImprovement),
            Instant.EPOCH,
            canonicalHash,
            null,
            0,
            paths.size(),
            pathIds,
            1.0,
            assumptions);
        return new MacroCandidate(
            rule.id(), operationSequence, pathIds, rule,
            atomicSteps(paths.getFirst()),
            "deterministic polynomial normal forms match");
    }

    private List<TransformationStep> atomicSteps(
        SuccessfulTransformationPath path
    ) {
        List<TransformationStep> result = new ArrayList<>();
        for (int index = 0; index < path.rules().size(); index++) {
            String before = path.expressionPath().get(index);
            String after = path.expressionPath().get(index + 1);
            result.add(new TransformationStep(
                index,
                before,
                after,
                path.rules().get(index),
                RewriteKind.NORMALIZE,
                scorer.score(before).weightedTotal(),
                scorer.score(after).weightedTotal(),
                true,
                path.rules().get(index),
                path.assumptions()));
        }
        return List.copyOf(result);
    }

    private Comparator<Transformation> transformationOrder() {
        return Comparator
            .comparingInt(Transformation::estimatedCostDelta)
            .thenComparing(transformation ->
                transformation.rule().contains("macro") ? 0 : 1)
            .thenComparing(Transformation::rule)
            .thenComparing(Transformation::transformedExpression)
            .thenComparing(Transformation::applicationKey);
    }

    private int compatibleOperationIndex(
        List<String> expected,
        int currentIndex,
        String operation
    ) {
        if (currentIndex < 0) {
            return expected.getFirst().equals(operation) ? 0 : -1;
        }
        if (expected.get(currentIndex).equals(operation)) {
            return currentIndex;
        }
        int next = currentIndex + 1;
        return next < expected.size() && expected.get(next).equals(operation)
            ? next : -1;
    }

    private boolean same(String left, String right) {
        return canonicalizer.canonicalize(left)
            .equals(canonicalizer.canonicalize(right));
    }

    private String replayKey(String expression, int operationIndex) {
        return canonicalizer.stableHash(expression) + ':' + operationIndex;
    }

    private Set<String> placeholders(String pattern) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(pattern);
        while (matcher.find()) {
            result.add(matcher.group());
        }
        return Set.copyOf(result);
    }

    private static final class AntiUnificationState {
        private final List<Map<String, Expr>> bindings;
        private final LinkedHashMap<String, String> placeholderByVector =
            new LinkedHashMap<>();
        private final List<String> placeholders = new ArrayList<>();

        private AntiUnificationState(int examples) {
            bindings = new ArrayList<>();
            for (int index = 0; index < examples; index++) {
                bindings.add(new LinkedHashMap<>());
            }
        }

        private String placeholder(List<Expr> values) {
            String key = values.stream()
                .map(ExpressionFormatter::format)
                .collect(java.util.stream.Collectors.joining("\u0001"));
            String placeholder = placeholderByVector.get(key);
            if (placeholder == null) {
                placeholder = String.valueOf(
                    (char) ('A' + placeholderByVector.size()));
                if (placeholder.charAt(0) > 'Z') {
                    throw new IllegalStateException(
                        "macro source anti-unification exhausted placeholders");
                }
                placeholderByVector.put(key, placeholder);
                placeholders.add(placeholder);
                for (int index = 0; index < values.size(); index++) {
                    bindings.get(index).put(placeholder, values.get(index));
                }
            }
            return placeholder;
        }

        private List<String> placeholders() {
            return List.copyOf(placeholders);
        }

        private List<Map<String, Expr>> bindings() {
            return bindings.stream().map(Map::copyOf).toList();
        }
    }

    private record SourceGeneralization(
        String pattern,
        List<String> placeholders,
        List<Map<String, Expr>> bindings
    ) {
    }

    private record ReplayNode(
        String expression,
        int depth,
        int operationIndex
    ) {
    }
}
