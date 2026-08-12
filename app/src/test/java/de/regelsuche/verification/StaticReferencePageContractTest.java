package de.regelsuche.verification;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class StaticReferencePageContractTest {

    private static final String ROOT_PROPERTY =
        "regelsuche.staticReferencePage.root";
    private static final String CONFIGURATION_MESSAGE =
        "The generated-page contract is configured by its build task";
    private static final List<String> REQUIRED_FILES = List.of(
        "index.html",
        "timeline.html",
        "observatory.html",
        "timeline.md",
        "version.txt"
    );
    private static final List<List<String>> REQUIRED_INDEX_MARKERS = List.of(
        List.of(
            "LIVE EXECUTION",
            "REPLAYED FROM CANONICAL",
            "PRE-GENERATED REFERENCE"
        ),
        List.of("runReferenceCampaign"),
        List.of("generateStaticReferencePage"),
        List.of("Dockerfile.proof")
    );

    @Test
    void generatedReferencePageContainsRequiredArtifactsAndMarkers()
            throws IOException {
        String configuredRoot = System.getProperty(ROOT_PROPERTY, "");
        assumeFalse(configuredRoot.isBlank(), CONFIGURATION_MESSAGE);

        Path root = Path.of(configuredRoot).toAbsolutePath().normalize();
        Path index = root.resolve("index.html");
        String indexText = Files.isRegularFile(index)
            ? Files.readString(index, StandardCharsets.UTF_8)
            : "";
        List<Executable> checks = new ArrayList<>();

        REQUIRED_FILES.stream()
            .map(root::resolve)
            .forEach(path -> checks.add(() -> assertTrue(
                Files.isRegularFile(path) && Files.size(path) > 0L,
                () -> "missing or empty: " + path
            )));
        REQUIRED_INDEX_MARKERS.forEach(alternatives -> checks.add(() ->
            assertTrue(
                alternatives.stream().anyMatch(indexText::contains),
                () -> "index.html is missing one of: "
                    + String.join(", ", alternatives)
            )
        ));

        assertAll("Static reference page validation failed", checks);
    }
}
