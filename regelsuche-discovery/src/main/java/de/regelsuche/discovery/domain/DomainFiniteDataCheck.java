package de.regelsuche.discovery.domain;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.discovery.domain.FiniteDifferenceSequenceDomain.*;
import de.regelsuche.discovery.domain.LinearRecurrenceSequenceDomain.*;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Independent finite checks, not inference, source authority or an infinite-sequence proof. */
public final class DomainFiniteDataCheck {
    public static final String SCHEMA = "regelsuche.domain-finite-data-check/v1";
    public static final String NEWTON_CONTRACT = "regelsuche.newton-binomial-finite-check/v1";
    public static final String RESIDUAL_CONTRACT = "regelsuche.recurrence-residual-finite-check/v1";
    private static final int MAX_TERMS = 256, MAX_ORDER = 8, MAX_INPUT_ITEMS = 2048;

    private DomainFiniteDataCheck() { }

    public enum Status { CONFIRMED_FINITE_DATA, REFUTED_FINITE_DATA, INVALID_WITNESS, INCONCLUSIVE, UNSUPPORTED, TECHNICAL_FAILURE }

    /** Hard ceilings bound this ordinary checker, independently of the historical search budget. */
    public record Budget(int maxTermChecks, int maxScalarOperations, int maxScalarBits) {
        public Budget {
            if (maxTermChecks < 0 || maxTermChecks > MAX_TERMS || maxScalarOperations < 0
                    || maxScalarOperations > 100_000 || maxScalarBits < 1 || maxScalarBits > 4096) {
                throw new IllegalArgumentException("invalid finite-check budget");
            }
        }
        public static Budget defaults() { return new Budget(MAX_TERMS, 20_000, 1024); }
    }

    /** Privately issued by a completed or interrupted execution; its hash alone is not source authority. */
    public static final class Result {
        private final Status status;
        private final boolean complete;
        private final String canonicalJson, contentHash;

        private Result(Execution execution, Status status, String detail) {
            this.status = status;
            this.complete = execution.rows.size() == execution.total && execution.total > 0;
            ObjectNode value = DomainExportWorkspace.JSON.createObjectNode();
            value.put("schema", SCHEMA).put("checkerContract", execution.contract).put("status", status.name())
                .put("complete", complete).put("detail", detail).put("retainedTermCount", execution.total)
                .put("observedCount", execution.observedCount).put("holdoutCount", execution.total - execution.observedCount)
                .put("counterexampleStatus", execution.counterexample >= 0 ? "FOUND" : complete ? "NONE_FOUND" : "INCONCLUSIVE");
            if (execution.counterexample >= 0) value.put("firstCounterexampleIndex", execution.counterexample);
            else value.putNull("firstCounterexampleIndex");
            value.set("rows", execution.rows);
            ObjectNode work = value.putObject("work");
            work.put("contract", "regelsuche.finite-check-admitted-dispatch-work/v1");
            line(work, "inputItems", MAX_INPUT_ITEMS, execution.inputItems);
            line(work, "termChecks", execution.budget.maxTermChecks(), execution.terms);
            line(work, "scalarOperations", execution.budget.maxScalarOperations(), execution.operations);
            work.put("maxScalarBits", execution.budget.maxScalarBits()).put("refusedDimension", execution.refused)
                .put("totalArithmeticWork", "UNAVAILABLE").put("primitiveArithmeticWork", "UNAVAILABLE")
                .put("encodingAndHashWork", "UNAVAILABLE");
            value.put("claimBoundary", "Agreement or counterexamples on retained finite data only; no unique infinite continuation, minimal order or universal proof.");
            this.contentHash = DomainCanonical.sha256(DomainExportWorkspace.write(value));
            value.put("contentHash", contentHash);
            this.canonicalJson = DomainExportWorkspace.write(value);
        }
        public Status status() { return status; }
        public boolean complete() { return complete; }
        public String contentHash() { return contentHash; }
        public String toCanonicalJson() { return canonicalJson; }
    }

