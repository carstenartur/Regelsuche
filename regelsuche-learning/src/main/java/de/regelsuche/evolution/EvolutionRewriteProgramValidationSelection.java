package de.regelsuche.evolution;

import de.regelsuche.evolution.EvolutionRewriteProgramValidationEvidence.CandidateEvidence;
import de.regelsuche.evolution.EvolutionRewriteProgramValidationEvidence.PairedCase;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministic VALIDATION selection over explicit combined program configurations. */
public record EvolutionRewriteProgramValidationSelection(String schema,
    EvolutionRewriteProgramValidationPlan plan, List<CandidateEvidence> candidates,
    String selectedConfigurationHash, String validationStatus, String contentHash) {
    public static final String SCHEMA = "regelsuche.evolution-rewrite-program-validation-selection/v1";
    public static final String COMPLETED = "COMPLETED";

    // The existing VALIDATION policy, with the additive complete configuration identity as tie-break.
    private static final Comparator<CandidateEvidence> ORDER = Comparator
        .comparingInt((CandidateEvidence item) -> item.validationMetrics().newlySolvedCases()).reversed()
        .thenComparing(Comparator.comparingInt((CandidateEvidence item) ->
            item.validationMetrics().reachedCases()).reversed())
        .thenComparingLong(item -> item.validationMetrics().exploredStates())
        .thenComparingLong(item -> item.validationMetrics().candidateEvaluations())
        .thenComparing(CandidateEvidence::configurationHash);

    public EvolutionRewriteProgramValidationSelection {
        if (!SCHEMA.equals(schema) || !COMPLETED.equals(validationStatus)) {
            throw new IllegalArgumentException("unsupported or incomplete program VALIDATION selection");
        }
        Objects.requireNonNull(plan, "plan");
        candidates = canonicalCandidates(plan, candidates);
        Objects.requireNonNull(selectedConfigurationHash, "selectedConfigurationHash");
        if (!select(candidates).equals(selectedConfigurationHash)) {
            throw new IllegalArgumentException("selected configuration differs from deterministic VALIDATION result");
        }
        EvolutionProgramValidationJson.requireHash(contentHash, material(plan, candidates, selectedConfigurationHash));
    }

    static EvolutionRewriteProgramValidationSelection create(EvolutionRewriteProgramValidationPlan plan,
        List<CandidateEvidence> evidence) {
        var candidates = canonicalCandidates(plan, evidence);
        String selected = select(candidates);
        return new EvolutionRewriteProgramValidationSelection(SCHEMA, plan, candidates, selected, COMPLETED,
            EvolutionProgramValidationJson.hash(material(plan, candidates, selected)));
    }

    public boolean hasSelection() { return !selectedConfigurationHash.isEmpty(); }

    public EvolutionRewriteProgramValidationHandoff handoff() {
        if (!hasSelection()) {
            throw new IllegalStateException("VALIDATION has no eligible configuration for downstream handoff");
        }
        return EvolutionRewriteProgramValidationHandoff.create(this);
    }

    public String toCanonicalJson() { return EvolutionProgramValidationJson.write(this); }
    public static EvolutionRewriteProgramValidationSelection fromCanonicalJson(String json) {
        return EvolutionProgramValidationJson.read(json, EvolutionRewriteProgramValidationSelection.class);
    }

    /** Imports against an independently retained plan instead of trusting a self-rehashed replacement. */
    public static EvolutionRewriteProgramValidationSelection fromCanonicalJson(String json,
        EvolutionRewriteProgramValidationPlan expectedPlan) {
        var selection = fromCanonicalJson(json);
        if (!selection.plan().equals(Objects.requireNonNull(expectedPlan, "expectedPlan"))) {
            throw new IllegalArgumentException("imported selection differs from the externally expected VALIDATION plan");
        }
        return selection;
    }

    private static String select(List<CandidateEvidence> candidates) {
        return candidates.stream().filter(CandidateEvidence::eligible).min(ORDER)
            .map(CandidateEvidence::configurationHash).orElse("");
    }

    private static List<CandidateEvidence> canonicalCandidates(EvolutionRewriteProgramValidationPlan plan,
        List<CandidateEvidence> evidence) {
        var candidates = Objects.requireNonNull(evidence, "candidates").stream()
            .sorted(Comparator.comparing(CandidateEvidence::configurationHash)).toList();
        if (!candidates.stream().map(CandidateEvidence::configurationHash).toList().equals(
                plan.configurations().stream().map(EvolutionRewriteProgramValidationPlan.Configuration::contentHash).toList())) {
            throw new IllegalArgumentException("selection must retain the entire frozen configuration matrix");
        }
        var expectedIds = plan.cases().stream().map(EvolutionSplitManifest.CaseReference::caseId).toList();
        var expectedFamilies = plan.cases().stream().map(EvolutionSplitManifest.CaseReference::familyId).toList();
        for (int index = 0; index < candidates.size(); index++) {
            var candidate = candidates.get(index);
            candidate.requireConfiguration(plan.configurations().get(index));
            if (!candidate.cases().stream().map(PairedCase::caseId).toList().equals(expectedIds)
                    || !candidate.cases().stream().map(PairedCase::familyId).toList().equals(expectedFamilies)) {
                throw new IllegalArgumentException("selection must retain all committed cases in their frozen order");
            }
            for (int caseIndex = 0; caseIndex < candidate.cases().size(); caseIndex++) {
                var row = candidate.cases().get(caseIndex);
                var reference = plan.cases().get(caseIndex);
                requirePathMatches(row.baseline(), reference);
                requirePathMatches(row.candidate(), reference);
            }
        }
        return candidates;
    }

    private static void requirePathMatches(EvolutionRewriteProgramValidationEvidence.Measurement measurement,
        EvolutionSplitManifest.CaseReference reference) {
        if (measurement.reached()
                && (!EvolutionGenome.hash(measurement.path().getFirst()).equals(reference.inputHash())
                    || !EvolutionGenome.hash(measurement.path().getLast()).equals(reference.hiddenTargetHash()))) {
            throw new IllegalArgumentException("reached path differs from committed input or target");
        }
    }

    private static Map<String, Object> material(EvolutionRewriteProgramValidationPlan plan,
        List<CandidateEvidence> candidates, String selected) {
        return EvolutionProgramValidationJson.material(SCHEMA, "plan", plan, "candidates", candidates,
            "selectedConfigurationHash", selected, "validationStatus", COMPLETED);
    }
}
