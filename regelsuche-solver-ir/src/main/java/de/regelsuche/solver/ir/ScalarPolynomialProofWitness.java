package de.regelsuche.solver.ir;

import de.regelsuche.ast.Expr;
import de.regelsuche.assumption.Assumption;
import de.regelsuche.egraph.proof.ScalarCertificateCodec;
import de.regelsuche.egraph.proof.ScalarExtractionProof;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.polynomial.ExactParsedUnivariatePolynomialView;
import de.regelsuche.parse.ExpressionParser;
import java.util.Optional;

/** Reconstructs the exact selected obligation and reruns the bounded native backend on the receiving side. */
public final class ScalarPolynomialProofWitness implements ScalarExtractionProof.BackendVerifier {
    public static final String ID = "polynomial-normal-form/1";
    public Optional<ScalarExtractionProof.BackendWitness> execute(ScalarExtractionProof.Context context,Expr left,Expr right) {
        String source=ExpressionFormatter.format(left), target=ExpressionFormatter.format(right);
        if (!admitted(source) || !admitted(target)) return Optional.empty();
        try {
            var obligation = new SolverObligationFactory().equality("native-scalar-equality",source,target,
                context.assumptions().stream().map(Assumption::expression).toList(),SolverIr.RequestedEvidence.SYMBOLIC_CERTIFICATE,
                new SolverIr.SourceProvenance("native-scalar-egraph",ScalarCertificateCodec.SCHEMA,ScalarCertificateCodec.contextHash(context)));
            var execution = new PolynomialNormalFormSolverBackend().execute(obligation);
            if (execution.result().status() != SolverIr.ResultStatus.CONFIRMED) return Optional.empty();
            return Optional.of(new ScalarExtractionProof.BackendWitness(ID,obligation.toCanonicalJson(),execution.toCanonicalJson(),execution.result().toCanonicalJson()));
        } catch (IllegalArgumentException unsupported) { return Optional.empty(); }
    }
    private static boolean admitted(String expression) {
        if (expression.length()>256) return false;
        return new ExactParsedUnivariatePolynomialView().analyze(new ExpressionParser().parseExactTerm(expression)).supported();
    }
    @Override public boolean verify(ScalarExtractionProof.Context context,Expr source,Expr target,ScalarExtractionProof.BackendWitness witness) {
        return ID.equals(witness.backend()) && execute(context,source,target).filter(witness::equals).isPresent();
    }
}
