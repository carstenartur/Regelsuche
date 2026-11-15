package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.math.algorithms.equivalence.Monomial;
import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactMatrix;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactVector;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.RowOrigin;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSupport.Work;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Evolution;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Proposal;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Request;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Transition;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantVerifier.VerifiedLaw;
import de.regelsuche.math.algorithms.oracle.StateChoiceOraclePanel;
import de.regelsuche.representation.RepresentationBridge.Budget;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static de.regelsuche.math.algorithms.linalg.RecurrenceExperimentTrajectories.evaluate;
import static de.regelsuche.math.algorithms.oracle.StateChoiceOraclePanel.*;
import static org.junit.jupiter.api.Assertions.*;

class CoupledRecurrenceTransferExperimentTest {
    private static final RecurrenceInvariantSynthesis SOLVER = new RecurrenceInvariantSynthesis();
    private static final RecurrenceInvariantVerifier VERIFIER = new RecurrenceInvariantVerifier();

    @Test
    void measuresTransportOfAlreadyProvedLawsAndPaysForMisses() throws IOException {
        var checkout = StateChoiceOraclePanel.load().checkout();
        var protocol = CoupledRecurrenceExperimentTest.protocol(checkout);
        var requests = CoupledRecurrenceExperimentTest.requests(protocol);
        var indices = array(protocol.get("indices")).stream()
            .map(value -> BigInteger.valueOf(((Number) value).longValue())).toList();
        var report = new StringBuilder();
        row(report, "case", "anchor", "route", "direct", "preparation", "rechecked", "proofTransfer");

        var solved = new HashMap<String, RecurrenceInvariantSynthesis.Report>();
        for (var entry : requests.entrySet()) {
            var result = SOLVER.solve(entry.getValue());
            assertEquals(RecurrenceInvariantSynthesis.Status.COMPLETE, result.status(), entry.getKey());
            solved.put(entry.getKey(), result);
        }

        for (var anchor : List.of("symplectic-euler", "cayley-rotation")) {
            var source = solved.get(anchor);
            source.laws().forEach(law -> indices.forEach(law::valueAt));
            int total = source.work().consumedWorkUnits() + source.laws().size() * indices.size();
            row(report, anchor, "-", "DIRECT_ANCHOR", total, 0, total, total);
        }

        record Transfer(String anchor, String target, String route) {}
        var trials = List.of(
            new Transfer("symplectic-euler", "symplectic-euler-shifted", "TRANSLATION"),
            new Transfer("cayley-rotation", "cayley-rotoreflection", "SIGN_REVERSAL"),
            new Transfer("cayley-rotation", "cayley-axial-drift", "DIRECT_FALLBACK"),
            new Transfer("cayley-rotation", "tribonacci", "DIRECT_FALLBACK"));
        for (var trial : trials) {
            var source = requests.get(trial.anchor());
            var target = requests.get(trial.target());
            var direct = solved.get(trial.target());
            var work = new Work(target.budget().maxWorkUnits());
            var transport = prepare(source, target, solved.get(trial.anchor()).laws(), work);
            int preparation = work.ledger().consumedWorkUnits();
            assertEquals(trial.route(), transport.map(Prepared::route).orElse("DIRECT_FALLBACK"));
            int directTotal = direct.work().consumedWorkUnits() + direct.laws().size() * indices.size();
            if (transport.isPresent()) {
                measureTransport(report, trial.anchor(), trial.target(), target, direct.laws(), directTotal,
                    transport.orElseThrow(), preparation, indices);
            } else {
                var fallback = SOLVER.solve(withBudget(target, work.remaining()));
                assertEquals(direct.status(), fallback.status(), trial.target());
                assertEquals(proposals(direct.laws()), proposals(fallback.laws()), trial.target());
                assertEquals(direct.laws().stream().map(VerifiedLaw::initialValue).toList(),
                    fallback.laws().stream().map(VerifiedLaw::initialValue).toList(), trial.target());
                for (int i = 0; i < direct.laws().size(); i++) {
                    for (var index : indices) {
                        assertEquals(direct.laws().get(i).valueAt(index), fallback.laws().get(i).valueAt(index),
                            trial.target());
                    }
                }
                int total = preparation + fallback.work().consumedWorkUnits() + fallback.laws().size() * indices.size();
                row(report, trial.target(), trial.anchor(), trial.route(), directTotal, preparation, total, total);
            }
        }
        var output = System.getProperty("coupledRecurrenceTransferOutput");
        var receipt = checkout.resolve("docs/research/coupled-recurrence-v1/transfer.tsv");
        if (output == null) {
            assertEquals(Files.readString(receipt), report.toString());
        } else {
            Files.writeString(Path.of(output), report.toString());
        }
    }

