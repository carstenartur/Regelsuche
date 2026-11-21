package de.regelsuche.egraph.proof;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScalarExtractionProofTest {
    final ExpressionParser parser = new ExpressionParser();

    @Test void replaysMixedAcGuardAndCongruenceAgainstReceiverOwnedContext() {
        var rules = AstRewriteTransformationEngine.allBuiltInRules();
        var context = ScalarExtractionProof.Context.create(rules, List.of(Assumption.nonZero("a")), "a".repeat(40));
        var source = parser.parseTerm("f((b*a)/a + 0)");
        var target = parser.parseTerm("f(b)");
        var steps = List.of(
            new ScalarExtractionProof.Lemma(ScalarExtractionProof.AC, parser.parseTerm("b*a"), parser.parseTerm("a*b")),
            new ScalarExtractionProof.Lemma("ast_cancel_division_factor", parser.parseTerm("(a*b)/a"), parser.parseTerm("b")),
            new ScalarExtractionProof.Lemma("ast_add_zero_right", parser.parseTerm("b+0"), parser.parseTerm("b")));
        var certificate = ScalarExtractionProof.build(source,target,context,steps,ProofLimits.defaults()).orElseThrow();
        assertTrue(ScalarExtractionProof.check(source,target,context,certificate,ProofLimits.defaults()));
        assertFalse(ScalarExtractionProof.check(target,source,context,certificate,ProofLimits.defaults()));
        var bytes=ScalarCertificateCodec.encode(certificate,ProofLimits.defaults());
        var digest=ScalarCertificateCodec.hash(bytes);
        assertEquals(certificate,ScalarCertificateCodec.read(bytes,digest,context,ProofLimits.defaults()));
        var changedRevision=ScalarExtractionProof.Context.create(rules,List.of(Assumption.nonZero("a")),"b".repeat(40));
        assertThrows(IllegalArgumentException.class,() -> ScalarCertificateCodec.read(bytes,digest,changedRevision,ProofLimits.defaults()));
        var foreignDomain=new ScalarExtractionProof.Context("matrix-untyped/v0",context.analysis(),context.sourceRevision(),context.inventory(),context.assumptions());
        assertFalse(ScalarExtractionProof.check(source,target,foreignDomain,new ScalarExtractionProof.Bundle(foreignDomain,steps,certificate.dag()),ProofLimits.defaults()));
        var dag=certificate.dag();
        var corrupted=new java.util.ArrayList<>(dag.steps());
        corrupted.set(dag.conclusion(),new ExtractionCertificate.Symmetry(dag.conclusion()));
        var cycle=new ExtractionCertificate(dag.schemaVersion(),dag.terms(),dag.rules(),corrupted,dag.inputTerm(),dag.outputTerm(),dag.conclusion());
        assertFalse(ScalarExtractionProof.check(source,target,context,new ScalarExtractionProof.Bundle(context,steps,cycle),ProofLimits.defaults()));
        bytes[bytes.length-1]^=1;
        assertThrows(IllegalArgumentException.class,() -> ScalarCertificateCodec.read(bytes,digest,context,ProofLimits.defaults()));
        var noGuard = ScalarExtractionProof.Context.create(rules, List.of(), "a".repeat(40));
        var rebound = new ScalarExtractionProof.Bundle(noGuard, steps, certificate.dag());
        assertFalse(ScalarExtractionProof.check(source,target,noGuard,rebound,ProofLimits.defaults()));
        var conflict = ScalarExtractionProof.Context.create(rules,List.of(Assumption.nonZero("a"),
            Assumption.customPredicate("a = 0",List.of("a"))), "a".repeat(40));
        assertFalse(ScalarExtractionProof.check(source,target,conflict,new ScalarExtractionProof.Bundle(conflict,steps,certificate.dag()),ProofLimits.defaults()));
        var falseAc = new ScalarExtractionProof.Lemma(ScalarExtractionProof.AC, parser.parseTerm("b/a"), parser.parseTerm("a/b"));
        assertFalse(ScalarExtractionProof.check(source,target,context,new ScalarExtractionProof.Bundle(context,List.of(falseAc),certificate.dag()),ProofLimits.defaults()));
    }

    @Test void metersBothAnAcceptedProofAndARejectedScalarWitness() {
        var rules = AstRewriteTransformationEngine.allBuiltInRules();
        var context = ScalarExtractionProof.Context.create(rules, List.of(), "a".repeat(40));
        var source = parser.parseTerm("x+0");
        var target = parser.parseTerm("x");
        var admitted = new ScalarExtractionProof.Lemma("ast_add_zero_right", source, target);
        var work = new java.util.concurrent.atomic.AtomicLong();
        var observed = ScalarExtractionProof.buildObserved(source, target, context, List.of(admitted),
                ProofLimits.defaults(), work::addAndGet);
        assertEquals(ScalarExtractionProof.build(source, target, context, List.of(admitted), ProofLimits.defaults()), observed);
        assertTrue(observed.isPresent());
        assertTrue(work.get() > 0);

        work.set(0);
        var refused = new ScalarExtractionProof.Lemma("ast_add_zero_right", parser.parseTerm("x+1"), target);
        assertFalse(ScalarExtractionProof.admitsObserved(context, refused, work::addAndGet));
        assertTrue(work.get() > 1);
    }
}
