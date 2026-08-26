package de.regelsuche.math.sympy;

import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;

/**
 * Primary in-process SymPy adapter backed by one reusable embedded GraalPy
 * context. All proposals remain untrusted until FactorizationVerifier accepts
 * their exact product.
 */
public final class GraalPySymPyFactorizationEngine<C>
        extends SymPyFactorizationEngine<C>
        implements AutoCloseable {
    public static final String INTEGER_ENGINE_ID =
        "regelsuche.factorization.sympy-graalpy.integer/v1";
    public static final String RATIONAL_ENGINE_ID =
        "regelsuche.factorization.sympy-graalpy.rational/v1";

    private final GraalPySymPyRuntime runtime;

    private GraalPySymPyFactorizationEngine(
        String engineId,
        SymPyFactorizationCodec<C> codec,
        SymPyFactorizationPolicy policy
    ) {
        super(engineId, codec, policy);
        runtime = new GraalPySymPyRuntime();
    }

    public static GraalPySymPyFactorizationEngine<BigInteger> integers() {
        return integers(SymPyFactorizationPolicy.pinned());
    }

    public static GraalPySymPyFactorizationEngine<BigInteger> integers(
        SymPyFactorizationPolicy policy
    ) {
        return new GraalPySymPyFactorizationEngine<>(
            INTEGER_ENGINE_ID,
            SymPyFactorizationCodec.integers(),
            policy);
    }

    public static GraalPySymPyFactorizationEngine<ExactRational> rationals() {
        return rationals(SymPyFactorizationPolicy.pinned());
    }

    public static GraalPySymPyFactorizationEngine<ExactRational> rationals(
        SymPyFactorizationPolicy policy
    ) {
        return new GraalPySymPyFactorizationEngine<>(
            RATIONAL_ENGINE_ID,
            SymPyFactorizationCodec.rationals(),
            policy);
    }

    @Override
    SymPyInvocation invoke(String payload) {
        return runtime.invoke(payload, policy().timeout());
    }

    @Override
    public void close() {
        runtime.close();
    }
}
