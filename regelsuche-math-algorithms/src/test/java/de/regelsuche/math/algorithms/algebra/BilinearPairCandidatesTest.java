package de.regelsuche.math.algorithms.algebra;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class BilinearPairCandidatesTest {
    private record Node(String name, List<Node> arguments) {
        Node(String name) { this(name, List.of()); }
    }
    private static final BilinearPairCandidates.Algebra<Node> ALGEBRA = new BilinearPairCandidates.Algebra<>() {
        public Optional<BilinearPairCandidates.Binary<Node>> view(Node value) {
            if (value.arguments().size() != 2) return Optional.empty();
            return Optional.of(new BilinearPairCandidates.Binary<>(
                BilinearPairCandidates.Operation.valueOf(value.name()),
                value.arguments().get(0), value.arguments().get(1)));
        }
        public Node make(BilinearPairCandidates.Operation operation, Node left, Node right) {
            return new Node(operation.name(), List.of(left, right));
        }
    };
    private static Node op(String operation, Node left, Node right) {
        return new Node(operation, List.of(left, right));
    }
    private static List<Node> source() {
        var a = new Node("a"); var b = new Node("b");
        var c = new Node("c"); var d = new Node("d");
        return List.of(op("SUBTRACT", op("MULTIPLY", a, c), op("MULTIPLY", b, d)),
            op("ADD", op("MULTIPLY", a, d), op("MULTIPLY", b, c)));
    }

    @Test void generatedJointProposalsPreserveBothOutputsAcrossExactIntegerInputs() {
        var source = source();
        var proposals = BilinearPairCandidates.generate(source, 8, ALGEBRA, ignored -> {});
        assertFalse(proposals.isEmpty());
        for (var proposal : proposals) {
            assertEquals(source.getFirst(), proposal.getFirst());
            assertNotEquals(source.get(1), proposal.get(1));
            for (int a = -2; a <= 2; a++) for (int b = -2; b <= 2; b++)
                for (int c = -2; c <= 2; c++) for (int d = -2; d <= 2; d++) {
                    var values = Map.of("a", BigInteger.valueOf(a), "b", BigInteger.valueOf(b),
                        "c", BigInteger.valueOf(c), "d", BigInteger.valueOf(d));
                    assertEquals(evaluate(source.get(1), values), evaluate(proposal.get(1), values));
                }
        }
    }

    @Test void modularProposalsRequireIdenticalModuliAndKeepOutputPositions() {
        var source = source(); var q = new Node("q"); var other = new Node("other");
        var mismatched = List.of(op("MOD", source.get(0), q), op("MOD", source.get(1), other));
        assertTrue(BilinearPairCandidates.generate(mismatched, 4, ALGEBRA, ignored -> {}).isEmpty());
        var inputs = List.of(other, op("MOD", source.get(1), q), q, op("MOD", source.get(0), q));
        var result = BilinearPairCandidates.generate(inputs, 1, ALGEBRA, ignored -> {});
        assertEquals(1, result.size());
        for (int i : List.of(0, 2, 3)) assertEquals(inputs.get(i), result.getFirst().get(i));
        assertEquals(q, result.getFirst().get(1).arguments().get(1));
        assertThrows(UnsupportedOperationException.class, () -> result.getFirst().add(q));
    }

    @Test void irrelevantInputScanIsLinearAndCooperativelyInterruptible() {
        var values = new ArrayList<Node>();
        for (int i = 0; i < 1500; i++) values.add(new Node("v" + i));
        var work = new AtomicLong();
        assertTrue(BilinearPairCandidates.generate(values, 4, ALGEBRA, work::addAndGet).isEmpty());
        assertTrue(work.get() <= 2L * values.size());
        assertThrows(IllegalStateException.class, () -> BilinearPairCandidates.generate(
            values, 4, ALGEBRA, ignored -> { throw new IllegalStateException("cancelled"); }));
        assertTrue(BilinearPairCandidates.generate(source(), 0, ALGEBRA,
            ignored -> fail("zero candidates needs no search")).isEmpty());
    }

    @Test void linkedOutputsAreScannedSequentiallyAndRetainTheirPositions() {
        var indexedReads = new AtomicLong();
        var linked = new LinkedList<Node>() {
            @Override public Node get(int index) {
                indexedReads.incrementAndGet();
                return super.get(index);
            }
        };
        for (int i = 0; i < 128; i++) linked.add(new Node("unused"));
        var eligible = source();
        linked.add(1, eligible.get(1));
        linked.add(101, eligible.getFirst());
        var expected = BilinearPairCandidates.generate(List.copyOf(linked), 1, ALGEBRA, ignored -> {});
        assertEquals(1, expected.size());
        assertEquals(expected, BilinearPairCandidates.generate(linked, 1, ALGEBRA, ignored -> {}));
        assertEquals(0, indexedReads.get(), "Repeated positional reads retraverse a linked list");
    }

    private static BigInteger evaluate(Node expression, Map<String, BigInteger> values) {
        if (expression.arguments().isEmpty()) return values.get(expression.name());
        var left = evaluate(expression.arguments().get(0), values);
        var right = evaluate(expression.arguments().get(1), values);
        return switch (expression.name()) {
            case "ADD" -> left.add(right);
            case "SUBTRACT" -> left.subtract(right);
            case "MULTIPLY" -> left.multiply(right);
            case "MOD" -> left.mod(right);
            default -> throw new IllegalArgumentException(expression.name());
        };
    }
}
