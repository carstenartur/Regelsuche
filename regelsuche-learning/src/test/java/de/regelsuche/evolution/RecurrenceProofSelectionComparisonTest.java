package de.regelsuche.evolution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.math.algorithms.equivalence.Monomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactMatrix;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactVector;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Evolution;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Request;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Transition;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantVerifier;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantVerifier.VerifiedLaw;
import de.regelsuche.representation.RepresentationBridge.Budget;
import de.regelsuche.scalar.ExactRational;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static de.regelsuche.evolution.CumulativeTaskKnowledge.Strategy.AUTOMATIC;
import static de.regelsuche.evolution.CumulativeTaskKnowledge.Strategy.BASELINE;
import static org.junit.jupiter.api.Assertions.*;

class RecurrenceProofSelectionComparisonTest {
    private static final String HASH = "sha256:cb27b8901fdcc14f8fedebff9666e7dfba603d55cbb6be1a43aea14f56b880b9";
    private static final Path PROTOCOL = Path.of("config/benchmarks/recurrence-proof-selection-v1.json");
    private static final Path RECEIPTS = Path.of("docs/research/recurrence-proof-selection-v1");

    @Test
    void comparesPaidAutomaticSelectionWithTheNativeFirstLawGoal() throws IOException {
        var checkout = checkout();
        String frozen = Files.readString(checkout.resolve(PROTOCOL));
        assertEquals(HASH, SchematicProofPlan.hash(frozen));
        var spec = new ObjectMapper().readTree(frozen);
        var indices = spec.required("queryIndices").valueStream().map(value -> value.bigIntegerValue()).toList();
        var prefixes = spec.required("prefixRounds").valueStream().map(JsonNode::intValue).toList();
        int budget = spec.required("requestBudget").intValue();
        var baseline = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory());
        var lessons = spec.required("lessons").valueStream().map(row -> request(spec, row, 0, budget)).toList();
        var validation = spec.required("validation").valueStream().map(row -> request(spec, row, 0, budget)).toList();
        var update = baseline.learnRecurrences(lessons, validation, new Budget(spec.required("acquisitionBudget").intValue()));
        assertTrue(update.promoted(), () -> update.reason().name());
        assertEquals(lessons.size(), update.next().recurrenceCount());
        var restored = CumulativeTaskKnowledge.restore(TraceStrategyTransferExample.inventory(), update.next().toCanonicalJson());
        assertEquals(update.next().id(), restored.id());

