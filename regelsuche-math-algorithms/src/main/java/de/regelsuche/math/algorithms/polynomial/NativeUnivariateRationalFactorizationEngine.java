package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.ExactRationalField;
import de.regelsuche.polynomial.FactorizationEngine;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.scalar.ExactRational;
import java.util.Objects;

/** General bounded native univariate factorization over {@code Q[x]}. */
public final class NativeUnivariateRationalFactorizationEngine
        implements FactorizationEngine<ExactRational> {
    public static final String ENGINE_ID =
        "regelsuche.factorization.native-univariate-rational/v1";

    private final NativeUnivariateFactorizationPolicy policy;

    public NativeUnivariateRationalFactorizationEngine(
        NativeUnivariateFactorizationPolicy policy
    ) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public static NativeUnivariateRationalFactorizationEngine
            boundedDefaults() {
        return new NativeUnivariateRationalFactorizationEngine(
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
        return ExactRationalField.DOMAIN_ID;
    }

    @Override
    public EngineResult<ExactRational> propose(
        FactorizationRequest<ExactRational> request
    ) {
        return NativeUnivariateFactorizationPipeline.factor(
            request,
            policy,
            NativeCoefficientAdapter.RationalAdapter.INSTANCE);
    }
}
