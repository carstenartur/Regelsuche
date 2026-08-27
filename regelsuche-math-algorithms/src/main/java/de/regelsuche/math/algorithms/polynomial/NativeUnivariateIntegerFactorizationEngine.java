package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.BigIntegerDomain;
import de.regelsuche.polynomial.FactorizationEngine;
import de.regelsuche.polynomial.FactorizationRequest;
import java.math.BigInteger;
import java.util.Objects;

/** General bounded native univariate factorization over {@code Z[x]}. */
public final class NativeUnivariateIntegerFactorizationEngine
        implements FactorizationEngine<BigInteger> {
    public static final String ENGINE_ID =
        "regelsuche.factorization.native-univariate-integer/v1";

    private final NativeUnivariateFactorizationPolicy policy;

    public NativeUnivariateIntegerFactorizationEngine(
        NativeUnivariateFactorizationPolicy policy
    ) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public static NativeUnivariateIntegerFactorizationEngine
            boundedDefaults() {
        return new NativeUnivariateIntegerFactorizationEngine(
            NativeUnivariateFactorizationPolicy.boundedDefaults());
    }

    public NativeUnivariateFactorizationPolicy policy() {
        return policy;
    }

    @Override
    public String engineId() {
        return ENGINE_ID;
    }

    @Override
    public String coefficientDomainId() {
        return BigIntegerDomain.DOMAIN_ID;
    }

    @Override
    public EngineResult<BigInteger> propose(
        FactorizationRequest<BigInteger> request
    ) {
        return NativeUnivariateFactorizationPipeline.factorInteger(
            request,
            policy,
            ENGINE_ID);
    }
}
