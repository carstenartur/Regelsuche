package de.regelsuche.sdk.optimization.polynomial;

import de.regelsuche.math.algorithms.polynomial.FiniteFieldFactorizationPolicy;
import de.regelsuche.math.algorithms.polynomial.PrimeFieldMultiplicity;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.sdk.optimization.CancellationToken;
import java.math.BigInteger;

/** Exact prime-field factorization with multiplicities. */
public final class PrimeFieldFactorizationSdk {
    private PrimeFieldFactorizationSdk() {}

    public static PrimeFieldMultiplicity.Result factor(FactorizationRequest<BigInteger> request,
        FiniteFieldFactorizationPolicy policy, CancellationToken token) {
        return PrimeFieldMultiplicity.factor(request, policy, token::isCancelled);
    }

    public static PrimeFieldMultiplicity.Result verify(FactorizationRequest<BigInteger> request,
        FiniteFieldFactorizationPolicy policy, PrimeFieldMultiplicity.Certificate certificate, CancellationToken token) {
        return PrimeFieldMultiplicity.verify(request, policy, certificate, token::isCancelled);
    }
}