    public static Result check(FiniteDifferenceCandidate candidate, FiniteDifferenceCertificate witness, Budget budget) {
        Objects.requireNonNull(candidate); Objects.requireNonNull(witness);
        Execution e = new Execution(NEWTON_CONTRACT, candidate.observed().size(), candidate.holdout().size(), budget);
        try {
            e.admit(candidate.order(), (long) e.total + witness.generatedTerms().size()
                + candidate.initialDifferences().size() + witness.initialDifferences().size());
            e.longs(candidate.observed()); e.longs(candidate.holdout()); e.longs(candidate.initialDifferences());
            e.longs(witness.generatedTerms()); e.longs(witness.initialDifferences());
            if (candidate.order() != witness.order() || !candidate.initialDifferences().equals(witness.initialDifferences())
                    || witness.observedCount() != e.observedCount || witness.holdoutCount() != candidate.holdout().size()
                    || !"FINITE_DIFFERENCE_VALIDATION_NOT_FORMAL_PROOF".equals(witness.evidenceStrength())) {
                return e.result(Status.INVALID_WITNESS, "MODEL_COUNTS_OR_STRENGTH_MISMATCH");
            }
            List<Long> retained = joined(candidate.observed(), candidate.holdout());
            for (int n = 0; n < e.total; n++) {
                e.term();
                ExactRational sum = ExactRational.ZERO, choose = ExactRational.ONE;
                for (int j = 0; j <= Math.min(n, candidate.order()); j++) {
                    if (j > 0) choose = e.operation(e.operation(choose, ExactRational.integer(n-j+1), '*'), ExactRational.integer(j), '/');
                    sum = e.operation(sum, e.operation(choose, ExactRational.integer(candidate.initialDifferences().get(j)), '*'), '+');
                }
                ExactRational actual = ExactRational.integer(retained.get(n));
                e.row(n, "NEWTON_TERM", actual, sum, e.operation(actual, sum, '-'),
                    Long.toString(witness.generatedTerms().get(n)), -1, -1);
            }
            return e.finished();
        } catch (Refusal ignored) { return e.result(Status.INCONCLUSIVE, "RESOURCE_ADMISSION_REFUSED"); }
        catch (UnsupportedInput ignored) { return e.result(Status.UNSUPPORTED, "OUTSIDE_FINITE_CHECK_FRAGMENT"); }
        catch (RuntimeException failure) { return e.result(Status.TECHNICAL_FAILURE, failure.getClass().getName()); }
    }

    public static Result check(LinearRecurrenceCandidate candidate, LinearRecurrenceCertificate witness, Budget budget) {
        Objects.requireNonNull(candidate); Objects.requireNonNull(witness);
        Execution e = new Execution(RESIDUAL_CONTRACT, candidate.observed().size(), candidate.holdout().size(), budget);
        try {
            int order = candidate.model().order();
            e.admit(order, (long) e.total + witness.generatedTerms().size()
                + 2L * (candidate.model().coefficients().size() + witness.model().coefficients().size()));
            e.longs(candidate.observed()); e.longs(candidate.holdout());
            for (var model : List.of(candidate.model(), witness.model())) for (var coefficient : model.coefficients()) {
                e.bits(width(coefficient.numerator())); e.bits(width(coefficient.denominator()));
            }
            // Witness text is compared with independently computed canonical values; never parsed as input to arithmetic.
            for (String term : witness.generatedTerms()) if (term.length() > 4096) throw new UnsupportedInput();
            if (!candidate.model().equals(witness.model()) || witness.observedCount() != e.observedCount
                    || witness.holdoutCount() != candidate.holdout().size()
                    || !"LINEAR_RECURRENCE_FINITE_DATA_VALIDATION_NOT_FORMAL_PROOF".equals(witness.evidenceStrength())) {
                return e.result(Status.INVALID_WITNESS, "MODEL_COUNTS_OR_STRENGTH_MISMATCH");
            }
            List<ExactRational> coefficients = candidate.model().coefficients().stream()
                .map(c -> new ExactRational(c.numerator(), c.denominator())).toList();
            List<Long> retained = joined(candidate.observed(), candidate.holdout());
            for (int n = 0; n < e.total; n++) {
                e.term();
                ExactRational actual = ExactRational.integer(retained.get(n));
                ExactRational predicted = n < order ? actual : ExactRational.ZERO;
                if (n >= order) for (int j = 0; j < order; j++) {
                    predicted = e.operation(predicted,
                        e.operation(coefficients.get(j), ExactRational.integer(retained.get(n-j-1)), '*'), '+');
                }
                e.row(n, n < order ? "SEED_IDENTITY" : "RECURRENCE_RESIDUAL", actual, predicted,
                    e.operation(actual, predicted, '-'), witness.generatedTerms().get(n), n < order ? -1 : n-order, n < order ? -1 : n);
            }
            return e.finished();
        } catch (Refusal ignored) { return e.result(Status.INCONCLUSIVE, "RESOURCE_ADMISSION_REFUSED"); }
        catch (UnsupportedInput ignored) { return e.result(Status.UNSUPPORTED, "OUTSIDE_FINITE_CHECK_FRAGMENT"); }
        catch (RuntimeException failure) { return e.result(Status.TECHNICAL_FAILURE, failure.getClass().getName()); }
    }

