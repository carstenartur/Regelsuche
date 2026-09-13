package de.regelsuche.discovery.domain;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.discovery.domain.FiniteDifferenceSequenceDomain.*;
import de.regelsuche.discovery.domain.LinearRecurrenceSequenceDomain.*;
import java.util.Objects;

/** Productive, bounded consumer of an immutable verified export. Never authorizes a later lifecycle stage. */
public final class DomainDownstreamValidation {
    public static final String SCHEMA = "regelsuche.domain-downstream-validation/v1";
    public static final String CONTRACT = "regelsuche.builtin-sequence-export-validation/v1";
    private DomainDownstreamValidation() { }

    public record Budget(int maxReplayAttempts, int maxPayloadCharacters, DomainFiniteDataCheck.Budget check) {
        public Budget {
            if (maxReplayAttempts < 0 || maxReplayAttempts > 1 || maxPayloadCharacters < 0 || maxPayloadCharacters > 512) {
                throw new IllegalArgumentException("invalid downstream replay admission");
            }
            Objects.requireNonNull(check, "check");
        }
        public static Budget defaults() { return new Budget(1, 512, DomainFiniteDataCheck.Budget.defaults()); }
    }

    public static final class Receipt {
        private final String canonicalJson, contentHash;
        private Receipt(ObjectNode value) {
            contentHash = DomainCanonical.sha256(DomainExportWorkspace.write(value));
            value.put("contentHash", contentHash);
            canonicalJson = DomainExportWorkspace.write(value);
        }
        public String contentHash() { return contentHash; }
        public String toCanonicalJson() { return canonicalJson; }
    }

    public static Receipt validate(DomainExportWorkspace workspace) { return validate(workspace, Budget.defaults()); }

    public static Receipt validate(DomainExportWorkspace workspace, Budget budget) {
        Objects.requireNonNull(workspace, "workspace"); Objects.requireNonNull(budget, "budget");
        var execution = new Execution(workspace, budget);
        if (budget.maxReplayAttempts() == 0 || !workspace.replaySupported()
                || workspace.evidence().seed().payload().length() > budget.maxPayloadCharacters()) {
            return execution.receipt("INCONCLUSIVE", "SOURCE_EXCEEDS_DOWNSTREAM_REPLAY_ADMISSION");
        }
        execution.replayAttempts++; // Admit the actual call before invoking the historical runner, without changing its budget.
        try {
            var observation = workspace.replayObserved();
            var result = observation.result();
            execution.replayed = result.evidence();
            execution.replayStatus = "IDENTICAL_CANONICAL_EVIDENCE";
            if (result.selectedCandidate().isEmpty()) {
                return execution.receipt("NOT_EVALUATED_NO_SELECTED_CANDIDATE", "SOURCE_RETAINS_NO_SELECTED_TYPED_CANDIDATE");
            }
            execution.selectedBound = true;
            Object candidate = result.selectedCandidate().orElseThrow(), witness = result.selectedCertificate().orElseThrow();
            if (candidate instanceof FiniteDifferenceCandidate c && witness instanceof FiniteDifferenceCertificate k) {
                execution.check = DomainFiniteDataCheck.check(c, k, budget.check());
            } else if (candidate instanceof LinearRecurrenceCandidate c && witness instanceof LinearRecurrenceCertificate k) {
                execution.check = DomainFiniteDataCheck.check(c, k, budget.check());
            } else return execution.receipt("UNSUPPORTED", "NO_INDEPENDENT_CHECKER_FOR_SELECTED_TYPES");
            return execution.receipt(execution.check.status().name(), "INDEPENDENT_FINITE_CHECK_RETURNED");
        } catch (DomainExportWorkspace.ReplayMismatch mismatch) {
            execution.replayed = mismatch.actual(); execution.replayStatus = "MISMATCH";
            return execution.receipt("REPLAY_MISMATCH", "COMPLETE_CANONICAL_SOURCE_OR_SELECTED_OBJECT_BINDING_DIFFERS");
        } catch (RuntimeException failure) {
            execution.failureClass = failure.getClass().getName();
            if (execution.replayed == null) execution.replayStatus = "TECHNICAL_FAILURE";
            return execution.receipt("TECHNICAL_FAILURE", "EXECUTION_DID_NOT_RETURN_A_CHECK_RESULT");
        }
    }

    private static final class Execution {
        final DomainExportWorkspace workspace;
        final Budget budget;
        int replayAttempts;
        String replayStatus = "NOT_EVALUATED", failureClass = "";
        boolean selectedBound;
        DomainDiscoveryEvidence replayed;
        DomainFiniteDataCheck.Result check;
        Execution(DomainExportWorkspace workspace, Budget budget) { this.workspace = workspace; this.budget = budget; }

