package de.regelsuche.discovery.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.discovery.domain.FiniteDifferenceSequenceDomain.FiniteDifferenceCandidate;
import de.regelsuche.discovery.domain.FiniteDifferenceSequenceDomain.FiniteDifferenceCertificate;
import de.regelsuche.discovery.domain.LinearRecurrenceSequenceDomain.LinearRecurrenceCandidate;
import de.regelsuche.discovery.domain.LinearRecurrenceSequenceDomain.LinearRecurrenceCertificate;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.RecurrenceObservationKrylov;
import de.regelsuche.math.algorithms.linalg.RecurrenceObservationKrylov.Result;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Source-bound model theorems; finite source observations never establish an unspecified infinite continuation. */
public final class DomainModelProof {
    public static final String SCHEMA = "regelsuche.domain-model-proof/v1";
    public static final String CONTRACT = "regelsuche.builtin-sequence-affine-model-proof/v1";
    public static final String CHECKER_CONTRACT = "regelsuche.recurrence-observation-krylov-model-proof/v1";
    private static final int MAX_PHASE_WORK = 200_000;
    private static final int MAX_RECEIPT_BYTES = 1_048_576;
    private static final Set<String> BUDGET_FIELDS = Set.of("maxReplayAttempts", "maxPayloadCharacters", "finiteCheck",
        "maxPreparationWorkUnits", "maxBindingWorkUnits", "maxQueryWorkUnits");
    private static final Set<String> FINITE_BUDGET_FIELDS = Set.of("maxTermChecks", "maxScalarOperations", "maxScalarBits");
    private static final String CLAIM_BOUNDARY = "The checked theorem concerns the explicit affine candidate model only. "
        + "Exact model terms beyond retained data are conditional predictions. Finite observations establish neither "
        + "a unique infinite continuation nor that the original source follows this model. No formal proof, external "
        + "novelty, promotion, Public Evidence, release qualification or comparative total-work claim is authorized.";

    private DomainModelProof() { }

    public enum Status {
        PROVED_MODEL, INCONCLUSIVE, UNSUPPORTED, NOT_EVALUATED_NO_SELECTED_CANDIDATE,
        FINITE_CHECK_NOT_CONFIRMED, REPLAY_MISMATCH, TECHNICAL_FAILURE
    }

    /** Every phase has an independent hard ceiling. Unknown arithmetic and encoding work is not added to a total. */
    public record Budget(int maxReplayAttempts, int maxPayloadCharacters, DomainFiniteDataCheck.Budget finiteCheck,
            int maxPreparationWorkUnits, int maxBindingWorkUnits, int maxQueryWorkUnits) {
        public Budget {
            if (maxReplayAttempts < 0 || maxReplayAttempts > 1 || maxPayloadCharacters < 0 || maxPayloadCharacters > 512) {
                throw new IllegalArgumentException("invalid model-proof replay admission");
            }
            Objects.requireNonNull(finiteCheck, "finiteCheck");
            for (int phase : new int[] {maxPreparationWorkUnits, maxBindingWorkUnits, maxQueryWorkUnits}) {
                if (phase < 0 || phase > MAX_PHASE_WORK) throw new IllegalArgumentException("invalid model-proof phase budget");
            }
        }

        public static Budget defaults() {
            return new Budget(1, 512, DomainFiniteDataCheck.Budget.defaults(), MAX_PHASE_WORK, MAX_PHASE_WORK, MAX_PHASE_WORK);
        }
    }

    /** Only actual admitted executions issue a receipt. A serialized receipt is not a verified mathematical plan. */
    public static final class Receipt {
        private final Status status;
        private final String canonicalJson, contentHash;

        private Receipt(Status status, ObjectNode value) {
            this.status = status;
            this.contentHash = DomainCanonical.sha256(DomainExportWorkspace.write(value));
            value.put("contentHash", contentHash);
            this.canonicalJson = DomainExportWorkspace.write(value);
        }

        public Status status() { return status; }
        public String contentHash() { return contentHash; }
        public String toCanonicalJson() { return canonicalJson; }
    }

    public static Receipt prove(DomainExportWorkspace workspace) { return prove(workspace, Budget.defaults()); }

