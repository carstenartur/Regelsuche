package de.regelsuche.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

class ExtensionCatalogTest {
    private static final ExtensionPoint<Runnable> RUNNABLES =
        ExtensionPoint.of("example.runnable", Runnable.class);
    private static final ExtensionOrigin ORIGIN =
        ExtensionOrigin.classpathPlugin("fixture", "1", "unit-test");

    @Test
    void catalogHashIsIndependentOfRegistrationOrder() {
        var first = new RegisteredExtension<>(RUNNABLES,
            ExtensionDescriptor.named("a"), (Runnable) () -> {}, ORIGIN);
        var second = new RegisteredExtension<>(RUNNABLES,
            ExtensionDescriptor.named("b"), (Runnable) () -> {}, ORIGIN);

        assertEquals(
            ExtensionCatalogs.of(List.of(first, second)).contentHash(),
            ExtensionCatalogs.of(List.of(second, first)).contentHash());
    }

    @Test
    void implementationStringIsNotPartOfManifest() {
        Runnable implementation = new Runnable() {
            @Override public void run() {}
            @Override public String toString() { return "must-not-appear"; }
        };
        var catalog = ExtensionCatalogs.of(List.of(new RegisteredExtension<>(
            RUNNABLES,
            new ExtensionDescriptor("r", "Name with quotes and tab\t", List.of("x,y")),
            implementation,
            ORIGIN)));

        assertFalse(catalog.canonicalManifest().contains("must-not-appear"));
        assertEquals(1, catalog.registrations(RUNNABLES).size());
    }

    @Test
    void duplicateContributionWithinOnePointFailsClosed() {
        var first = new RegisteredExtension<>(RUNNABLES,
            ExtensionDescriptor.named("same"), (Runnable) () -> {}, ORIGIN);
        var second = new RegisteredExtension<>(RUNNABLES,
            ExtensionDescriptor.named("same"), (Runnable) () -> {}, ORIGIN);

        assertThrows(IllegalArgumentException.class,
            () -> ExtensionCatalogs.of(List.of(first, second)));
    }

    @Test
    void onePointIdCannotBindTwoContractTypes() {
        var callablePoint = ExtensionPoint.of("example.runnable", Callable.class);
        var runnable = new RegisteredExtension<>(RUNNABLES,
            ExtensionDescriptor.named("r"), (Runnable) () -> {}, ORIGIN);
        var callable = new RegisteredExtension<>(callablePoint,
            ExtensionDescriptor.named("c"), (Callable<String>) () -> "x", ORIGIN);

        assertThrows(IllegalArgumentException.class,
            () -> ExtensionCatalogs.of(List.of(runnable, callable)));
    }

    @Test
    void sameContributionIdInDifferentPointsIsAllowed() {
        var callablePoint = ExtensionPoint.of("example.callable", Callable.class);
        var runnable = new RegisteredExtension<>(RUNNABLES,
            ExtensionDescriptor.named("same"), (Runnable) () -> {}, ORIGIN);
        var callable = new RegisteredExtension<>(callablePoint,
            ExtensionDescriptor.named("same"), (Callable<String>) () -> "x", ORIGIN);

        var catalog = ExtensionCatalogs.of(List.of(runnable, callable));
        assertEquals(1, catalog.registrations(RUNNABLES).size());
        assertEquals(1, catalog.registrations(callablePoint).size());
    }

    @Test
    void emptyCatalogHasCanonicalManifestAndNoLookupResults() {
        var catalog = ExtensionCatalogs.empty();

        assertEquals("{\"extensions\":[]}", catalog.canonicalManifest());
        assertEquals(ExtensionCatalogs.of(List.of()).contentHash(), catalog.contentHash());
        assertTrue(catalog.registrations(RUNNABLES).isEmpty());
        assertTrue(catalog.find(RUNNABLES, "missing").isEmpty());
    }

    @Test
    void lookupUsesAnImmutableSnapshotAndTheExactPointContract() {
        var registration = new RegisteredExtension<>(RUNNABLES,
            ExtensionDescriptor.named("r"), (Runnable) () -> {}, ORIGIN);
        var input = new ArrayList<RegisteredExtension<?>>();
        input.add(registration);
        var catalog = ExtensionCatalogs.of(input);
        String hash = catalog.contentHash();
        input.clear();

        assertEquals(registration, catalog.find(RUNNABLES, "r").orElseThrow());
        assertTrue(catalog.find(RUNNABLES, "missing").isEmpty());
        assertTrue(catalog.find(ExtensionPoint.of("example.other", Runnable.class), "r").isEmpty());
        assertTrue(catalog.find(ExtensionPoint.of(RUNNABLES.id(), Callable.class), "r").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> catalog.find(RUNNABLES, " "));
        assertThrows(NullPointerException.class, () -> catalog.find(null, "r"));
        assertThrows(UnsupportedOperationException.class,
            () -> catalog.registrations(RUNNABLES).clear());
        assertEquals(hash, catalog.contentHash());
        assertEquals(List.of(registration), catalog.registrations(RUNNABLES));
    }

    @Test
    void manifestEscapesQuotesBackslashesAndEmbeddedControlCharacters() {
        String name = "a\"b\\c\bd\fe\nf\rg\th\u0001i";
        var catalog = ExtensionCatalogs.of(List.of(new RegisteredExtension<>(
            RUNNABLES, new ExtensionDescriptor("r", name, List.of()),
            (Runnable) () -> {}, ORIGIN)));

        assertTrue(catalog.canonicalManifest().contains(
            "\"name\":\"a\\\"b\\\\c\\bd\\fe\\nf\\rg\\th\\u0001i\""));
        assertFalse(catalog.canonicalManifest().chars().anyMatch(ch -> ch < 0x20));
        assertEquals(name, catalog.find(RUNNABLES, "r").orElseThrow().descriptor().name());
    }
}
