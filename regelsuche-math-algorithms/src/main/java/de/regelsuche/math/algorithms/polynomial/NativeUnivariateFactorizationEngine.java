package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.FactorizationEngine;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.Objects;

/** Complete bounded native univariate factorization over Z[x] or Q[x]. */
public final class NativeUnivariateFactorizationEngine<C>
        implements FactorizationEngine<C> {
    private final NativeUnivariateFactorizationPolicy policy;
    private final NativeCoefficientAdapter<C> adapter;

    private NativeUnivariateFactorizationEngine(
        NativeUnivariateFactorizationPolicy policy,
        NativeCoefficientAdapter<C> adapter
    ) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
    }

    public static NativeUnivariateFactorizationEngine<BigInteger>
            integers(
        NativeUnivariateFactorizationPolicy policy
    ) {
        return new NativeUnivariateFactorizationEngine<>(
            policy,
            NativeCoefficientAdapter.IntegerAdapter.INSTANCE);
    }

    public static NativeUnivariateFactorizationEngine<ExactRational>
            rationals(
        NativeUnivariateFactorizationPolicy policy
    ) {
        return new NativeUnivariateFactorizationEngine<>(
            policy,
            NativeCoefficientAdapter.RationalAdapter.INSTANCE);
    }

    public static NativeUnivariateFactorizationEngine<BigInteger>
            boundedIntegers() {
        return integers(
            NativeUnivariateFactorizationPolicy.boundedDefaults());
    }

    public static NativeUnivariateFactorizationEngine<ExactRational>
            boundedRationals() {
        return rationals(
            NativeUnivariateFactorizationPolicy.boundedDefaults());
    }

    public NativeUnivariateFactorizationPolicy policy() {
        return policy;
    }

    @Override
    public String engineId() {
        return adapter.engineId();
    }

    @Override
    public String coefficientDomainId() {
        return adapter.domainId();
    }

    @Override
    public EngineResult<C> propose(
        FactorizationRequest<C> request
    ) {
        return NativeUnivariateFactorizationPipeline.factor(
            request,
            policy,
            adapter);
    }
}
