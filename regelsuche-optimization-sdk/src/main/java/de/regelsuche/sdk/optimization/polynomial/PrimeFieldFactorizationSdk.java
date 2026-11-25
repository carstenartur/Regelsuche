package de.regelsuche.sdk.optimization.polynomial;

import de.regelsuche.math.algorithms.polynomial.FiniteFieldFactorizationPolicy;
import de.regelsuche.math.algorithms.polynomial.PrimeFieldMultiplicity;
import de.regelsuche.math.algorithms.polynomial.LargePrimeFactorization;
import de.regelsuche.math.algorithms.polynomial.LargePrimeFactorizationVerifier;
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
    /** Large-prime path; the returned status explicitly retains a prime assumption. */
    public static LargePrimeFactorization.Result factor(FactorizationRequest<BigInteger> request,
        LargePrimeFactorization.Policy policy, CancellationToken token) {
        return LargePrimeFactorization.factor(request, policy, token::isCancelled);
    }

    /** Check supplied complete factors with no search or field-enumeration limit. */
    public static LargePrimeFactorization.Result verify(FactorizationRequest<BigInteger> request,
        LargePrimeFactorizationVerifier.Policy policy, LargePrimeFactorization.Certificate certificate,
        CancellationToken token) {
        return LargePrimeFactorizationVerifier.verify(request, policy, certificate, token::isCancelled);
    }
}
