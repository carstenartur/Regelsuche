package de.regelsuche.value;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExprValueSnapshotTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test void reloadReinternsStructureIntoTheNewOwner() {
        try (var source = new ExprValueArena(100); var destination = new ExprValueArena(100)) {
            var id = source.intern(parser.parseTerm("pair(a+a+b, 2/6)"));
            var expected = source.digest(id);
            destination.intern(new VariableExpr("allocatedFirst"));
            var snapshot = source.snapshot(id);
            byte[] bytes = ExprValueSnapshot.encode(snapshot, 10_000);
            var restored = destination.restore(ExprValueSnapshot.decode(bytes, 100, 10_000), expected, 10_000);
            assertNotSame(id, restored);
            assertSame(restored, destination.intern(parser.parseTerm("pair(b+(a+a), 1/3)")));
            assertEquals(expected, destination.digest(restored));
            assertThrows(IllegalArgumentException.class, () -> destination.digest(id));
            assertArrayEquals(bytes, ExprValueSnapshot.encode(destination.snapshot(restored), 10_000));
        }
    }

    @Test void snapshotBytesIgnoreAllocationOrderAndAcGrouping() {
        try (var first = new ExprValueArena(100); var second = new ExprValueArena(100)) {
            first.intern(parser.parseTerm("unused(x,y)"));
            var a = first.intern(parser.parseTerm("a+(b+a)"));
            second.intern(new VariableExpr("b"));
            var b = second.intern(parser.parseTerm("(a+a)+b"));
            assertArrayEquals(ExprValueSnapshot.encode(first.snapshot(a), 10_000),
                ExprValueSnapshot.encode(second.snapshot(b), 10_000));
            assertEquals(3, first.snapshot(a).nodes().size());
        }
    }

    @Test void changedAtomsOrBindingsCannotAuthorizeTheExpectedValueAndRollback() {
        try (var source = new ExprValueArena(100); var destination = new ExprValueArena(100)) {
            var id = source.intern(parser.parseTerm("a+b"));
            var good = source.snapshot(id);
            var nodes = new ArrayList<>(good.nodes());
            var atom = nodes.getFirst();
            nodes.set(0, new ExprValueSnapshot.Node(atom.kind(), "changed", atom.edges()));
            var changed = new ExprValueSnapshot.Snapshot(good.version(), good.root(), good.digest(), nodes);
            var existing = destination.intern(new VariableExpr("retained"));
            var digest = destination.digest(existing);
            assertThrows(IllegalArgumentException.class, () -> destination.restore(changed, good.digest(), 10_000));
            assertEquals(1, destination.size());
            assertEquals(digest, destination.digest(existing));
            assertThrows(IllegalArgumentException.class, () -> destination.restore(good, "wrong-digest", 10_000));
            assertEquals(1, destination.size());
        }
    }

    @Test void invalidTopologyAndExtraUnreachableNodesAreRejected() {
        try (var arena = new ExprValueArena(100)) {
            var x = arena.snapshot(arena.intern(new VariableExpr("x")));
            var cyclic = new ExprValueSnapshot.Snapshot(x.version(), 0, x.digest(),
                List.of(new ExprValueSnapshot.Node("POW", "", List.of(0, 0))));
            assertThrows(IllegalArgumentException.class, () -> arena.restore(cyclic, x.digest(), 10_000));
            var unreachable = new ExprValueSnapshot.Snapshot(x.version(), 0, x.digest(),
                List.of(x.nodes().getFirst(), new ExprValueSnapshot.Node("VARIABLE", "unused", List.of())));
            assertThrows(IllegalArgumentException.class, () -> arena.restore(unreachable, x.digest(), 10_000));
            assertEquals(1, arena.size());
        }
    }

    @Test void bytesNodesAndArenaCapacityAreEnforcedBeforePublication() {
        try (var source = new ExprValueArena(20); var destination = new ExprValueArena(2)) {
            var snapshot = source.snapshot(source.intern(parser.parseTerm("pair(a,b)")));
            byte[] bytes = ExprValueSnapshot.encode(snapshot, 10_000);
            assertThrows(IllegalArgumentException.class, () -> ExprValueSnapshot.encode(snapshot, bytes.length - 1));
            assertThrows(IllegalArgumentException.class, () -> ExprValueSnapshot.decode(bytes, 2, 10_000));
            assertThrows(IllegalArgumentException.class, () -> ExprValueSnapshot.decode(bytes, 20, bytes.length - 1));
            assertThrows(IllegalArgumentException.class, () -> destination.restore(snapshot, snapshot.digest(), 10_000));
            assertEquals(0, destination.size());
            assertThrows(IllegalArgumentException.class, () -> source.restore(snapshot, snapshot.digest(), bytes.length - 1));
        }
    }

    @Test void codecRejectsTrailingTruncatedOrUnversionedBytes() {
        try (var arena = new ExprValueArena(10)) {
            byte[] bytes = ExprValueSnapshot.encode(arena.snapshot(arena.intern(new VariableExpr("x"))), 10_000);
            assertThrows(IllegalArgumentException.class, () -> ExprValueSnapshot.decode(
                java.util.Arrays.copyOf(bytes, bytes.length + 1), 10, 10_000));
            assertThrows(IllegalArgumentException.class, () -> ExprValueSnapshot.decode(
                java.util.Arrays.copyOf(bytes, bytes.length - 1), 10, 10_000));
            bytes[0] ^= 1;
            assertThrows(IllegalArgumentException.class, () -> ExprValueSnapshot.decode(bytes, 10, 10_000));
        }
    }

    @Test void deepSharedDagUsesLinearSnapshotStorage() {
        Expr expression = new VariableExpr("x");
        for (int i = 0; i < 4000; i++) expression = new BinaryExpr(expression, BinaryOperator.POW, expression);
        try (var arena = new ExprValueArena(4001); var other = new ExprValueArena(4001)) {
            var id = arena.intern(expression);
            var snapshot = arena.snapshot(id);
            byte[] bytes = ExprValueSnapshot.encode(snapshot, 1_000_000);
            assertEquals(4001, snapshot.nodes().size());
            assertTrue(bytes.length < 150_000);
            assertEquals(arena.digest(id), other.digest(other.restore(
                ExprValueSnapshot.decode(bytes, 4001, 1_000_000), snapshot.digest(), 1_000_000)));
            assertTrue(arena.structuralBytes() < 150_000);
            assertEquals(4001, arena.digestComputations());
        }
    }
}
