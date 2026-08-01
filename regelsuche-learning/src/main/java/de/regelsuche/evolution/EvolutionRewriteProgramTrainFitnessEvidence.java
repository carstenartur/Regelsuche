package de.regelsuche.evolution;

import de.regelsuche.evolution.EvolutionStudyPlan.FitnessComponent;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Canonical paired production-search evidence for one genome/program candidate
 * on one frozen assumption-aware TRAIN suite and evaluation protocol.
 */
public record EvolutionRewriteProgramTrainFitnessEvidence(
    String schema,
    String suiteHash,
    String evaluationProtocolHash,
    String candidateHash,
    String genomeHash,
    String planHash,
    List<CaseMeasurement> cases,
    Map<FitnessComponent, Integer> rawComponents,
    List<String> blockers,
    String validationStatus,
    String finalTestStatus,
    String proofStatus,
    String externalNoveltyStatus,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.evolution-rewrite-program-train-fitness/v1";
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]{2,127}");

    public EvolutionRewriteProgramTrainFitnessEvidence {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported rewrite-program TRAIN fitness schema");
        }
        EvolutionGenome.requireSha256(suiteHash, "suiteHash");
        EvolutionGenome.requireSha256(
            evaluationProtocolHash, "evaluationProtocolHash");
        EvolutionGenome.requireSha256(candidateHash, "candidateHash");
        EvolutionGenome.requireSha256(genomeHash, "genomeHash");
        EvolutionGenome.requireSha256(planHash, "planHash");
        cases = canonicalCases(cases);
        rawComponents = canonicalComponents(rawComponents);
        blockers = canonicalStrings(blockers);
        if (!"NOT_EVALUATED".equals(validationStatus)
                || !"NOT_EVALUATED".equals(finalTestStatus)
                || !"NOT_EVALUATED".equals(proofStatus)
                || !"NOT_EVALUATED".equals(externalNoveltyStatus)) {
            throw new IllegalArgumentException(
                "TRAIN evidence cannot contain later-stage outcomes");
        }
        EvolutionGenome.requireSha256(contentHash, "contentHash");
        String expected = EvolutionGenome.hash(render(
            suiteHash,
            evaluationProtocolHash,
            candidateHash,
            genomeHash,
            planHash,
            cases,
            rawComponents,
            blockers,
            null));
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "rewrite-program TRAIN fitness contentHash mismatch");
        }
    }

    /** Compatibility path bound to the official information-parity protocol. */
    public static EvolutionRewriteProgramTrainFitnessEvidence create(
        EvolutionRewriteProgramTrainSuite suite,
        EvolutionRewriteProgramCandidate candidate,
        List<CaseMeasurement> cases,
        Map<FitnessComponent, Integer> rawComponents,
        List<String> blockers
    ) {
        return create(
            suite,
            EvolutionRewriteProgramEvaluationProtocol
                .informationParityExactRationalV1(),
            candidate,
            cases,
            rawComponents,
            blockers);
    }

    public static EvolutionRewriteProgramTrainFitnessEvidence create(
        EvolutionRewriteProgramTrainSuite suite,
        EvolutionRewriteProgramEvaluationProtocol protocol,
        EvolutionRewriteProgramCandidate candidate,
        List<CaseMeasurement> cases,
        Map<FitnessComponent, Integer> rawComponents,
        List<String> blockers
    ) {
        Objects.requireNonNull(suite, "suite");
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(candidate, "candidate");
        if (suite.evaluatorProfile() != protocol.evaluatorProfile()) {
            throw new IllegalArgumentException(
                "TRAIN suite evaluator profile differs from evidence protocol");
        }
        List<CaseMeasurement> canonicalCases = canonicalCases(cases);
        Map<FitnessComponent, Integer> canonicalComponents =
            canonicalComponents(rawComponents);
        List<String> canonicalBlockers = canonicalStrings(blockers);
        String hash = EvolutionGenome.hash(render(
            suite.contentHash(),
            protocol.contentHash(),
            candidate.contentHash(),
            candidate.genome().contentHash(),
            candidate.plan().contentHash(),
            canonicalCases,
            canonicalComponents,
            canonicalBlockers,
            null));
        return new EvolutionRewriteProgramTrainFitnessEvidence(
            SCHEMA,
            suite.contentHash(),
            protocol.contentHash(),
            candidate.contentHash(),
            candidate.genome().contentHash(),
            candidate.plan().contentHash(),
            canonicalCases,
            canonicalComponents,
            canonicalBlockers,
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            hash);
    }

    public String toCanonicalJson() {
        return render(
            suiteHash,
            evaluationProtocolHash,
            candidateHash,
            genomeHash,
            planHash,
            cases,
            rawComponents,
            blockers,
            contentHash);
    }

    private static List<CaseMeasurement> canonicalCases(
        List<CaseMeasurement> values
    ) {
        List<CaseMeasurement> result = values == null
            ? List.of()
            : values.stream()
                .map(item -> Objects.requireNonNull(item, "case measurement"))
                .sorted(Comparator.comparing(CaseMeasurement::caseId))
                .toList();
        if (new HashSet<>(result.stream().map(CaseMeasurement::caseId).toList()).size()
                != result.size()) {
            throw new IllegalArgumentException(
                "rewrite-program case measurements require unique IDs");
        }
        return result;
    }

    private static Map<FitnessComponent, Integer> canonicalComponents(
        Map<FitnessComponent, Integer> values
    ) {
        EnumMap<FitnessComponent, Integer> result =
            new EnumMap<>(FitnessComponent.class);
        if (values != null) {
            values.forEach((component, value) -> {
                Objects.requireNonNull(component, "fitness component");
                Objects.requireNonNull(value, "fitness component value");
                if (value < -1000 || value > 1000) {
                    throw new IllegalArgumentException(
                        "fitness component must be in [-1000,1000]");
                }
                result.put(component, value);
            });
        }
        return Collections.unmodifiableMap(result);
    }

    private static List<String> canonicalStrings(List<String> values) {
        return values == null
            ? List.of()
            : values.stream()
                .map(value -> requireText(value, "blocker"))
                .distinct()
                .sorted()
                .toList();
    }

    private static String render(
        String suiteHash,
        String evaluationProtocolHash,
        String candidateHash,
        String genomeHash,
        String planHash,
        List<CaseMeasurement> cases,
        Map<FitnessComponent, Integer> rawComponents,
        List<String> blockers,
        String contentHash
    ) {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", SCHEMA)
            .property("suiteHash", suiteHash)
            .property("evaluationProtocolHash", evaluationProtocolHash)
            .property("candidateHash", candidateHash)
            .property("genomeHash", genomeHash)
            .property("planHash", planHash)
            .array("cases", array -> cases.forEach(item ->
                array.objectValue(object -> {
                    object.property("caseId", item.caseId())
                        .property("familyId", item.familyId())
                        .property("baselineStatus", item.baselineStatus())
                        .property("candidateStatus", item.candidateStatus())
                        .property("baselineReached", item.baselineReached())
                        .property("candidateReached", item.candidateReached())
                        .property("baselinePathCorrectness",
                            item.baselinePathCorrectness().name())
                        .property("candidatePathCorrectness",
                            item.candidatePathCorrectness().name())
                        .property("baselinePathLength", item.baselinePathLength())
                        .property("candidatePathLength", item.candidatePathLength())
                        .property("baselinePrimitiveSteps",
                            item.baselinePrimitiveSteps())
                        .property("candidatePrimitiveSteps",
                            item.candidatePrimitiveSteps())
                        .property("baselineExploredStates",
                            item.baselineExploredStates())
                        .property("candidateExploredStates",
                            item.candidateExploredStates())
                        .property("baselineGeneratedTransformations",
                            item.baselineGeneratedTransformations())
                        .property("candidateGeneratedTransformations",
                            item.candidateGeneratedTransformations())
                        .property("baselineOuterSearchWorkUnits",
                            item.baselineOuterSearchWorkUnits())
                        .property("candidateOuterSearchWorkUnits",
                            item.candidateOuterSearchWorkUnits())
                        .property("baselinePathAuditCalls",
                            item.baselinePathAuditCalls())
                        .property("candidatePathAuditCalls",
                            item.candidatePathAuditCalls())
                        .property("baselineTotalWorkUnits",
                            item.baselineTotalWorkUnits())
                        .property("candidateTotalWorkUnits",
                            item.candidateTotalWorkUnits());
                    object.object("baselineTransformationWork", work ->
                        writeWork(work, item.baselineTransformationWork()));
                    object.object("candidateTransformationWork", work ->
                        writeWork(work, item.candidateTransformationWork()));
                    object.property("programUsed", item.programUsed())
                        .property("newlySolved", item.newlySolved())
                        .property("reachabilityRegression",
                            item.reachabilityRegression())
                        .property("correctnessFailure", item.correctnessFailure())
                        .property("correctnessRegression",
                            item.correctnessRegression());
                })))
            .object("rawComponents", object -> {
                for (FitnessComponent component : FitnessComponent.values()) {
                    Integer value = rawComponents.get(component);
                    if (value != null) {
                        object.property(component.name(), value);
                    }
                }
            })
            .stringArray("blockers", blockers)
            .property("validationStatus", "NOT_EVALUATED")
            .property("finalTestStatus", "NOT_EVALUATED")
            .property("proofStatus", "NOT_EVALUATED")
            .property("externalNoveltyStatus", "NOT_EVALUATED");
        if (contentHash != null) {
            json.property("contentHash", contentHash);
        }
        return json.endObject().toString();
    }

    private static void writeWork(
        JsonWriter json,
        TransformationWorkMetrics work
    ) {
        json.property("engineInvocations", work.engineInvocations())
            .property("programNodeVisits", work.programNodeVisits())
            .property("sourceInvocations", work.sourceInvocations())
            .property("sourceCandidates", work.sourceCandidates())
            .property("composedCandidates", work.composedCandidates())
            .property("requirementEvaluations", work.requirementEvaluations())
            .property("requirementRejections", work.requirementRejections())
            .property("priorityCandidatesOrdered",
                work.priorityCandidatesOrdered())
            .property("prunedCandidates", work.prunedCandidates())
            .property("repeatIterations", work.repeatIterations())
            .property("repeatEndpoints", work.repeatEndpoints())
            .property("alternativeSelections", work.alternativeSelections())
            .property("alternativesSkipped", work.alternativesSkipped())
            .property("duplicateCandidatesDropped",
                work.duplicateCandidatesDropped())
            .property("totalWorkUnits", work.totalWorkUnits());
    }

    public enum PathCorrectness {
        CONFIRMED,
        REFUTED,
        MISSING_ASSUMPTION,
        UNSUPPORTED,
        NOT_EVALUATED
    }

    public record CaseMeasurement(
        String caseId,
        String familyId,
        String baselineStatus,
        String candidateStatus,
        boolean baselineReached,
        boolean candidateReached,
        PathCorrectness baselinePathCorrectness,
        PathCorrectness candidatePathCorrectness,
        int baselinePathLength,
        int candidatePathLength,
        int baselinePrimitiveSteps,
        int candidatePrimitiveSteps,
        long baselineExploredStates,
        long candidateExploredStates,
        long baselineGeneratedTransformations,
        long candidateGeneratedTransformations,
        long baselineOuterSearchWorkUnits,
        long candidateOuterSearchWorkUnits,
        long baselinePathAuditCalls,
        long candidatePathAuditCalls,
        TransformationWorkMetrics baselineTransformationWork,
        TransformationWorkMetrics candidateTransformationWork,
        boolean programUsed,
        boolean newlySolved,
        boolean reachabilityRegression,
        boolean correctnessFailure,
        boolean correctnessRegression
    ) {
        /** Compatibility constructor for older diagnostic fixtures. */
        public CaseMeasurement(
            String caseId,
            String familyId,
            String baselineStatus,
            String candidateStatus,
            boolean baselineReached,
            boolean candidateReached,
            PathCorrectness baselinePathCorrectness,
            PathCorrectness candidatePathCorrectness,
            int baselinePathLength,
            int candidatePathLength,
            int baselinePrimitiveSteps,
            int candidatePrimitiveSteps,
            long baselineExploredStates,
            long candidateExploredStates,
            long baselineGeneratedTransformations,
            long candidateGeneratedTransformations,
            boolean programUsed,
            boolean newlySolved,
            boolean reachabilityRegression,
            boolean correctnessFailure,
            boolean correctnessRegression
        ) {
            this(
                caseId,
                familyId,
                baselineStatus,
                candidateStatus,
                baselineReached,
                candidateReached,
                baselinePathCorrectness,
                candidatePathCorrectness,
                baselinePathLength,
                candidatePathLength,
                baselinePrimitiveSteps,
                candidatePrimitiveSteps,
                baselineExploredStates,
                candidateExploredStates,
                baselineGeneratedTransformations,
                candidateGeneratedTransformations,
                0,
                0,
                0,
                0,
                TransformationWorkMetrics.ZERO,
                TransformationWorkMetrics.ZERO,
                programUsed,
                newlySolved,
                reachabilityRegression,
                correctnessFailure,
                correctnessRegression);
        }

        public CaseMeasurement {
            requireId(caseId, "caseId");
            requireId(familyId, "familyId");
            requireText(baselineStatus, "baselineStatus");
            requireText(candidateStatus, "candidateStatus");
            Objects.requireNonNull(
                baselinePathCorrectness, "baselinePathCorrectness");
            Objects.requireNonNull(
                candidatePathCorrectness, "candidatePathCorrectness");
            baselineTransformationWork = Objects.requireNonNull(
                baselineTransformationWork, "baselineTransformationWork");
            candidateTransformationWork = Objects.requireNonNull(
                candidateTransformationWork, "candidateTransformationWork");
            if (baselinePathLength < -1 || candidatePathLength < -1
                    || baselinePrimitiveSteps < 0 || candidatePrimitiveSteps < 0
                    || baselineExploredStates < 0 || candidateExploredStates < 0
                    || baselineGeneratedTransformations < 0
                    || candidateGeneratedTransformations < 0
                    || baselineOuterSearchWorkUnits < 0
                    || candidateOuterSearchWorkUnits < 0
                    || baselinePathAuditCalls < 0
                    || candidatePathAuditCalls < 0) {
                throw new IllegalArgumentException(
                    "invalid rewrite-program TRAIN case metrics");
            }
            if (!baselineReached
                    && baselinePathCorrectness != PathCorrectness.NOT_EVALUATED) {
                throw new IllegalArgumentException(
                    "unreached baseline path must be NOT_EVALUATED");
            }
            if (!candidateReached
                    && candidatePathCorrectness != PathCorrectness.NOT_EVALUATED) {
                throw new IllegalArgumentException(
                    "unreached candidate path must be NOT_EVALUATED");
            }
            if (programUsed && !candidateReached) {
                throw new IllegalArgumentException(
                    "programUsed requires a reached candidate path");
            }
            boolean expectedNewlySolved = !baselineReached
                && candidateReached
                && programUsed
                && candidatePathCorrectness == PathCorrectness.CONFIRMED;
            if (newlySolved != expectedNewlySolved) {
                throw new IllegalArgumentException("newlySolved is inconsistent");
            }
            if (reachabilityRegression != (baselineReached && !candidateReached)) {
                throw new IllegalArgumentException(
                    "reachabilityRegression is inconsistent");
            }
            boolean expectedCorrectnessFailure = candidateReached
                && (candidatePathCorrectness == PathCorrectness.REFUTED
                    || candidatePathCorrectness
                        == PathCorrectness.MISSING_ASSUMPTION);
            if (correctnessFailure != expectedCorrectnessFailure) {
                throw new IllegalArgumentException(
                    "correctnessFailure is inconsistent");
            }
            boolean expectedCorrectnessRegression = baselineReached
                && baselinePathCorrectness == PathCorrectness.CONFIRMED
                && expectedCorrectnessFailure;
            if (correctnessRegression != expectedCorrectnessRegression) {
                throw new IllegalArgumentException(
                    "correctnessRegression is inconsistent");
            }
        }

        public long baselineTotalWorkUnits() {
            return totalWork(
                baselineTransformationWork,
                baselineOuterSearchWorkUnits,
                baselinePathAuditCalls);
        }

        public long candidateTotalWorkUnits() {
            return totalWork(
                candidateTransformationWork,
                candidateOuterSearchWorkUnits,
                candidatePathAuditCalls);
        }

        private static long totalWork(
            TransformationWorkMetrics transformationWork,
            long outerSearchWorkUnits,
            long pathAuditCalls
        ) {
            return add(add(
                transformationWork.totalWorkUnits(),
                outerSearchWorkUnits),
                pathAuditCalls);
        }

        private static long add(long left, long right) {
            try {
                return Math.addExact(left, right);
            } catch (ArithmeticException exception) {
                return Long.MAX_VALUE;
            }
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static void requireId(String value, String name) {
        if (value == null || !ID.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " has invalid syntax");
        }
    }
}
