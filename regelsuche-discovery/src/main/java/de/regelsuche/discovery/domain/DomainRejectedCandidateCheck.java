package de.regelsuche.discovery.domain;

import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.RecurrenceObservationKrylov;
import de.regelsuche.representation.RepresentationBridge.Budget;
import java.math.BigInteger;
import java.util.stream.IntStream;

/** Recomputes an exact retained-term counterexample for the first replayed, rejected sequence model. */
final class DomainRejectedCandidateCheck {
    private static final int MAX_TERMS = 64;
    private static final int PHASE_WORK = 4096;
    private DomainRejectedCandidateCheck() { }

    static ObjectNode check(DomainExportWorkspace.RejectedAttempt attempt) {
        var result = DomainExportWorkspace.JSON.createObjectNode().put("contract", "regelsuche.affine-model-counterexample/v1")
            .put("candidateHash", attempt.row().candidateHash()).put("attemptSequence", attempt.row().sequence())
            .put("termLimit", MAX_TERMS);
        try {
            var model = DomainSequenceAffineModel.from(attempt.candidate());
            if (model.retained().size() > MAX_TERMS) return result.put("status", "TERM_LIMIT_EXCEEDED");
            result.put("modelHash", model.contentHash());
            var work = result.putArray("phases");
            var prepared = RecurrenceObservationKrylov.prepare(model.transition(), model.observation(), new Budget(PHASE_WORK));
            phase(work.addObject(), "PREPARE", prepared);
            if (prepared.value().isEmpty()) return result.put("status", "INCONCLUSIVE");
            var bound = prepared.value().orElseThrow().bind(model.initialState(), new Budget(PHASE_WORK));
            phase(work.addObject(), "BIND", bound);
            if (bound.value().isEmpty()) return result.put("status", "INCONCLUSIVE");
            var indices = IntStream.range(0, model.retained().size()).mapToObj(BigInteger::valueOf).toList();
            var values = bound.value().orElseThrow().valuesAt(indices, new Budget(PHASE_WORK));
            phase(work.addObject(), "QUERY", values);
            if (values.value().isEmpty()) return result.put("status", "INCONCLUSIVE");
            var terms = values.value().orElseThrow();
            for (int index = 0; index < terms.size(); index++) {
                Rational actual = Rational.of(model.retained().get(index));
                if (!actual.equals(terms.get(index))) {
                    return result.put("status", "REFUTED_FINITE_MODEL").put("index", index)
                        .put("retained", actual.toString()).put("predicted", terms.get(index).toString());
                }
            }
            return result.put("status", "NO_MODEL_MISMATCH_IN_RETAINED_TERMS");
        } catch (DomainSequenceAffineModel.UnsupportedModel unavailable) {
            return result.put("status", "UNSUPPORTED_MODEL").put("detail", unavailable.getMessage());
        }
    }

    private static void phase(ObjectNode line, String name, RecurrenceObservationKrylov.Result<?> result) {
        line.put("phase", name).put("status", result.status().name()).put("configured", result.work().configuredWorkUnits())
            .put("executed", result.work().consumedWorkUnits()).put("remaining", result.work().remainingWorkUnits());
    }
}
