package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.math.algorithms.equivalence.Monomial;
import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactMatrix;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactVector;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Evolution;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.FirstLawReport;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Proposal;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Request;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Status;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Transition;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSupport.Work;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantVerifier.VerifiedLaw;
import de.regelsuche.math.algorithms.oracle.StateChoiceOraclePanel;
import de.regelsuche.representation.RepresentationBridge.Budget;
import de.regelsuche.scalar.ExactRational;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static de.regelsuche.math.algorithms.oracle.StateChoiceOraclePanel.*;
import static org.junit.jupiter.api.Assertions.*;

class RecurrenceStateChoiceExperimentTest {
    private static final String[] HEADER = {"case", "arm", "outcome", "budget", "preparation", "planningWithProof",
        "query", "total", "usedSupport", "evolution", "polynomial", "values"};

    @Test
    void measuresChoosingTheCoefficientSupportOnEveryFrozenInvariantCase() throws IOException {
        var panel = StateChoiceOraclePanel.load();
        var spec = object(panel.protocol().get("recurrences"));
        var source = StateChoiceOraclePanel.read(panel.checkout().resolve((String) spec.get("source")),
            (String) spec.get("sourceSha256"));
        var all = array(source.get("cases")).stream().map(StateChoiceOraclePanel::object)
            .filter(item -> "INVARIANT".equals(object(item.get("task")).get("kind"))).toList();
        var hints = array(spec.get("cases")).stream().map(StateChoiceOraclePanel::object).toList();
        assertEquals(all.stream().map(item -> item.get("id")).toList(), hints.stream().map(item -> item.get("id")).toList());
        var indices = array(spec.get("valueIndices")).stream().map(v -> ((Number) v).intValue()).toList();
        var solver = new RecurrenceInvariantSynthesis();
        var report = new StringBuilder();
        var grouped = new StringBuilder();
        row(report, (Object[]) HEADER);
        row(grouped, (Object[]) HEADER);
        int solved = 0;
        for (int i = 0; i < all.size(); i++) {
            String id = (String) all.get(i).get("id");
            var fields = object(object(all.get(i).get("task")).get("recurrence"));
            var transition = new Transition(new ExactMatrix(array(fields.get("linear")).stream()
                .map(RecurrenceStateChoiceExperimentTest::vector).toList()), new ExactVector(vector(fields.get("offset"))));
            var initial = vector(fields.get("initial"));
            int budget = number(fields, "workBudget");
            // Reserve one work unit for each requested valueAt call in both arms.
            int degree = number(fields, "degree");
            var request = new Request(transition, initial, degree, new Budget(budget - indices.size()));
            var reference = reference(hints.get(i), request);
            compare(report, id, budget, request, reference, indices, solver::findFirst);
            compare(grouped, id, budget, request, reference(hints.get(i), request), indices,
                solver::findFirstWithHomogeneousBlocks);
            if (!reference.support().isEmpty()) solved++;
        }
        assertEquals(6, solved);
        panel.checkOrWrite("recurrences.tsv", report.toString());
        panel.checkOrWrite("recurrences-degree-control.tsv", grouped.toString());
    }

    private static void compare(StringBuilder report, String id, int budget, Request request, Reference reference,
            List<Integer> indices, BiFunction<Request, List<Monomial>, FirstLawReport> search) {
        var direct = search.apply(request, List.of());
        assertEquals(Status.COMPLETE, direct.status(), id);
        assertEquals(!reference.support().isEmpty(), direct.law().isPresent(), id);
        var directValues = auditedValues(id, direct, indices);
        retain(report, id, "DIRECT", budget, direct, 0, directValues);

        var supplied = search.apply(request, reference.support());
        checkParity(id, direct, supplied, reference, indices, directValues);
        retain(report, id, "SUPPLIED", budget, supplied, 0, directValues);

        // Preparation and solving share one allowance in the cold-input arm.
        var afterPreparation = new Request(request.transition(), request.initialState(), request.degree(),
            new Budget(request.budget().maxWorkUnits() - reference.preparationWork()));
        var rechecked = search.apply(afterPreparation, reference.support());
        assertEquals(request.budget().maxWorkUnits(),
            reference.preparationWork() + rechecked.work().configuredWorkUnits(), id);
        checkParity(id, direct, rechecked, reference, indices, directValues);
        retain(report, id, "SUPPLIED_RECHECKED", budget, rechecked, reference.preparationWork(), directValues);
    }

