package de.regelsuche.symbol;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SymbolScopeTest {
    private static final UUID ROOT = new UUID(0, 1);
    private static final UUID CHILD = new UUID(0, 2);

    @Test void namesResolveOnceAndAliasesShareTheActualSymbol() {
        var scope = new SymbolScope(ROOT);
        var x = scope.resolve("x");
        assertSame(x, scope.resolve("x"));
        assertSame(x, scope.alias("horizontal", x));
        assertSame(x, scope.resolve("horizontal"));
        assertNotEquals(x, scope.resolve("y"));
        assertEquals(3, scope.snapshot().bindings().size());
        assertEquals(3, scope.snapshot().nextOrdinal());
        assertThrows(IllegalArgumentException.class, () -> scope.declare("x"));
        assertThrows(IllegalArgumentException.class, () -> scope.alias("x", scope.resolve("y")));
        assertThrows(IllegalArgumentException.class, () -> scope.alias("foreign", new SymbolId(CHILD, 1)));
    }

    @Test void explicitDeclarationShadowsAnInheritedNameWithoutChangingItsParent() {
        var parent = new SymbolScope(ROOT);
        var x = parent.resolve("x");
        var child = parent.child(CHILD);
        assertSame(x, child.resolve("x"));
        var localX = child.declare("x");
        assertNotEquals(x, localX);
        assertSame(localX, child.resolve("x"));
        assertSame(x, parent.resolve("x"));
        assertSame(x, child.alias("outerX", x));
        assertEquals(ROOT, child.snapshot().parentNamespace());
    }

    @Test void invalidOrOversizedBatchesDoNotPartiallyAllocate() {
        var scope = new SymbolScope(ROOT, new SymbolScope.Limits(2, 4, 2));
        scope.resolve("x");
        var before = scope.snapshot();
        for (String invalid : List.of("", "bad name", "1x", "_x", "rsym_internal", "a".repeat(129))) {
            assertThrows(IllegalArgumentException.class, () -> scope.resolveAll(List.of("y", invalid)));
            assertEquals(before, scope.snapshot());
        }
        assertThrows(IllegalArgumentException.class, () -> scope.resolveAll(List.of("y", "z")));
        assertEquals(before, scope.snapshot());
        assertEquals(2, scope.resolve("y").ordinal());
        assertThrows(IllegalArgumentException.class, () -> scope.alias("thirdName", scope.resolve("y")));
        assertEquals(2, scope.snapshot().bindings().size());
    }

    @Test void repeatedBatchNamesConsumeOnlyOneOrdinal() {
        var scope = new SymbolScope(ROOT);
        var resolved = scope.resolveAll(List.of("y", "y", "x"));
        assertEquals(2, resolved.size());
        assertEquals(1, resolved.get("y").ordinal());
        assertEquals(2, resolved.get("x").ordinal());
        assertThrows(UnsupportedOperationException.class, () -> resolved.clear());
    }

    @Test void jsonSnapshotResumesTheAllocatorAndCanonicalizesAliases() throws Exception {
        var parent = new SymbolScope(ROOT);
        var x = parent.resolve("x");
        var child = parent.child(CHILD);
        child.alias("outer", x);
        child.resolve("y");
        child.alias("vertical", child.resolve("y"));
        var mapper = new ObjectMapper();
        var parentCopy = mapper.readValue(mapper.writeValueAsBytes(parent.snapshot()), SymbolScope.Snapshot.class);
        var childCopy = mapper.readValue(mapper.writeValueAsBytes(child.snapshot()), SymbolScope.Snapshot.class);
        var resumedParent = SymbolScope.restore(parentCopy);
        var resumedChild = resumedParent.restoreChild(childCopy);
        assertSame(resumedParent.resolve("x"), resumedChild.resolve("outer"));
        assertSame(resumedChild.resolve("y"), resumedChild.resolve("vertical"));
        assertEquals(child.resolve("y"), resumedChild.resolve("y"));
        assertEquals(2, resumedChild.resolve("z").ordinal());
        assertThrows(IllegalArgumentException.class, () -> resumedParent.restoreChild(childCopy));
        assertThrows(IllegalArgumentException.class, () -> SymbolScope.restore(childCopy));
    }

    @Test void malformedSnapshotsCannotReuseAnOrdinalOrSmuggleForeignSymbols() {
        var limits = SymbolScope.Limits.defaults();
        for (var snapshot : List.of(
                new SymbolScope.Snapshot(ROOT, null, 1, Map.of("x", new SymbolId(ROOT, 1)), limits),
                new SymbolScope.Snapshot(ROOT, null, 3, Map.of("x", new SymbolId(ROOT, 1)), limits),
                new SymbolScope.Snapshot(ROOT, null, 2, Map.of("x", new SymbolId(CHILD, 1)), limits),
                new SymbolScope.Snapshot(ROOT, null, 2, Map.of("bad name", new SymbolId(ROOT, 1)), limits))) {
            assertThrows(IllegalArgumentException.class, () -> SymbolScope.restore(snapshot));
        }
        var parent = new SymbolScope(ROOT);
        var invalid = new SymbolScope.Snapshot(CHILD, ROOT, 1, Map.of("x", new SymbolId(ROOT, 1)), limits);
        assertThrows(IllegalArgumentException.class, () -> parent.restoreChild(invalid));
        // A rejected restoration does not reserve its namespace.
        assertEquals(CHILD, parent.child(CHILD).namespace());
    }

    @Test void namespaceAndDepthLimitsAreCheckedBeforeRegistration() {
        var root = new SymbolScope(ROOT, new SymbolScope.Limits(3, 2, 1));
        assertThrows(IllegalArgumentException.class, () -> root.child(ROOT));
        var child = root.child(CHILD);
        assertThrows(IllegalArgumentException.class, () -> root.child(CHILD));
        assertThrows(IllegalArgumentException.class, () -> root.child(new UUID(0, 3)));
        assertThrows(IllegalArgumentException.class, () -> child.child(new UUID(0, 4)));
        assertThrows(IllegalArgumentException.class, () -> new SymbolScope.Limits(0, 2, 1));
        assertThrows(IllegalArgumentException.class, () -> new SymbolScope.Limits(3, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new SymbolScope.Limits(3, 2, 65));
    }
}