        Receipt receipt(String status, String detail) {
            var snapshot = workspace.snapshot(); var manifest = snapshot.manifest(); var source = workspace.evidence();
            ObjectNode value = DomainExportWorkspace.JSON.createObjectNode();
            value.put("schema", SCHEMA).put("consumerContract", CONTRACT).put("status", status).put("detail", detail)
                .put("failureClass", failureClass).put("sourceOutcome", source.outcome().name());
            ObjectNode binding = value.putObject("source");
            binding.put("runId", workspace.runId()).put("domainId", manifest.domainId()).put("domainRevision", manifest.domainRevision())
                .put("manifestHash", manifest.contentHash()).put("manifestByteHash", snapshot.verification().manifestByteHash())
                .put("verificationHash", snapshot.verification().contentHash()).put("workspaceHash", workspace.contentHash())
                .put("descriptorHash", manifest.domainDescriptorHash()).put("seedHash", source.seed().contentHash())
                .put("evidenceHash", source.contentHash()).put("handoffHash", manifest.lifecycleHandoffHash());
            binding.set("artifactBytes", DomainExportWorkspace.read(manifest.toCanonicalJson()).required("artifacts"));
            nullable(binding, "selectedCandidateHash", source.selectedCandidateHash().isEmpty() ? null : source.selectedCandidateHash());
            nullable(binding, "certificateObjectHash", source.certificate() == null ? null : source.certificate().certificateObjectHash());
            nullable(binding, "renderedCertificateHash", source.certificate() == null ? null : source.certificate().contentHash());
            nullable(value, "verifiedCandidateHash", selectedBound ? source.selectedCandidateHash() : null);
            nullable(value, "verifiedCertificateObjectHash", selectedBound ? source.certificate().certificateObjectHash() : null);
            nullable(value, "verifiedRenderedCertificateHash", selectedBound ? source.certificate().contentHash() : null);

            ObjectNode replay = value.putObject("replay");
            replay.put("status", replayStatus).put("maxPayloadCharacters", budget.maxPayloadCharacters());
            replay.putObject("admission").put("configured", budget.maxReplayAttempts()).put("executed", replayAttempts)
                .put("skipped", 0).put("remaining", budget.maxReplayAttempts()-replayAttempts);
            nullable(replay, "actualEvidenceHash", replayed == null ? null : replayed.contentHash());
            if (replayed == null) replay.putNull("resources");
            else replay.set("resources", DomainExportWorkspace.read(replayed.toCanonicalJson()).required("resources"));
            replay.put("resourceObservation", replayed == null ? "UNAVAILABLE" : "RETURNED_RUNNER_ROLE_COUNTS")
                .put("internalArithmeticWork", "UNAVAILABLE");
            value.putObject("checkBudget").put("maxTermChecks", budget.check().maxTermChecks())
                .put("maxScalarOperations", budget.check().maxScalarOperations()).put("maxScalarBits", budget.check().maxScalarBits());
            if (check == null) value.putNull("check");
            else value.set("check", DomainExportWorkspace.read(check.toCanonicalJson()));
            value.put("evaluationStatus", check == null ? "NOT_EVALUATED" : switch (check.status()) {
                case CONFIRMED_FINITE_DATA -> "CONFIRMED"; case REFUTED_FINITE_DATA -> "REFUTED";
                case UNSUPPORTED -> "UNSUPPORTED"; default -> "INCONCLUSIVE";
            });
            value.put("counterexampleStatus", check == null ? "NOT_EVALUATED"
                : DomainExportWorkspace.read(check.toCanonicalJson()).required("counterexampleStatus").asText());
            value.put("proofStatus", "NOT_EVALUATED").put("universalProofStatus", "NOT_PRODUCED")
                .put("externalNoveltyStatus", "NOT_EVALUATED").put("promotionStatus", "NOT_EVALUATED").put("publicEvidenceStatus", "NOT_EVALUATED");
            ArrayNode roles = value.putArray("artifacts");
            role(roles, "SOURCE_LIFECYCLE_HANDOFF", "AVAILABLE", DiscoveryLifecycleHandoff.SCHEMA, manifest.lifecycleHandoffHash());
            for (String name : new String[]{"VALIDATION_EVIDENCE", "FINITE_COUNTEREXAMPLE_CHECK"}) {
                role(roles, name, check == null ? "NOT_EVALUATED" : "AVAILABLE", check == null ? null : DomainFiniteDataCheck.SCHEMA,
                    check == null ? null : check.contentHash());
            }
            for (String name : new String[]{"PROOF_OBLIGATIONS", "UNIVERSAL_PROOF"}) role(roles, name, "NOT_PRODUCED", null, null);
            for (String name : new String[]{"EXTERNAL_NOVELTY", "PROMOTION", "PUBLIC_EVIDENCE"}) role(roles, name, "NOT_EVALUATED", null, null);
            value.put("claimBoundary", "Source identity, deterministic replay and independent retained finite-data checking only. "
                + "No universal proof, external novelty, promotion, Public Evidence or later lifecycle authorization; total work remains unavailable.");
            return new Receipt(value);
        }
    }

    private static void nullable(ObjectNode value, String key, String text) { if (text == null) value.putNull(key); else value.put(key, text); }
    private static void role(ArrayNode roles, String name, String status, String schema, String hash) {
        ObjectNode role = roles.addObject().put("role", name).put("status", status);
        nullable(role, "artifactSchema", schema); nullable(role, "targetContentHash", hash);
    }
}
