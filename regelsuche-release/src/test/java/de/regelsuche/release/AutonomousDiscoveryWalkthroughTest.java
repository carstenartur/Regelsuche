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
    void readmeClaimsMatchTheCurrentRepresentativeCase() throws Exception {
        var cases = releaseRun.qualificationRun().utility().cases();
        var representative = cases.stream().filter(item -> item.materialGain()).findFirst().orElse(cases.getFirst());
        Path root = repositoryRoot();
        String readme = Files.readString(root.resolve("README.md")).replaceAll("\\s+", " ");
        assertTrue(readme.contains(
                "](docs/generated/autonomous-discovery-walkthrough-shared-core-v3/representative-search.svg)"));
        assertTrue(readme.contains("| Verbesserte Basis | " + (representative.baselineReached() ? "ja" : "nein")
                + " | " + representative.baselineExploredStates() + " | " + representative.baselinePathLength() + " |"));
        assertTrue(readme.contains("| Mit gelernter Regel | " + (representative.candidateReached() ? "ja" : "nein")
                + " | " + representative.candidateExploredStates() + " | " + representative.candidatePathLength() + " |"));
    }

    @Test
    void committedGalleryMatchesTheCurrentQualifiedRun(@TempDir Path temp) throws Exception {
        new AutonomousDiscoveryWalkthroughRunner().generate(temp, REVISION, releaseRun);
        Path root = repositoryRoot();
        assertTreesEqual(temp.resolve("figures"),
                root.resolve("docs/generated/autonomous-discovery-walkthrough-shared-core-v3"));
        var reference = new ObjectMapper().readTree(root.resolve(
                "docs/generated/autonomous-discovery-walkthrough-shared-core-v3-reference.json").toFile());
        try (var figures = Files.list(temp.resolve("figures"))) {
            for (Path figure : figures.sorted().toList()) {
                String digest = "sha256:" + java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(figure)));
                assertEquals(digest, reference.path("figures").required(figure.getFileName().toString()).asText());
            }
        }
    }

    private static Path repositoryRoot() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("docs/generated"))) root = root.getParent();
        org.junit.jupiter.api.Assertions.assertNotNull(root, "checkout documentation directory");
        return root;
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
