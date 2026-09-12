package de.regelsuche.extension.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.extension.ExtensionApi;
import de.regelsuche.extension.ExtensionContext;
import de.regelsuche.extension.ExtensionDescriptor;
import de.regelsuche.extension.ExtensionPoint;
import de.regelsuche.extension.PluginDependency;
import de.regelsuche.extension.PluginDescriptor;
import de.regelsuche.extension.RegelsuchePlugin;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ExtensionRuntimeCompatibilityTest {
    private static final ExtensionPoint<Runnable> POINT =
        ExtensionPoint.of("compatibility.runnable", Runnable.class);
    private static final List<String> INVALID_VERSIONS = List.of(
        "garbage", "1.invalid.0", "0.0.invalid", "1..0", "1.0.", "1.0.0.0",
        "1.0.0-rc.1", "v1.0.0", "-1.0.0", "1.0.+1", "1.0.0+meta");

    @Test
    void largeNewerCoreRequirementsAreRejectedBeforeContribution() {
        for (String required : List.of(
                "2147483648.0.0", "1.2147483648.0", "1.0.2147483648",
                "9999999999999999999999999999999999999999.0.0")) {
            var calls = new AtomicInteger();
            var plugin = plugin("large", "1", required, List.of(), c -> calls.incrementAndGet());
            assertThrows(IllegalArgumentException.class, () -> {
                try (var ignored = ExtensionRuntime.open(config("1.0.0", plugin))) {
                    // Closing an unexpectedly accepted runtime also avoids leaking test resources.
                }
            });
            assertEquals(0, calls.get());
        }
    }

    @Test
    void malformedRequirementsAreNotNormalizedIntoCompatibleVersions() {
        for (String required : INVALID_VERSIONS) {
            var calls = new AtomicInteger();
            var plugin = plugin("malformed", "1", required, List.of(), c -> calls.incrementAndGet());
            assertThrows(IllegalArgumentException.class, () -> {
                try (var ignored = ExtensionRuntime.open(config("1.0.0", plugin))) {
                }
            });
            assertEquals(0, calls.get());
        }
    }

    @Test
    void malformedHostVersionIsRejectedEvenWithoutPlugins() {
        for (String host : INVALID_VERSIONS) {
            assertThrows(IllegalArgumentException.class, () -> {
                try (var ignored = ExtensionRuntime.open(config(host))) {
                }
            });
        }
    }

    @Test
    void malformedHostVersionIsRejectedBeforeExternalAdmission() {
        var admissions = new AtomicInteger();
        var config = new ExtensionRuntimeConfig(
            "invalid", false, List.of(), List.of(Path.of("not-loaded.jar")),
            source -> {
                admissions.incrementAndGet();
                throw new AssertionError("invalid host must not reach external admission");
            }, getClass().getClassLoader());
        assertThrows(IllegalArgumentException.class, () -> ExtensionRuntime.open(config));
        assertEquals(0, admissions.get());
    }

    @Test
    void malformedOptionalMissingConstraintFailsBeforeContribution() {
        for (String constraint : List.of(">=2", "[1,2)", "1.*", "^2", "2 || 3")) {
            var calls = new AtomicInteger();
            var plugin = plugin("optional", "1", "1", List.of(
                new PluginDependency("absent", constraint, true)), c -> calls.incrementAndGet());
            assertThrows(IllegalArgumentException.class, () -> {
                try (var ignored = ExtensionRuntime.open(config("1", plugin))) {
                }
            });
            assertEquals(0, calls.get());
        }
    }

    @Test
    void failedCompatibilityReloadRetainsExactCatalogAndUsableContribution() {
        var invocations = new AtomicInteger();
        var good = plugin("good", "1", "1", List.of(), c -> c.contribute(
            POINT, ExtensionDescriptor.named("run"), invocations::incrementAndGet));
        var rejectedContributions = new AtomicInteger();
        var incompatible = plugin("incompatible", "1", "2147483648", List.of(),
            c -> rejectedContributions.incrementAndGet());
        var invalidOptional = plugin("invalid-optional", "1", "1", List.of(
            new PluginDependency("absent", ">=2", true)), c -> rejectedContributions.incrementAndGet());
        try (var runtime = ExtensionRuntime.open(config("1.0.0", good))) {
            var previous = runtime.catalog();
            for (var candidate : List.of(incompatible, invalidOptional)) {
                var result = runtime.reload(config("1.0.0", candidate));
                assertFalse(result.applied());
                assertSame(previous, runtime.catalog());
                assertEquals(previous.contentHash(), result.previousCatalogHash());
                assertEquals(previous.contentHash(), result.currentCatalogHash());
                assertFalse(result.diagnostic().isBlank());
                runtime.catalog().find(POINT, "run").orElseThrow().implementation().run();
            }
            assertEquals(2, invocations.get());
            assertEquals(0, rejectedContributions.get());
        }
    }

    @Test
    void numericLevelsAllowOmittedZerosAndUseNumericOrdering() {
        for (String required : List.of("1", "1.0", "1.0.0", "01.00.000", "0.99.99")) {
            var calls = new AtomicInteger();
            var plugin = plugin("compatible", "1", required, List.of(), c -> calls.incrementAndGet());
            try (var ignored = ExtensionRuntime.open(config("1.0.0", plugin))) {
                assertEquals(1, calls.get());
            }
        }
        var calls = new AtomicInteger();
        var plugin = plugin("numeric", "1", "1.9.99", List.of(), c -> calls.incrementAndGet());
        try (var ignored = ExtensionRuntime.open(config("1.10.0", plugin))) {
            assertEquals(1, calls.get());
        }
    }

    @Test
    void largeNumericLevelsRemainComparableWithoutOverflow() {
        String large = "9999999999999999999999999999999999999999";
        var calls = new AtomicInteger();
        var plugin = plugin("large-compatible", "1", large + ".9.0", List.of(),
            c -> calls.incrementAndGet());
        try (var ignored = ExtensionRuntime.open(config(large + ".10.0", plugin))) {
            assertEquals(1, calls.get());
        }
        assertThrows(IllegalArgumentException.class, () -> {
            try (var ignored = ExtensionRuntime.open(config(large + ".8.0", plugin))) {
            }
        });
        assertEquals(1, calls.get());
    }

    @Test
    void numericDeclarationLengthIsBoundedWithoutTruncation() {
        String maximum = "9".repeat(128);
        var plugin = plugin("maximum", "1", maximum, List.of(), c -> {});
        try (var runtime = ExtensionRuntime.open(config(maximum, plugin))) {
            assertTrue(runtime.catalog().registrations(POINT).isEmpty());
        }
        assertThrows(IllegalArgumentException.class, () -> {
            try (var ignored = ExtensionRuntime.open(config(maximum + "9"))) {
            }
        });
        var tooLong = plugin("too-long", "1", maximum + "9", List.of(), c -> {});
        assertThrows(IllegalArgumentException.class, () -> {
            try (var ignored = ExtensionRuntime.open(config("1", tooLong))) {
            }
        });
    }

    @Test
    void optionalAbsenceAllowsSupportedAnyAndExactConstraints() {
        var plugin = plugin("optional", "1", "1", List.of(
            new PluginDependency("absent-any", "any", true),
            new PluginDependency("absent-exact", "2.1-rc+meta", true)), c -> {});
        try (var runtime = ExtensionRuntime.open(config("1", plugin))) {
            assertTrue(runtime.catalog().registrations(POINT).isEmpty());
        }
    }

    @Test
    void presentOptionalDependencyStillMustMatchItsExactVersion() {
        var provider = plugin("provider", "1", "1", List.of(), c -> {});
        var dependent = plugin("dependent", "1", "1", List.of(
            new PluginDependency("provider", "2", true)), c -> {});
        assertThrows(IllegalArgumentException.class, () -> {
            try (var ignored = ExtensionRuntime.open(config("1", dependent, provider))) {
            }
        });
    }

    @Test
    void pluginReleaseVersionsRemainOpaqueExactValues() {
        var provider = plugin("provider", "2.1-rc+meta", "1", List.of(), c -> {});
        var dependent = plugin("dependent", "1", "1", List.of(
            new PluginDependency("provider", "2.1-rc+meta", false)), c -> {});
        try (var runtime = ExtensionRuntime.open(config("1", dependent, provider))) {
            assertTrue(runtime.catalog().registrations(POINT).isEmpty());
        }
    }

    private static ExtensionRuntimeConfig config(String core, RegelsuchePlugin... plugins) {
        return new ExtensionRuntimeConfig(core, false, List.of(plugins), List.of(),
            source -> { throw new AssertionError("unexpected external admission"); },
            ExtensionRuntimeCompatibilityTest.class.getClassLoader());
    }

    private static RegelsuchePlugin plugin(
        String id, String version, String minimumCore, List<PluginDependency> dependencies,
        Consumer<ExtensionContext> body
    ) {
        return new RegelsuchePlugin() {
            @Override
            public PluginDescriptor descriptor() {
                return new PluginDescriptor(id, id, version, ExtensionApi.VERSION,
                    minimumCore, Set.of(), dependencies, "compatibility-test");
            }

            @Override
            public void contribute(ExtensionContext context) {
                body.accept(context);
            }
        };
    }
}
