package de.regelsuche.sdk.discovery.python;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.discovery.domain.DiscoveryDomain.Evaluation;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class PythonDomainEnvelopeTest {
    @Test void fullCanonicalEnvelopeIncludingDefinitionAndEscapingMustFitTheLimit() {
        var limits = new PythonDiscoveryDomain.Limits(1024, 1024, 128, 10, 30000, 120000);
        var definition = new PythonDiscoveryDomain.Definition("python-control", "v1", "a".repeat(64), "control", limits);
        var adapter = new PythonDiscoveryDomain<Integer, String>(definition,
                new PythonDiscoveryDomainTest.FakeTransport(), Integer::parseInt, Object::toString,
                (candidate, witness) -> false, candidate -> Evaluation.refuted("control", Map.of()),
                "CONTROL", Function.identity(), Function.identity());
        assertTrue(adapter.domain().stateCodec().canonicalForm("0").length() <= 1024);
        assertThrows(IllegalArgumentException.class, () -> adapter.domain().stateCodec().canonicalForm("x".repeat(900)));
        assertThrows(IllegalArgumentException.class, () -> adapter.domain().certificateCodec().canonicalForm("x".repeat(900)));
        assertThrows(IllegalArgumentException.class, () -> adapter.domain().certificateCodec().canonicalForm("\n".repeat(400)));
        var largeCandidate = new PythonDiscoveryDomain<Integer, String>(definition,
                new PythonDiscoveryDomainTest.FakeTransport(), Integer::parseInt, ignored -> "x".repeat(900),
                (candidate, witness) -> false, candidate -> Evaluation.refuted("control", Map.of()),
                "CONTROL", Function.identity(), Function.identity());
        assertThrows(IllegalArgumentException.class, () -> largeCandidate.domain().candidateCodec().canonicalForm(1));
    }
}
