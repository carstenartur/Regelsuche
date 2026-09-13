package de.regelsuche.evolution;

import de.regelsuche.evolution.EvolutionRewriteProgramTrainFitnessEvidence.PathCorrectness;
import de.regelsuche.evolution.EvolutionRewriteProgramValidationPlan.Configuration;
import de.regelsuche.search.strategy.SearchWorkMetrics;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy.Budget;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy.Status;
import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Complete paired rows; technical failures preserve observations and unknown totals. */
public final class EvolutionRewriteProgramValidationEvidence {
    private EvolutionRewriteProgramValidationEvidence() { }

    public record Measurement(String terminalReason, boolean reached, int depth, int primitiveSteps,
        List<String> path, boolean programUsed, PathCorrectness correctness,
        SearchWorkMetrics searchWork, TransformationWorkMetrics transformationWork,
        long pathAuditCalls, Long totalWorkUnits, String failure) {
        public Measurement {
            EvolutionValidationArtifactSupport.requireText(terminalReason, "terminalReason");
            path = List.copyOf(Objects.requireNonNull(path, "path"));
            Objects.requireNonNull(correctness, "correctness");
            Objects.requireNonNull(failure, "failure");
            if ("NOT_OBSERVED".equals(terminalReason)) {
                if (failure.isEmpty() || reached || searchWork != null || transformationWork != null) {
                    throw new IllegalArgumentException("unobserved search cannot claim measured success");
                }
            } else {
                Status status = Status.valueOf(terminalReason);
                if (reached != (status == Status.REACHED || status == Status.ROOT_ALREADY_TARGET)
                        || (status == Status.INCOMPLETE_EXPANSION && failure.isEmpty())) {
                    throw new IllegalArgumentException("terminal status contradicts search completeness or reachability");
                }
            }
            if (depth < -1 || primitiveSteps < 0 || pathAuditCalls < 0
                    || (reached && (depth < 0 || path.size() != depth + 1))
                    || (!reached && (depth != -1 || !path.isEmpty() || programUsed
                        || correctness != PathCorrectness.NOT_EVALUATED))) {
                throw new IllegalArgumentException("inconsistent program VALIDATION path evidence");
            }
            if (failure.isEmpty()) {
                Objects.requireNonNull(searchWork, "complete searchWork");
                Objects.requireNonNull(transformationWork, "complete transformationWork");
                requireCompletedAudit(reached, depth, correctness, pathAuditCalls);
                long expected = sum(searchWork.totalWorkUnits(), transformationWork.totalWorkUnits(), pathAuditCalls);
                if (totalWorkUnits == null || totalWorkUnits != expected) {
                    throw new IllegalArgumentException("total work differs from complete retained work vectors");
                }
            } else if (totalWorkUnits != null) {
                throw new IllegalArgumentException("technical failure cannot claim a complete work total");
            }
        }

        public boolean complete() { return failure.isEmpty(); }

        private static void requireCompletedAudit(boolean reached, int depth, PathCorrectness correctness,
            long pathAuditCalls) {
            if ((reached && correctness == PathCorrectness.NOT_EVALUATED)
                    || pathAuditCalls > Math.max(0, depth)
                    || (reached && correctness == PathCorrectness.CONFIRMED && pathAuditCalls != depth)) {
                throw new IllegalArgumentException("completed correctness audit differs from retained path edges");
            }
        }

        void requireBudget(Budget budget) {
            if (!complete()) { return; } // Partial observations retain actual work and remain ineligible.
            if (primitiveSteps > budget.maxPrimitiveSteps()
                    || searchWork.exploredStates() > budget.maxExploredStates()) {
                throw new IllegalArgumentException("complete measurement exceeds the bound path or state allowance");
            }
            // v1 may charge an entire batch before stopping with WORK_BUDGET; preserve that observation.
            if (!Status.WORK_BUDGET.name().equals(terminalReason)
                    && sum(searchWork.totalWorkUnits(), transformationWork.totalWorkUnits())
                        > budget.mechanicalSearchWorkBudget()) {
                throw new IllegalArgumentException("complete terminal outcome contradicts its bound search work allowance");
            }
        }

        static Measurement unavailable(String reason) {
            return new Measurement("NOT_OBSERVED", false, -1, 0, List.of(), false,
                PathCorrectness.NOT_EVALUATED, null, null, 0, null, reason);
        }
    }

    public record PairedCase(String caseId, String familyId, Measurement baseline, Measurement candidate) {
        public PairedCase {
            EvolutionValidationArtifactSupport.requireText(caseId, "caseId");
            EvolutionValidationArtifactSupport.requireText(familyId, "familyId");
            Objects.requireNonNull(baseline, "baseline");
            Objects.requireNonNull(candidate, "candidate");
        }

        static PairedCase unavailable(EvolutionSplitManifest.CaseReference reference, String reason) {
            return new PairedCase(reference.caseId(), reference.familyId(),
                Measurement.unavailable(reason), Measurement.unavailable(reason));
        }
    }

