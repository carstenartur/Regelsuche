package de.regelsuche.plugin;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.Expr;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;
import org.junit.jupiter.api.Test;

class RuleRegistryTypedSelectionTest {
    @Test
    void registrationReturnsTheExactConcreteTypeAndResolvesWireIdsExplicitly() {
        var registry = new RuleRegistry();
        var rule = new Rule("identity");
        Rule registered = registry.registerAndGet(rule);
        assertSame(rule, registered);
        assertSame(rule, registry.requireRule("identity"));
        assertEquals(1, registry.enabledRules().size());
        assertThrows(IllegalArgumentException.class, () -> registry.requireRule("identtiy"));
        assertThrows(IllegalArgumentException.class, () -> registry.registerAndGet(new Rule("identity")));
        assertThrows(NullPointerException.class, () -> registry.registerAndGet(null));
        assertThrows(NullPointerException.class, () -> registry.requireRule(null));
        // Resolving a registered object must not re-enable it or confer execution authority.
        registry.disable("identity");
        assertSame(rule, registry.requireRule("identity"));
        assertTrue(registry.enabledRules().isEmpty());
    }

    private record Rule(String id) implements RewriteRule {
        @Override public RewriteKind kind() { return RewriteKind.NORMALIZE; }
        @Override public boolean mayIncreaseComplexity() { return false; }
        @Override public int estimatedCostDelta() { return 0; }
        @Override public boolean isEquivalencePreservingByConstruction() { return true; }
        @Override public boolean matches(Expr subtree) { return true; }
        @Override public Expr apply(Expr subtree) { return subtree; }
    }
}
