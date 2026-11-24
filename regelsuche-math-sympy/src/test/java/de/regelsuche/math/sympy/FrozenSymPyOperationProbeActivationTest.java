package de.regelsuche.math.sympy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class FrozenSymPyOperationProbeActivationTest {
    private static final List<String> PROPERTIES =
        List.of("frozen.checkout", "frozen.graalpy", "frozen.packages");
    private final Map<String, String> previous = new HashMap<>();

    @BeforeEach
    void preserveAndClearHistoricalProvisioning() {
        for (String property : PROPERTIES) {
            previous.put(property, System.getProperty(property));
            System.clearProperty(property);
        }
    }

    @AfterEach
    void restoreHistoricalProvisioning() {
        previous.forEach((property, value) -> {
            if (value == null) System.clearProperty(property);
            else System.setProperty(property, value);
        });
    }

    @Test
    void noHistoricalPropertiesMeansTheProbeWasNotRequested() {
        assertThrows(TestAbortedException.class, this::runOriginalProbe);
    }

    @ParameterizedTest
    @MethodSource("partialProvisioning")
    void anyHistoricalPropertyRequiresCompleteProvisioning(Map<String, String> supplied, String missing) {
        supplied.forEach(System::setProperty);
        var failure = assertThrows(AssertionFailedError.class, this::runOriginalProbe);
        assertTrue(failure.getMessage().contains(missing), failure::getMessage);
    }

    static Stream<Arguments> partialProvisioning() {
        return Stream.of(
            Arguments.of(Map.of("frozen.checkout", "/checkout"), "frozen.graalpy"),
            Arguments.of(Map.of("frozen.graalpy", "/graalpy"), "frozen.checkout"),
            Arguments.of(Map.of("frozen.packages", "/packages"), "frozen.checkout"),
            Arguments.of(Map.of("frozen.checkout", "/checkout", "frozen.graalpy", "/graalpy"), "frozen.packages"),
            Arguments.of(Map.of("frozen.checkout", "/checkout", "frozen.packages", "/packages"), "frozen.graalpy"),
            Arguments.of(Map.of("frozen.graalpy", "/graalpy", "frozen.packages", "/packages"), "frozen.checkout"));
    }

    @ParameterizedTest
    @MethodSource("blankProvisioning")
    void emptyOrBlankPropertiesFailInsteadOfUsingTheWorkingDirectory(String property, String value) {
        PROPERTIES.forEach(name -> System.setProperty(name, "/configured"));
        System.setProperty(property, value);
        var failure = assertThrows(AssertionFailedError.class, this::runOriginalProbe);
        assertTrue(failure.getMessage().contains(property), failure::getMessage);
    }

    static Stream<Arguments> blankProvisioning() {
        return PROPERTIES.stream().flatMap(property ->
            Stream.of(Arguments.of(property, ""), Arguments.of(property, " \t")));
    }

    @Test
    void completePropertiesEnterTheOriginalProbeAndRejectAnInvalidCheckout(@TempDir Path checkout) {
        System.setProperty("frozen.checkout", checkout.toString());
        System.setProperty("frozen.graalpy", checkout.resolve("missing-graalpy").toString());
        System.setProperty("frozen.packages", checkout.resolve("missing-packages").toString());
        var failure = assertThrows(NoSuchFileException.class, this::runOriginalProbe);
        assertEquals(checkout.resolve("scripts/check-frozen-polynomial-operation-work.py").toString(),
            failure.getFile());
    }

    private void runOriginalProbe() throws Exception {
        new FrozenSymPyOperationProbeIT().originalGraalPyReceivesTheJavaCodecAndReturnsTheExactAdmittedPrefix();
    }
}
