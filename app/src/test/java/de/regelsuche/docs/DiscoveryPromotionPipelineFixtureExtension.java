package de.regelsuche.docs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * Reuses the expensive, deterministic promotion-pipeline fixture across tests
 * in the same test JVM while proving that consumers do not mutate it.
 */
final class DiscoveryPromotionPipelineFixtureExtension
        implements BeforeEachCallback, AfterEachCallback, ParameterResolver {

    private static volatile DiscoveryPromotionPipelineFixture sharedFixture;

    @Override
    public void beforeEach(ExtensionContext context) {
        assertExistingFixtureUnchanged();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        assertExistingFixtureUnchanged();
    }

    @Override
    public boolean supportsParameter(
        ParameterContext parameterContext,
        ExtensionContext extensionContext
    ) {
        return parameterContext.getParameter().getType()
            == DiscoveryPromotionPipelineFixture.class;
    }

    @Override
    public Object resolveParameter(
        ParameterContext parameterContext,
        ExtensionContext extensionContext
    ) {
        if (!supportsParameter(parameterContext, extensionContext)) {
            throw new ParameterResolutionException(
                "Unsupported promotion-pipeline fixture parameter: "
                    + parameterContext.getParameter()
            );
        }
        DiscoveryPromotionPipelineFixture fixture = fixture();
        fixture.assertUnchanged();
        return fixture;
    }

    private static DiscoveryPromotionPipelineFixture fixture() {
        DiscoveryPromotionPipelineFixture current = sharedFixture;
        if (current != null) {
            return current;
        }
        synchronized (DiscoveryPromotionPipelineFixtureExtension.class) {
            current = sharedFixture;
            if (current == null) {
                current = DiscoveryPromotionPipelineFixture.create();
                sharedFixture = current;
            }
            return current;
        }
    }

    private static void assertExistingFixtureUnchanged() {
        DiscoveryPromotionPipelineFixture current = sharedFixture;
        if (current != null) {
            current.assertUnchanged();
        }
    }
}

final class DiscoveryPromotionPipelineFixture {
    private final Path outputDirectory;
    private final DiscoveryPromotionPipelineRunner.PipelineReport report;
    private final Map<String, String> expectedTreeState;

    private DiscoveryPromotionPipelineFixture(
        Path outputDirectory,
        DiscoveryPromotionPipelineRunner.PipelineReport report,
        Map<String, String> expectedTreeState
    ) {
        this.outputDirectory = outputDirectory;
        this.report = report;
        this.expectedTreeState = expectedTreeState;
    }

    static DiscoveryPromotionPipelineFixture create() {
        try {
            Path testTmp = Path.of(System.getProperty("java.io.tmpdir"))
                .toAbsolutePath()
                .normalize();
            Path output = testTmp.resolve(
                "shared-discovery-promotion-" + ProcessHandle.current().pid()
            );
            deleteRecursively(output);
            Files.createDirectories(output);
            DiscoveryPromotionPipelineRunner.PipelineReport report =
                new DiscoveryPromotionPipelineRunner().writeReport(output);
            return new DiscoveryPromotionPipelineFixture(
                output,
                report,
                treeState(output)
            );
        } catch (IOException exception) {
            throw new UncheckedIOException(
                "Cannot create shared discovery-promotion test fixture",
                exception
            );
        }
    }

    Path outputDirectory() {
        return outputDirectory;
    }

    Path campaignDirectory(String campaignId) {
        return outputDirectory.resolve(campaignId);
    }

    DiscoveryPromotionPipelineRunner.PipelineReport report() {
        return report;
    }

    Set<String> candidateIdsFromCampaigns(Set<String> campaignIds) {
        TreeSet<String> ids = new TreeSet<>();
        report.promotionRecords().stream()
            .filter(record -> campaignIds.contains(record.sourceCampaign()))
            .map(PromotionRecord::candidateId)
            .forEach(ids::add);
        return Set.copyOf(ids);
    }

    Set<String> inputTargetPairsFromCampaigns(Set<String> campaignIds) {
        TreeSet<String> pairs = new TreeSet<>();
        report.promotionRecords().stream()
            .filter(record -> campaignIds.contains(record.sourceCampaign()))
            .map(record -> inputTargetPair(
                record.originalExpression(),
                record.discoveredStructure()
            ))
            .forEach(pairs::add);
        return Set.copyOf(pairs);
    }

    List<NoveltyChecker.Candidate> noveltyCandidatesFromCampaigns(
        List<String> campaignIds
    ) {
        List<NoveltyChecker.Candidate> candidates = new ArrayList<>();
        for (String campaignId : campaignIds) {
            report.promotionRecords().stream()
                .filter(record -> campaignId.equals(record.sourceCampaign()))
                .map(record -> new NoveltyChecker.Candidate(
                    record.candidateId(),
                    record.family(),
                    record.originalExpression(),
                    record.discoveredStructure(),
                    record.sourceOperator(),
                    record.rulePath()
                ))
                .forEach(candidates::add);
        }
        return List.copyOf(candidates);
    }

    static String inputTargetPair(String input, String target) {
        return input + " -> " + target;
    }

    void assertUnchanged() {
        try {
            Map<String, String> actual = treeState(outputDirectory);
            if (expectedTreeState.equals(actual)) {
                return;
            }
            TreeSet<String> changed = new TreeSet<>();
            changed.addAll(expectedTreeState.keySet());
            changed.addAll(actual.keySet());
            changed.removeIf(path -> Objects.equals(
                expectedTreeState.get(path),
                actual.get(path)
            ));
            throw new AssertionError(
                "Shared discovery-promotion fixture was mutated: " + changed
            );
        } catch (IOException exception) {
            throw new AssertionError(
                "Cannot verify shared discovery-promotion fixture",
                exception
            );
        }
    }

    private static Map<String, String> treeState(Path root)
            throws IOException {
        Map<String, String> state = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted().toList()) {
                if (path.equals(root)) {
                    continue;
                }
                String relative = root.relativize(path)
                    .toString()
                    .replace('\\', '/');
                if (Files.isSymbolicLink(path)) {
                    state.put(
                        relative,
                        "symlink:" + Files.readSymbolicLink(path)
                    );
                } else if (Files.isDirectory(
                    path,
                    LinkOption.NOFOLLOW_LINKS
                )) {
                    state.put(relative, "directory");
                } else if (Files.isRegularFile(
                    path,
                    LinkOption.NOFOLLOW_LINKS
                )) {
                    state.put(
                        relative,
                        "file:" + sha256(Files.readAllBytes(path))
                    );
                } else {
                    state.put(relative, "other");
                }
            }
        }
        return Map.copyOf(state);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(root)) {
            paths = new ArrayList<>(walk.sorted((left, right) ->
                right.compareTo(left)).toList());
        }
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }
}