    private static void checkParity(String id, FirstLawReport direct, FirstLawReport supplied, Reference reference,
            List<Integer> indices, List<String> directValues) {
        assertEquals(direct.status(), supplied.status(), id);
        assertEquals(!reference.support().isEmpty(), supplied.usedSupport(), id);
        assertEquals(direct.law().map(VerifiedLaw::proposal), supplied.law().map(VerifiedLaw::proposal), id);
        assertEquals(directValues, auditedValues(id, supplied, indices), id);
    }

    private static Reference reference(Map<?, ?> hint, Request request) {
        if (!hint.containsKey("polynomial")) return new Reference(List.of(), 0);
        var proposal = new Proposal((String) hint.get("polynomial"), Evolution.valueOf((String) hint.get("evolution")));
        var check = new RecurrenceInvariantVerifier().verify(request.transition(), proposal, request.initialState(), request.budget());
        var law = check.law().orElseThrow(() -> new AssertionError(check.outcome()));
        var work = new Work(request.budget().maxWorkUnits());
        work.add(check.work().consumedWorkUnits());
        var polynomial = RecurrenceInvariantSupport.project(law.proposal().polynomial(), work);
        work.add(polynomial.termCount());
        assertTrue(polynomial.totalDegree() <= request.degree());
        // Only monomial names and powers enter the search. The source, sign and coefficients in the checked law do not.
        return new Reference(List.copyOf(polynomial.terms().keySet()), work.ledger().consumedWorkUnits());
    }

    private static List<String> auditedValues(String id, FirstLawReport result, List<Integer> indices) {
        if (result.law().isEmpty()) return List.of();
        var law = result.law().orElseThrow();
        var polynomial = new ExactPolynomialAnalysis().normalForm(law.proposal().polynomial());
        var state = law.initialState();
        int step = 0;
        var values = new ArrayList<String>();
        for (int index : indices) {
            while (step < index) {
                state = advance(law.transition(), state);
                step++;
            }
            var actual = law.valueAt(BigInteger.valueOf(index));
            assertEquals(evaluate(polynomial, state), actual, id + " at " + index);
            values.add(actual.toString());
        }
        return List.copyOf(values);
    }

    private static List<Rational> advance(Transition transition, List<Rational> state) {
        var next = new ArrayList<Rational>();
        for (int i = 0; i < state.size(); i++) {
            var value = transition.offset().get(i);
            for (int j = 0; j < state.size(); j++) {
                value = value.add(transition.linear().get(i, j).multiply(state.get(j)));
            }
            next.add(value);
        }
        return List.copyOf(next);
    }

    private static Rational evaluate(Polynomial polynomial, List<Rational> state) {
        Rational sum = Rational.ZERO;
        for (var term : polynomial.terms().entrySet()) {
            var value = term.getValue();
            for (int i = 0; i < state.size(); i++) {
                int exponent = term.getKey().exponentOf("x" + i);
                for (int j = 0; j < exponent; j++) value = value.multiply(state.get(i));
            }
            sum = sum.add(value);
        }
        return sum;
    }

    private static List<Rational> vector(Object raw) {
        return array(raw).stream().map(v -> Rational.fromExact(ExactRational.parse((String) v))).toList();
    }

    private static void retain(StringBuilder report, String id, String arm, int budget, FirstLawReport search,
            int preparationWork, List<String> values) {
        long total = (long) preparationWork + search.work().consumedWorkUnits() + values.size();
        assertTrue(total <= budget, id);
        row(report, id, arm, search.law().isPresent() ? "VERIFIED" : "NO_LAW_IN_ANSATZ",
            budget, preparationWork, search.work().consumedWorkUnits(), values.size(), total,
            search.usedSupport(), search.law().map(v -> v.proposal().evolution().name()).orElse("-"),
            search.law().map(v -> v.proposal().polynomial()).orElse("-"), values.isEmpty() ? "-" : String.join(",", values));
    }

    private record Reference(List<Monomial> support, int preparationWork) {}
}