    private static void measureTransport(StringBuilder report, String anchor, String id, Request target,
            List<VerifiedLaw> direct, int directTotal, Prepared prepared, int preparation, List<BigInteger> indices) {
        assertEquals(proposals(direct), prepared.laws().stream().map(Transported::proposal).toList(), id);
        var recheck = new Work(target.budget().maxWorkUnits() - preparation);
        var withProof = new Work(target.budget().maxWorkUnits() - preparation);
        for (int i = 0; i < direct.size(); i++) {
            var transported = prepared.laws().get(i);
            var replay = VERIFIER.verify(target.transition(), transported.proposal(), target.initialState(),
                new Budget(recheck.remaining()));
            var checked = replay.law().orElseThrow();
            recheck.add(replay.work().consumedWorkUnits());
            // The checked coordinate map proves the step using the source law. Only the target base is new.
            var polynomial = transported.polynomial();
            for (var monomial : polynomial.terms().keySet()) {
                withProof.add(1L + target.initialState().size() + monomial.totalDegree());
            }
            var base = evaluate(polynomial, target.initialState());
            assertEquals(direct.get(i).initialValue(), base, id);
            for (var index : indices) {
                var expected = direct.get(i).valueAt(index);
                assertEquals(expected, checked.valueAt(index), id);
                var transferred = transported.proposal().evolution() == Evolution.ALTERNATING && index.testBit(0)
                    ? base.negate() : base;
                assertEquals(expected, transferred, id);
            }
        }
        int query = direct.size() * indices.size();
        row(report, id, anchor, prepared.route(), directTotal, preparation,
            preparation + recheck.ledger().consumedWorkUnits() + query,
            preparation + withProof.ledger().consumedWorkUnits() + query);
    }

    private static Optional<Prepared> prepare(Request source, Request target, List<VerifiedLaw> laws, Work work) {
        var from = source.transition();
        var to = target.transition();
        int dimension = from.linear().columns();
        work.add(1);
        if (dimension != to.linear().columns()) return Optional.empty();
        work.add((long) dimension * dimension);
        if (from.linear().equals(to.linear())) {
            return translation(from, to, work).map(shift -> translated(source, laws, shift, work));
        }
        for (int i = 0; i < dimension; i++) {
            work.add(2);
            if (!from.offset().get(i).isZero() || !to.offset().get(i).isZero()) return Optional.empty();
            for (int j = 0; j < dimension; j++) {
                work.add(2);
                if (!from.linear().get(i, j).negate().equals(to.linear().get(i, j))) return Optional.empty();
            }
        }
        var directions = new ArrayList<Transported>();
        for (var law : laws) {
            var polynomial = RecurrenceInvariantSupport.project(law.proposal().polynomial(), work);
            int degree = polynomial.totalDegree();
            work.add(1L + polynomial.termCount());
            assertTrue(polynomial.terms().keySet().stream().allMatch(monomial -> monomial.totalDegree() == degree));
            var evolution = degree % 2 == 0 ? law.proposal().evolution() : reverse(law.proposal().evolution());
            directions.add(new Transported(new Proposal(law.proposal().polynomial(), evolution), polynomial));
        }
        directions.sort((left, right) -> {
            work.add(1);
            int signs = left.proposal().evolution().compareTo(right.proposal().evolution());
            if (signs != 0) return signs;
            work.add(1L + left.polynomial().termCount() + right.polynomial().termCount());
            return Integer.compare(left.polynomial().totalDegree(), right.polynomial().totalDegree());
        });
        return Optional.of(new Prepared("SIGN_REVERSAL", directions));
    }

