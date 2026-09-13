package de.regelsuche.evolution;

import de.regelsuche.evolution.EvolutionRewriteProgramTrainFitnessEvidence.PathCorrectness;
import de.regelsuche.evolution.EvolutionRewriteProgramValidationEvidence.Measurement;
import de.regelsuche.evolution.EvolutionRewriteProgramValidationEvidence.PairedCase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Complete one-shot observations. Completion consumes the attempt, without passing later gates. */
public record EvolutionRewriteProgramFinalTestEvaluation(String schema, EvolutionRewriteProgramFinalTestPlan plan,
    List<PairedCase> cases, Summary summary, String finalTestStatus, String proofStatus,
    String externalNoveltyStatus, String promotionStatus, String publicEvidenceStatus, String contentHash) {
    public static final String SCHEMA = "regelsuche.evolution-rewrite-program-final-test-evaluation/v1";
    public static final String COMPLETED = "COMPLETED";
    public static final String NOT_EVALUATED = "NOT_EVALUATED";

    public EvolutionRewriteProgramFinalTestEvaluation {
        if (!SCHEMA.equals(schema) || !COMPLETED.equals(finalTestStatus)) {
            throw new IllegalArgumentException("unsupported or unconsumed program FINAL TEST evidence");
        }
        Objects.requireNonNull(plan, "plan");
        cases = List.copyOf(Objects.requireNonNull(cases, "cases"));
        if (!cases.stream().map(PairedCase::caseId).toList().equals(
                plan.cases().stream().map(EvolutionSplitManifest.CaseReference::caseId).toList())
                || !cases.stream().map(PairedCase::familyId).toList().equals(
                    plan.cases().stream().map(EvolutionSplitManifest.CaseReference::familyId).toList())) {
            throw new IllegalArgumentException("FINAL TEST must retain every committed case and family in order");
        }
        for (int index = 0; index < cases.size(); index++) {
            requirePathMatches(cases.get(index).baseline(), plan.cases().get(index));
            requirePathMatches(cases.get(index).candidate(), plan.cases().get(index));
            cases.get(index).baseline().requireBudget(plan.selectedConfiguration().effectiveBudget());
            cases.get(index).candidate().requireBudget(plan.selectedConfiguration().effectiveBudget());
        }
        if (!summarize(cases).equals(Objects.requireNonNull(summary, "summary"))) {
            throw new IllegalArgumentException("FINAL TEST summary differs from complete retained observations");
        }
        for (String status : List.of(proofStatus, externalNoveltyStatus, promotionStatus, publicEvidenceStatus)) {
            if (!NOT_EVALUATED.equals(status)) {
                throw new IllegalArgumentException("FINAL TEST execution cannot advance downstream qualification gates");
            }
        }
        EvolutionProgramValidationJson.requireHash(contentHash, material(plan, cases, summary));
    }

    static EvolutionRewriteProgramFinalTestEvaluation create(EvolutionRewriteProgramFinalTestPlan plan,
        List<PairedCase> cases) {
        var summary = summarize(cases);
        return new EvolutionRewriteProgramFinalTestEvaluation(SCHEMA, plan, cases, summary, COMPLETED,
            NOT_EVALUATED, NOT_EVALUATED, NOT_EVALUATED, NOT_EVALUATED,
            EvolutionProgramValidationJson.hash(material(plan, cases, summary)));
    }

    public boolean qualificationEligible() { return summary.blockers().isEmpty(); }

    public String toCanonicalJson() { return EvolutionProgramValidationJson.write(this); }
    public static EvolutionRewriteProgramFinalTestEvaluation fromCanonicalJson(String json,
        EvolutionRewriteProgramFinalTestPlan expectedPlan) {
        var result = EvolutionProgramValidationJson.read(json, EvolutionRewriteProgramFinalTestEvaluation.class);
        if (!result.plan().equals(Objects.requireNonNull(expectedPlan, "expectedPlan"))) {
            throw new IllegalArgumentException("FINAL TEST evidence differs from externally expected selected identity");
        }
        return result;
    }

    private static void requirePathMatches(Measurement measurement, EvolutionSplitManifest.CaseReference reference) {
        if (measurement.reached()
                && (!EvolutionGenome.hash(measurement.path().getFirst()).equals(reference.inputHash())
                    || !EvolutionGenome.hash(measurement.path().getLast()).equals(reference.hiddenTargetHash()))) {
            throw new IllegalArgumentException("FINAL TEST path differs from committed input or target");
        }
    }

    private static Summary summarize(List<PairedCase> cases) {
        int completed = 0, technical = 0, reached = 0, gained = 0, lost = 0, refuted = 0, regressed = 0;
        Long baselineWork = 0L, candidateWork = 0L;
        List<String> blockers = new ArrayList<>();
        for (var row : cases) {
            var baseline = row.baseline();
            var candidate = row.candidate();
            baselineWork = addWork(baselineWork, baseline.totalWorkUnits());
            candidateWork = addWork(candidateWork, candidate.totalWorkUnits());
            sideBlockers(blockers, row.caseId(), "BASELINE", baseline);
            sideBlockers(blockers, row.caseId(), "CANDIDATE", candidate);
            if (!baseline.complete() || !candidate.complete()) {
                technical++;
                continue; // Unknown observations are neither losses nor new solves.
            }
            completed++;
            if (candidate.reached()) { reached++; }
            if (!baseline.reached() && candidate.reached() && candidate.correctness() == PathCorrectness.CONFIRMED) { gained++; }
            if (baseline.reached() && !candidate.reached()) {
                lost++;
                blockers.add("REACHABILITY_REGRESSION:" + row.caseId());
            }
            if (candidate.reached() && candidate.correctness() == PathCorrectness.REFUTED) {
                refuted++;
                if (baseline.reached() && baseline.correctness() == PathCorrectness.CONFIRMED) { regressed++; }
            }
        }
        if (cases.isEmpty()) { blockers.add("EMPTY_FINAL_TEST"); }
        return new Summary(completed, technical, reached, gained, lost, refuted, regressed, baselineWork,
            candidateWork, blockers.stream().distinct().sorted().toList());
    }

    private static Long addWork(Long previous, Long measured) {
        return previous == null || measured == null ? null : EvolutionRewriteProgramValidationEvidence.sum(previous, measured);
    }

    private static void sideBlockers(List<String> blockers, String id, String side, Measurement measurement) {
        if (!measurement.complete()) { blockers.add(side + ":" + id + ":" + measurement.failure()); }
        else if (measurement.reached() && measurement.correctness() != PathCorrectness.CONFIRMED) {
            blockers.add(side + ":" + id + ":" + measurement.correctness());
        }
    }

    public record Summary(int completedCases, int technicalFailures, int reachedCases, int newlySolvedCases,
        int reachabilityRegressions, int correctnessFailures, int correctnessRegressions,
        Long baselineWorkUnits, Long candidateWorkUnits, List<String> blockers) {
        public Summary { blockers = List.copyOf(Objects.requireNonNull(blockers, "blockers")); }
    }

    private static Map<String, Object> material(EvolutionRewriteProgramFinalTestPlan plan,
        List<PairedCase> cases, Summary summary) {
        return EvolutionProgramValidationJson.material(SCHEMA, "plan", plan, "cases", cases, "summary", summary,
            "finalTestStatus", COMPLETED, "proofStatus", NOT_EVALUATED, "externalNoveltyStatus", NOT_EVALUATED,
            "promotionStatus", NOT_EVALUATED, "publicEvidenceStatus", NOT_EVALUATED);
    }
}
