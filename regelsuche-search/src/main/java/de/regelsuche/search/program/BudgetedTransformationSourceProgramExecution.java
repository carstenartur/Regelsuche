package de.regelsuche.search.program;

import de.regelsuche.search.program.BudgetedTransformationSource.ExactTheoryTransition;
import de.regelsuche.search.program.BudgetedTransformationSource.Status;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Content-addressed result of executing one top-level
 * {@link RewriteProgram.BudgetedSource}.
 *
 * <p>The exact-theory candidates remain separate from ordinary
 * {@code Transformation} instances. Mathematical work stays on each transition;
 * interpreter and delegated source mechanics are retained in a separate
 * ledger.</p>
 */
public record BudgetedTransformationSourceProgramExecution(
    String executionRevision,
    RewriteProgram.NodeMetadata programMetadata,
    BudgetedTransformationSourceExecutor.Execution sourceExecution,
    List<ExactTheoryCandidate> candidates,
    ProgramWork programWork,
    String contentHash
) {
    public static final String EXECUTION_REVISION =
        "regelsuche.budgeted-transformation-source-program-execution/v1";

    public BudgetedTransformationSourceProgramExecution {
        if (!EXECUTION_REVISION.equals(executionRevision)) {
            throw new IllegalArgumentException(
                "unsupported budgeted program execution revision");
        }
        programMetadata = Objects.requireNonNull(
            programMetadata,
            "programMetadata");
        sourceExecution = Objects.requireNonNull(
            sourceExecution,
            "sourceExecution");
        candidates = normalizeCandidates(candidates);
        requireCandidateProjection(
            programMetadata.id(),
            sourceExecution,
            candidates);
        programWork = Objects.requireNonNull(programWork, "programWork");
        ProgramWork expectedWork = ProgramWork.forExecution(sourceExecution);
        if (!expectedWork.equals(programWork)) {
            throw new IllegalArgumentException(
                "budgeted program work differs from delegated execution");
        }
        contentHash = requireSha256(contentHash, "contentHash");
        String expectedHash = executionHash(
            executionRevision,
            programMetadata,
            sourceExecution,
            candidates,
            programWork);
        if (!expectedHash.equals(contentHash)) {
            throw new IllegalArgumentException(
                "budgeted program execution contentHash differs from contents");
        }
    }

    static BudgetedTransformationSourceProgramExecution create(
        RewriteProgram.BudgetedSource program,
        BudgetedTransformationSourceExecutor.Execution sourceExecution
    ) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(sourceExecution, "sourceExecution");
        List<ExactTheoryCandidate> candidates = sourceExecution.candidates()
            .stream()
            .map(transition -> ExactTheoryCandidate.create(
                program.id(),
                transition))
            .sorted(Comparator.comparing(ExactTheoryCandidate::contentHash))
            .toList();
        ProgramWork work = ProgramWork.forExecution(sourceExecution);
        return new BudgetedTransformationSourceProgramExecution(
            EXECUTION_REVISION,
            program.metadata(),
            sourceExecution,
            candidates,
            work,
            executionHash(
                EXECUTION_REVISION,
                program.metadata(),
                sourceExecution,
                candidates,
                work));
    }

    public Status status() {
        return sourceExecution.status();
    }

    public boolean complete() {
        return sourceExecution.complete();
    }

    public String inputExpression() {
        return sourceExecution.inputExpression();
    }

    public long availableMathematicalWorkUnits() {
        return sourceExecution.availableMathematicalWorkUnits();
    }

    public long minimumRequiredMathematicalWorkUnits() {
        return sourceExecution.sourceResult()
            .minimumRequiredMathematicalWorkUnits();
    }

    public String detailCode() {
        return sourceExecution.sourceResult().detailCode();
    }

    private static List<ExactTheoryCandidate> normalizeCandidates(
        List<ExactTheoryCandidate> candidates
    ) {
        Objects.requireNonNull(candidates, "candidates");
        List<ExactTheoryCandidate> normalized = candidates.stream()
            .map(candidate -> Objects.requireNonNull(candidate, "candidate"))
            .sorted(Comparator.comparing(ExactTheoryCandidate::contentHash))
            .toList();
        if (new HashSet<>(normalized.stream()
                .map(ExactTheoryCandidate::contentHash)
                .toList()).size() != normalized.size()) {
            throw new IllegalArgumentException(
                "budgeted program candidate identities must be unique");
        }
        return List.copyOf(normalized);
    }

    private static void requireCandidateProjection(
        String programId,
        BudgetedTransformationSourceExecutor.Execution execution,
        List<ExactTheoryCandidate> candidates
    ) {
        if (candidates.stream().anyMatch(candidate ->
                !programId.equals(candidate.originNodeId()))) {
            throw new IllegalArgumentException(
                "budgeted program candidate has another origin node");
        }
        List<String> expected = execution.candidates().stream()
            .map(ExactTheoryTransition::contentHash)
            .sorted()
            .toList();
        List<String> actual = candidates.stream()
            .map(candidate -> candidate.transition().contentHash())
            .sorted()
            .toList();
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(
                "budgeted program candidates differ from source execution");
        }
    }

    private static String executionHash(
        String revision,
        RewriteProgram.NodeMetadata metadata,
        BudgetedTransformationSourceExecutor.Execution sourceExecution,
        List<ExactTheoryCandidate> candidates,
        ProgramWork work
    ) {
        StringBuilder material = new StringBuilder();
        append(material, revision);
        append(material, metadata.id());
        append(material, metadata.label());
        append(material, metadata.sourceLocation().sourceName());
        append(material, Integer.toString(metadata.sourceLocation().line()));
        append(material, Integer.toString(metadata.sourceLocation().column()));
        append(material, sourceExecution.contentHash());
        candidates.forEach(candidate ->
            append(material, candidate.contentHash()));
        append(material, Long.toString(work.interpreterInvocations()));
        append(material, Long.toString(work.programNodeVisits()));
        append(material, Long.toString(work.executorDelegations()));
        append(material, Long.toString(work.candidateProjectionVisits()));
        append(material, Long.toString(
            work.delegatedMechanicalWorkUnits()));
        append(material, Long.toString(work.totalMechanicalWorkUnits()));
        return sha256(material.toString());
    }

    /** One program-origin-bound exact-theory candidate. */
    public record ExactTheoryCandidate(
        String originNodeId,
        ExactTheoryTransition transition,
        String contentHash
    ) {
        public ExactTheoryCandidate {
            originNodeId = requireText(originNodeId, "originNodeId");
            transition = Objects.requireNonNull(transition, "transition");
            contentHash = requireSha256(contentHash, "contentHash");
            String expected = candidateHash(originNodeId, transition);
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "exact-theory program candidate hash differs from contents");
            }
        }

        private static ExactTheoryCandidate create(
            String originNodeId,
            ExactTheoryTransition transition
        ) {
            String origin = requireText(originNodeId, "originNodeId");
            ExactTheoryTransition checked = Objects.requireNonNull(
                transition,
                "transition");
            return new ExactTheoryCandidate(
                origin,
                checked,
                candidateHash(origin, checked));
        }

        public String sourceExpression() {
            return transition.sourceExpression();
        }

        public String transformedExpression() {
            return transition.transformedExpression();
        }

        public String theoryStepId() {
            return transition.theoryStepId();
        }

        public String evidenceHash() {
            return transition.evidenceHash();
        }

        public List<String> assumptions() {
            return transition.assumptions();
        }

        public long mathematicalWorkUnits() {
            return transition.mathematicalWorkUnits();
        }

        public String applicationKey() {
            return transition.applicationKey();
        }

        public int primitiveRewriteSteps() {
            return 0;
        }

        public List<String> primitiveRuleIds() {
            return List.of();
        }

        public int exactTheorySteps() {
            return 1;
        }

        public List<String> exactTheoryStepIds() {
            return List.of(transition.theoryStepId());
        }

        private static String candidateHash(
            String originNodeId,
            ExactTheoryTransition transition
        ) {
            StringBuilder material = new StringBuilder();
            append(material, EXECUTION_REVISION);
            append(material, "exact-theory-program-candidate");
            append(material, originNodeId);
            append(material, transition.contentHash());
            return sha256(material.toString());
        }
    }

    /**
     * Mechanical work introduced by the top-level program layer.
     *
     * <p>The delegated executor work remains a separately identified component;
     * candidate mathematical work is not included here.</p>
     */
    public record ProgramWork(
        long interpreterInvocations,
        long programNodeVisits,
        long executorDelegations,
        long candidateProjectionVisits,
        long delegatedMechanicalWorkUnits,
        long totalMechanicalWorkUnits
    ) {
        public ProgramWork {
            if (interpreterInvocations != 1
                    || programNodeVisits != 1
                    || executorDelegations != 1
                    || candidateProjectionVisits < 0
                    || delegatedMechanicalWorkUnits < 0) {
                throw new IllegalArgumentException(
                    "budgeted program mechanical work is inconsistent");
            }
            long expected = Math.addExact(
                Math.addExact(
                    interpreterInvocations,
                    programNodeVisits),
                Math.addExact(
                    executorDelegations,
                    Math.addExact(
                        candidateProjectionVisits,
                        delegatedMechanicalWorkUnits)));
            if (totalMechanicalWorkUnits != expected) {
                throw new IllegalArgumentException(
                    "budgeted program total mechanical work is inconsistent");
            }
        }

        private static ProgramWork forExecution(
            BudgetedTransformationSourceExecutor.Execution execution
        ) {
            long candidateCount = execution.candidates().size();
            long delegated = execution.mechanicalWork()
                .totalMechanicalWorkUnits();
            long total = Math.addExact(
                3L,
                Math.addExact(candidateCount, delegated));
            return new ProgramWork(
                1,
                1,
                1,
                candidateCount,
                delegated,
                total);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String requireSha256(String value, String name) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                name + " must be a lowercase SHA-256 identifier");
        }
        return value;
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.getBytes(StandardCharsets.UTF_8).length)
            .append(':')
            .append(value);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
