package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.ReferenceIndependentCandidateValidation.*;

import de.regelsuche.discovery.representation.TargetFreeIntrinsicCandidateValidator.IntrinsicValidation;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.validation.OracleValidator;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

/** Validates retained inputs; never forms candidates or opens qualification. */
public final class ReferenceIndependentCandidateValidationRunner {
    private ReferenceIndependentCandidateValidationRunner() {
    }

    public static Artifact run(String planJson, String freezeJson, String expectedFreezeHash,
                               String repositoryRevision, Budget budget) {
        try (var oracle = new ReferenceIndependentOracleSession(budget.timeoutMillis())) {
            return run(planJson, freezeJson, expectedFreezeHash, repositoryRevision, budget, oracle);
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 9 && args[0].equals("run")) {
            Path plan = Path.of(args[1]);
            Path freeze = Path.of(args[2]);
            Path output = Path.of(args[8]);
            if (Files.exists(output) && (Files.isSameFile(output, plan) || Files.isSameFile(output, freeze))) {
                throw new IllegalArgumentException("output must not replace frozen input artifacts");
            }
            String planJson = readArtifact(plan);
            String freezeJson = readArtifact(freeze);
            var budget = new Budget(Integer.parseInt(args[5]), Integer.parseInt(args[6]),
                Integer.parseInt(args[7]));
            var artifact = run(planJson, freezeJson, args[3], args[4], budget);
            ReferenceIndependentCandidateValidationVerifier.verifyBindings(
                planJson, freezeJson, args[3], artifact.toCanonicalJson());
            writeNewOrIdentical(output, artifact.toCanonicalJson());
            System.out.println("referenceIndependentValidationHash=" + artifact.contentHash());
            System.out.println("referenceIndependentValidationRows=" + artifact.content().summary().rows());
            System.out.println("referenceIndependentValidationCandidates=" + artifact.content().summary().candidates());
        } else if (args.length == 5 && args[0].equals("verify")) {
            var artifact = ReferenceIndependentCandidateValidationVerifier.verifyReplay(
                readArtifact(Path.of(args[1])), readArtifact(Path.of(args[2])),
                args[3], readArtifact(Path.of(args[4])));
            System.out.println("referenceIndependentReplayVerifiedHash=" + artifact.contentHash());
        } else {
            throw new IllegalArgumentException("usage: run <plan.json[.gz]> <freeze.json[.gz]> "
                + "<freeze-hash> <implementation-commit> <max-oracle-calls> <max-input-characters> "
                + "<timeout-ms> <output.json> | verify <plan> <freeze> <freeze-hash> <validation.json>");
        }
    }

    static String readArtifact(Path path) throws IOException {
        try (var raw = Files.newInputStream(path);
             var input = path.toString().endsWith(".gz") ? new GZIPInputStream(raw) : raw) {
            byte[] bytes = input.readNBytes(32 * 1024 * 1024 + 1);
            if (bytes.length > 32 * 1024 * 1024) {
                throw new IOException("artifact exceeds 32 MiB bounded input limit");
            }
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        }
    }

