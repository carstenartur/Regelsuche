package de.regelsuche.sdk.discovery;

import de.regelsuche.discovery.domain.DiscoveryDomain;
import java.util.Objects;

/**
 * Binds one Java input type and its wire codec to an existing discovery domain.
 * The domain's generator must decode seeds using the same codec. The underlying
 * domain, runner, budgets, provider checks and evidence format remain unchanged.
 * The {@link #domain()} accessor is the explicit untyped import/catalog boundary;
 * ordinary Java consumers pass this binding to {@link RegelsucheDiscovery#forDomain(TypedDiscoveryDomain)}.
 *
 * @param <I> input type
 * @param <S> state type
 * @param <C> candidate type
 * @param <K> certificate type
 */
public record TypedDiscoveryDomain<I, S, C, K>(
        DiscoveryDomain<S, C, K> domain,
        DiscoveryInputCodec<I> inputCodec
) {
    public TypedDiscoveryDomain {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(inputCodec, "inputCodec");
    }

    public static <I, S, C, K> TypedDiscoveryDomain<I, S, C, K> of(
            DiscoveryDomain<S, C, K> domain,
            DiscoveryInputCodec<I> inputCodec
    ) {
        return new TypedDiscoveryDomain<>(domain, inputCodec);
    }
}
