package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Verifies the exact output produced by the Gradle reference-page generator. */
@EnabledIfSystemProperty(
    named = StaticReferencePageContractTest.OUTPUT_PROPERTY,
    matches = ".+"
)
class StaticReferencePageContractTest {
    static final String OUTPUT_PROPERTY =
        "regelsuche.staticReferencePageDir";

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
            "PRE-GENERATED REFERENCE"),
        List.of("runReferenceCampaign"),
        List.of("generateStaticReferencePage"),
        List.of("Dockerfile.proof")
    );

    @Test
    void generatedReferencePageSatisfiesTheCheckoutContract()
            throws Exception {
        Path output = Path.of(System.getProperty(OUTPUT_PROPERTY))
            .toAbsolutePath()
            .normalize();
        List<String> failures = new ArrayList<>();

        for (String fileName : REQUIRED_FILES) {
            Path file = output.resolve(fileName);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(file)
                    || Files.size(file) == 0L) {
                failures.add("missing, empty or non-regular: " + file);
            }
        }

        Path index = output.resolve("index.html");
        if (Files.isRegularFile(index, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(index)) {
            String text = Files.readString(index, StandardCharsets.UTF_8);
            for (List<String> alternatives : REQUIRED_INDEX_MARKERS) {
                if (alternatives.stream().noneMatch(text::contains)) {
                    failures.add(
                        "index.html is missing one of: "
                            + String.join(", ", alternatives));
                }
            }
        }

        assertTrue(
            failures.isEmpty(),
            () -> "Static reference page validation failed:\n"
                + String.join("\n", failures));
    }
}
