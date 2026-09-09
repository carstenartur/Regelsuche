package de.regelsuche.search.strategy;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.transform.Transformation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Observations of the existing frontier, without changing its policy or replay bytes. */
public record SearchRunDiagnostics(
        long generatedSuccessors, long consumedSuccessors, long enqueuedSuccessors,
        long discardedSuccessors, long unconsumedSuccessors, long duplicateSuccessors,
        long deadEnds, int exploredStates, long expandedStates, int firstHitDepth,
        int firstHitPrimitiveDepth, int deepestPrimitiveDepth, long primitiveWork,
        long searchWork, long verificationWork, Map<String, Long> matchesByRuleFamily,
        Map<String, Long> rejectionsByReason, List<LearnedContribution> learnedContributions) {
    public static final String REVISION = "regelsuche.search-run-diagnostics/v1";
    public static final String MATCH_SCOPE = "RETURNED_SOURCE_MATCHES_BY_RULE_FAMILY;NOT_INTERNAL_MATCH_ATTEMPTS";
    public static final String WORK_SCOPE = "V1_SOURCE_CANDIDATES_PLUS_OTHER_MECHANICAL_EVENTS_PLUS_PRIMITIVE_AUDIT_CALLS;NOT_CPU_WORK";

    public SearchRunDiagnostics {
        if (generatedSuccessors < consumedSuccessors || consumedSuccessors < enqueuedSuccessors
                || discardedSuccessors != generatedSuccessors - enqueuedSuccessors
                || unconsumedSuccessors != generatedSuccessors - consumedSuccessors
                || enqueuedSuccessors < 0 || primitiveWork < 0 || searchWork < 0 || verificationWork < 0) {
            throw new IllegalArgumentException("inconsistent successor or work accounting");
        }
        matchesByRuleFamily = java.util.Collections.unmodifiableMap(new TreeMap<>(matchesByRuleFamily));
        rejectionsByReason = java.util.Collections.unmodifiableMap(new TreeMap<>(rejectionsByReason));
        learnedContributions = List.copyOf(learnedContributions);
    }

    /** One retained learned edge, including its full charged primitive path length. */
    public record LearnedContribution(String ruleId, String from, String to,
                                      int searchDepth, int primitiveSteps) {}

    public static SearchRunDiagnostics observe(WorkBudgetBestFirstSearchStrategy.Result result,
            Function<String, String> familyOf, Set<String> learnedRuleIds, long primitiveAuditCalls) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(familyOf, "familyOf");
        Objects.requireNonNull(learnedRuleIds, "learnedRuleIds");
        if (result.metrics().workRevision() != SearchExpansionSource.WorkRevision.MECHANICAL_V1) {
            throw new IllegalArgumentException("v1 diagnostic cost partition requires the historical mechanical ledger");
        }
        var matches = new TreeMap<String, Long>();
        result.expansions().forEach(expansion -> expansion.execution().transformations().forEach(step ->
            matches.merge(Objects.requireNonNull(familyOf.apply(step.rule())), 1L, Math::addExact)));
        var rejections = new TreeMap<String, Long>();
        result.candidateDecisions().stream().filter(d -> d.outcome() != WorkBudgetBestFirstSearchStrategy.Decision.ENQUEUED)
            .forEach(d -> rejections.merge(d.outcome().name(), 1L, Math::addExact));
        long consumed = result.candidateDecisions().size();
        long enqueued = result.candidateDecisions().stream()
            .filter(d -> d.outcome() == WorkBudgetBestFirstSearchStrategy.Decision.ENQUEUED).count();
        long generated = result.metrics().generatedTransformations();
        var hit = result.reachedState();
        long primitive = result.metrics().transformationWork().sourceCandidates();
        return new SearchRunDiagnostics(generated, consumed, enqueued, generated - enqueued,
            generated - consumed, rejections.getOrDefault("DUPLICATE", 0L)
                + result.metrics().transformationWork().duplicateCandidatesDropped(),
            countDeadEnds(result), result.exploredStates().size(), result.metrics().expandedStates(),
            hit == null ? -1 : hit.edgeDepth(), hit == null ? -1 : hit.primitiveDepth(),
            result.exploredStates().stream().mapToInt(WorkBudgetBestFirstSearchStrategy.State::primitiveDepth).max().orElse(0),
            primitive, result.metrics().chargedSearchWorkUnits() - primitive, primitiveAuditCalls,
            matches, rejections, contributions(hit, learnedRuleIds));
    }

    private static long countDeadEnds(WorkBudgetBestFirstSearchStrategy.Result result) {
        var decisions = result.candidateDecisions().stream()
            .collect(Collectors.groupingBy(WorkBudgetBestFirstSearchStrategy.CandidateDecision::source));
        return result.expansions().stream().filter(expansion -> {
            var consumed = decisions.getOrDefault(expansion.source(), List.of());
            return expansion.execution().complete()
                && consumed.size() == expansion.execution().transformations().size()
                && consumed.stream().allMatch(d -> switch (d.outcome()) {
                    case DUPLICATE, REPEATED_APPLICATION, SAME_EXPRESSION -> true;
                    default -> false;
                });
        }).map(WorkBudgetBestFirstSearchStrategy.ExpansionObservation::source).distinct().count();
    }

    private static List<LearnedContribution> contributions(WorkBudgetBestFirstSearchStrategy.State hit,
            Set<String> learnedRuleIds) {
        if (hit == null) return List.of();
        var rows = new ArrayList<LearnedContribution>();
        for (int i = 0; i < hit.transformations().size(); i++) {
            Transformation step = hit.transformations().get(i);
            if (learnedRuleIds.contains(step.rule())) rows.add(new LearnedContribution(step.rule(),
                hit.path().get(i), step.transformedExpression(), i + 1, step.primitiveStepCount()));
        }
        return List.copyOf(rows);
    }

    /** Mean admitted successors per expanded state; not a fitted tree-depth estimate. */
    public double effectiveBranchingFactor() {
        return expandedStates == 0 ? 0.0 : (double) enqueuedSuccessors / expandedStates;
    }

    public long totalWork() { return Math.addExact(Math.addExact(primitiveWork, searchWork), verificationWork); }

    public String toCanonicalJson() {
        var json = new JsonWriter().beginObject().property("schema", REVISION)
            .property("matchScope", MATCH_SCOPE).property("workScope", WORK_SCOPE)
            .property("successorScope", "SOURCE_OUTPUT_TO_FRONTIER;DISCARDED_INCLUDES_NEVER_CONSUMED")
            .property("generatedSuccessors", generatedSuccessors).property("consumedSuccessors", consumedSuccessors)
            .property("enqueuedSuccessors", enqueuedSuccessors).property("discardedSuccessors", discardedSuccessors)
            .property("unconsumedSuccessors", unconsumedSuccessors).property("duplicateSuccessors", duplicateSuccessors)
            .property("deadEnds", deadEnds).property("exploredStates", exploredStates).property("expandedStates", expandedStates)
            .property("effectiveBranchingFactor", effectiveBranchingFactor())
            .property("branchingDefinition", "ENQUEUED_SUCCESSORS_DIVIDED_BY_EXPANDED_STATES")
            .property("firstHitDepth", firstHitDepth).property("firstHitPrimitiveDepth", firstHitPrimitiveDepth)
            .property("deepestPrimitiveDepth", deepestPrimitiveDepth).property("primitiveWork", primitiveWork)
            .property("searchWork", searchWork).property("verificationWork", verificationWork).property("totalWork", totalWork())
            .object("matchesByRuleFamily", value -> matchesByRuleFamily.forEach(value::property))
            .object("rejectionsByReason", value -> rejectionsByReason.forEach(value::property))
            .array("learnedContributions", values -> learnedContributions.forEach(row -> values.objectValue(value ->
                value.property("ruleId", row.ruleId()).property("from", row.from()).property("to", row.to())
                    .property("searchDepth", row.searchDepth()).property("primitiveSteps", row.primitiveSteps()))));
        return json.endObject().toString();
    }
}
