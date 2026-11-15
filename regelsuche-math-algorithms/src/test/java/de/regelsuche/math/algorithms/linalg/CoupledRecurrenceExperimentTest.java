package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactMatrix;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactVector;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Evolution;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Request;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Status;
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
import java.util.List;
import java.util.Map;

import static de.regelsuche.math.algorithms.linalg.RecurrenceExperimentTrajectories.*;
import static de.regelsuche.math.algorithms.oracle.StateChoiceOraclePanel.*;
import static org.junit.jupiter.api.Assertions.*;

class CoupledRecurrenceExperimentTest {
    private static final String PROTOCOL = "config/benchmarks/coupled-recurrence-v1.json";
    private static final String DIGEST = "c2a45639714bb4fd990d31e4625e7b9de4185b65e0d5e37f801b0fc769a91b4a";
    private static final String RECEIPT = "docs/research/coupled-recurrence-v1/degree-blocks.tsv";

    @Test
    void measuresCompleteSynthesisOnEveryCoupledTransition() throws IOException {
        var checkout = StateChoiceOraclePanel.load().checkout();
        var protocol = read(checkout.resolve(PROTOCOL), DIGEST);
        var receipt = new StringBuilder();
        row(receipt, "case", "family", "budget", "status", "fixed", "alternating", "formation", "proof", "query",
            "total", "lawsAndValues");
        for (var raw : array(protocol.get("cases"))) {
            measure(receipt, protocol, object(raw));
        }
        var output = System.getProperty("coupledRecurrenceOutput");
        if (output == null) {
            assertEquals(Files.readString(checkout.resolve(RECEIPT)), receipt.toString());
        } else {
            Files.writeString(Path.of(output), receipt.toString());
        }
    }

    private static void measure(StringBuilder receipt, Map<?, ?> protocol, Map<?, ?> fixture) {
        var id = (String) fixture.get("id");
        var transition = new Transition(new ExactMatrix(array(fixture.get("linear")).stream()
            .map(RecurrenceExperimentTrajectories::vector).toList()), new ExactVector(vector(fixture.get("offset"))));
        var initial = vector(fixture.get("initial"));
        int budget = number(protocol, "workBudget");
        int reserve = number(protocol, "queryReserve");
        var indices = array(protocol.get("indices")).stream().map(v -> ((Number) v).intValue()).toList();
        var request = new Request(transition, initial, number(protocol, "degree"), new Budget(budget - reserve));
        var report = new RecurrenceInvariantSynthesis().solve(request);
        assertEquals(Status.COMPLETE, report.status(), id);
        long fixed = report.laws().stream().filter(law -> law.proposal().evolution() == Evolution.FIXED).count();
        long alternating = report.laws().size() - fixed;
        assertEquals(number(fixture, "fixed"), fixed, id);
        assertEquals(number(fixture, "alternating"), alternating, id);

        var verifier = new RecurrenceInvariantVerifier();
        var lawValues = new ArrayList<String>();
        int proof = 0;
        for (var law : report.laws()) {
            var replay = verifier.verify(transition, law.proposal(), initial, request.budget());
            var proofLaw = replay.law().orElseThrow(() -> new AssertionError(id + ": " + replay.outcome()));
            assertEquals(law.initialValue(), proofLaw.initialValue(), id);
            proof += replay.work().consumedWorkUnits();
            lawValues.add(law.proposal().evolution() + " " + law.proposal().polynomial() + " @ " + auditedValues(id, law, indices));
        }
        int query = report.laws().size() * indices.size();
        int synthesis = report.work().consumedWorkUnits();
        assertTrue(query <= reserve && synthesis + query <= budget && proof <= synthesis, id);
        row(receipt, id, fixture.get("family"), budget, report.status(), fixed, alternating, synthesis - proof, proof,
            query, synthesis + query, lawValues.isEmpty() ? "-" : String.join(" | ", lawValues));
    }

    private static String auditedValues(String id, VerifiedLaw law, List<Integer> indices) {
        var polynomial = new ExactPolynomialAnalysis().normalForm(law.proposal().polynomial());
        var state = law.initialState();
        var values = new ArrayList<String>();
        int step = 0;
        for (int index : indices) {
            while (step < index) {
                state = advance(law.transition(), state);
                step++;
            }
            var value = law.valueAt(BigInteger.valueOf(index));
            assertEquals(evaluate(polynomial, state), value, id + " at " + index);
            values.add(value.toString());
        }
        return String.join(",", values);
    }
}