    /** Finds p satisfying (I-A)p=b_target-b_source, using a homogeneous bordered matrix. */
    private static Optional<List<Rational>> translation(Transition from, Transition to, Work work) {
        int dimension = from.linear().columns();
        var rows = new ArrayList<List<Rational>>();
        var names = new ArrayList<String>();
        var origins = new ArrayList<RowOrigin>();
        for (int i = 0; i < dimension; i++) {
            var row = new ArrayList<Rational>();
            names.add("p" + i);
            origins.add(new RowOrigin(i, "translation coordinate " + i));
            for (int j = 0; j < dimension; j++) {
                work.add(1);
                row.add(identityEntry(i, j).subtract(from.linear().get(i, j)));
            }
            work.add(4);
            row.add(from.offset().get(i).subtract(to.offset().get(i)));
            rows.add(row);
        }
        names.add("scale");
        var reduced = new ExactRrefSolver().solveHomogeneous(new ExactMatrix(rows), names, origins,
            new Budget(work.remaining()));
        work.add(reduced.work().consumedWorkUnits());
        for (var vector : reduced.reduction().orElseThrow().nullspaceBasis()) {
            work.add(1);
            var scale = vector.get(dimension);
            if (scale.isZero()) continue;
            work.add(dimension);
            var shift = vector.values().subList(0, dimension).stream().map(value -> value.divide(scale)).toList();
            for (int i = 0; i < dimension; i++) {
                work.add(2L * dimension + 1);
                var residual = rows.get(i).get(dimension);
                for (int j = 0; j < dimension; j++) {
                    residual = residual.add(rows.get(i).get(j).multiply(shift.get(j)));
                }
                assertEquals(Rational.ZERO, residual, "checked translation identity");
            }
            return Optional.of(shift);
        }
        return Optional.empty();
    }

    private static Prepared translated(Request source, List<VerifiedLaw> laws, List<Rational> shift, Work work) {
        int dimension = shift.size();
        var identity = new ArrayList<List<Rational>>();
        for (int i = 0; i < dimension; i++) {
            var row = new ArrayList<Rational>();
            for (int j = 0; j < dimension; j++) row.add(identityEntry(i, j));
            identity.add(row);
        }
        work.add((long) dimension * (dimension + 1));
        var change = new Transition(new ExactMatrix(identity), new ExactVector(shift.stream().map(Rational::negate).toList()));
        var basis = RecurrenceInvariantSupport.basis(dimension, source.degree());
        work.add(basis.size());
        var images = RecurrenceInvariantSupport.liftedImages(change, basis, work);
        var result = new ArrayList<Transported>();
        for (var law : laws) {
            var original = RecurrenceInvariantSupport.project(law.proposal().polynomial(), work);
            var polynomial = Polynomial.zero();
            for (var monomial : basis) {
                work.add(1);
                var coefficient = original.terms().get(monomial);
                if (coefficient == null) continue;
                var image = images.get(monomial);
                work.add(polynomial.termCount() + 2L * image.termCount());
                polynomial = polynomial.add(image.multiply(coefficient));
            }
            var evolution = law.proposal().evolution();
            polynomial = normalize(polynomial, evolution, basis, work);
            var text = polynomial.toCanonicalString();
            work.add(text.length());
            result.add(new Transported(new Proposal(text, evolution), polynomial));
        }
        return new Prepared("TRANSLATION", result);
    }

    private static Polynomial normalize(Polynomial polynomial, Evolution evolution, List<Monomial> basis, Work work) {
        if (evolution == Evolution.FIXED) {
            work.add(1L + polynomial.termCount());
            var constant = polynomial.terms().getOrDefault(Monomial.constant(), Rational.ZERO);
            polynomial = polynomial.subtract(Polynomial.constant(constant));
        }
        for (var monomial : basis) {
            work.add(1);
            var first = polynomial.terms().get(monomial);
            if (first == null) continue;
            work.add(1L + polynomial.termCount());
            return polynomial.multiply(Rational.ONE.divide(first));
        }
        throw new AssertionError("invertible translation produced a constant direction");
    }

    private static Evolution reverse(Evolution evolution) {
        return evolution == Evolution.FIXED ? Evolution.ALTERNATING : Evolution.FIXED;
    }

    private static Rational identityEntry(int row, int column) {
        return row == column ? Rational.ONE : Rational.ZERO;
    }

    private static Request withBudget(Request task, int budget) {
        return new Request(task.transition(), task.initialState(), task.degree(), new Budget(budget));
    }

    private static List<Proposal> proposals(List<VerifiedLaw> laws) {
        return laws.stream().map(VerifiedLaw::proposal).toList();
    }

    private record Prepared(String route, List<Transported> laws) {}
    private record Transported(Proposal proposal, Polynomial polynomial) {}
}
