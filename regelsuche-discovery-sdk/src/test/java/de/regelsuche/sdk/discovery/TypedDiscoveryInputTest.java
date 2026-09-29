package de.regelsuche.sdk.discovery;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.discovery.domain.DiscoveryDomain;
import de.regelsuche.discovery.domain.DiscoveryDomain.CounterexampleResult;
import de.regelsuche.discovery.domain.DiscoveryDomain.Evaluation;
import de.regelsuche.discovery.domain.DiscoveryDomain.InvariantResult;
import de.regelsuche.discovery.domain.DiscoveryDomain.ObjectiveAssessment;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TypedDiscoveryInputTest {
    private record Input(int value) { }

    private static final DiscoveryInputCodec<Input> CODEC = DiscoveryInputCodec.of(
        input -> Integer.toString(input.value()),
        payload -> new Input(Integer.parseInt(payload)));

    private static TypedDiscoveryDomain<Input, Integer, Integer, Integer> domain() {
        var raw = DiscoveryDomainBuilder.<Integer, Integer, Integer>domain("typed-input", "v1")
            .generator(seed -> List.of(CODEC.decode(seed.payload()).value()))
            .stateCodec(Object::toString)
            .invariant("positive", value -> value > 0
                ? InvariantResult.pass() : InvariantResult.fail("non-positive"))
            .operator("no-successors", value -> List.of())
            .objective(value -> new ObjectiveAssessment(0, true, Map.of()))
            .candidate(context -> context.currentState(), Object::toString)
            .counterexamples((value, budget) -> CounterexampleResult.noneFound(0, Map.of()))
            .evaluator(value -> Evaluation.confirmed(value, "finite identity", Map.of()))
            .certificate("INTEGER_WITNESS", Object::toString, Object::toString)
            .build();
        return TypedDiscoveryDomain.of(raw, CODEC);
    }

    @Test
    void typedInputUsesTheSameSeedAndEvidenceAsTheLegacyBoundary() {
        var domain = domain();
        var typed = RegelsucheDiscovery.forDomain(domain)
            .campaign("same-run").seed("seed", new Input(7), "test")
            .budget(DiscoveryBudgets.small()).run();
        var legacy = RegelsucheDiscovery.forDomain(domain.domain())
            .campaign("same-run").seed("seed", "7", "test")
            .budget(DiscoveryBudgets.small()).run();
        assertEquals(7, typed.selectedCandidate().orElseThrow());
        assertEquals(legacy.canonicalEvidence(), typed.canonicalEvidence());
        DiscoveryRunAssertions.assertThat(typed).isConfirmed().hasContentAddressedEvidence();
    }

    @Test
    void inputValuesActuallyReachTheDomain() {
        var run = RegelsucheDiscovery.forDomain(domain())
            .campaign("different-input").seed("seed", new Input(11), "test").run();
        assertEquals(11, run.selectedCandidate().orElseThrow());
    }

    @Test
    void retainsRequestValidationAndRejectsNullInput() {
        assertThrows(IllegalStateException.class, () -> RegelsucheDiscovery.forDomain(domain()).run());
        assertThrows(IllegalStateException.class, () -> RegelsucheDiscovery.forDomain(domain())
            .campaign("run").run());
        assertThrows(NullPointerException.class, () -> RegelsucheDiscovery.forDomain(domain())
            .seed("seed", null, "test"));
        assertThrows(NullPointerException.class, () -> TypedDiscoveryDomain.of(null, CODEC));
        assertThrows(NullPointerException.class, () -> TypedDiscoveryDomain.of(domain().domain(), null));
        assertThrows(IllegalArgumentException.class, () -> RegelsucheDiscovery.forDomain(domain())
            .campaign(" "));
        assertThrows(NullPointerException.class, () -> RegelsucheDiscovery.forDomain(domain()).budget(null));
    }

    @Test
    void codecChecksBothSidesOfTheTextBoundary() {
        assertEquals(new Input(7), CODEC.decode(CODEC.encode(new Input(7))));
        assertThrows(NullPointerException.class, () -> CODEC.encode(null));
        assertThrows(NullPointerException.class, () -> CODEC.decode(null));
        assertThrows(NumberFormatException.class, () -> CODEC.decode("seven"));
        assertThrows(NullPointerException.class, () -> DiscoveryInputCodec.of(null, CODEC::decode));
        assertThrows(NullPointerException.class, () -> DiscoveryInputCodec.of(CODEC::encode, null));
        DiscoveryInputCodec<Input> badEncoder = DiscoveryInputCodec.of(value -> null, CODEC::decode);
        DiscoveryInputCodec<Input> badDecoder = DiscoveryInputCodec.of(CODEC::encode, value -> null);
        assertThrows(NullPointerException.class, () -> badEncoder.encode(new Input(1)));
        assertThrows(NullPointerException.class, () -> badDecoder.decode("1"));
    }
}