        var totals = new StringBuilder("rounds\trequests\tverified\treused\tdirect\tpersistent\tacquisition\trestore\tfull\n");
        var details = new StringBuilder("round\tcase\toutcome\treused\tdirect\tautomatic\n");
        long directWork = 0, automaticWork = 0;
        int requests = 0, verified = 0, reused = 0;
        for (int round = 0; round < prefixes.getLast(); round++) {
            for (var fixture : spec.required("queries")) {
                var task = request(spec, fixture, round, budget - indices.size());
                assertFalse(lessons.stream().anyMatch(prior -> sameSourceTask(prior, task)));
                assertFalse(validation.stream().anyMatch(prior -> sameSourceTask(prior, task)));
                var direct = baseline.solve(task, BASELINE);
                var automatic = restored.solve(task, AUTOMATIC);
                assertEquals(direct.outcome(), automatic.outcome());
                assertEquals(fixture.required("solved").booleanValue(), direct.solved());
                assertEquals(fixture.required("reused").booleanValue(), automatic.reused());
                int query = 0;
                if (direct.solved()) {
                    checkEquivalent(task, direct.law().orElseThrow(), automatic.law().orElseThrow(), indices);
                    query = indices.size();
                    verified++;
                }
                if (automatic.reused()) reused++;
                assertTrue(direct.totalWork() + query <= budget && automatic.totalWork() + query <= budget);
                directWork += direct.totalWork() + query;
                automaticWork += automatic.totalWork() + query;
                requests++;
                line(details, round + 1, fixture.required("id").asText(), direct.outcome(), automatic.reused(),
                    direct.totalWork() + query, automatic.totalWork() + query);
            }
            if (prefixes.contains(round + 1)) {
                line(totals, round + 1, requests, verified, reused, directWork, automaticWork, update.work().total(),
                    restored.sessionSetupWork(), automaticWork + update.work().total() + restored.sessionSetupWork());
            }
        }
        assertEquals(update.next().id(), restored.id(), "queries cannot alter the generation");
        checkOrWrite(checkout, "work.tsv", totals.toString());
        checkOrWrite(checkout, "requests.tsv", details.toString());
    }

    private static boolean sameSourceTask(Request first, Request second) {
        return first.transition().equals(second.transition()) && first.initialState().equals(second.initialState())
            && first.degree() == second.degree();
    }

    private static void checkEquivalent(Request request, VerifiedLaw direct, VerifiedLaw transported, List<BigInteger> indices) {
        var replay = new RecurrenceInvariantVerifier().verify(request.transition(), transported.proposal(),
            request.initialState(), request.budget()).law().orElseThrow();
        assertEquals(direct.proposal().evolution(), replay.proposal().evolution());
        var analysis = new ExactPolynomialAnalysis();
        var left = analysis.normalForm(direct.proposal().polynomial());
        var right = analysis.normalForm(replay.proposal().polynomial());
        var pivot = left.terms().keySet().stream().filter(monomial -> monomial.totalDegree() > 0)
            .min(Comparator.comparing(Monomial::key)).orElseThrow();
        var scale = right.terms().getOrDefault(pivot, Rational.ZERO).divide(left.terms().get(pivot));
        assertFalse(scale.isZero());
        var difference = right.subtract(left.multiply(scale));
        assertEquals(0, difference.totalDegree());
        var offset = difference.terms().getOrDefault(Monomial.constant(), Rational.ZERO);
        if (direct.proposal().evolution() == Evolution.ALTERNATING) assertTrue(offset.isZero());
        for (var index : indices) {
            var expected = direct.valueAt(index).multiply(scale).add(offset);
            assertEquals(expected, replay.valueAt(index));
            assertEquals(expected, transported.valueAt(index));
        }
    }

    private static Request request(JsonNode spec, JsonNode row, int round, int budget) {
        var matrix = spec.required("matrices").required(row.required("matrix").asText()).valueStream()
            .map(RecurrenceProofSelectionComparisonTest::rationalArray).toList();
        var offset = vector(row, "offset", "offsetStep", round);
        var start = vector(row, "initial", "initialStep", round);
        return new Request(new Transition(new ExactMatrix(matrix), new ExactVector(offset)), start,
            row.required("degree").intValue(), new Budget(budget));
    }

    private static List<Rational> vector(JsonNode row, String baseName, String stepName, int round) {
        var base = rationalArray(row.required(baseName));
        if (!row.has(stepName)) return base;
        var step = rationalArray(row.required(stepName));
        var result = new ArrayList<Rational>();
        for (int i = 0; i < base.size(); i++) result.add(base.get(i).add(step.get(i).multiply(Rational.of(round))));
        return List.copyOf(result);
    }

    private static List<Rational> rationalArray(JsonNode row) {
        return row.valueStream().map(value -> Rational.fromExact(ExactRational.parse(value.asText()))).toList();
    }

    private static Path checkout() throws IOException {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve(PROTOCOL))) return dir;
        }
        throw new IOException("comparison protocol not found");
    }

    private static void checkOrWrite(Path checkout, String filename, String content) throws IOException {
        String output = System.getProperty("recurrenceProofSelectionOutput");
        if (output == null) {
            assertEquals(Files.readString(checkout.resolve(RECEIPTS).resolve(filename)), content, filename);
        } else {
            var dir = Path.of(output);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(filename), content);
        }
    }

    private static void line(StringBuilder report, Object... values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) report.append('\t');
            report.append(values[i]);
        }
        report.append('\n');
    }
}