    public static Receipt prove(DomainExportWorkspace workspace, Budget budget) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(budget, "budget");
        var execution = new Execution(workspace, budget);
        if (workspace.scopedLogicDomain()) {
            return execution.receipt(Status.UNSUPPORTED, workspace.replayUnavailableReason());
        }
        if (budget.maxReplayAttempts() == 0 || !workspace.replaySupported()
                || workspace.evidence().seed().payload().length() > budget.maxPayloadCharacters()) {
            return execution.receipt(Status.INCONCLUSIVE, "SOURCE_EXCEEDS_MODEL_PROOF_REPLAY_ADMISSION");
        }
        execution.replayAttempts++;
        try {
            var result = workspace.replayObserved().result();
            execution.replayed = result.evidence();
            execution.replayStatus = "IDENTICAL_CANONICAL_EVIDENCE";
            if (result.selectedCandidate().isEmpty()) {
                return execution.receipt(Status.NOT_EVALUATED_NO_SELECTED_CANDIDATE, "SOURCE_RETAINS_NO_SELECTED_TYPED_CANDIDATE");
            }
            Object candidate = result.selectedCandidate().orElseThrow();
            Object certificate = result.selectedCertificate().orElseThrow();
            DomainSequenceAffineModel.requireSupported(candidate);
            if (candidate instanceof FiniteDifferenceCandidate c && certificate instanceof FiniteDifferenceCertificate k) {
                execution.check = DomainFiniteDataCheck.check(c, k, budget.finiteCheck());
            } else if (candidate instanceof LinearRecurrenceCandidate c && certificate instanceof LinearRecurrenceCertificate k) {
                execution.check = DomainFiniteDataCheck.check(c, k, budget.finiteCheck());
            } else return execution.receipt(Status.UNSUPPORTED, "NO_INDEPENDENT_CHECK_FOR_SELECTED_TYPES");
            if (execution.check.status() != DomainFiniteDataCheck.Status.CONFIRMED_FINITE_DATA || !execution.check.complete()) {
                return execution.receipt(Status.FINITE_CHECK_NOT_CONFIRMED, "INDEPENDENT_FINITE_CHECK_" + execution.check.status());
            }
            execution.model = DomainSequenceAffineModel.from(candidate);
            execution.preparation.started();
            var prepared = RecurrenceObservationKrylov.prepare(execution.model.transition(), execution.model.observation(),
                new de.regelsuche.representation.RepresentationBridge.Budget(budget.maxPreparationWorkUnits()));
            execution.preparation.finished(prepared);
            if (prepared.value().isEmpty()) return execution.receipt(Status.INCONCLUSIVE, "MODEL_PREPARATION_" + prepared.status());

            var plan = prepared.value().orElseThrow();
            execution.binding.started();
            var bound = plan.bind(execution.model.initialState(),
                new de.regelsuche.representation.RepresentationBridge.Budget(budget.maxBindingWorkUnits()));
            execution.binding.finished(bound);
            if (bound.value().isEmpty()) return execution.receipt(Status.INCONCLUSIVE, "MODEL_BINDING_" + bound.status());

            List<BigInteger> indices = execution.model.indices();
            execution.queries.started();
            var queried = bound.value().orElseThrow().valuesAt(indices,
                new de.regelsuche.representation.RepresentationBridge.Budget(budget.maxQueryWorkUnits()));
            execution.queries.finished(queried);
            if (queried.value().isEmpty()) return execution.receipt(Status.INCONCLUSIVE, "MODEL_QUERIES_" + queried.status());
            var values = queried.value().orElseThrow();
            for (int index = 0; index < execution.model.retained().size(); index++) {
                if (!values.get(index).equals(Rational.of(execution.model.retained().get(index)))) {
                    return execution.receipt(Status.TECHNICAL_FAILURE, "PROJECTED_MODEL_DISAGREES_WITH_CHECKED_FINITE_DATA");
                }
            }
            execution.proof = proof(execution.model, plan.coefficients(), indices, values);
            return execution.receipt(Status.PROVED_MODEL, "INDEPENDENTLY_CHECKED_EXPLICIT_MODEL_RECURRENCE");
        } catch (DomainSequenceAffineModel.UnsupportedModel unsupported) {
            return execution.receipt(Status.UNSUPPORTED, unsupported.getMessage());
        } catch (DomainExportWorkspace.ReplayMismatch mismatch) {
            execution.replayed = mismatch.actual();
            execution.replayStatus = "MISMATCH";
            return execution.receipt(Status.REPLAY_MISMATCH, "COMPLETE_CANONICAL_SOURCE_OR_SELECTED_OBJECT_BINDING_DIFFERS");
        } catch (RuntimeException failure) {
            execution.failureClass = failure.getClass().getName();
            if (execution.replayed == null) execution.replayStatus = "TECHNICAL_FAILURE";
            return execution.receipt(Status.TECHNICAL_FAILURE, "MODEL_PROOF_EXECUTION_DID_NOT_COMPLETE");
        }
    }

    /**
     * Revalidates bounded original bytes by actually repeating source replay, finite checking and model proof.
     * It never turns a retained failed or incomplete receipt into a successful proof.
     */
    public static void requireRetained(DomainExportWorkspace workspace, String expectedHash, byte[] bytes) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(bytes, "bytes");
        DomainCanonical.requireSha256(expectedHash, "expectedHash");
        if (bytes.length < 1 || bytes.length > MAX_RECEIPT_BYTES) throw new IllegalArgumentException("model-proof receipt size is outside limits");
        ObjectNode value = (ObjectNode) DomainExportWorkspace.read(bytes);
        if (!SCHEMA.equals(value.path("schema").asText()) || !CONTRACT.equals(value.path("consumerContract").asText())
                || !expectedHash.equals(value.path("contentHash").asText())
                || !DomainDownstreamValidation.sourceBinding(workspace).equals(value.path("source"))) {
            throw new IllegalArgumentException("model-proof receipt schema or source binding differs");
        }
        ObjectNode material = value.deepCopy();
        material.remove("contentHash");
        if (!expectedHash.equals(DomainCanonical.sha256(DomainExportWorkspace.write(material)))) {
            throw new IllegalArgumentException("model-proof receipt content hash differs");
        }
        Budget budget = retainedBudget(value.path("budget"));
        Receipt actual = prove(workspace, budget);
        if (!Arrays.equals(bytes, actual.toCanonicalJson().getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("retained model proof differs from actual source and proof recomputation");
        }
    }

    private static Budget retainedBudget(JsonNode value) {
        requireFields(value, BUDGET_FIELDS);
        JsonNode finite = value.path("finiteCheck");
        requireFields(finite, FINITE_BUDGET_FIELDS);
        return new Budget(integer(value, "maxReplayAttempts"), integer(value, "maxPayloadCharacters"),
            new DomainFiniteDataCheck.Budget(integer(finite, "maxTermChecks"), integer(finite, "maxScalarOperations"),
                integer(finite, "maxScalarBits")), integer(value, "maxPreparationWorkUnits"),
            integer(value, "maxBindingWorkUnits"), integer(value, "maxQueryWorkUnits"));
    }

    private static void requireFields(JsonNode value, Set<String> fields) {
        if (!value.isObject() || value.size() != fields.size() || !fields.stream().allMatch(value::has)) {
            throw new IllegalArgumentException("invalid retained model-proof budget fields");
        }
    }

    private static int integer(JsonNode value, String field) {
        JsonNode number = value.path(field);
        if (!number.isIntegralNumber() || !number.canConvertToInt()) throw new IllegalArgumentException("invalid model-proof integer budget");
        return number.intValue();
    }

    private static ObjectNode proof(DomainSequenceAffineModel model, List<Rational> coefficients,
            List<BigInteger> indices, List<Rational> values) {
        ObjectNode proof = DomainExportWorkspace.JSON.createObjectNode().put("checkerContract", CHECKER_CONTRACT)
            .put("modelHash", model.contentHash()).put("scope", "EXPLICIT_AFFINE_MODEL").put("recurrenceOrder", coefficients.size());
        DomainSequenceAffineModel.rationals(proof.putArray("coefficientsOldestFirst"), coefficients);
        var queries = proof.putArray("queries");
        for (int index = 0; index < indices.size(); index++) {
            int termIndex = indices.get(index).intValueExact();
            boolean retained = termIndex < model.retained().size();
            var row = queries.addObject().put("index", termIndex).put("value", values.get(index).toString())
                .put("role", retained ? "RETAINED_MODEL_TERM" : "MODEL_PREDICTION");
            if (retained) row.put("matchesRetained", true); else row.putNull("matchesRetained");
        }
        proof.put("contentHash", DomainCanonical.sha256(DomainExportWorkspace.write(proof)));
        return proof;
    }

    private static final class Execution {
        final DomainExportWorkspace workspace;
        final Budget budget;
        final Phase preparation, binding, queries;
        int replayAttempts;
        String replayStatus = "NOT_EVALUATED", failureClass = "";
        DomainDiscoveryEvidence replayed;
        DomainFiniteDataCheck.Result check;
        DomainSequenceAffineModel model;
        ObjectNode proof;

        Execution(DomainExportWorkspace workspace, Budget budget) {
            this.workspace = workspace; this.budget = budget;
            preparation = new Phase(budget.maxPreparationWorkUnits());
            binding = new Phase(budget.maxBindingWorkUnits());
            queries = new Phase(budget.maxQueryWorkUnits());
        }

        Receipt receipt(Status status, String detail) {
            ObjectNode value = DomainExportWorkspace.JSON.createObjectNode().put("schema", SCHEMA)
                .put("consumerContract", CONTRACT).put("status", status.name()).put("detail", detail)
                .put("failureClass", failureClass).put("sourceOutcome", workspace.evidence().outcome().name());
            value.set("source", DomainDownstreamValidation.sourceBinding(workspace));
            value.set("model", model == null ? null : model.json());
            value.set("proof", proof);
            value.set("finiteCheck", check == null ? null : DomainExportWorkspace.read(check.toCanonicalJson()));
            ObjectNode replay = value.putObject("replay").put("status", replayStatus)
                .put("maxPayloadCharacters", budget.maxPayloadCharacters());
            replay.putObject("admission").put("configured", budget.maxReplayAttempts()).put("executed", replayAttempts)
                .put("skipped", 0).put("remaining", budget.maxReplayAttempts() - replayAttempts);
            if (replayed == null) replay.putNull("actualEvidenceHash").putNull("resources");
            else {
                replay.put("actualEvidenceHash", replayed.contentHash());
                replay.set("resources", DomainExportWorkspace.read(replayed.toCanonicalJson()).required("resources"));
            }
            replay.put("resourceObservation", replayed == null ? "UNAVAILABLE" : "RETURNED_RUNNER_ROLE_COUNTS")
                .put("internalArithmeticWork", "UNAVAILABLE");
            ObjectNode limits = value.putObject("budget").put("maxReplayAttempts", budget.maxReplayAttempts())
                .put("maxPayloadCharacters", budget.maxPayloadCharacters());
            limits.putObject("finiteCheck").put("maxTermChecks", budget.finiteCheck().maxTermChecks())
                .put("maxScalarOperations", budget.finiteCheck().maxScalarOperations()).put("maxScalarBits", budget.finiteCheck().maxScalarBits());
            limits.put("maxPreparationWorkUnits", budget.maxPreparationWorkUnits()).put("maxBindingWorkUnits", budget.maxBindingWorkUnits())
                .put("maxQueryWorkUnits", budget.maxQueryWorkUnits());
            ObjectNode work = value.putObject("work");
            work.set("preparation", preparation.json()); work.set("binding", binding.json()); work.set("queries", queries.json());
            work.put("modelProjectionWork", "UNAVAILABLE").put("encodingAndHashWork", "UNAVAILABLE").put("totalWork", "UNAVAILABLE");
            value.put("originalSequenceProofStatus", "NOT_ESTABLISHED").put("formalProofStatus", "NOT_EVALUATED")
                .put("externalNoveltyStatus", "NOT_EVALUATED").put("promotionStatus", "NOT_EVALUATED")
                .put("publicEvidenceStatus", "NOT_EVALUATED").put("claimBoundary", CLAIM_BOUNDARY);
            return new Receipt(status, value);
        }
    }

    private static final class Phase {
        final int configured;
        String status = "NOT_RUN";
        Integer executed = 0, remaining;
        Phase(int configured) { this.configured = configured; remaining = configured; }
        void started() { status = "TECHNICAL_FAILURE"; executed = null; remaining = null; }
        void finished(Result<?> result) {
            status = result.status().name();
            executed = result.work().consumedWorkUnits();
            remaining = result.work().remainingWorkUnits();
            if (result.work().configuredWorkUnits() != configured) throw new IllegalStateException("backend phase budget changed");
        }
        ObjectNode json() {
            ObjectNode result = DomainExportWorkspace.JSON.createObjectNode().put("status", status).put("configured", configured);
            if (executed == null) result.putNull("executed").putNull("skipped").putNull("remaining");
            else result.put("executed", executed).put("skipped", 0).put("remaining", remaining);
            return result;
        }
    }
}
