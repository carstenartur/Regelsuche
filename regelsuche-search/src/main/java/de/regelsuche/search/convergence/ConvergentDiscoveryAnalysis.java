package de.regelsuche.search.convergence;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchState;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Finds canonical states reached by genuinely different rule-family paths. */
public final class ConvergentDiscoveryAnalysis {
    private final RuleFamilyClassifier classifier;

    public ConvergentDiscoveryAnalysis() {
        this(new RuleFamilyClassifier());
    }

    public ConvergentDiscoveryAnalysis(RuleFamilyClassifier classifier) {
        this.classifier = classifier == null ? new RuleFamilyClassifier() : classifier;
    }

    public ConvergentDiscoveryReport analyze(SearchProblem problem, Collection<SearchState> states) {
        return analyze(
            problem.rootExpression(),
            states,
            problem.canonicalizer(),
            problem.scorer()
        );
    }

    public ConvergentDiscoveryReport analyze(
        String inputExpression,
        Collection<SearchState> states,
        ExpressionCanonicalizer canonicalizer,
        ExpressionScorer scorer
    ) {
        Map<String, List<ConvergentPath>> byFinalHash = new LinkedHashMap<>();
        for (SearchState state : states == null ? List.<SearchState>of() : states) {
            if (state.depth() == 0 || state.appliedRuleIds().isEmpty()) {
                continue;
            }
            ConvergentPath path = toPath(inputExpression, state, scorer);
            byFinalHash.computeIfAbsent(state.canonicalHash(), ignored -> new ArrayList<>()).add(path);
        }

        List<ConvergentState> convergentStates = new ArrayList<>();
        for (Map.Entry<String, List<ConvergentPath>> entry : byFinalHash.entrySet()) {
            List<ConvergentPath> distinct = distinctInterestingPaths(entry.getValue());
            if (distinct.size() < 2) {
                continue;
            }
            Set<RuleFamily> families = nonNormalizationFamilies(distinct);
            if (families.size() < 2) {
                continue;
            }
            convergentStates.add(toState(entry.getKey(), distinct));
        }
        convergentStates.sort(Comparator
            .comparingInt((ConvergentState state) -> bestLength(state.incomingPaths()))
            .thenComparing(ConvergentState::canonicalHash));

        ConvergentState target = convergentStates.isEmpty() ? null : convergentStates.getFirst();
        List<ConvergentPath> pathsToTarget = target == null ? List.of() : target.incomingPaths();
        List<ConvergentState> sharedIntermediateStates = target == null
            ? List.of()
            : sharedIntermediateStates(inputExpression, pathsToTarget, canonicalizer);
        Set<RuleFamily> families = nonNormalizationFamilies(pathsToTarget);
        Set<String> evidenceKinds = new LinkedHashSet<>();
        if (!pathsToTarget.isEmpty()) {
            evidenceKinds.add("SEARCH_GRAPH");
            evidenceKinds.add("CANONICAL_CONVERGENCE");
        }
        if (pathsToTarget.stream().anyMatch(ConvergentPath::containsHypothesisStep)) {
            evidenceKinds.add("HYPOTHESIS_PATH");
        }
        if (pathsToTarget.stream().anyMatch(ConvergentPath::containsMacroStep)) {
            evidenceKinds.add("MACRO_REUSE");
        }
        String targetExpression = pathsToTarget.isEmpty() ? "" : pathsToTarget.getFirst().finalExpression();
        return new ConvergentDiscoveryReport(
            inputExpression,
            targetExpression,
            convergentStates,
            pathsToTarget,
            sharedIntermediateStates,
            interestingAlternatives(pathsToTarget),
            families,
            evidenceKinds
        );
    }

    private ConvergentPath toPath(String inputExpression, SearchState state, ExpressionScorer scorer) {
        List<String> rules = state.appliedRuleIds();
        List<RuleFamily> families = rules.stream().map(classifier::classify).toList();
        boolean hypothesis = rules.stream().anyMatch(rule -> rule != null && rule.startsWith("hypothesis_"));
        boolean macro = families.contains(RuleFamily.LEARNED_MACRO);
        boolean validated = state.equivalencePreservingFlags().isEmpty()
            || state.equivalencePreservingFlags().stream().allMatch(Boolean::booleanValue);
        return new ConvergentPath(
            "path-" + Integer.toHexString(String.join("\u0001", state.path()) .hashCode())
                + "-" + Integer.toHexString(String.join("\u0001", rules).hashCode()),
            state.path(),
            rules,
            families,
            state.expression(),
            state.score() == null ? scorer.score(state.expression()) : state.score(),
            Math.max(0, state.path().size() - 1),
            hypothesis,
            macro,
            macro,
            validated ? "EQUIVALENCE_PRESERVING" : "MIXED",
            validated ? "VALIDATED_BY_CONSTRUCTION" : "REQUIRES_VALIDATION",
            List.of(inputExpression)
        );
    }

