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
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantFamily;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantFamily.Anchor;
import de.regelsuche.representation.RepresentationBridge.Budget;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongConsumer;

/** Strict bounded payload inside the existing content-addressed knowledge snapshot. */
final class RecurrenceKnowledgeCodec {
    private static final int MAX_PAYLOAD_LENGTH = 200_000;
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private RecurrenceKnowledgeCodec() { }
    record Loaded(List<RecurrenceKnowledge.Entry> entries, long work) { }

    static String encode(List<RecurrenceKnowledge.Entry> entries) {
        String json = payload(entries);
        if (json.length() > MAX_PAYLOAD_LENGTH) throw new IllegalArgumentException("recurrence payload too large");
        return json;
    }
    /** Admission pays for the actual canonical payload, including both anchors of every family. */
    static boolean fitsPayload(List<RecurrenceKnowledge.Entry> entries, LongConsumer work) {
        String json = payload(entries);
        work.accept(json.length());
        return json.length() <= MAX_PAYLOAD_LENGTH;
    }
    private static String payload(List<RecurrenceKnowledge.Entry> entries) {
        var root = JSON.createArrayNode();
        for (var entry : entries) {
            var node = root.addObject();
            writeAnchor(node,entry.transition(),entry.degree(),entry.proposal());
            entry.family().ifPresent(f -> writeAnchor(node.putObject("family"),f.second().transition(),f.degree(),f.second().proposal()));
        }
        return root.toString();
    }
    private static void writeAnchor(com.fasterxml.jackson.databind.node.ObjectNode node,
            Transition transition, int degree, Proposal proposal) {
        node.put("degree",degree).put("polynomial",proposal.polynomial()).put("evolution",proposal.evolution().name());
        var matrix=node.putArray("linear");
        for (var row : transition.linear().rows()) {
            var cells=matrix.addArray(); row.forEach(value -> cells.add(value.toString()));
        }
        var offset=node.putArray("offset");
        transition.offset().values().forEach(value -> offset.add(value.toString()));
    }
    static Loaded restore(String json) {
        if (json.isEmpty()) return new Loaded(List.of(), 0);
        if (json.length() > MAX_PAYLOAD_LENGTH) throw new IllegalArgumentException("recurrence payload too large");
        long[] work = {json.length()};
        try {
            JsonNode root = JSON.readTree(json);
            if (root == null || !root.isArray() || root.size() > RecurrenceKnowledge.MAX_ENTRIES)
                throw new IllegalArgumentException("invalid recurrence catalog size");
            var entries = new ArrayList<RecurrenceKnowledge.Entry>();
            for (var node : root) {
                var entry = readEntry(node, work);
                if (entries.stream().anyMatch(e -> sameKey(e, entry)))
                    throw new IllegalArgumentException("duplicate recurrence key");
                verify(entry, work); entries.add(entry);
            }
            if (!encode(entries).equals(json)) throw new IllegalArgumentException("noncanonical recurrence catalog");
            return new Loaded(List.copyOf(entries), work[0]);
        } catch (java.io.IOException | IllegalArgumentException | ArithmeticException failure) {
            throw new IllegalArgumentException("invalid or unproved recurrence payload; knownWork=" + work[0], failure);
        }
    }
    private static boolean sameKey(RecurrenceKnowledge.Entry a, RecurrenceKnowledge.Entry b) {
        if (!a.transition().equals(b.transition()) || a.degree()!=b.degree() || a.family().isPresent()!=b.family().isPresent()) return false;
        return a.family().isEmpty() || a.family().get().second().transition().equals(b.family().get().second().transition());
    }
    private static RecurrenceKnowledge.Entry readEntry(JsonNode node, long[] work) {
        var exact=readAnchor(node,true);
        if (!node.has("family")) return exact;
        var other=readAnchor(node.get("family"),false);
        if (other.degree()!=exact.degree()) throw new IllegalArgumentException("family degree mismatch");
        var formation=new RecurrenceInvariantFamily().form(new Anchor(exact.transition(),exact.proposal()),
            new Anchor(other.transition(),other.proposal()),exact.degree(),new Budget(200_000));
        work[0]=Math.addExact(work[0],formation.work().consumedWorkUnits());
        return RecurrenceKnowledge.Entry.fromFamily(formation.family()
            .orElseThrow(() -> new IllegalArgumentException("stored family failed symbolic verification")));
    }
    private static RecurrenceKnowledge.Entry readAnchor(JsonNode node, boolean allowFamily) {
        var names = new HashSet<String>();
        if (!node.isObject()) throw new IllegalArgumentException("recurrence object required");
        node.fieldNames().forEachRemaining(names::add);
        var expected=new HashSet<>(Set.of("degree", "polynomial", "evolution", "linear", "offset"));
        if (allowFamily && node.has("family")) expected.add("family");
        if (!names.equals(expected))
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