    static void writeNewOrIdentical(Path output, String json) throws IOException {
        Path target = output.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        if (Files.exists(target)) {
            if (!Files.readString(target, StandardCharsets.UTF_8).equals(json)) {
                throw new IOException("refusing to replace different retained evidence");
            }
            return;
        }
        Files.writeString(target, json, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    static Artifact run(String planJson, String freezeJson, String expectedFreezeHash,
                        String repositoryRevision, Budget budget, OracleValidator oracle) {
        Objects.requireNonNull(oracle, "oracle");
        Objects.requireNonNull(budget, "budget");
        if (repositoryRevision == null || !repositoryRevision.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("implementation revision must be a full Git commit");
        }
        PlanArtifact plan = PlanArtifact.fromCanonicalJson(planJson);
        FreezeArtifact freeze = FreezeArtifact.fromCanonicalJson(freezeJson);
        requireInputs(plan, freeze, expectedFreezeHash);
        var cases = plan.content().cases().stream()
            .collect(Collectors.toUnmodifiableMap(CaseSpec::id, Function.identity()));
        List<Row> rows = new ArrayList<>();
        int usedCalls = 0;
        for (FreezeRow frozen : freeze.content().rows()) {
            String source = cases.get(frozen.caseId()).sourceExpression();
            List<Outcome> outcomes = new ArrayList<>();
            for (CandidateEvidence retained : frozen.candidates()) {
                Candidate candidate = Candidate.fromFrozen(retained);
                int characters = Math.addExact(source.length(), candidate.expression().length());
                String refusal = !candidate.equivalencePreserving()
                    ? "NOT_EQUIVALENCE_PRESERVING"
                    : characters > budget.maxInputCharacters() ? "INPUT_BUDGET_EXCEEDED"
                    : usedCalls >= budget.maxOracleCalls() ? "ORACLE_BUDGET_EXHAUSTED" : "";
                IntrinsicValidation validation;
                Work work;
                if (!refusal.isEmpty()) {
                    validation = refused(candidate, refusal);
                    work = new Work(1, 0, 0, 0);
                } else {
                    usedCalls++;
                    validation = TargetFreeIntrinsicCandidateValidator.validate(source,
                        candidate.expression(), candidate.assumptions(), true, oracle);
                    work = new Work(1, 1,
                        validation.oracleStatus().startsWith("VALIDATOR_ERROR_") ? 0 : 1,
                        characters);
                }
                outcomes.add(new Outcome(candidate,
                    inputHash(frozen.configurationId(), source, candidate), "$",
                    pathHash(candidate), "FROZEN_LINEAGE_NOT_REPLAYED",
                    source.equals(candidate.expression()) ? "EXACT_SOURCE_BYTES_EQUAL"
                        : "DIFFERENT_SOURCE_BYTES",
                    validation, refusal.isEmpty() ? terminal(validation) : refusal,
                    strength(validation), "NOT_ESTABLISHED", hash(validation), work));
            }
            rows.add(new Row(frozen.sequence(), frozen.configurationId(), source, hash(source),
                frozen.candidateBatchHash(), frozen.candidateSetHash(),
                frozen.candidateFreezeReceiptHash(), frozen.work().contentHash(),
                outcomes, Work.sum(outcomes.stream().map(Outcome::work).toList())));
        }
        return Artifact.create(new Content(SCHEMA, EVIDENCE_SCOPE, repositoryRevision,
            freeze.content().repositoryRevision(), plan.contentHash(), freeze.contentHash(),
            ORACLE_REVISION, WORK_AUTHORITY, budget, rows, Summary.derive(rows), CLAIM_BOUNDARY));
    }

    static void requireInputs(PlanArtifact plan, FreezeArtifact freeze, String expectedFreezeHash) {
        if (!freeze.contentHash().equals(expectedFreezeHash)
                || !freeze.content().planHash().equals(plan.contentHash())
                || !freeze.content().repositoryRevision().equals(plan.content().repositoryRevision())) {
            throw new IllegalArgumentException("candidate freeze is not the expected plan authority");
        }
        var cases = plan.content().cases().stream()
            .collect(Collectors.toUnmodifiableMap(CaseSpec::id, Function.identity()));
        for (int index = 0; index < 144; index++) {
            var frozen = freeze.content().rows().get(index);
            var planned = plan.content().rows().get(index);
            if (!frozen.configurationId().equals(planned.configurationId())
                    || !frozen.caseId().equals(planned.caseId())
                    || !frozen.policyId().equals(planned.policyId())
                    || frozen.checkpoint() != planned.checkpoint()
                    || !cases.containsKey(frozen.caseId())) {
                throw new IllegalArgumentException("frozen row does not match plan");
            }
            String source = cases.get(frozen.caseId()).sourceExpression();
            for (CandidateEvidence candidate : frozen.candidates()) {
                if (!source.equals(candidate.pathExpressions().getFirst())
                        || !candidate.expression().equals(candidate.pathExpressions().getLast())
                        || candidate.depth() != candidate.primitiveRuleIds().size()) {
                    throw new IllegalArgumentException("candidate lineage does not bind its source and result");
                }
            }
        }
    }

    static IntrinsicValidation refused(Candidate candidate, String reason) {
        var scope = !candidate.equivalencePreserving()
            ? TargetFreeIntrinsicCandidateValidator.ValidationScope.NOT_APPLICABLE
            : candidate.assumptions().isEmpty()
                ? TargetFreeIntrinsicCandidateValidator.ValidationScope.UNCONDITIONAL
                : TargetFreeIntrinsicCandidateValidator.ValidationScope.CONDITIONAL_ASSUMPTIONS_NOT_EVALUATED;
        return new IntrinsicValidation(CandidateProofStatus.OBSERVED, "NOT_RUN_" + reason,
            "Validation was not admitted: " + reason, scope, candidate.assumptions());
    }

    static String terminal(IntrinsicValidation value) {
        if (value.oracleStatus().startsWith("VALIDATOR_ERROR_")) {
            return value.oracleStatus().equals("VALIDATOR_ERROR_OracleTimeoutException")
                ? "TIMEOUT" : "TECHNICAL_FAILURE";
        }
        if (value.oracleStatus().equals("UNAVAILABLE")) {
            return "UNSUPPORTED";
        }
        if (value.conditionalValidityUnresolved()) {
            return "CONDITIONAL_ASSUMPTIONS_NOT_EVALUATED";
        }
        return value.intrinsicallyVerified() ? "ORACLE_AGREE"
            : value.intrinsicallyRejected() ? "REFUTED" : "UNRESOLVED";
    }

    static String strength(IntrinsicValidation value) {
        return switch (value.oracleEvidence()) {
            case "validated by deterministic numeric samples",
                 "not equivalent under deterministic numeric samples" -> "DETERMINISTIC_NUMERIC_SAMPLES";
            case "matching normalized quadratic coefficients" -> "NORMALIZED_QUADRATIC_COEFFICIENTS";
            default -> "NO_CLASSIFIED_INDEPENDENT_EQUALITY_EVIDENCE";
        };
    }
}
