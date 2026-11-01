package de.regelsuche.sdk.optimization;

import de.regelsuche.ast.Expr;
import de.regelsuche.search.program.JointComputationPlan;
import java.util.*;

/** Conditional unit-root theorem in the same positive modular ring. The caller must
 * establish the bound original inverse's success; neither a source ID nor this receipt observes it.
 */
public final class ModularUnitVerifier {
    public record Verification(boolean verified,String reason,long work) { }
    public Verification verifyPowerRoot(OptimizationRequest request,Expr base,int positiveExponent,CancellationToken token) {
        Objects.requireNonNull(request); Objects.requireNonNull(base);
        var work=new VerificationWork(request,token);
        try {
            work.charge(1);
            if(positiveExponent<1 || positiveExponent>PolynomialProof.MAX_DEGREE)
                return new Verification(false,"POSITIVE_BOUNDED_EXPONENT_REQUIRED",work.used());
            var checker=new SemanticChecker(request,work); checker.validate();
            Expr inverse=checker.successfulInverse();
            if(inverse==null) return new Verification(false,"SUCCESSFUL_INVERSE_PREMISE_REQUIRED",work.used());
            var valuePlan=new JointComputationPlan(request.plan().inputs(),Map.of(),List.of(
                new JointComputationPlan.Output("unit-base",NumericKind.BIG_INTEGER.type(),base)));
            if(!checker.check(valuePlan,valuePlan).accepted()) return new Verification(false,"UNIT_BASE_NOT_VALIDATED",work.used());
            var args=JavaExpressions.operands(inverse);
            Expr power=JavaExpressions.operation(NumericKind.BIG_INTEGER,NumericOperation.POW,base,JavaExpressions.literal(positiveExponent));
            boolean identity=new PolynomialProof(args.get(1),work).equivalent(args.getFirst(),power);
            return new Verification(identity,identity?"VERIFIED_CONDITIONAL_MODULAR_UNIT_ROOT":"UNIT_POWER_IDENTITY_NOT_PROVED",work.used());
        } catch(PolynomialProof.OutsideFragment outside) { return new Verification(false,"OUTSIDE_PROVED_NUMERIC_FRAGMENT",work.used()); }
        catch(VerificationWork.Stopped stopped) { return new Verification(false,stopped.cancelled?"CANCELLED":"VERIFICATION_BUDGET_EXCEEDED",work.used()); }
        catch(IllegalArgumentException invalid) { return new Verification(false,invalid.getMessage(),work.used()); }
    }
}
