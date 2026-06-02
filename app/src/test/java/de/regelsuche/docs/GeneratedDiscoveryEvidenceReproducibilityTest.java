package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GeneratedDiscoveryEvidenceReproducibilityTest {
    @Test
    void regeneratingDiscoveryGalleryIsDeterministic() throws IOException {
        Path repoRoot = locateRepoRoot();
        new DocsDiscoveryGalleryGenerator().generate(repoRoot);
        Map<String, String> first = snapshot(repoRoot);

        new DocsDiscoveryGalleryGenerator().generate(repoRoot);
        Map<String, String> second = snapshot(repoRoot);
        assertEquals(first, second, "Consecutive gallery generations must be deterministic.");
    }

    private Map<String, String> snapshot(Path repoRoot) throws IOException {
        LinkedHashMap<String, String> files = new LinkedHashMap<>();
        files.put("README.md", Files.readString(repoRoot.resolve("README.md"), StandardCharsets.UTF_8));
        files.put("docs/demo-gallery.md", Files.readString(repoRoot.resolve("docs/demo-gallery.md"), StandardCharsets.UTF_8));
        Path generated = repoRoot.resolve("docs/generated/discovery");
        for (Path file : Files.walk(generated).filter(Files::isRegularFile).sorted().toList()) {
            String relative = generated.relativize(file).toString().replace('\\', '/');
            files.put("docs/generated/discovery/" + relative, Files.readString(file, StandardCharsets.UTF_8));
        }
        return Map.copyOf(files);
    }

    private static Path locateRepoRoot() {
        Path candidate = Paths.get(".").toAbsolutePath().normalize();
        for (int i = 0; i < 6; i++) {
            if (Files.exists(candidate.resolve("README.md")) && Files.exists(candidate.resolve("settings.gradle"))) {
                return candidate;
            }
            candidate = candidate.getParent();
            if (candidate == null) {
                break;
            }
        }
        throw new IllegalStateException("Could not locate repository root");
    }
}
