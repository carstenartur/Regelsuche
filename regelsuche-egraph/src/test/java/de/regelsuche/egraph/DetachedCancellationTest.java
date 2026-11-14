package de.regelsuche.egraph;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.Expr;
import de.regelsuche.egraph.proof.*;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteRule;
import java.util.List;
import org.junit.jupiter.api.Test;

class DetachedCancellationTest {
    private static final RewriteRule CANCEL = AstRewriteTransformationEngine.defaultRules().stream()
        .filter(r -> r.id().equals("ast_cancel_division_factor")).findFirst().orElseThrow();
    private static final ProofLimits LIMITS = ProofLimits.defaults();

    @Test void nestedCancellationsHaveACheckedPersistableDagWithReceiverOwnedGuards() {
        var input = parse("f((x*y)/x,(z*2)/2)");
        var output = parse("f(y,z)");
        var context = List.of(Assumption.positive("x"));
        var proved = new EqualitySaturation(List.of(CANCEL)).saturateWithCancellationProof(input, context, n -> 1,
            EqualitySaturation.ProofOptions.defaults());
        assertEquals(output, proved.expression());
        assertTrue(proved.proof().complete(), proved.proof().detail());
        var c = proved.proof().checked().orElseThrow().certificate();
        var decoded = ExtractionCertificateCodec.decode(ExtractionCertificateCodec.encode(c, LIMITS), LIMITS);
        assertEquals(c, decoded);
        assertTrue(c.steps().stream().anyMatch(ExtractionCertificate.Congruence.class::isInstance));
        assertEquals(ExtractionProofChecker.Status.ACCEPTED,
            CancellationProof.check(input, output, decoded, List.of(CANCEL), context, LIMITS).status());
        for (var wrong : List.of(List.<Assumption>of(), List.of(Assumption.nonZero("z")),
                List.of(Assumption.customPredicate("x != 0", List.of("x"))),
                List.of(new Assumption(Assumption.Kind.NON_ZERO, "x = 0", List.of("x"))))) {
            assertEquals(ExtractionProofChecker.Status.REJECTED,
                CancellationProof.check(input, output, decoded, List.of(CANCEL), wrong, LIMITS).status());
        }
        assertEquals(ExtractionProofChecker.Status.REJECTED,
            CancellationProof.check(input, output, decoded, List.of(), context, LIMITS).status());
        assertEquals(ExtractionProofChecker.Status.REJECTED,
            CancellationProof.check(input, parse("f(y,y)"), decoded, List.of(CANCEL), context, LIMITS).status());
        var claims = new java.util.ArrayList<>(c.rules());
        var axiom = claims.getFirst();
        claims.set(0,new ExtractionCertificate.RuleIdentity(axiom.id(), axiom.target(), axiom.source()));
        var changed = new ExtractionCertificate(c.schemaVersion(), c.terms(), claims, c.steps(), c.inputTerm(),
            c.outputTerm(), c.conclusion());
        assertEquals(ExtractionProofChecker.Status.REJECTED,
            CancellationProof.check(input, output, changed, List.of(CANCEL), context, LIMITS).status());
    }

    @Test void zeroUnknownAndExhaustedReplayNeverReleaseAnUnprovedOutput() {
        for (String expr : List.of("(0*y)/0", "(x*y)/x")) {
            Expr input = parse(expr);
            var result = new EqualitySaturation(List.of(CANCEL)).saturateWithCancellationProof(input, List.of(), n -> 1,
                EqualitySaturation.ProofOptions.defaults());
            assertEquals(input, result.expression());
        }
        Expr input = parse("(x*y)/x");
        var constrained = new ProofLimits(16, 16, 32, 64, 64, 16, 32, 0);
        var limited = new EqualitySaturation(List.of(CANCEL)).saturateWithCancellationProof(input,
            List.of(Assumption.nonZero("x")), n -> 1, new EqualitySaturation.ProofOptions(LIMITS, constrained));
        assertSame(input, limited.expression());
        assertFalse(limited.proof().complete());
        assertEquals(ProofOutcome.Status.LIMIT_EXCEEDED, limited.proof().status());
    }

    private static Expr parse(String text) {
        return new ExpressionParser().parse(new InputRequest(InputType.TERM, text)).terms().getFirst();
    }
}
