package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.requireSha256;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.requireText;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import de.regelsuche.util.AtomicJsonFile;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Independently verifies exact held-out evidence from two checkout JVMs and
 * one digest-pinned, network-disabled Linux container.
 */
public final class TargetFreeHeldOutContainerReproductionVerifier {
    public static final String SCHEMA =
        "regelsuche.target-free-held-out-container-reproduction/v1";
    public static final String EVIDENCE_STATUS =
        "HOST_REPEAT_AND_PINNED_CONTAINER_BYTE_IDENTICAL";
    public static final String BASE_IMAGE =
        "eclipse-temurin:25.0.3_9-jdk-noble@"
            + "sha256:3eb81ed94d8c1a34422f19f8188548bdf02cae69c91d0328"
            + "afdbb7abed90f617";
    public static final String BASE_IMAGE_DIGEST =
        "sha256:3eb81ed94d8c1a34422f19f8188548bdf02cae69c91d0328"
            + "afdbb7abed90f617";
    public static final String PLATFORM = "linux/amd64";
    public static final String RUNTIME_USER = "reproducer:10001";
    public static final String SCHEMA_PATH =
        "docs/schemas/"
            + "regelsuche-target-free-held-out-container-reproduction-v1."
            + "schema.json";
    public static final String DOCKERFILE_PATH =
        "Dockerfile.target-free-held-out-reproduction";
    public static final String CLAIM_BOUNDARY =
        "Exact byte reproduction for one frozen repository revision in two "
            + "checkout JVM runs and one digest-pinned Linux container; no "
            + "claim of external mathematical novelty, global optimality, "
            + "universal policy superiority, cross-platform reproduction or "
            + "equal CPU cost.";
    public static final List<String> EXPECTED_FILES = List.of(
        TargetFreeHeldOutMatrixRunner.FREEZE_FILE_NAME,
        TargetFreeHeldOutMatrixRunner.PLAN_FILE_NAME,
        TargetFreeHeldOutMatrixRunner.QUALIFICATION_FILE_NAME);

    private static final String REPOSITORY = "carstenartur/Regelsuche";
    private static final String BUILD_NETWORK = "DEPENDENCY_RESOLUTION_ONLY";
    private static final String RUN_NETWORK = "DISABLED";
    private static final String HOST_ENVIRONMENT = "CHECKOUT_JVM";
    private static final String CONTAINER_ENVIRONMENT =
        "DIGEST_PINNED_LINUX_CONTAINER";
    private static final Pattern REVISION = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern HEX_256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern FROM = Pattern.compile(
        "^\\s*FROM\\s+(?:--platform=\\S+\\s+)?(\\S+)"
            + "(?:\\s+AS\\s+(\\S+))?\\s*$",
        Pattern.CASE_INSENSITIVE);

