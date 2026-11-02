package de.regelsuche.evolution;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactMatrix;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactVector;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Evolution;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Proposal;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Transition;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantVerifier;
import de.regelsuche.representation.RepresentationBridge.Budget;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Strict bounded payload inside the existing content-addressed knowledge snapshot. */
final class RecurrenceKnowledgeCodec {
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private RecurrenceKnowledgeCodec() { }
    record Loaded(List<RecurrenceKnowledge.Entry> entries, long work) { }

    static String encode(List<RecurrenceKnowledge.Entry> entries) {
        var root = JSON.createArrayNode();
        for (var entry : entries) {
            var node = root.addObject().put("degree", entry.degree()).put("polynomial", entry.proposal().polynomial())
                .put("evolution", entry.proposal().evolution().name());
            var matrix = node.putArray("linear");
            for (var row : entry.transition().linear().rows()) {
                var cells = matrix.addArray(); row.forEach(value -> cells.add(value.toString()));
            }
            var offset = node.putArray("offset");
            entry.transition().offset().values().forEach(value -> offset.add(value.toString()));
        }
        return root.toString();
    }
    static Loaded restore(String json) {
        if (json.isEmpty()) return new Loaded(List.of(), 0);
        if (json.length() > 200_000) throw new IllegalArgumentException("recurrence payload too large");
        long[] work = {json.length()};
        try {
            JsonNode root = JSON.readTree(json);
            if (root == null || !root.isArray() || root.size() > RecurrenceKnowledge.MAX_ENTRIES)
                throw new IllegalArgumentException("invalid recurrence catalog size");
            var entries = new ArrayList<RecurrenceKnowledge.Entry>();
            for (var node : root) {
                var entry = readEntry(node);
                if (entries.stream().anyMatch(e -> e.transition().equals(entry.transition()) && e.degree() == entry.degree()))
                    throw new IllegalArgumentException("duplicate recurrence key");
                verify(entry, work); entries.add(entry);
            }
            if (!encode(entries).equals(json)) throw new IllegalArgumentException("noncanonical recurrence catalog");
            return new Loaded(List.copyOf(entries), work[0]);
        } catch (java.io.IOException | IllegalArgumentException | ArithmeticException failure) {
            throw new IllegalArgumentException("invalid or unproved recurrence payload; knownWork=" + work[0], failure);
        }
    }
    private static RecurrenceKnowledge.Entry readEntry(JsonNode node) {
        var names = new HashSet<String>();
        if (!node.isObject()) throw new IllegalArgumentException("recurrence object required");
        node.fieldNames().forEachRemaining(names::add);
        if (!names.equals(Set.of("degree", "polynomial", "evolution", "linear", "offset")))
            throw new IllegalArgumentException("unexpected recurrence fields");
        var degree = node.get("degree");
        if (!degree.isIntegralNumber() || !degree.canConvertToInt()) throw new IllegalArgumentException("integer degree required");
        var matrix = node.get("linear");
        if (!matrix.isArray() || matrix.isEmpty() || matrix.size() > 3) throw new IllegalArgumentException("bounded matrix required");
        var rows = new ArrayList<List<Rational>>();
        for (var row : matrix) rows.add(vector(row));
        var source = new Transition(new ExactMatrix(rows), new ExactVector(vector(node.get("offset"))));
        return new RecurrenceKnowledge.Entry(source, degree.intValue(),
            new Proposal(text(node.get("polynomial")), Evolution.valueOf(text(node.get("evolution")))));
    }
    private static List<Rational> vector(JsonNode node) {
        if (!node.isArray() || node.isEmpty() || node.size() > 3) throw new IllegalArgumentException("bounded vector required");
        var values = new ArrayList<Rational>();
        for (var value : node) {
            String text = text(value);
            if (!text.matches("-?[0-9]{1,40}(/[0-9]{1,40})?")) throw new IllegalArgumentException("bounded exact rational required");
            String[] pair = text.split("/", -1);
            values.add(new Rational(new BigInteger(pair[0]), pair.length == 1 ? BigInteger.ONE : new BigInteger(pair[1])));
        }
        return List.copyOf(values);
    }
    private static String text(JsonNode node) {
        if (!node.isTextual()) throw new IllegalArgumentException("text required");
        return node.textValue();
    }
    private static void verify(RecurrenceKnowledge.Entry entry, long[] work) {
        var initial = java.util.Collections.nCopies(entry.transition().linear().columns(), Rational.ZERO);
        var checked = new RecurrenceInvariantVerifier().verify(entry.transition(), entry.proposal(), initial, new Budget(200_000));
        work[0] = Math.addExact(work[0], checked.work().consumedWorkUnits());
        if (checked.law().isEmpty()) throw new IllegalArgumentException("stored recurrence identity failed revalidation");
        var polynomial = new ExactPolynomialAnalysis(units -> work[0] = Math.addExact(work[0], units))
            .normalForm(entry.proposal().polynomial());
        if (polynomial.totalDegree() > entry.degree()) throw new IllegalArgumentException("stored degree bound is false");
    }
}
