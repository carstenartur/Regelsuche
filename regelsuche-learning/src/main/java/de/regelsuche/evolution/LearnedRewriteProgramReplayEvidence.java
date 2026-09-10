package de.regelsuche.evolution;

import static de.regelsuche.evolution.LearnedPatternAuthorizationJson.hash;
import static de.regelsuche.evolution.LearnedPatternAuthorizationJson.requireHash;
import static de.regelsuche.evolution.LearnedPatternAuthorizationJson.requireText;

import de.regelsuche.search.program.BudgetedRewriteProgramExecution.PathBudget;
import de.regelsuche.search.program.RewriteCandidate;
import de.regelsuche.search.program.RewriteExecution;
import de.regelsuche.search.program.RewriteProgramInterpreter;
import de.regelsuche.transform.ExecutionWork;
import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Content-addressed deterministic replay evidence for one learned rewrite
 * program.
 *
 * <p>The evidence is bound to the complete genome+plan candidate and retains
 * ordered outputs, complete primitive lineage and the interpreter work ledger.
 * A composed program therefore remains charged for the primitive work of its
 * leaf applications; sequence/repeat composition is never collapsed into one
 * free edge.</p>
 */
public record LearnedRewriteProgramReplayEvidence(
    String schema,
    String candidateHash,
    String genomeHash,
    String genomeAlphaStructuralHash,
    String planHash,
    String planAlphaStructuralHash,
    List<ReplayCase> cases,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.learned-rewrite-program-replay-evidence/v1";

    public LearnedRewriteProgramReplayEvidence {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported learned rewrite-program replay schema");
        }
        for (String value : List.of(
                candidateHash,
                genomeHash,
                genomeAlphaStructuralHash,
                planHash,
                planAlphaStructuralHash,
                contentHash)) {
            requireHash(value, "rewrite-program replay hash");
        }
        cases = canonicalCases(cases);
        String expected = hash(payload(
            schema,
            candidateHash,
            genomeHash,
            genomeAlphaStructuralHash,
            planHash,
            planAlphaStructuralHash,
            cases));
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "rewrite-program replay evidence contentHash mismatch");
        }
    }

    static LearnedRewriteProgramReplayEvidence capture(
        EvolutionRewriteProgramCandidate candidate,
        EvolutionRewriteProgramCompiler.CompiledRewriteProgram compiled,
        List<ReplayInput> inputs
    ) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(compiled, "compiled");
        requireCompiledIdentity(candidate, compiled);
        List<ReplayInput> retainedInputs = canonicalInputs(inputs);
        PathBudget replayBudget = new PathBudget(
            candidate.genome().budget().maxApplicationsPerPath(),
            0);
        RewriteProgramInterpreter interpreter = new RewriteProgramInterpreter();
        List<ReplayCase> replayCases = retainedInputs.stream()
            .map(input -> ReplayCase.capture(
                input.caseId(),
                input.inputExpression(),
                interpreter.executeWithWorkBudget(
                    compiled.program(),
                    input.inputExpression(),
                    replayBudget)))
            .toList();
        Map<String, Object> payload = payload(
            SCHEMA,
            candidate.contentHash(),
            candidate.genome().contentHash(),
            candidate.genome().alphaStructuralHash(),
            candidate.plan().contentHash(),
            candidate.plan().alphaStructuralHash(),
            replayCases);
        return new LearnedRewriteProgramReplayEvidence(
            SCHEMA,
            candidate.contentHash(),
            candidate.genome().contentHash(),
            candidate.genome().alphaStructuralHash(),
            candidate.plan().contentHash(),
            candidate.plan().alphaStructuralHash(),
            replayCases,
            hash(payload));
    }

    public static LearnedRewriteProgramReplayEvidence fromCanonicalJson(
        String json
    ) {
        return LearnedPatternAuthorizationJson.read(
            json,
            LearnedRewriteProgramReplayEvidence.class,
            "learned rewrite-program replay evidence");
    }

    public String toCanonicalJson() {
        return LearnedPatternAuthorizationJson.write(this);
    }

    public List<ReplayInput> replayInputs() {
        return cases.stream()
            .map(value -> new ReplayInput(value.caseId(), value.inputExpression()))
            .toList();
    }

    void requireCandidate(EvolutionRewriteProgramCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (!candidateHash.equals(candidate.contentHash())
                || !genomeHash.equals(candidate.genome().contentHash())
                || !genomeAlphaStructuralHash.equals(
                    candidate.genome().alphaStructuralHash())
                || !planHash.equals(candidate.plan().contentHash())
                || !planAlphaStructuralHash.equals(
                    candidate.plan().alphaStructuralHash())) {
            throw new IllegalArgumentException(
                "rewrite-program replay evidence is bound to a different candidate");
        }
    }

    private static void requireCompiledIdentity(
        EvolutionRewriteProgramCandidate candidate,
        EvolutionRewriteProgramCompiler.CompiledRewriteProgram compiled
    ) {
        if (!compiled.genomeHash().equals(candidate.genome().contentHash())
                || !compiled.genomeAlphaStructuralHash().equals(
                    candidate.genome().alphaStructuralHash())
                || !compiled.planHash().equals(candidate.plan().contentHash())
                || !compiled.planAlphaStructuralHash().equals(
                    candidate.plan().alphaStructuralHash())
                || !compiled.referencedGeneIds().equals(
                    candidate.plan().referencedGeneIds())) {
            throw new IllegalArgumentException(
                "compiled rewrite program differs from candidate identity");
        }
    }

    private static List<ReplayCase> canonicalCases(List<ReplayCase> values) {
        Objects.requireNonNull(values, "cases");
        if (values.isEmpty()) {
            throw new IllegalArgumentException(
                "rewrite-program replay requires at least one case");
        }
        List<ReplayCase> retained = values.stream()
            .map(value -> Objects.requireNonNull(value, "replay case"))
            .sorted(java.util.Comparator.comparing(ReplayCase::caseId))
            .toList();
        if (new HashSet<>(retained.stream().map(ReplayCase::caseId).toList())
                .size() != retained.size()) {
            throw new IllegalArgumentException(
                "rewrite-program replay contains duplicate case ids");
        }
        return List.copyOf(retained);
    }

    private static List<ReplayInput> canonicalInputs(List<ReplayInput> values) {
        Objects.requireNonNull(values, "inputs");
        if (values.isEmpty()) {
            throw new IllegalArgumentException(
                "rewrite-program replay requires at least one input");
        }
        List<ReplayInput> retained = values.stream()
            .map(value -> Objects.requireNonNull(value, "replay input"))
            .sorted(java.util.Comparator.comparing(ReplayInput::caseId))
            .toList();
        if (new HashSet<>(retained.stream().map(ReplayInput::caseId).toList())
                .size() != retained.size()) {
            throw new IllegalArgumentException(
                "rewrite-program replay contains duplicate input ids");
        }
        return List.copyOf(retained);
    }

    private static Map<String, Object> payload(
        String schema,
        String candidateHash,
        String genomeHash,
        String genomeAlphaStructuralHash,
        String planHash,
        String planAlphaStructuralHash,
        List<ReplayCase> cases
    ) {
        Map<String, Object> value = new TreeMap<>();
        value.put("candidateHash", candidateHash);
        value.put("cases", cases);
        value.put("genomeAlphaStructuralHash", genomeAlphaStructuralHash);
        value.put("genomeHash", genomeHash);
        value.put("planAlphaStructuralHash", planAlphaStructuralHash);
        value.put("planHash", planHash);
        value.put("schema", schema);
        return value;
    }

    public record ReplayInput(String caseId, String inputExpression) {
        public ReplayInput {
            requireText(caseId, "caseId");
            requireText(inputExpression, "inputExpression");
        }
    }

    public record ReplayCase(
        String caseId,
        String inputExpression,
        List<CandidateSnapshot> candidates,
        boolean complete,
        String workRevision,
        TransformationWorkMetrics workMetrics,
        PathBudgetSnapshot pathBudget,
        String contentHash
    ) {
        public ReplayCase {
            requireText(caseId, "caseId");
            requireText(inputExpression, "inputExpression");
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
            requireText(workRevision, "workRevision");
            workMetrics = Objects.requireNonNull(workMetrics, "workMetrics");
            pathBudget = Objects.requireNonNull(pathBudget, "pathBudget");
            requireHash(contentHash, "replay case contentHash");
            String expected = hash(casePayload(
                caseId,
                inputExpression,
                candidates,
                complete,
                workRevision,
                workMetrics,
                pathBudget));
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "rewrite-program replay case contentHash mismatch");
            }
        }

        static ReplayCase capture(
            String caseId,
            String inputExpression,
            RewriteExecution execution
        ) {
            List<CandidateSnapshot> snapshots = execution.candidates().stream()
                .map(CandidateSnapshot::capture)
                .toList();
            PathBudgetSnapshot budget = PathBudgetSnapshot.from(execution.pathBudget());
            Map<String, Object> payload = casePayload(
                caseId,
                inputExpression,
                snapshots,
                execution.complete(),
                execution.workRevision(),
                execution.workMetrics(),
                budget);
            return new ReplayCase(
                caseId,
                inputExpression,
                snapshots,
                execution.complete(),
                execution.workRevision(),
                execution.workMetrics(),
                budget,
                hash(payload));
        }
    }

    public record CandidateSnapshot(
        String originNodeId,
        String outputExpression,
        List<String> ruleIds,
        List<String> primitiveRuleIds,
        List<String> assumptions,
        String provenanceHash,
        ExecutionWork executionWork,
        String contentHash
    ) {
        public CandidateSnapshot {
            requireText(originNodeId, "originNodeId");
            requireText(outputExpression, "outputExpression");
            ruleIds = List.copyOf(Objects.requireNonNull(ruleIds, "ruleIds"));
            primitiveRuleIds = List.copyOf(Objects.requireNonNull(
                primitiveRuleIds, "primitiveRuleIds"));
            assumptions = List.copyOf(Objects.requireNonNull(
                assumptions, "assumptions"));
            if (ruleIds.isEmpty() || primitiveRuleIds.isEmpty()) {
                throw new IllegalArgumentException(
                    "replay candidate must retain rule and primitive lineage");
            }
            requireHash(provenanceHash, "provenanceHash");
            executionWork = Objects.requireNonNull(executionWork, "executionWork");
            requireHash(contentHash, "candidate snapshot contentHash");
            String expected = hash(candidatePayload(
                originNodeId,
                outputExpression,
                ruleIds,
                primitiveRuleIds,
                assumptions,
                provenanceHash,
                executionWork));
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "rewrite-program candidate snapshot contentHash mismatch");
            }
        }

        static CandidateSnapshot capture(RewriteCandidate candidate) {
            var transformation = candidate.toTransformation();
            ExecutionWork work = candidate.executionWork();
            Map<String, Object> payload = candidatePayload(
                candidate.originNodeId(),
                candidate.outputExpression(),
                candidate.ruleIds(),
                candidate.primitiveRuleIds(),
                transformation.assumptions(),
                candidate.provenance().contentHash(),
                work);
            return new CandidateSnapshot(
                candidate.originNodeId(),
                candidate.outputExpression(),
                candidate.ruleIds(),
                candidate.primitiveRuleIds(),
                transformation.assumptions(),
                candidate.provenance().contentHash(),
                work,
                hash(payload));
        }
    }

    public record PathBudgetSnapshot(
        boolean present,
        long primitiveRewriteUnits,
        long exactTheoryWorkUnits
    ) {
        public PathBudgetSnapshot {
            if (!present && (primitiveRewriteUnits != 0 || exactTheoryWorkUnits != 0)) {
                throw new IllegalArgumentException(
                    "absent path budget must retain zero units");
            }
            if (primitiveRewriteUnits < 0 || exactTheoryWorkUnits < 0) {
                throw new IllegalArgumentException(
                    "path budget units must not be negative");
            }
        }

        static PathBudgetSnapshot from(PathBudget budget) {
            return budget == null
                ? new PathBudgetSnapshot(false, 0, 0)
                : new PathBudgetSnapshot(
                    true,
                    budget.primitiveRewriteUnits(),
                    budget.exactTheoryWorkUnits());
        }
    }

    private static Map<String, Object> casePayload(
        String caseId,
        String inputExpression,
        List<CandidateSnapshot> candidates,
        boolean complete,
        String workRevision,
        TransformationWorkMetrics workMetrics,
        PathBudgetSnapshot pathBudget
    ) {
        Map<String, Object> value = new TreeMap<>();
        value.put("candidates", candidates);
        value.put("caseId", caseId);
        value.put("complete", complete);
        value.put("inputExpression", inputExpression);
        value.put("pathBudget", pathBudget);
        value.put("workMetrics", workMetrics);
        value.put("workRevision", workRevision);
        return value;
    }

    private static Map<String, Object> candidatePayload(
        String originNodeId,
        String outputExpression,
        List<String> ruleIds,
        List<String> primitiveRuleIds,
        List<String> assumptions,
        String provenanceHash,
        ExecutionWork executionWork
    ) {
        Map<String, Object> value = new TreeMap<>();
        value.put("assumptions", assumptions);
        value.put("executionWork", executionWork);
        value.put("originNodeId", originNodeId);
        value.put("outputExpression", outputExpression);
        value.put("primitiveRuleIds", primitiveRuleIds);
        value.put("provenanceHash", provenanceHash);
        value.put("ruleIds", ruleIds);
        return value;
    }
}