    private TargetFreeHeldOutContainerReproductionVerifier() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 10) {
            throw new IllegalArgumentException(
                "usage: <revision> <repository-root> <host-a> <host-b> "
                    + "<container-run> <dockerfile> <image-id-file> "
                    + "<platform> <schema> <output>");
        }
        ReproductionReceipt receipt = verify(
            args[0],
            Path.of(args[1]),
            Path.of(args[2]),
            Path.of(args[3]),
            Path.of(args[4]),
            Path.of(args[5]),
            Path.of(args[6]),
            args[7],
            Path.of(args[8]));
        Path output = Path.of(args[9]).toAbsolutePath().normalize();
        String canonical = receipt.toCanonicalJson();
        AtomicJsonFile.writeUtf8(output, canonical);
        if (!Files.isRegularFile(output)
                || !canonical.equals(Files.readString(
                    output, StandardCharsets.UTF_8))) {
            throw new IllegalStateException(
                "held-out reproduction receipt changed while writing");
        }
        System.out.println("targetFreeHeldOutReproduction="
            + receipt.content().evidenceStatus());
        System.out.println("targetFreeHeldOutArtifactSet="
            + receipt.content().artifactSetHash());
        System.out.println("targetFreeHeldOutContainerImage="
            + receipt.content().container().builtImageId());
        System.out.println("targetFreeHeldOutReproductionReceipt=" + output);
    }

    static ReproductionReceipt verify(
        String repositoryRevision,
        Path repositoryRoot,
        Path hostA,
        Path hostB,
        Path containerRun,
        Path dockerfile,
        Path imageIdFile,
        String platform,
        Path schemaPath
    ) throws IOException {
        String revision = requireRevision(repositoryRevision);
        Path root = requireDirectory(repositoryRoot, "repositoryRoot");
        RunSnapshot first = readRun(hostA, revision);
        RunSnapshot second = readRun(hostB, revision);
        RunSnapshot container = readRun(containerRun, revision);
        requireSameBytes(first, second, "host-repeat");
        requireSameBytes(first, container, "host-container");

        Path definition = requireFile(dockerfile, "dockerfile");
        requireExternalImages(definition);
        String imageId = requireSha256(
            Files.readString(requireFile(imageIdFile, "imageIdFile"),
                StandardCharsets.UTF_8).trim(),
            "builtImageId");
        if (!PLATFORM.equals(requireText(platform, "platform"))) {
            throw new IllegalArgumentException(
                "unexpected held-out reproduction platform: " + platform);
        }
        Path schema = requireFile(schemaPath, "schemaPath");
        requireSchemaIdentity(schema);

        List<ArtifactIdentity> artifacts = EXPECTED_FILES.stream()
            .map(name -> {
                byte[] value = Objects.requireNonNull(
                    first.bytes().get(name), name);
                return new ArtifactIdentity(
                    name,
                    value.length,
                    TargetFreeRepresentationEvaluationPlan.sha256(value));
            })
            .toList();
        String artifactSetHash = KnownStructureCatalog.sha256(
            TargetFreeHeldOutMatrixRunner.canonical(artifacts));
        MatrixSummary matrix = matrixSummary(first);
        ContainerIdentity containerIdentity = new ContainerIdentity(
            normalizedRelativePath(root, definition),
            TargetFreeRepresentationEvaluationPlan.sha256(
                Files.readAllBytes(definition)),
            BASE_IMAGE,
            BASE_IMAGE_DIGEST,
            imageId,
            PLATFORM,
            RUNTIME_USER,
            BUILD_NETWORK,
            RUN_NETWORK,
            gradleDistributionSha256(root));
        String schemaResource = normalizedRelativePath(root, schema);
        if (!SCHEMA_PATH.equals(schemaResource)) {
            throw new IllegalArgumentException(
                "unexpected reproduction schema path: " + schemaResource);
        }
        ReproductionContent content = new ReproductionContent(
            SCHEMA,
            EVIDENCE_STATUS,
            REPOSITORY,
            revision,
            matrix,
            containerIdentity,
            schemaResource,
            TargetFreeRepresentationEvaluationPlan.sha256(
                Files.readAllBytes(schema)),
            artifactSetHash,
            List.of(
                new RunIdentity("HOST_A", HOST_ENVIRONMENT,
                    artifactSetHash),
                new RunIdentity("HOST_B", HOST_ENVIRONMENT,
                    artifactSetHash),
                new RunIdentity("PINNED_CONTAINER", CONTAINER_ENVIRONMENT,
                    artifactSetHash)),
            artifacts,
            new Comparison(
                "BYTE_IDENTICAL",
                "BYTE_IDENTICAL",
                EXPECTED_FILES),
            CLAIM_BOUNDARY);
        return ReproductionReceipt.create(content);
    }

    private static RunSnapshot readRun(Path directory, String revision)
            throws IOException {
        Path root = requireDirectory(directory, "runDirectory");
        if (Files.isSymbolicLink(root)) {
            throw new IllegalArgumentException(
                "held-out run directory must not be a symlink: " + root);
        }
        List<Path> entries;
        try (Stream<Path> stream = Files.list(root)) {
            entries = stream.sorted().toList();
        }
        List<String> names = entries.stream()
            .map(path -> path.getFileName().toString()).toList();
        if (!EXPECTED_FILES.equals(names)) {
            throw new IllegalArgumentException(
                "unexpected held-out artifact set under " + root + ": "
                    + names);
        }
        Map<String, byte[]> bytes = new TreeMap<>();
        for (Path entry : entries) {
            if (Files.isSymbolicLink(entry)
                    || !Files.isRegularFile(
                        entry, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException(
                    "held-out artifact is not a regular file: " + entry);
            }
            bytes.put(entry.getFileName().toString(),
                Files.readAllBytes(entry));
        }
        PlanArtifact plan = PlanArtifact.fromCanonicalJson(text(
            bytes.get(TargetFreeHeldOutMatrixRunner.PLAN_FILE_NAME)));
        FreezeArtifact freeze = FreezeArtifact.fromCanonicalJson(text(
            bytes.get(TargetFreeHeldOutMatrixRunner.FREEZE_FILE_NAME)));
        QualificationArtifact qualification =
            QualificationArtifact.fromCanonicalJson(text(bytes.get(
                TargetFreeHeldOutMatrixRunner.QUALIFICATION_FILE_NAME)));
        validateRun(revision, plan, freeze, qualification);
        return new RunSnapshot(bytes, plan, freeze, qualification);
    }

    private static void validateRun(
        String revision,
        PlanArtifact plan,
        FreezeArtifact freeze,
        QualificationArtifact qualification
    ) {
        PlanContent planContent = plan.content();
        FreezeContent freezeContent = freeze.content();
        QualificationContent qualificationContent = qualification.content();
        if (!revision.equals(planContent.repositoryRevision())
                || !revision.equals(freezeContent.repositoryRevision())
                || !revision.equals(
                    qualificationContent.repositoryRevision())) {
            throw new IllegalArgumentException(
                "held-out artifacts bind different repository revisions");
        }
        if (!TargetFreeHeldOutMatrixRunner.QUALIFICATION_NOT_DISCLOSED
                .equals(planContent.qualificationDisclosure())
                || !TargetFreeHeldOutMatrixRunner.QUALIFICATION_NOT_DISCLOSED
                    .equals(freezeContent.qualificationDisclosure())
                || !TargetFreeHeldOutMatrixRunner.QUALIFICATION_DISCLOSED
                    .equals(qualificationContent.qualificationDisclosure())) {
            throw new IllegalArgumentException(
                "held-out qualification disclosure boundary differs");
        }
        if (planContent.cases().size() != 6
                || planContent.policies().size() != 4
                || !planContent.workMatching().checkpoints().equals(
                    List.of(8, 16, 32, 64, 128, 256))
                || planContent.rows().size() != 144
                || freezeContent.rows().size() != 144
                || qualificationContent.rows().size() != 144
                || freezeContent.matchedWorkGroups().size() != 36
                || qualificationContent.comparisons().size() != 36) {
            throw new IllegalArgumentException(
                "held-out matrix dimensions differ from the freeze");
        }
        List<String> planIds = planContent.rows().stream()
            .map(PlanRow::configurationId).toList();
        List<String> freezeIds = freezeContent.rows().stream()
            .map(FreezeRow::configurationId).toList();
        List<String> qualificationIds = qualificationContent.rows().stream()
            .map(QualificationRow::configurationId).toList();
        if (new HashSet<>(planIds).size() != 144
                || !planIds.equals(freezeIds)
                || !planIds.equals(qualificationIds)) {
            throw new IllegalArgumentException(
                "held-out configuration identities differ or repeat");
        }
        if (!freezeContent.planHash().equals(plan.contentHash())
                || !qualificationContent.planHash().equals(
                    plan.contentHash())
                || !qualificationContent.candidateFreezeHash().equals(
                    freeze.contentHash())) {
            throw new IllegalArgumentException(
                "held-out plan/freeze/qualification binding differs");
        }
    }

    private static MatrixSummary matrixSummary(RunSnapshot snapshot) {
        FreezeSummary freeze = snapshot.freeze().content().summary();
        QualificationSummary qualification =
            snapshot.qualification().content().summary();
        return new MatrixSummary(
            snapshot.plan().content().cases().size(),
            snapshot.plan().content().policies().size(),
            snapshot.plan().content().workMatching().checkpoints().size(),
            snapshot.plan().content().rows().size(),
            snapshot.freeze().content().matchedWorkGroups().size(),
            freeze.exactCheckpointRows(),
            freeze.eligibleMatchedWorkGroups(),
            qualification.qualifiedPositiveRows(),
            qualification.qualifiedCandidates(),
            qualification.negativeControlPassedRows(),
            qualification.negativeControlViolations());
    }

    private static void requireSameBytes(
        RunSnapshot left,
        RunSnapshot right,
        String label
    ) {
        for (String name : EXPECTED_FILES) {
            if (!Arrays.equals(left.bytes().get(name), right.bytes().get(name))) {
                throw new IllegalArgumentException(
                    label + " differs for " + name + ": "
                        + TargetFreeRepresentationEvaluationPlan.sha256(
                            left.bytes().get(name))
                        + " != "
                        + TargetFreeRepresentationEvaluationPlan.sha256(
                            right.bytes().get(name)));
            }
        }
    }

    private static void requireExternalImages(Path dockerfile)
            throws IOException {
        List<String> external = new ArrayList<>();
        Set<String> aliases = new HashSet<>();
        for (String line : Files.readAllLines(
                dockerfile, StandardCharsets.UTF_8)) {
            if (!line.stripLeading().regionMatches(
                    true, 0, "FROM ", 0, 5)) {
                continue;
            }
            Matcher matcher = FROM.matcher(line);
            if (!matcher.matches()) {
                throw new IllegalArgumentException(
                    "unsupported Dockerfile FROM syntax: " + line);
            }
            String image = matcher.group(1);
            if (!aliases.contains(image.toLowerCase(Locale.ROOT))) {
                external.add(image);
            }
            if (matcher.group(2) != null) {
                aliases.add(matcher.group(2).toLowerCase(Locale.ROOT));
            }
        }
        if (!external.equals(List.of(BASE_IMAGE))) {
            throw new IllegalArgumentException(
                "held-out reproduction base images differ: " + external);
        }
    }

    private static void requireSchemaIdentity(Path schema) throws IOException {
        JsonNode value = TargetFreeHeldOutMatrixRunner.JSON.readTree(
            Files.readAllBytes(schema));
        if (value == null
                || !"https://json-schema.org/draft/2020-12/schema".equals(
                    value.path("$schema").asText())
                || !SCHEMA.equals(value.path("$id").asText())
                || !"object".equals(value.path("type").asText())) {
            throw new IllegalArgumentException(
                "held-out reproduction schema identity differs");
        }
    }

    private static String gradleDistributionSha256(Path root)
            throws IOException {
        Path propertiesPath = requireFile(
            root.resolve("gradle/wrapper/gradle-wrapper.properties"),
            "gradleWrapperProperties");
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(
                propertiesPath, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        String value = requireText(
            properties.getProperty("distributionSha256Sum"),
            "gradleDistributionSha256");
        if (!HEX_256.matcher(value).matches()) {
            throw new IllegalArgumentException(
                "Gradle distribution SHA-256 is invalid");
        }
        return value;
    }

    private static Path requireDirectory(Path value, String name) {
        Path path = Objects.requireNonNull(value, name)
            .toAbsolutePath().normalize();
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(
                name + " is not a directory: " + path);
        }
        return path;
    }

    private static Path requireFile(Path value, String name) {
        Path path = Objects.requireNonNull(value, name)
            .toAbsolutePath().normalize();
        if (Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(
                name + " is not a regular file: " + path);
        }
        return path;
    }

    private static String normalizedRelativePath(Path root, Path value) {
        Path normalized = value.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new IllegalArgumentException(
                "reproduction input is outside the repository: " + value);
        }
        return root.relativize(normalized).toString()
            .replace(File.separatorChar, '/');
    }

    private static String requireRevision(String value) {
        String revision = requireText(value, "repositoryRevision");
        if (!REVISION.matcher(revision).matches()) {
            throw new IllegalArgumentException(
                "repositoryRevision must be a lowercase 40-character SHA");
        }
        return revision;
    }

    private static String text(byte[] value) {
        return new String(Objects.requireNonNull(value, "value"),
            StandardCharsets.UTF_8);
    }

    private record RunSnapshot(
        Map<String, byte[]> bytes,
        PlanArtifact plan,
        FreezeArtifact freeze,
        QualificationArtifact qualification
    ) {
        private RunSnapshot {
            Map<String, byte[]> retained = new TreeMap<>();
            bytes.forEach((key, value) ->
                retained.put(key, value.clone()));
            bytes = Collections.unmodifiableMap(retained);
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(freeze, "freeze");
            Objects.requireNonNull(qualification, "qualification");
        }
    }

    public record MatrixSummary(
        int caseCount,
        int policyCount,
        int checkpointCount,
        int configuredRows,
        int matchedWorkGroups,
        int exactCheckpointRows,
        int eligibleMatchedWorkGroups,
        int qualifiedPositiveRows,
        int qualifiedCandidates,
        int negativeControlPassedRows,
        int negativeControlViolations
    ) {
        public MatrixSummary {
            if (caseCount != 6 || policyCount != 4 || checkpointCount != 6
                    || configuredRows != 144 || matchedWorkGroups != 36
                    || exactCheckpointRows < 0
                    || exactCheckpointRows > configuredRows
                    || eligibleMatchedWorkGroups < 0
                    || eligibleMatchedWorkGroups > matchedWorkGroups
                    || qualifiedPositiveRows < 0
                    || qualifiedCandidates < 0
                    || negativeControlPassedRows < 0
                    || negativeControlViolations < 0) {
                throw new IllegalArgumentException(
                    "held-out reproduction matrix summary is invalid");
            }
        }
    }

    public record ContainerIdentity(
        String definitionPath,
        String definitionSha256,
        String baseImage,
        String baseImageDigest,
        String builtImageId,
        String platform,
        String runtimeUser,
        String buildNetworkPolicy,
        String evaluatedRunNetworkPolicy,
        String gradleDistributionSha256
    ) {
        public ContainerIdentity {
            definitionPath = requireText(definitionPath, "definitionPath");
            definitionSha256 = requireSha256(
                definitionSha256, "definitionSha256");
            baseImage = requireText(baseImage, "baseImage");
            baseImageDigest = requireSha256(
                baseImageDigest, "baseImageDigest");
            builtImageId = requireSha256(builtImageId, "builtImageId");
            platform = requireText(platform, "platform");
            runtimeUser = requireText(runtimeUser, "runtimeUser");
            buildNetworkPolicy = requireText(
                buildNetworkPolicy, "buildNetworkPolicy");
            evaluatedRunNetworkPolicy = requireText(
                evaluatedRunNetworkPolicy, "evaluatedRunNetworkPolicy");
            gradleDistributionSha256 = requireText(
                gradleDistributionSha256, "gradleDistributionSha256");
            if (!DOCKERFILE_PATH.equals(definitionPath)
                    || !BASE_IMAGE.equals(baseImage)
                    || !BASE_IMAGE_DIGEST.equals(baseImageDigest)
                    || !PLATFORM.equals(platform)
                    || !RUNTIME_USER.equals(runtimeUser)
                    || !BUILD_NETWORK.equals(buildNetworkPolicy)
                    || !RUN_NETWORK.equals(evaluatedRunNetworkPolicy)
                    || !HEX_256.matcher(
                        gradleDistributionSha256).matches()) {
                throw new IllegalArgumentException(
                    "held-out container identity differs from its contract");
            }
        }
    }

    public record RunIdentity(
        String id,
        String environment,
        String artifactSetHash
    ) {
        public RunIdentity {
            id = requireText(id, "run id");
            environment = requireText(environment, "environment");
            artifactSetHash = requireSha256(
                artifactSetHash, "artifactSetHash");
        }
    }

    public record ArtifactIdentity(
        String path,
        long byteLength,
        String sha256
    ) {
        public ArtifactIdentity {
            path = requireText(path, "artifact path");
            if (byteLength < 1) {
                throw new IllegalArgumentException(
                    "artifact byteLength must be positive");
            }
            sha256 = requireSha256(sha256, "artifact sha256");
        }
    }

    public record Comparison(
        String hostRepeat,
        String hostContainer,
        List<String> expectedFiles
    ) {
        public Comparison {
            hostRepeat = requireText(hostRepeat, "hostRepeat");
            hostContainer = requireText(hostContainer, "hostContainer");
            expectedFiles = List.copyOf(expectedFiles);
            if (!"BYTE_IDENTICAL".equals(hostRepeat)
                    || !"BYTE_IDENTICAL".equals(hostContainer)
                    || !EXPECTED_FILES.equals(expectedFiles)) {
                throw new IllegalArgumentException(
                    "held-out reproduction comparison is invalid");
            }
        }
    }

    public record ReproductionContent(
        String schema,
        String evidenceStatus,
        String repository,
        String repositoryRevision,
        MatrixSummary matrix,
        ContainerIdentity container,
        String schemaResource,
        String schemaSha256,
        String artifactSetHash,
        List<RunIdentity> runs,
        List<ArtifactIdentity> artifacts,
        Comparison comparison,
        String claimBoundary
    ) {
        public ReproductionContent {
            schema = requireText(schema, "schema");
            evidenceStatus = requireText(
                evidenceStatus, "evidenceStatus");
            repository = requireText(repository, "repository");
            repositoryRevision = requireRevision(repositoryRevision);
            Objects.requireNonNull(matrix, "matrix");
            Objects.requireNonNull(container, "container");
            schemaResource = requireText(
                schemaResource, "schemaResource");
            schemaSha256 = requireSha256(schemaSha256, "schemaSha256");
            artifactSetHash = requireSha256(
                artifactSetHash, "artifactSetHash");
            runs = List.copyOf(runs);
            artifacts = List.copyOf(artifacts);
            Objects.requireNonNull(comparison, "comparison");
            claimBoundary = requireText(claimBoundary, "claimBoundary");
            List<String> artifactPaths = artifacts.stream()
                .map(ArtifactIdentity::path).toList();
            String derivedArtifactSet = KnownStructureCatalog.sha256(
                TargetFreeHeldOutMatrixRunner.canonical(artifacts));
            if (!SCHEMA.equals(schema)
                    || !EVIDENCE_STATUS.equals(evidenceStatus)
                    || !REPOSITORY.equals(repository)
                    || !SCHEMA_PATH.equals(schemaResource)
                    || !CLAIM_BOUNDARY.equals(claimBoundary)
                    || runs.size() != 3
                    || !List.of("HOST_A", "HOST_B", "PINNED_CONTAINER")
                        .equals(runs.stream().map(RunIdentity::id).toList())
                    || !List.of(
                        HOST_ENVIRONMENT,
                        HOST_ENVIRONMENT,
                        CONTAINER_ENVIRONMENT).equals(runs.stream()
                            .map(RunIdentity::environment).toList())
                    || runs.stream().anyMatch(run ->
                        !derivedArtifactSet.equals(run.artifactSetHash()))
                    || !EXPECTED_FILES.equals(artifactPaths)
                    || !artifactSetHash.equals(derivedArtifactSet)) {
                throw new IllegalArgumentException(
                    "held-out reproduction content does not balance");
            }
        }
    }

    public record ReproductionReceipt(
        ReproductionContent content,
        String contentHash
    ) {
        public ReproductionReceipt {
            Objects.requireNonNull(content, "content");
            contentHash = requireSha256(contentHash, "contentHash");
            String expected = KnownStructureCatalog.sha256(
                TargetFreeHeldOutMatrixRunner.canonical(content));
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "held-out reproduction receipt hash mismatch");
            }
        }

        static ReproductionReceipt create(ReproductionContent content) {
            return new ReproductionReceipt(
                content,
                KnownStructureCatalog.sha256(
                    TargetFreeHeldOutMatrixRunner.canonical(content)));
        }

        public String toCanonicalJson() {
            return TargetFreeHeldOutMatrixRunner.canonical(this);
        }

        public static ReproductionReceipt fromCanonicalJson(String value) {
            try {
                ReproductionReceipt receipt =
                    TargetFreeHeldOutMatrixRunner.JSON.readValue(
                        Objects.requireNonNull(value, "value"),
                        ReproductionReceipt.class);
                if (!receipt.toCanonicalJson().equals(value)) {
                    throw new IllegalArgumentException(
                        "held-out reproduction JSON is not canonical");
                }
                return receipt;
            } catch (JsonProcessingException exception) {
                throw new IllegalArgumentException(
                    "invalid held-out reproduction JSON", exception);
            }
        }
    }
}
