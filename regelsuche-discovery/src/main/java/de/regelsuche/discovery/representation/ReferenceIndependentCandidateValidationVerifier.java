package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.ReferenceIndependentCandidateValidation.*;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.validation.OracleValidator;
import java.util.HashSet;

/** Verifies a validation companion against its external frozen authority. */
public final class ReferenceIndependentCandidateValidationVerifier {
    private ReferenceIndependentCandidateValidationVerifier() {
    }

    public static ReferenceIndependentCandidateValidation.Artifact verifyBindings(
        String planJson, String freezeJson, String expectedFreezeHash, String validationJson
    ) {
        var plan = PlanArtifact.fromCanonicalJson(planJson);
        var freeze = FreezeArtifact.fromCanonicalJson(freezeJson);
        ReferenceIndependentCandidateValidationRunner.requireInputs(plan, freeze, expectedFreezeHash);
        var artifact = Artifact.fromCanonicalJson(validationJson);
        var content = artifact.content();
        require(content.planHash().equals(plan.contentHash())
            && content.candidateFreezeHash().equals(freeze.contentHash())
            && content.formationRepositoryRevision().equals(freeze.content().repositoryRevision()),
            "companion does not bind the supplied frozen authority");
        int admittedCalls = 0;
        for (int index = 0; index < 144; index++) {
            var frozen = freeze.content().rows().get(index);
            var row = content.rows().get(index);
            String source = plan.content().cases().stream()
                .filter(value -> value.id().equals(frozen.caseId()))
                .findFirst().orElseThrow().sourceExpression();
            require(row.configurationId().equals(frozen.configurationId())
                && row.sourceExpression().equals(source)
                && row.candidateBatchHash().equals(frozen.candidateBatchHash())
                && row.candidateSetHash().equals(frozen.candidateSetHash())
                && row.candidateFreezeReceiptHash().equals(frozen.candidateFreezeReceiptHash())
                && row.formationWorkHash().equals(frozen.work().contentHash()),
                "companion row authority mismatch");
            require(row.outcomes().size() == frozen.candidates().size(),
                "candidate freeze and validation candidate sets differ");
            var identities = new HashSet<String>();
            for (int candidateIndex = 0; candidateIndex < row.outcomes().size(); candidateIndex++) {
                var outcome = row.outcomes().get(candidateIndex);
                var candidate = outcome.candidate();
                require(identities.add(candidate.candidateHash())
                    && candidate.equals(Candidate.fromFrozen(frozen.candidates().get(candidateIndex))),
                    "candidate freeze and validation lineages differ");
                require(outcome.validation().assumptions().equals(
                    AssumptionSignature.ofExpressions(candidate.assumptions()).normalizedAssumptions()),
                    "validation assumptions differ from the frozen candidate");
                require(outcome.exactSourceEquality().equals(source.equals(candidate.expression())
                        ? "EXACT_SOURCE_BYTES_EQUAL" : "DIFFERENT_SOURCE_BYTES")
                    && outcome.evidenceStrength().equals(
                        ReferenceIndependentCandidateValidationRunner.strength(outcome.validation())),
                    "equality evidence classification mismatch");
                long characters = (long) source.length() + candidate.expression().length();
                String refusal = !candidate.equivalencePreserving() ? "NOT_EQUIVALENCE_PRESERVING"
                    : characters > content.budget().maxInputCharacters() ? "INPUT_BUDGET_EXCEEDED"
                    : admittedCalls >= content.budget().maxOracleCalls() ? "ORACLE_BUDGET_EXHAUSTED" : "";
                if (refusal.isEmpty()) {
                    admittedCalls++;
                    int completed = outcome.validation().oracleStatus()
                        .startsWith("VALIDATOR_ERROR_") ? 0 : 1;
                    require(!outcome.validation().oracleStatus().startsWith("NOT_RUN_")
                        && outcome.work().equals(new Work(1, 1, completed, characters))
                        && outcome.terminalReason().equals(
                            ReferenceIndependentCandidateValidationRunner.terminal(outcome.validation())),
                        "admitted validation work or terminal reason mismatch");
                } else {
                    require(outcome.work().equals(new Work(1, 0, 0, 0))
                        && outcome.terminalReason().equals(refusal)
                        && outcome.validation().equals(
                            ReferenceIndependentCandidateValidationRunner.refused(candidate, refusal)),
                        "refused validation work or terminal reason mismatch");
                }
            }
        }
        return artifact;
    }

    /** Re-executes every admitted oracle invocation; hashes alone are not authenticity. */
    public static Artifact verifyReplay(String planJson, String freezeJson, String expectedFreezeHash,
                                        String validationJson) {
        var checked = verifyBindings(planJson, freezeJson, expectedFreezeHash, validationJson);
        try (var oracle = new ReferenceIndependentOracleSession(checked.content().budget().timeoutMillis())) {
            return verifyReplay(planJson, freezeJson, expectedFreezeHash, validationJson, oracle);
        }
    }

    static Artifact verifyReplay(String planJson, String freezeJson, String expectedFreezeHash,
                                 String validationJson, OracleValidator oracle) {
        var artifact = verifyBindings(planJson, freezeJson, expectedFreezeHash, validationJson);
        var replay = ReferenceIndependentCandidateValidationRunner.run(planJson, freezeJson,
            expectedFreezeHash, artifact.content().repositoryRevision(), artifact.content().budget(), oracle);
        require(replay.equals(artifact), "oracle evidence did not reproduce from the frozen inputs");
        return artifact;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