    private List<ConvergentPath> distinctInterestingPaths(List<ConvergentPath> candidates) {
        Map<String, ConvergentPath> distinct = new LinkedHashMap<>();
        for (ConvergentPath path : candidates.stream()
            .sorted(Comparator.comparingInt(ConvergentPath::length).thenComparing(ConvergentPath::pathId))
            .toList()) {
            String signature = semanticSignature(path);
            if (signature.isBlank()) {
                continue;
            }
            distinct.putIfAbsent(signature, path);
        }
        return List.copyOf(distinct.values());
    }

    private String semanticSignature(ConvergentPath path) {
        List<String> nonNormalizationRules = new ArrayList<>();
        List<RuleFamily> nonNormalizationFamilies = new ArrayList<>();
        for (int i = 0; i < path.ruleIds().size(); i++) {
            RuleFamily family = path.ruleFamilies().get(i);
            if (family == RuleFamily.NORMALIZATION) {
                continue;
            }
            nonNormalizationRules.add(path.ruleIds().get(i));
            nonNormalizationFamilies.add(family);
        }
        if (nonNormalizationFamilies.stream().allMatch(family -> family == RuleFamily.OTHER)) {
            return "";
        }
        return String.join(">", nonNormalizationRules) + "|" + nonNormalizationFamilies;
    }

    private Set<RuleFamily> nonNormalizationFamilies(Collection<ConvergentPath> paths) {
        Set<RuleFamily> families = new LinkedHashSet<>();
        for (ConvergentPath path : paths) {
            for (RuleFamily family : path.ruleFamilies()) {
                if (family != RuleFamily.NORMALIZATION && family != RuleFamily.OTHER) {
                    families.add(family);
                }
            }
        }
        return families;
    }

    private ConvergentState toState(String canonicalHash, List<ConvergentPath> paths) {
        ConvergentPath shortest = paths.stream()
            .min(Comparator.comparingInt(ConvergentPath::length).thenComparing(ConvergentPath::pathId))
            .orElseThrow();
        ConvergentPath didactic = paths.stream()
            .max(Comparator.comparingInt(this::didacticScore).thenComparing(ConvergentPath::pathId))
            .orElse(shortest);
        return new ConvergentState(
            shortest.finalExpression(),
            canonicalHash,
            paths,
            shortest.pathId(),
            didactic.pathId(),
            paths.stream().filter(ConvergentPath::containsMacroStep).map(ConvergentPath::pathId).findFirst()
        );
    }

    private int didacticScore(ConvergentPath path) {
        int score = new LinkedHashSet<>(path.ruleFamilies()).size();
        if (path.ruleFamilies().contains(RuleFamily.HIDDEN_STRUCTURE)) {
            score += 4;
        }
        if (path.ruleFamilies().contains(RuleFamily.COMPLETE_SQUARE)) {
            score += 3;
        }
        if (path.containsMacroStep()) {
            score += 2;
        }
        return score;
    }

    private int bestLength(List<ConvergentPath> paths) {
        return paths.stream().mapToInt(ConvergentPath::length).min().orElse(Integer.MAX_VALUE);
    }

    private List<ConvergentPath> interestingAlternatives(List<ConvergentPath> paths) {
        if (paths.size() <= 1) {
            return List.of();
        }
        String shortest = paths.stream()
            .min(Comparator.comparingInt(ConvergentPath::length).thenComparing(ConvergentPath::pathId))
            .map(ConvergentPath::pathId)
            .orElse("");
        return paths.stream().filter(path -> !path.pathId().equals(shortest)).toList();
    }

    private List<ConvergentState> sharedIntermediateStates(
        String inputExpression,
        List<ConvergentPath> paths,
        ExpressionCanonicalizer canonicalizer
    ) {
        String inputHash = canonicalizer.stableHash(inputExpression);
        String finalHash = paths.isEmpty() ? "" : canonicalizer.stableHash(paths.getFirst().finalExpression());
        Map<String, List<ConvergentPath>> incoming = new LinkedHashMap<>();
        Map<String, String> expressionByHash = new LinkedHashMap<>();
        for (ConvergentPath path : paths) {
            for (int i = 1; i < path.expressions().size() - 1; i++) {
                String expression = path.expressions().get(i);
                String hash = canonicalizer.stableHash(expression);
                if (hash.equals(inputHash) || hash.equals(finalHash)) {
                    continue;
                }
                expressionByHash.putIfAbsent(hash, expression);
                incoming.computeIfAbsent(hash, ignored -> new ArrayList<>()).add(path);
            }
        }
        List<ConvergentState> shared = new ArrayList<>();
        for (Map.Entry<String, List<ConvergentPath>> entry : incoming.entrySet()) {
            List<ConvergentPath> distinct = distinctInterestingPaths(entry.getValue());
            if (distinct.size() >= 2) {
                shared.add(toState(entry.getKey(), distinct));
            }
        }
        return shared;
    }
}
