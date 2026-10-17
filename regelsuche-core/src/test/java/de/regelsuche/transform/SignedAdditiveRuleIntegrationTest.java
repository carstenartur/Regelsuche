package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.Expr;
import de.regelsuche.knowledge.CoreRuleCatalog;
import de.regelsuche.parse.ExpressionParser;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** No optimization SDK dependency and no direct invocation of the additive helper. */
@Timeout(20)
class SignedAdditiveRuleIntegrationTest {
    private static final String RULE = "ast_signed_additive_normalize";
    private final ExpressionParser parser = new ExpressionParser();

    @Test void ordinaryInventoryExposesAndReplaysCancellationInsideAFunction() {
        Expr source = parser.parseTerm("abs(((a+b)-c)-a)");
        Expr expected = parser.parseTerm("abs(b-c)");
        var engine = new AstRewriteTransport(AstRewriteTransformationEngine.defaultRules(), 12, 128);
        var step = engine.generate(source).stream()
                .filter(candidate -> candidate.rule().equals(RULE) && candidate.target().equals(expected))
                .findFirst().orElseThrow(() -> new AssertionError("Ordinary Regelsuche did not offer the signed-additive rewrite"));
        assertEquals(expected, engine.replay(source, List.of(step)));
        // Transport records built-in provenance; selectable sub-packs belong to
        // CoreRuleCatalog. Treating a built-in rule as an external pack would
        // change its trust contract merely to satisfy an incorrect assertion.
        assertEquals("core", step.packId());
        assertEquals(CoreRuleCatalog.TERM_COLLECTION, CoreRuleCatalog.packIdForRule(step.rule()));
    }

    @Test void ordinaryInventoryExposesReusableDifferencesWithoutAPaethRule() {
        Expr source = parser.parseTerm("((north+east)-origin)-origin");
        Expr expected = parser.parseTerm("(north-origin)+(east-origin)");
        var engine = new AstRewriteTransport(AstRewriteTransformationEngine.defaultRules(), 12, 128);
        assertTrue(engine.generate(source).stream()
                .anyMatch(step -> step.rule().equals(RULE) && step.target().equals(expected)),
                "Differences must be available outside the Java SDK");
    }

    @Test void ruleBelongsToTheSharedTermCollectionPackExactlyOnce() {
        assertEquals(CoreRuleCatalog.TERM_COLLECTION, CoreRuleCatalog.packIdForRule(RULE));
        assertEquals(1, AstRewriteTransformationEngine.allBuiltInRules().stream()
                .filter(rule -> rule.id().equals(RULE)).count());
        assertEquals(1, AstRewriteTransformationEngine.defaultRules().stream()
                .filter(rule -> rule.id().equals(RULE)).count());
    }

    @Test void ordinaryRuleDoesNotEraseAnUndefinedOperand() {
        RewriteRule rule = AstRewriteTransformationEngine.defaultRules().stream()
                .filter(candidate -> candidate.id().equals(RULE)).findFirst()
                .orElseThrow(() -> new AssertionError("Signed-additive rule is not registered"));
        assertFalse(rule.matches(parser.parseTerm("(a+1/x)-1/x")));
        assertFalse(rule.matches(parser.parseTerm("(a+ln(x))-ln(x)")));
        assertTrue(rule.matches(parser.parseTerm("(a+b)-b")));
    }
}
