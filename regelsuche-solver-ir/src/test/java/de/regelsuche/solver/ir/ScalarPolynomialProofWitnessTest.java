package de.regelsuche.solver.ir;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.egraph.proof.*;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScalarPolynomialProofWitnessTest {
    @Test void verifiesTheExactBackendReceiptAndRejectsSubstitutedResultsAfterRehash() {
        var parser = new ExpressionParser();
        var a=parser.parseTerm("(x+1)*(x+1)"); var b=parser.parseTerm("x^2+2*x+1");
        var ctx=ScalarExtractionProof.Context.create(AstRewriteTransformationEngine.allBuiltInRules(),List.of(),"a".repeat(40));
        var port=new ScalarPolynomialProofWitness();
        var receipt=port.execute(ctx,a,b).orElseThrow();
        var step=new ScalarExtractionProof.Lemma(ScalarExtractionProof.BACKEND,a,b,receipt);
        var limits=ProofLimits.defaults();
        var bundle=ScalarExtractionProof.build(a,b,ctx,List.of(step),limits,port).orElseThrow();
        var bytes=ScalarCertificateCodec.encode(bundle,limits);
        var restored=ScalarCertificateCodec.read(bytes,ScalarCertificateCodec.hash(bytes),ctx,limits);
        assertTrue(ScalarExtractionProof.check(a,b,ctx,restored,limits,port));
        assertFalse(ScalarExtractionProof.check(a,b,ctx,restored,limits));
        var other=port.execute(ctx,b,b).orElseThrow();
        var bad=new ScalarExtractionProof.Lemma(step.ruleId(),a,b,new ScalarExtractionProof.BackendWitness(receipt.backend(),
            receipt.obligationJson(),receipt.executionJson(),other.resultJson()));
        var forged=new ScalarExtractionProof.Bundle(ctx,List.of(bad),restored.dag());
        byte[] rehashed=ScalarCertificateCodec.encode(forged,limits);
        var reopened=ScalarCertificateCodec.read(rehashed,ScalarCertificateCodec.hash(rehashed),ctx,limits);
        assertFalse(ScalarExtractionProof.check(a,b,ctx,reopened,limits,port));
    }
}