    public record CandidateEvidence(String configurationHash, List<PairedCase> cases,
        EvolutionValidationCandidate validationMetrics, List<String> blockers) {
        public CandidateEvidence {
            EvolutionGenome.requireSha256(configurationHash, "configurationHash");
            cases = List.copyOf(Objects.requireNonNull(cases, "cases"));
            blockers = List.copyOf(Objects.requireNonNull(blockers, "blockers"));
            if (cases.isEmpty() || cases.stream().map(PairedCase::caseId).distinct().count() != cases.size()
                    || !blockers.equals(EvolutionRewriteProgramValidationEvidence.blockers(cases))) {
                throw new IllegalArgumentException("incomplete or inconsistent paired VALIDATION evidence");
            }
        }

        static CandidateEvidence create(Configuration configuration, List<PairedCase> cases) {
            List<String> blockers = EvolutionRewriteProgramValidationEvidence.blockers(cases);
            return new CandidateEvidence(configuration.contentHash(), cases,
                metrics(configuration, cases, blockers), blockers);
        }

        void requireConfiguration(Configuration configuration) {
            if (!configurationHash.equals(configuration.contentHash())
                    || !Objects.equals(validationMetrics, metrics(configuration, cases, blockers))) {
                throw new IllegalArgumentException("VALIDATION metrics differ from full configuration and paired rows");
            }
            Budget budget = configuration.effectiveBudget();
            for (PairedCase item : cases) {
                item.baseline().requireBudget(budget);
                item.candidate().requireBudget(budget);
            }
        }

        public boolean eligible() {
            return validationMetrics != null && validationMetrics.eligible() && blockers.isEmpty();
        }
    }

    private static List<String> blockers(List<PairedCase> cases) {
        List<String> result = new ArrayList<>();
        for (PairedCase item : cases) {
            sideBlockers(result, item.caseId(), "BASELINE", item.baseline());
            sideBlockers(result, item.caseId(), "CANDIDATE", item.candidate());
        }
        return result.stream().distinct().sorted().toList();
    }

    private static void sideBlockers(List<String> result, String id, String side, Measurement measurement) {
        if (!measurement.complete()) {
            result.add(side + ":" + id + ":" + measurement.failure());
        } else if (measurement.reached() && measurement.correctness() != PathCorrectness.CONFIRMED) {
            result.add(side + ":" + id + ":" + measurement.correctness());
        }
    }

    private static EvolutionValidationCandidate metrics(Configuration configuration, List<PairedCase> cases,
        List<String> blockers) {
        if (cases.stream().anyMatch(item -> !item.baseline().complete() || !item.candidate().complete())) {
            return null; // There is no honest numeric aggregate over unknown work.
        }
        var genome = configuration.candidate().genome();
        var budget = configuration.effectiveBudget();
        return EvolutionValidationCandidate.create(genome.contentHash(), genome.alphaStructuralHash(),
            new EvolutionValidationSearchConfiguration(
                budget.maxPrimitiveSteps(), budget.maxExploredStates(), budget.maxCandidatesPerState()),
            cases.stream().map(EvolutionRewriteProgramValidationEvidence::legacyCase).toList(), blockers);
    }

    private static EvolutionValidationCaseEvidence legacyCase(PairedCase row) {
        var baseline = row.baseline();
        var candidate = row.candidate();
        var baselineCorrectness = correctness(baseline.correctness());
        var candidateCorrectness = correctness(candidate.correctness());
        boolean refuted = candidate.reached() && candidateCorrectness == EvolutionCorrectnessStatus.REFUTED;
        return new EvolutionValidationCaseEvidence(row.caseId(), row.familyId(),
            baseline.reached(), candidate.reached(), baselineCorrectness, candidateCorrectness,
            baseline.terminalReason(), candidate.terminalReason(), baseline.depth(), candidate.depth(),
            baseline.searchWork().exploredStates(), candidate.searchWork().exploredStates(),
            baseline.searchWork().generatedTransformations(), candidate.searchWork().generatedTransformations(),
            !baseline.reached() && candidate.reached() && candidateCorrectness == EvolutionCorrectnessStatus.CONFIRMED,
            baseline.reached() && !candidate.reached(), refuted,
            baseline.reached() && baselineCorrectness == EvolutionCorrectnessStatus.CONFIRMED && refuted);
    }

    private static EvolutionCorrectnessStatus correctness(PathCorrectness status) {
        return switch (status) {
            case CONFIRMED -> EvolutionCorrectnessStatus.CONFIRMED;
            case REFUTED -> EvolutionCorrectnessStatus.REFUTED;
            case NOT_EVALUATED -> EvolutionCorrectnessStatus.NOT_EVALUATED;
            case MISSING_ASSUMPTION, UNSUPPORTED -> EvolutionCorrectnessStatus.INCONCLUSIVE;
        };
    }

    static long sum(long... values) {
        long result = 0;
        for (long value : values) {
            try { result = Math.addExact(result, value); }
            catch (ArithmeticException exception) { return Long.MAX_VALUE; }
        }
        return result;
    }
}
