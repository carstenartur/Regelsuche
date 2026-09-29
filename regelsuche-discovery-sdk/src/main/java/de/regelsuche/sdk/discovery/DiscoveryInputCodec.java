package de.regelsuche.sdk.discovery;

import java.util.Objects;
import java.util.function.Function;

/**
 * Domain-owned boundary between a Java input value and its persistent seed payload.
 * Implementations must encode deterministically and reject malformed input when decoding.
 * This is an input codec, not an assertion of mathematical correctness.
 *
 * @param <I> the input accepted by the domain
 */
public interface DiscoveryInputCodec<I> {
    String encode(I input);

    I decode(String payload);

    /** Creates a null-checking codec from domain-specific encoding and parsing functions. */
    static <I> DiscoveryInputCodec<I> of(
            Function<? super I, String> encoder,
            Function<String, ? extends I> decoder
    ) {
        Objects.requireNonNull(encoder, "encoder");
        Objects.requireNonNull(decoder, "decoder");
        return new DiscoveryInputCodec<>() {
            @Override
            public String encode(I input) {
                return Objects.requireNonNull(
                    encoder.apply(Objects.requireNonNull(input, "input")), "encoded payload");
            }

            @Override
            public I decode(String payload) {
                return Objects.requireNonNull(
                    decoder.apply(Objects.requireNonNull(payload, "payload")), "decoded input");
            }
        };
    }
}
