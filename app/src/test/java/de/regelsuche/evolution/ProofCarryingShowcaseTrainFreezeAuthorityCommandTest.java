package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProofCarryingShowcaseTrainFreezeAuthorityCommandTest {
    private static final String IMPLEMENTATION_COMMIT = "1".repeat(40);
    private static final String AUTHORITY_COMMIT = "2".repeat(40);
    private static final long RUN_ID = 123456789L;

    @TempDir
    Path temporaryDirectory;

    @Test
    void authorizesOnlyTheCanonicalFirstCreateRun() throws IOException {
        byte[] manifest = writeAuthorityManifest(
            temporaryDirectory,
            IMPLEMENTATION_COMMIT);
        var source = authoritySource(
            AUTHORITY_COMMIT,
            IMPLEMENTATION_COMMIT,
            manifest,
            List.of(new ProofCarryingShowcaseTrainFreezeAuthorityCommand
                .RunEvidence(
                    RUN_ID,
                    "create",
                    ProofCarryingShowcaseTrainFreezeAuthorityCommand
                        .AUTHORITY_BRANCH,
                    AUTHORITY_COMMIT)));

        var authority =
            ProofCarryingShowcaseTrainFreezeAuthorityCommand.authorize(
                temporaryDirectory,
                environment(false),
                source);

        assertEquals(AUTHORITY_COMMIT, authority.repositoryCommit());
        assertEquals(
            IMPLEMENTATION_COMMIT,
            authority.implementationCommit());
        assertEquals(RUN_ID, authority.runId());
        assertEquals(64, authority.authorityManifestSha256().length());
    }

    @Test
    void rejectsAPreviouslyConsumedAuthorityBranch() throws IOException {
        byte[] manifest = writeAuthorityManifest(
            temporaryDirectory,
            IMPLEMENTATION_COMMIT);
        var current = new ProofCarryingShowcaseTrainFreezeAuthorityCommand
            .RunEvidence(
                RUN_ID,
                "create",
                ProofCarryingShowcaseTrainFreezeAuthorityCommand
                    .AUTHORITY_BRANCH,
                AUTHORITY_COMMIT);
        var previous = new ProofCarryingShowcaseTrainFreezeAuthorityCommand
            .RunEvidence(
                RUN_ID - 1,
                "create",
                ProofCarryingShowcaseTrainFreezeAuthorityCommand
                    .AUTHORITY_BRANCH,
                AUTHORITY_COMMIT);

        assertThrows(
            IllegalArgumentException.class,
            () -> ProofCarryingShowcaseTrainFreezeAuthorityCommand.authorize(
                temporaryDirectory,
                environment(false),
                authoritySource(
                    AUTHORITY_COMMIT,
                    IMPLEMENTATION_COMMIT,
                    manifest,
                    List.of(current, previous))));
    }

    @Test
    void rejectsNonCanonicalAuthorityBytes() throws IOException {
        Path authority = authorityPath(temporaryDirectory);
        Files.createDirectories(authority.getParent());
        String canonical =
            ProofCarryingShowcaseTrainFreezeAuthorityCommand
                .canonicalManifest(IMPLEMENTATION_COMMIT);
        String nonCanonical = canonical.substring(
            0,
            canonical.length() - 1) + " \n";
        Files.writeString(
            authority,
            nonCanonical,
            StandardCharsets.UTF_8);
        byte[] bytes = Files.readAllBytes(authority);

        assertThrows(
            IllegalArgumentException.class,
            () -> ProofCarryingShowcaseTrainFreezeAuthorityCommand.authorize(
                temporaryDirectory,
                environment(false),
                authoritySource(
                    AUTHORITY_COMMIT,
                    IMPLEMENTATION_COMMIT,
                    bytes,
                    List.of())));
    }

    @Test
    void rejectsAuthorityCommitWithAnotherChangedFile() throws IOException {
        byte[] manifest = writeAuthorityManifest(
            temporaryDirectory,
            IMPLEMENTATION_COMMIT);
        var source = new ProofCarryingShowcaseTrainFreezeAuthorityCommand
            .AuthoritySource() {
            @Override
            public ProofCarryingShowcaseTrainFreezeAuthorityCommand
                    .CommitEvidence commit(
                        String repository,
                        String commit
                    ) {
                return new ProofCarryingShowcaseTrainFreezeAuthorityCommand
                    .CommitEvidence(
                        AUTHORITY_COMMIT,
                        List.of(IMPLEMENTATION_COMMIT),
                        List.of(
                            new ProofCarryingShowcaseTrainFreezeAuthorityCommand
                                .ChangedFile(
                                    ProofCarryingShowcaseTrainFreezeAuthorityCommand
                                        .AUTHORITY_FILE,
                                    "added",
                                    gitBlobSha1(manifest)),
                            new ProofCarryingShowcaseTrainFreezeAuthorityCommand
                                .ChangedFile(
                                    "README.md",
                                    "modified",
                                    "3".repeat(40))));
            }

            @Override
            public List<ProofCarryingShowcaseTrainFreezeAuthorityCommand
                    .RunEvidence> createRuns(
                        String repository,
                        String branch
                    ) {
                return List.of();
            }
        };

        assertThrows(
            IllegalArgumentException.class,
            () -> ProofCarryingShowcaseTrainFreezeAuthorityCommand.authorize(
                temporaryDirectory,
                environment(false),
                source));
    }

    @Test
    void executesTrainFreezeAndWritesOnlyPreRandomnessEvidence()
            throws IOException {
        Path repositoryPlan = repositoryRoot().resolve(
            "research/showcase/proof-carrying-self-improvement/"
                + "showcase-plan.json");
        Path plan = temporaryDirectory.resolve(
            "research/showcase/proof-carrying-self-improvement/"
                + "showcase-plan.json");
        Files.createDirectories(plan.getParent());
        Files.copy(repositoryPlan, plan);
        byte[] manifest = writeAuthorityManifest(
            temporaryDirectory,
            IMPLEMENTATION_COMMIT);
        var source = authoritySource(
            AUTHORITY_COMMIT,
            IMPLEMENTATION_COMMIT,
            manifest,
            List.of());

        var execution =
            ProofCarryingShowcaseTrainFreezeAuthorityCommand.execute(
                temporaryDirectory,
                environment(true),
                source,
                ProofCarryingShowcaseTrainFreezeAuthorityCommandTest
                    ::writeSyntheticFreeze);

        assertEquals(
            ProofCarryingShowcaseTrainFreezeAuthorityCommand
                .EXECUTION_STATUS,
            execution.status());
        assertTrue(Files.isRegularFile(
            execution.evidenceDirectory().resolve(
                "artifact-sha256sums.txt")));
        Path receipt = execution.evidenceDirectory().resolve(
            "execution-receipt.json");
        String receiptText = Files.readString(receipt);
        assertTrue(receiptText.contains(
            "\"status\":\"TRAIN_FREEZE_EXECUTED_FINAL_TEST_UNSEEN\""));
        assertTrue(receiptText.contains(
            "\"repositoryCommit\":\"" + AUTHORITY_COMMIT + "\""));
        assertFalse(receiptText.contains("public-randomness-receipt"));
        assertFalse(receiptText.contains("generated-final-test"));
    }

    private static ProofCarryingShowcaseTrainAndFreezeCommand.WrittenFreeze
            writeSyntheticFreeze(
                Path planPath,
                String repositoryCommit,
                Path outputDirectory
            ) {
        try {
            ProofCarryingShowcasePlan showcasePlan =
                ProofCarryingShowcasePlan.read(planPath);
            String training = EvolutionGenome.hash("training");
            String candidate = EvolutionGenome.hash("candidate");
            var freeze = ProofCarryingShowcaseCandidateFreeze.create(
                showcasePlan,
                repositoryCommit,
                training,
                EvolutionGenome.hash("selection"),
                candidate,
                EvolutionGenome.hash("alpha"),
                EvolutionGenome.hash("rendering"),
                EvolutionGenome.hash("inventory"),
                EvolutionGenome.hash("budget"),
                EvolutionGenome.hash("protocol"),
                List.of(EvolutionGenome.hash("seed")),
                4,
                true,
                true,
                3,
                2_000_000_000L);
            Files.createDirectories(outputDirectory);
            Path freezePath = outputDirectory.resolve(
                "candidate-freeze.json");
            Files.writeString(
                freezePath,
                freeze.toCanonicalJson(),
                StandardCharsets.UTF_8);
            Files.writeString(
                outputDirectory.resolve("selected-candidate.json"),
                "{}",
                StandardCharsets.UTF_8);
            return new ProofCarryingShowcaseTrainAndFreezeCommand
                .WrittenFreeze(
                    outputDirectory,
                    freezePath,
                    freeze.contentHash(),
                    freeze.candidateContentHash(),
                    freeze.trainingRunHash());
        } catch (IOException exception) {
            throw new IllegalStateException(
                "unable to create synthetic retained freeze",
                exception);
        }
    }

    private static Map<String, String> environment(boolean includeWorkflow) {
        java.util.HashMap<String, String> result = new java.util.HashMap<>();
        result.put("GITHUB_EVENT_NAME", "create");
        result.put(
            "GITHUB_REF",
            ProofCarryingShowcaseTrainFreezeAuthorityCommand.AUTHORITY_REF);
        result.put("GITHUB_RUN_ATTEMPT", "1");
        result.put("GITHUB_RUN_ID", Long.toString(RUN_ID));
        result.put("GITHUB_REPOSITORY", "carstenartur/Regelsuche");
        result.put(
            "REGELSUCHE_AUTHORITY_GITHUB_SHA",
            AUTHORITY_COMMIT);
        if (includeWorkflow) {
            result.put("GITHUB_WORKFLOW", "CI");
        }
        return Map.copyOf(result);
    }

    private static ProofCarryingShowcaseTrainFreezeAuthorityCommand
            .AuthoritySource authoritySource(
                String authorityCommit,
                String implementationCommit,
                byte[] manifest,
                List<ProofCarryingShowcaseTrainFreezeAuthorityCommand
                    .RunEvidence> runs
            ) {
        return new ProofCarryingShowcaseTrainFreezeAuthorityCommand
            .AuthoritySource() {
            @Override
            public ProofCarryingShowcaseTrainFreezeAuthorityCommand
                    .CommitEvidence commit(
                        String repository,
                        String commit
                    ) {
                assertEquals("carstenartur/Regelsuche", repository);
                assertEquals(authorityCommit, commit);
                return new ProofCarryingShowcaseTrainFreezeAuthorityCommand
                    .CommitEvidence(
                        authorityCommit,
                        List.of(implementationCommit),
                        List.of(
                            new ProofCarryingShowcaseTrainFreezeAuthorityCommand
                                .ChangedFile(
                                    ProofCarryingShowcaseTrainFreezeAuthorityCommand
                                        .AUTHORITY_FILE,
                                    "added",
                                    gitBlobSha1(manifest))));
            }

            @Override
            public List<ProofCarryingShowcaseTrainFreezeAuthorityCommand
                    .RunEvidence> createRuns(
                        String repository,
                        String branch
                    ) {
                assertEquals("carstenartur/Regelsuche", repository);
                assertEquals(
                    ProofCarryingShowcaseTrainFreezeAuthorityCommand
                        .AUTHORITY_BRANCH,
                    branch);
                return runs;
            }
        };
    }

    private static byte[] writeAuthorityManifest(
        Path root,
        String implementationCommit
    ) throws IOException {
        Path authority = authorityPath(root);
        Files.createDirectories(authority.getParent());
        byte[] bytes = ProofCarryingShowcaseTrainFreezeAuthorityCommand
            .canonicalManifest(implementationCommit)
            .getBytes(StandardCharsets.UTF_8);
        Files.write(authority, bytes);
        return bytes;
    }

    private static Path authorityPath(Path root) {
        return root.resolve(
            ProofCarryingShowcaseTrainFreezeAuthorityCommand.AUTHORITY_FILE);
    }

    private static String gitBlobSha1(byte[] content) {
        byte[] header = ("blob " + content.length + "\0")
            .getBytes(StandardCharsets.US_ASCII);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(header);
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("settings.gradle"))
                    || Files.isRegularFile(
                        current.resolve("settings.gradle.kts"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }
}
