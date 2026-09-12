package de.regelsuche.extension.runtime;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.regelsuche.extension.ExtensionApi;
import de.regelsuche.extension.ExtensionContext;
import de.regelsuche.extension.ExtensionDescriptor;
import de.regelsuche.extension.ExtensionPoint;
import de.regelsuche.extension.PluginDependency;
import de.regelsuche.extension.PluginDescriptor;
import de.regelsuche.extension.RegelsuchePlugin;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ExtensionDependencyDeclarationTest {
    private static final ExtensionPoint<Runnable> POINT =
        ExtensionPoint.of("dependency.runnable", Runnable.class);
    private static final List<String> MALFORMED =
        List.of("", " ", "\t", "\n", " any", "any ", " 1 ", "1\t");

    @Test
    void suppliedConstraintsArePreservedVerbatim() {
        assertAll(MALFORMED.stream().map(value -> () -> assertEquals(
            value, new PluginDependency("provider", value, true).versionConstraint(),
            "a supplied declaration must reach the runtime without normalization")));
    }

    @Test
    void malformedOptionalConstraintsAreRejectedEvenWhenDependencyIsAbsent() {
        assertAll(MALFORMED.stream().map(value -> () -> {
            AtomicInteger contributions = new AtomicInteger();
            RegelsuchePlugin dependent = plugin("dependent",
                List.of(new PluginDependency("absent", value, true)),
                context -> contributions.incrementAndGet());
            assertThrows(IllegalArgumentException.class, () -> {
                try (var ignored = ExtensionRuntime.open(config(dependent))) {
                }
            });
            assertEquals(0, contributions.get());
        }));
    }

    @Test
    void malformedConstraintsCannotBorrowValidityFromAPresentProvider() {
        for (boolean optional : List.of(false, true)) {
            assertAll(MALFORMED.stream().map(value -> () -> {
                AtomicInteger contributions = new AtomicInteger();
                RegelsuchePlugin provider = plugin("provider", List.of(),
                    context -> contributions.incrementAndGet());
                RegelsuchePlugin dependent = plugin("dependent",
                    List.of(new PluginDependency("provider", value, optional)),
                    context -> contributions.incrementAndGet());
                assertThrows(IllegalArgumentException.class, () -> {
                    try (var ignored = ExtensionRuntime.open(config(provider, dependent))) {
                    }
                });
                assertEquals(0, contributions.get());
            }));
        }
    }

    @Test
    void rejectedWhitespaceReloadRetainsPublishedCatalogAndImplementation() {
        AtomicInteger invocations = new AtomicInteger();
        RegelsuchePlugin good = plugin("good", List.of(), context -> context.contribute(
            POINT, ExtensionDescriptor.named("run"), invocations::incrementAndGet));
        AtomicInteger rejectedContributions = new AtomicInteger();
        try (var runtime = ExtensionRuntime.open(config(good))) {
            var previous = runtime.catalog();
            assertAll(MALFORMED.stream().map(value -> () -> {
                RegelsuchePlugin invalid = plugin("invalid",
                    List.of(new PluginDependency("absent", value, true)),
                    context -> rejectedContributions.incrementAndGet());
                var result = runtime.reload(config(invalid));
                assertFalse(result.applied());
                assertSame(previous, runtime.catalog());
                assertEquals(previous.contentHash(), result.currentCatalogHash());
                runtime.catalog().find(POINT, "run").orElseThrow().implementation().run();
            }));
            assertEquals(MALFORMED.size(), invocations.get());
            assertEquals(0, rejectedContributions.get());
        }
    }

    @Test
    void onlyAbsentConstraintDefaultsToAnyAndValidConstraintsStillLoad() {
        assertEquals("any", new PluginDependency("provider", null, true).versionConstraint());
        AtomicInteger contributions = new AtomicInteger();
        RegelsuchePlugin provider = plugin("provider", List.of(),
            context -> contributions.incrementAndGet());
        RegelsuchePlugin dependent = plugin("dependent", List.of(
            new PluginDependency("provider", "1", false),
            new PluginDependency("absent", null, true)),
            context -> contributions.incrementAndGet());
        try (var ignored = ExtensionRuntime.open(config(provider, dependent))) {
            assertEquals(2, contributions.get());
        }
    }

    private static ExtensionRuntimeConfig config(RegelsuchePlugin... plugins) {
        return new ExtensionRuntimeConfig("1", false, List.of(plugins), List.of(),
            source -> { throw new AssertionError("unexpected external admission"); },
            ExtensionDependencyDeclarationTest.class.getClassLoader());
    }

    private static RegelsuchePlugin plugin(
        String id, List<PluginDependency> dependencies, Consumer<ExtensionContext> body
    ) {
        return new RegelsuchePlugin() {
            @Override
            public PluginDescriptor descriptor() {
                return new PluginDescriptor(id, id, "1", ExtensionApi.VERSION,
                    "1", Set.of(), dependencies, "dependency-declaration-test");
            }

            @Override
            public void contribute(ExtensionContext context) {
                body.accept(context);
            }
        };
    }
}
