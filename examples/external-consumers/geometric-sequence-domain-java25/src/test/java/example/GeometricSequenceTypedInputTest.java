package example;

import static de.regelsuche.sdk.discovery.DiscoveryRunAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.sdk.discovery.DiscoveryBudgets;
import de.regelsuche.sdk.discovery.RegelsucheDiscovery;
import example.GeometricSequenceDomainProvider.Input;
import java.util.List;
import org.junit.jupiter.api.Test;

class GeometricSequenceTypedInputTest {
    private static final Input INPUT = new Input(List.of(2L, 4L, 8L, 16L), List.of(32L, 64L), 6);
    private static final String PAYLOAD = "observed=2,4,8,16;holdout=32,64;maxMultiplier=6";

    @Test
    void typedAndSerializedInputsHaveIdenticalEvidence() {
        var typed = RegelsucheDiscovery.forDomain(GeometricSequenceDomainProvider.typedDomain())
            .campaign("typed-sequence").seed("powers", INPUT, "test").run();
        var legacy = RegelsucheDiscovery.forDomain(GeometricSequenceDomainProvider.domain())
            .campaign("typed-sequence").seed("powers", PAYLOAD, "test").run();
        assertThat(typed).isConfirmed().hasContentAddressedEvidence();
        assertEquals(2, typed.selectedCandidate().orElseThrow().multiplier());
        assertEquals(legacy.canonicalEvidence(), typed.canonicalEvidence());
    }

    @Test
    void typedInputRetainsRefutationAndBudgetSemantics() {
        var refuted = RegelsucheDiscovery.forDomain(GeometricSequenceDomainProvider.typedDomain())
            .campaign("refuted").seed("powers", new Input(INPUT.observed(), List.of(33L), 6), "test").run();
        assertThat(refuted).isRefuted();
        var exhausted = RegelsucheDiscovery.forDomain(GeometricSequenceDomainProvider.typedDomain())
            .campaign("budget").seed("powers", INPUT, "test").budget(DiscoveryBudgets.tiny()).run();
        assertThat(exhausted).isBudgetExhausted();
    }

    @Test
    void codecPreservesLegacyPayloadAndTheDocumentedDefault() {
        var codec = GeometricSequenceDomainProvider.INPUT_CODEC;
        assertEquals(PAYLOAD, codec.encode(INPUT));
        assertEquals(INPUT, codec.decode(codec.encode(INPUT)));
        assertEquals(8, codec.decode("observed=2,4;holdout=8").maxMultiplier());
    }

    @Test
    void rejectsUnknownMalformedDuplicateAndTruncatedFields() {
        for (String invalid : List.of(
                "observed=2,4;holdout=8;maxMultipler=6",
                "observed=2,4;holdout=8;ignored",
                "observed=2,4;holdout=8;maxMultiplier=6;maxMultiplier=7",
                "observed=2,4,;holdout=8")) {
            assertThrows(IllegalArgumentException.class,
                () -> GeometricSequenceDomainProvider.INPUT_CODEC.decode(invalid), invalid);
        }
    }
}
