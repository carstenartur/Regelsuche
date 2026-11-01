package de.regelsuche.sdk.optimization.power;

import java.math.BigInteger;
import java.util.Objects;

/** Proposed x^(k-1) * knownInverse(x^k); the original successful inverse must remain.
 * This plan does not authorize deleting or moving a potentially throwing inverse operation.
 */
public record UnitInverseReusePlan(Premises source,FixedPowerPlan predecessorPower) {
    public static final int MAX_BINDING_ID_LENGTH=4096;
    /** Caller-established premises in one algebra and domain: poweredBinding is baseBinding^power,
     * knownInverseBinding is the retained original successful two-sided inverse of poweredBinding,
     * and multiplication is total and associative with a two-sided identity. These statements
     * require source proof; neither names, a prime-looking modulus nor this transport establishes them.
     */
    public record Premises(BigInteger power,String algebraId,String domainId,String baseBinding,
            String poweredBinding,String knownInverseBinding,boolean totalAssociativeMultiplicationWithIdentity,
            boolean successfulTwoSidedInverse) {
        public Premises {
            FixedPowerPlan.boundedExponent(power);
            boundedId(algebraId); boundedId(domainId); boundedId(baseBinding); boundedId(poweredBinding); boundedId(knownInverseBinding);
        }
        private static void boundedId(String id) {
            // No content scan in transport: optimizer/verifier must reserve/check its work first.
            if(id==null || id.length()>MAX_BINDING_ID_LENGTH) throw new IllegalArgumentException("BINDING_ID_LIMIT");
        }
    }
    public UnitInverseReusePlan { Objects.requireNonNull(source); Objects.requireNonNull(predecessorPower); }
    static void checkPremises(Premises source,PowerWork work) {
        work.charge(1); Objects.requireNonNull(source); work.exponent(source.power());
        work.identifier(source.algebraId()); work.identifier(source.domainId()); work.identifier(source.baseBinding());
        work.identifier(source.poweredBinding()); work.identifier(source.knownInverseBinding());
        if(source.power().signum()<=0) throw new PowerWork.Unavailable("POSITIVE_POWER_REQUIRED");
        if(!source.totalAssociativeMultiplicationWithIdentity()) throw new PowerWork.Unavailable("TOTAL_ASSOCIATIVE_MULTIPLICATION_WITH_IDENTITY_REQUIRED");
        if(!source.successfulTwoSidedInverse()) throw new PowerWork.Unavailable("ORIGINAL_SUCCESSFUL_TWO_SIDED_INVERSE_REQUIRED");
    }
}