    private static List<Long> joined(List<Long> observed, List<Long> holdout) {
        List<Long> result = new ArrayList<>(observed); result.addAll(holdout); return result;
    }
    private static int width(BigInteger value) { return value.bitLength() + 1; }
    private static void line(ObjectNode owner, String name, int configured, int executed) {
        owner.putObject(name).put("configured", configured).put("executed", executed).put("skipped", 0).put("remaining", configured-executed);
    }
    private static final class Refusal extends RuntimeException { }
    private static final class UnsupportedInput extends RuntimeException { }

    private static final class Execution {
        final String contract;
        final int observedCount, total;
        final Budget budget;
        final ArrayNode rows = DomainExportWorkspace.JSON.createArrayNode();
        int terms, operations, inputItems, counterexample = -1;
        boolean witnessMismatch;
        String refused = "NONE";
        Execution(String contract, int observed, int holdout, Budget budget) {
            this.contract = contract; this.observedCount = observed; this.total = Math.addExact(observed, holdout);
            this.budget = Objects.requireNonNull(budget);
        }
        void admit(int order, long inputCount) {
            if (total > MAX_TERMS || order > MAX_ORDER || inputCount > MAX_INPUT_ITEMS) throw new UnsupportedInput();
            inputItems = (int) inputCount;
        }
        void longs(List<Long> values) { for (long value : values) bits(value == 0 ? 1 : 65 - Long.numberOfLeadingZeros(value < 0 ? ~value : value)); }
        void bits(int upperBound) { if (upperBound > budget.maxScalarBits()) refuse("SCALAR_BITS"); }
        void term() { if (terms == budget.maxTermChecks()) refuse("TERM_CHECKS"); terms++; }
        void refuse(String dimension) { refused = dimension; throw new Refusal(); }
        ExactRational operation(ExactRational a, ExactRational b, char operator) {
            int an = width(a.numerator()), ad = width(a.denominator()), bn = width(b.numerator()), bd = width(b.denominator());
            int numeratorBound = operator == '*' ? an+bn : operator == '/' ? an+bd : Math.max(an+bd,bn+ad)+1;
            int denominatorBound = operator == '/' ? ad+bn : ad+bd;
            bits(Math.max(numeratorBound, denominatorBound));
            if (operations == budget.maxScalarOperations()) refuse("SCALAR_OPERATIONS");
            operations++;
            return switch (operator) {
                case '*' -> a.multiply(b); case '/' -> a.divide(b); case '+' -> a.add(b); case '-' -> a.subtract(b);
                default -> throw new IllegalArgumentException("unknown exact operation");
            };
        }
        void row(int index, String kind, ExactRational actual, ExactRational computed, ExactRational residual,
                String witness, int windowStart, int windowEnd) {
            boolean agrees = actual.equals(computed), witnessAgrees = computed.canonicalText().equals(witness);
            if (!agrees && counterexample < 0) counterexample = index;
            witnessMismatch |= !witnessAgrees;
            ObjectNode row = rows.addObject().put("index", index).put("partition", index < observedCount ? "OBSERVED" : "HOLDOUT")
                .put("kind", kind).put("retained", actual.canonicalText()).put("computed", computed.canonicalText())
                .put("residual", residual.canonicalText()).put("witness", witness)
                .put("dataAgrees", agrees).put("witnessAgrees", witnessAgrees);
            if (windowStart < 0) row.putNull("windowStartInclusive").putNull("windowEndExclusive");
            else row.put("windowStartInclusive", windowStart).put("windowEndExclusive", windowEnd);
        }
        Result finished() { return result(counterexample >= 0 ? Status.REFUTED_FINITE_DATA
            : witnessMismatch ? Status.INVALID_WITNESS : Status.CONFIRMED_FINITE_DATA, "ALL_RETAINED_TERMS_CHECKED"); }
        Result result(Status status, String detail) { return new Result(this, status, detail); }
    }
}
