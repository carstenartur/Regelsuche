package de.regelsuche.release;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AutonomousDiscoveryWalkthroughTest {
    private static final String REVISION =
        "0123456789abcdef0123456789abcdef01234567";
    private ReleaseReadinessRunner.ReleaseRun releaseRun;

    @BeforeAll
    void createQualifiedReleaseRun() {
        releaseRun = new ReleaseReadinessRunner().runQualified(null);
    }

    @Test
    void generatesCanonicalCardMarkdownFiguresAndRawEvidence(@TempDir Path temp)
            throws Exception {
        Path first = temp.resolve("first");
        Path second = temp.resolve("second");
        AutonomousDiscoveryWalkthroughRunner runner =
            new AutonomousDiscoveryWalkthroughRunner();

        var firstCard = runner.generate(first, REVISION, releaseRun);
        var secondCard = runner.generate(second, REVISION, releaseRun);

        assertEquals(firstCard.contentHash(), secondCard.contentHash());
        assertTreesEqual(first, second);
        for (String file : List.of(
                "result-card.json",
                "result-card.md",
                "walkthrough.md",
                "figures/sequence.svg",
                "figures/paired-utility.svg",
                "figures/candidate-lineage.svg",
                "figures/representative-search.svg",
                "evidence/release-readiness-run.json",
                "evidence/campaign/production-campaign-manifest.json",
                "evidence/qualification/qualification-utility.json")) {
            assertTrue(Files.isRegularFile(first.resolve(file)), file);
            assertTrue(Files.size(first.resolve(file)) > 0L, file);
        }

        var card = new ObjectMapper().readTree(first.resolve("result-card.json").toFile());
        assertEquals(
            "regelsuche.autonomous-discovery-result-card/v1",
            card.path("schema").asText());
        assertEquals("ABSENT", card.path("researchBrief")
            .path("targetOrExpectedAnswerAccess").asText());
        assertTrue(card.path("qualification").path("qualified").asBoolean());
        assertEquals(0, card.path("qualification")
            .path("correctnessRegressionCount").asInt());
        assertEquals("NOT_EVALUATED", card.path("claimBoundaries")
            .path("externalNoveltyStatus").asText());
        assertEquals("NOT_EVALUATED", card.path("claimBoundaries")
            .path("promotionStatus").asText());
        assertEquals("NOT_EVALUATED", card.path("claimBoundaries")
            .path("publicEvidenceStatus").asText());
    }

    @Test
    void rejectsUnboundRepositoryRevision(@TempDir Path temp) {
        assertThrows(IllegalArgumentException.class, () ->
            new AutonomousDiscoveryWalkthroughRunner().generate(
                temp, "WORKTREE", releaseRun));
    }

    private static void assertTreesEqual(Path first, Path second)
            throws Exception {
        List<Path> relative;
        try (var paths = Files.walk(first)) {
            relative = paths.filter(Files::isRegularFile)
                .map(first::relativize)
                .sorted(Comparator.comparing(Path::toString))
                .toList();
        }
        List<Path> other;
        try (var paths = Files.walk(second)) {
            other = paths.filter(Files::isRegularFile)
                .map(second::relativize)
                .sorted(Comparator.comparing(Path::toString))
                .toList();
        }
        assertEquals(relative, other);
        for (Path path : relative) {
            assertArrayEquals(
                Files.readAllBytes(first.resolve(path)),
                Files.readAllBytes(second.resolve(path)),
                path.toString());
        }
    }
}
