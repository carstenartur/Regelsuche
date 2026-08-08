package de.regelsuche.evolution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Verifies the one-attempt GitHub authority and executes the retained
 * showcase TRAIN/freeze command without exposing later-stage material.
 */
public final class ProofCarryingShowcaseTrainFreezeAuthorityCommand {
    static final String AUTHORITY_BRANCH =
        "showcase/train-freeze-authority-v1";
    static final String AUTHORITY_REF =
        "refs/heads/" + AUTHORITY_BRANCH;
    static final String AUTHORITY_FILE =
        "research/showcase/proof-carrying-self-improvement/"
            + "train-freeze-authority-v1.json";
    static final String AUTHORITY_SCHEMA =
        "regelsuche.proof-carrying-showcase-train-freeze-authority/v1";
    static final String AUTHORITY_ID =
        "proof-carrying-showcase-train-freeze-v1";
    static final String AUTHORITY_OPERATION =
        "TRAIN_AND_FREEZE_FINAL_TEST_UNSEEN";
    static final String AUTHORITY_STATUS = "AUTHORIZED_NOT_RUN";
    static final String EXECUTION_SCHEMA =
        "regelsuche.proof-carrying-showcase-train-freeze-execution/v1";
    static final String EXECUTION_STATUS =
        "TRAIN_FREEZE_EXECUTED_FINAL_TEST_UNSEEN";
    static final String WORKFLOW_FILE = "gradle.yml";
    private static final String PLAN_FILE =
        "research/showcase/proof-carrying-self-improvement/"
            + "showcase-plan.json";
    private static final Path OUTPUT_DIRECTORY = Path.of(
        "build/proof-carrying-showcase/train-freeze");
    private static final Path EVIDENCE_DIRECTORY = Path.of(
        "build/proof-carrying-showcase/execution-evidence");
    private static final Set<String> AUTHORITY_FIELDS = Set.of(
        "authorityBranch",
        "authorityId",
        "implementationCommit",
        "maximumWorkflowAttempts",
        "operation",
        "schema",
        "status");
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern REPOSITORY = Pattern.compile(
        "[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build();

    private ProofCarryingShowcaseTrainFreezeAuthorityCommand() {
    }

    public static void main(String[] arguments) {
        if (arguments.length != 0) {
            throw new IllegalArgumentException("usage: no arguments");
        }
        Path repositoryRoot = Path.of("").toAbsolutePath().normalize();
        Map<String, String> environment = System.getenv();
        String token = requireEnvironment(environment, "GITHUB_TOKEN");
        String apiUrl = requireEnvironment(environment, "GITHUB_API_URL");
        AuthoritySource authoritySource =
            new GitHubRestAuthoritySource(apiUrl, token);
        Execution execution = execute(
            repositoryRoot,
            environment,
            authoritySource,
            ProofCarryingShowcaseTrainAndFreezeCommand::execute);
        System.out.println(
            "showcaseAuthorityStatus=" + execution.status());
        System.out.println(
            "showcaseAuthorityCandidateFreezeHash="
                + execution.candidateFreezeHash());
        System.out.println(
            "showcaseAuthoritySelectedCandidateHash="
                + execution.selectedCandidateHash());
        System.out.println(
            "showcaseAuthorityEvidence=" + execution.evidenceDirectory());
    }

    static Execution execute(
        Path repositoryRoot,
        Map<String, String> environment,
        AuthoritySource authoritySource,
        TrainFreezeRunner runner
    ) {
        Objects.requireNonNull(runner, "runner");
        Path root = Objects.requireNonNull(
            repositoryRoot, "repositoryRoot")
            .toAbsolutePath()
            .normalize();
        Authority authority = authorize(
            root,
            environment,
            authoritySource);
        Path output = root.resolve(OUTPUT_DIRECTORY).normalize();
        Path evidence = root.resolve(EVIDENCE_DIRECTORY).normalize();
        if (Files.exists(output) || Files.exists(evidence)) {
            throw new IllegalStateException(
                "showcase authority output already exists");
        }

        Path planPath = root.resolve(PLAN_FILE).normalize();
        var written = runner.run(
            planPath,
            authority.repositoryCommit(),
            output);
        ProofCarryingShowcasePlan plan =
            ProofCarryingShowcasePlan.read(planPath);
        ProofCarryingShowcaseCandidateFreeze freeze = readFreeze(
            written.candidateFreezePath());
        freeze.requireCompatible(plan);
        if (!authority.repositoryCommit().equals(freeze.repositoryCommit())) {
            throw new IllegalStateException(
                "candidate freeze repository commit differs from authority");
        }
        if (!written.candidateFreezeHash().equals(freeze.contentHash())
                || !written.selectedCandidateHash().equals(
                    freeze.candidateContentHash())) {
            throw new IllegalStateException(
                "TRAIN/freeze result differs from retained candidate freeze");
        }
        rejectLaterStageArtifacts(written.outputDirectory());

        writeExecutionEvidence(
            evidence,
            written.outputDirectory(),
            authority,
            freeze,
            environment);
        return new Execution(
            EXECUTION_STATUS,
            written.outputDirectory(),
            evidence,
            freeze.contentHash(),
            freeze.candidateContentHash(),
            freeze.randomnessNotBeforeUnixTime());
    }

    static Authority authorize(
        Path repositoryRoot,
        Map<String, String> environment,
        AuthoritySource authoritySource
    ) {
        Objects.requireNonNull(authoritySource, "authoritySource");
        Map<String, String> env = Map.copyOf(
            Objects.requireNonNull(environment, "environment"));
        requireEquals(
            "create",
            requireEnvironment(env, "GITHUB_EVENT_NAME"),
            "authority requires a create event");
        requireEquals(
            AUTHORITY_REF,
            requireEnvironment(env, "GITHUB_REF"),
            "authority requires the reserved branch ref");
        requireEquals(
            "1",
            requireEnvironment(env, "GITHUB_RUN_ATTEMPT"),
            "authority version may not be rerun");

        long runId = requirePositiveLong(
            requireEnvironment(env, "GITHUB_RUN_ID"),
            "GITHUB_RUN_ID");
        String repository = requireEnvironment(env, "GITHUB_REPOSITORY");
        if (!REPOSITORY.matcher(repository).matches()) {
            throw new IllegalArgumentException(
                "invalid GITHUB_REPOSITORY");
        }
        String repositoryCommit = requireCommit(
            requireEnvironment(env, "REGELSUCHE_AUTHORITY_GITHUB_SHA"),
            "REGELSUCHE_AUTHORITY_GITHUB_SHA");

        Path authorityPath = repositoryRoot
            .resolve(AUTHORITY_FILE)
            .normalize();
        requireInside(repositoryRoot, authorityPath);
        byte[] authorityBytes = readRegularFile(authorityPath);
        AuthorityManifest manifest = parseAuthorityManifest(authorityBytes);
        String expectedManifest = canonicalManifest(
            manifest.implementationCommit());
        if (!MessageDigest.isEqual(
                expectedManifest.getBytes(StandardCharsets.UTF_8),
                authorityBytes)) {
            throw new IllegalArgumentException(
                "authority manifest is not canonical");
        }

        CommitEvidence commit = authoritySource.commit(
            repository,
            repositoryCommit);
        if (!repositoryCommit.equals(commit.sha())) {
            throw new IllegalArgumentException(
                "authority commit response has the wrong SHA");
        }
        if (!commit.parents().equals(
                List.of(manifest.implementationCommit()))) {
            throw new IllegalArgumentException(
                "authority commit must have exactly the reviewed parent");
        }
        String localBlobSha = gitBlobSha1(authorityBytes);
        if (!commit.files().equals(List.of(
                new ChangedFile(AUTHORITY_FILE, "added", localBlobSha)))) {
            throw new IllegalArgumentException(
                "authority manifest must be the commit's only added file");
        }

        for (RunEvidence run : authoritySource.createRuns(
                repository,
                AUTHORITY_BRANCH)) {
            if (run.id() != runId) {
                throw new IllegalArgumentException(
                    "showcase authority branch was already consumed");
            }
            requireEquals(
                "create",
                run.event(),
                "authority run history contains a non-create event");
            requireEquals(
                AUTHORITY_BRANCH,
                run.headBranch(),
                "authority run history has the wrong branch");
            requireEquals(
                repositoryCommit,
                run.headSha(),
                "current authority run has the wrong commit");
        }

        return new Authority(
            repository,
            repositoryCommit,
            manifest.implementationCommit(),
            runId,
            sha256(authorityBytes));
    }

    static String canonicalManifest(String implementationCommit) {
        String commit = requireCommit(
            implementationCommit, "implementationCommit");
        return "{\"authorityBranch\":\"" + AUTHORITY_BRANCH
            + "\",\"authorityId\":\"" + AUTHORITY_ID
            + "\",\"implementationCommit\":\"" + commit
            + "\",\"maximumWorkflowAttempts\":1"
            + ",\"operation\":\"" + AUTHORITY_OPERATION
            + "\",\"schema\":\"" + AUTHORITY_SCHEMA
            + "\",\"status\":\"" + AUTHORITY_STATUS + "\"}\n";
    }

    private static AuthorityManifest parseAuthorityManifest(byte[] bytes) {
        JsonNode document;
        try {
            document = JSON.readTree(bytes);
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                "invalid authority manifest JSON",
                exception);
        }
        if (document == null || !document.isObject()) {
            throw new IllegalArgumentException(
                "authority manifest must be a JSON object");
        }
        Set<String> fields = new java.util.HashSet<>();
        document.fieldNames().forEachRemaining(fields::add);
        if (!AUTHORITY_FIELDS.equals(fields)) {
            throw new IllegalArgumentException(
                "authority manifest has unknown or missing fields");
        }
        requireJsonText(document, "schema", AUTHORITY_SCHEMA);
        requireJsonText(document, "authorityId", AUTHORITY_ID);
        requireJsonText(document, "authorityBranch", AUTHORITY_BRANCH);
        requireJsonText(document, "operation", AUTHORITY_OPERATION);
        requireJsonText(document, "status", AUTHORITY_STATUS);
        JsonNode attempts = document.get("maximumWorkflowAttempts");
        if (attempts == null || !attempts.isIntegralNumber()
                || attempts.longValue() != 1L) {
            throw new IllegalArgumentException(
                "authority maximumWorkflowAttempts must equal one");
        }
        JsonNode implementation = document.get("implementationCommit");
        if (implementation == null || !implementation.isTextual()) {
            throw new IllegalArgumentException(
                "authority implementationCommit must be text");
        }
        return new AuthorityManifest(
            requireCommit(
                implementation.textValue(),
                "implementationCommit"));
    }

    private static void requireJsonText(
        JsonNode document,
        String field,
        String expected
    ) {
        JsonNode value = document.get(field);
        if (value == null || !value.isTextual()
                || !expected.equals(value.textValue())) {
            throw new IllegalArgumentException(
                "authority " + field + " mismatch");
        }
    }

    private static ProofCarryingShowcaseCandidateFreeze readFreeze(
        Path path
    ) {
        try {
            return ProofCarryingShowcaseCandidateFreeze.fromCanonicalJson(
                Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException(
                "unable to read retained candidate freeze",
                exception);
        }
    }

    private static void rejectLaterStageArtifacts(Path outputDirectory) {
        try (var files = Files.list(outputDirectory)) {
            boolean found = files
                .filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString().toLowerCase())
                .anyMatch(name -> name.contains("randomness")
                    || (name.contains("final") && name.contains("test")));
            if (found) {
                throw new IllegalStateException(
                    "later-stage artifact found in TRAIN/freeze output");
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                "unable to inspect TRAIN/freeze output",
                exception);
        }
    }

    private static void writeExecutionEvidence(
        Path evidenceDirectory,
        Path outputDirectory,
        Authority authority,
        ProofCarryingShowcaseCandidateFreeze freeze,
        Map<String, String> environment
    ) {
        Path parent = evidenceDirectory.getParent();
        if (parent == null) {
            throw new IllegalArgumentException(
                "execution evidence requires a parent directory");
        }
        Path staging = null;
        try {
            Files.createDirectories(parent);
            staging = Files.createTempDirectory(
                parent,
                ".proof-carrying-showcase-execution-evidence-");
            String artifactManifest = artifactManifest(outputDirectory);
            writeNew(
                staging.resolve("artifact-sha256sums.txt"),
                artifactManifest);
            String receipt = executionReceipt(
                authority,
                freeze,
                environment);
            writeNew(
                staging.resolve("execution-receipt.json"),
                receipt);
            writeNew(
                staging.resolve("execution-receipt.sha256"),
                "sha256:" + sha256(
                    receipt.getBytes(StandardCharsets.UTF_8)) + "\n");
            Files.move(
                staging,
                evidenceDirectory,
                StandardCopyOption.ATOMIC_MOVE);
            staging = null;
        } catch (IOException exception) {
            throw new IllegalStateException(
                "unable to write showcase execution evidence",
                exception);
        } finally {
            if (staging != null) {
                deleteTree(staging);
            }
        }
    }

    private static String artifactManifest(Path outputDirectory)
            throws IOException {
        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(outputDirectory)) {
            stream.filter(Files::isRegularFile).forEach(files::add);
        }
        files.sort(Comparator.comparing(path ->
            outputDirectory.relativize(path).toString()));
        StringBuilder result = new StringBuilder();
        for (Path file : files) {
            String relative = outputDirectory.relativize(file)
                .toString()
                .replace('\\', '/');
            result.append(sha256(Files.readAllBytes(file)))
                .append("  ")
                .append(relative)
                .append('\n');
        }
        return result.toString();
    }

    private static String executionReceipt(
        Authority authority,
        ProofCarryingShowcaseCandidateFreeze freeze,
        Map<String, String> environment
    ) {
        TreeMap<String, Object> receipt = new TreeMap<>();
        receipt.put(
            "authorityManifestSha256",
            "sha256:" + authority.authorityManifestSha256());
        receipt.put("authorityRef", AUTHORITY_REF);
        receipt.put("candidateFreezeHash", freeze.contentHash());
        receipt.put(
            "implementationCommit",
            authority.implementationCommit());
        receipt.put(
            "randomnessNotBeforeUnixTime",
            freeze.randomnessNotBeforeUnixTime());
        receipt.put("repositoryCommit", authority.repositoryCommit());
        receipt.put(
            "runAttempt",
            requireEnvironment(environment, "GITHUB_RUN_ATTEMPT"));
        receipt.put("runId", Long.toString(authority.runId()));
        receipt.put("schema", EXECUTION_SCHEMA);
        receipt.put(
            "selectedCandidateHash",
            freeze.candidateContentHash());
        receipt.put("status", EXECUTION_STATUS);
        receipt.put(
            "workflow",
            requireEnvironment(environment, "GITHUB_WORKFLOW"));
        try {
            return JSON.writeValueAsString(receipt) + "\n";
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                "unable to serialize showcase execution receipt",
                exception);
        }
    }

    private static byte[] readRegularFile(Path path) {
        try {
            if (!Files.isRegularFile(path)
                    || Files.isSymbolicLink(path)) {
                throw new IllegalArgumentException(
                    "authority manifest must be a regular file");
            }
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length == 0 || bytes.length > 4096) {
                throw new IllegalArgumentException(
                    "authority manifest has an invalid byte length");
            }
            return bytes;
        } catch (IOException exception) {
            throw new IllegalStateException(
                "unable to read authority manifest",
                exception);
        }
    }

    private static void requireInside(Path root, Path path) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException(
                "authority path escapes the repository root");
        }
    }

    private static String requireEnvironment(
        Map<String, String> environment,
        String name
    ) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                "missing environment variable " + name);
        }
        return value;
    }

    private static void requireEquals(
        String expected,
        String actual,
        String message
    ) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(message);
        }
    }

    private static long requirePositiveLong(String raw, String name) {
        try {
            long value = Long.parseLong(raw);
            if (value < 1) {
                throw new NumberFormatException("not positive");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                name + " must be a positive integer",
                exception);
        }
    }

    private static String requireCommit(String value, String name) {
        if (value == null || !COMMIT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                name + " must be a lowercase 40-character Git SHA");
        }
        return value;
    }

    private static String gitBlobSha1(byte[] content) {
        byte[] header = ("blob " + content.length + "\0")
            .getBytes(StandardCharsets.US_ASCII);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(header);
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 is unavailable", exception);
        }
    }

    private static String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void writeNew(Path path, String content)
            throws IOException {
        Files.writeString(
            path,
            content,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE);
    }

    private static void deleteTree(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new DeleteFailure(exception);
                }
            });
        } catch (IOException exception) {
            throw new IllegalStateException(
                "unable to clean execution-evidence staging directory",
                exception);
        } catch (DeleteFailure failure) {
            throw new IllegalStateException(
                "unable to clean execution-evidence staging directory",
                failure.getCause());
        }
    }

    @FunctionalInterface
    interface TrainFreezeRunner {
        ProofCarryingShowcaseTrainAndFreezeCommand.WrittenFreeze run(
            Path plan,
            String repositoryCommit,
            Path outputDirectory);
    }

    interface AuthoritySource {
        CommitEvidence commit(String repository, String commit);

        List<RunEvidence> createRuns(String repository, String branch);
    }

    record AuthorityManifest(String implementationCommit) {
        AuthorityManifest {
            requireCommit(implementationCommit, "implementationCommit");
        }
    }

    record ChangedFile(String path, String status, String blobSha) {
        ChangedFile {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("changed file path is blank");
            }
            if (status == null || status.isBlank()) {
                throw new IllegalArgumentException("changed file status is blank");
            }
            requireCommit(blobSha, "changed file blob SHA");
        }
    }

    record CommitEvidence(
        String sha,
        List<String> parents,
        List<ChangedFile> files
    ) {
        CommitEvidence {
            requireCommit(sha, "commit evidence SHA");
            parents = List.copyOf(parents);
            parents.forEach(parent ->
                requireCommit(parent, "commit parent SHA"));
            files = List.copyOf(files);
        }
    }

    record RunEvidence(
        long id,
        String event,
        String headBranch,
        String headSha
    ) {
        RunEvidence {
            if (id < 1 || event == null || headBranch == null) {
                throw new IllegalArgumentException(
                    "invalid authority run evidence");
            }
            requireCommit(headSha, "authority run head SHA");
        }
    }

    record Authority(
        String repository,
        String repositoryCommit,
        String implementationCommit,
        long runId,
        String authorityManifestSha256
    ) {
        Authority {
            if (!REPOSITORY.matcher(repository).matches()) {
                throw new IllegalArgumentException("invalid repository");
            }
            requireCommit(repositoryCommit, "repositoryCommit");
            requireCommit(implementationCommit, "implementationCommit");
            if (runId < 1
                    || authorityManifestSha256 == null
                    || !authorityManifestSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid authority evidence");
            }
        }
    }

    record Execution(
        String status,
        Path outputDirectory,
        Path evidenceDirectory,
        String candidateFreezeHash,
        String selectedCandidateHash,
        long randomnessNotBeforeUnixTime
    ) {
        Execution {
            requireEquals(
                EXECUTION_STATUS,
                status,
                "invalid execution status");
            Objects.requireNonNull(outputDirectory, "outputDirectory");
            Objects.requireNonNull(evidenceDirectory, "evidenceDirectory");
            EvolutionGenome.requireSha256(
                candidateFreezeHash,
                "candidateFreezeHash");
            EvolutionGenome.requireSha256(
                selectedCandidateHash,
                "selectedCandidateHash");
            if (randomnessNotBeforeUnixTime < 1) {
                throw new IllegalArgumentException(
                    "invalid public-randomness boundary");
            }
        }
    }

    private static final class GitHubRestAuthoritySource
            implements AuthoritySource {
        private static final Duration REQUEST_TIMEOUT =
            Duration.ofSeconds(30);
        private final URI apiBase;
        private final String token;
        private final HttpClient client;

        GitHubRestAuthoritySource(String apiUrl, String token) {
            this.apiBase = normalizeApiBase(apiUrl);
            this.token = Objects.requireNonNull(token, "token");
            if (token.isBlank()) {
                throw new IllegalArgumentException("GitHub token is blank");
            }
            this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        }

        @Override
        public CommitEvidence commit(String repository, String commit) {
            JsonNode document = get(
                "/repos/" + repository + "/commits/" + commit);
            JsonNode parentsNode = document.get("parents");
            JsonNode filesNode = document.get("files");
            if (parentsNode == null || !parentsNode.isArray()
                    || filesNode == null || !filesNode.isArray()) {
                throw new IllegalStateException(
                    "GitHub commit response lacks parents or files");
            }
            List<String> parents = new ArrayList<>();
            parentsNode.forEach(node ->
                parents.add(requireNodeText(node, "sha")));
            List<ChangedFile> files = new ArrayList<>();
            filesNode.forEach(node -> files.add(new ChangedFile(
                requireNodeText(node, "filename"),
                requireNodeText(node, "status"),
                requireNodeText(node, "sha"))));
            return new CommitEvidence(
                requireNodeText(document, "sha"),
                parents,
                files);
        }

        @Override
        public List<RunEvidence> createRuns(
            String repository,
            String branch
        ) {
            String encodedBranch = URLEncoder.encode(
                branch, StandardCharsets.UTF_8);
            JsonNode document = get(
                "/repos/" + repository
                    + "/actions/workflows/" + WORKFLOW_FILE
                    + "/runs?event=create&branch=" + encodedBranch
                    + "&per_page=100");
            JsonNode runsNode = document.get("workflow_runs");
            if (runsNode == null || !runsNode.isArray()) {
                throw new IllegalStateException(
                    "GitHub workflow-run response lacks workflow_runs");
            }
            List<RunEvidence> runs = new ArrayList<>();
            runsNode.forEach(node -> runs.add(new RunEvidence(
                requireNodeLong(node, "id"),
                requireNodeText(node, "event"),
                requireNodeText(node, "head_branch"),
                requireNodeText(node, "head_sha"))));
            return List.copyOf(runs);
        }

        private JsonNode get(String relativePath) {
            URI uri = apiBase.resolve(relativePath);
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + token)
                .header("X-GitHub-Api-Version", "2026-03-10")
                .header("User-Agent", "Regelsuche-showcase-authority")
                .GET()
                .build();
            HttpResponse<String> response;
            try {
                response = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(
                        StandardCharsets.UTF_8));
            } catch (IOException exception) {
                throw new IllegalStateException(
                    "GitHub authority request failed",
                    exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                    "GitHub authority request was interrupted",
                    exception);
            }
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                    "GitHub authority request returned HTTP "
                        + response.statusCode());
            }
            try {
                JsonNode result = JSON.readTree(response.body());
                if (result == null || !result.isObject()) {
                    throw new IllegalStateException(
                        "GitHub authority response is not an object");
                }
                return result;
            } catch (IOException exception) {
                throw new IllegalStateException(
                    "GitHub authority response is invalid JSON",
                    exception);
            }
        }

        private static URI normalizeApiBase(String apiUrl) {
            URI uri;
            try {
                uri = URI.create(apiUrl.endsWith("/")
                    ? apiUrl
                    : apiUrl + "/");
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                    "invalid GITHUB_API_URL",
                    exception);
            }
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException(
                    "GITHUB_API_URL must be an HTTPS base URL");
            }
            return uri;
        }

        private static String requireNodeText(
            JsonNode node,
            String field
        ) {
            JsonNode value = node.get(field);
            if (value == null || !value.isTextual()
                    || value.textValue().isBlank()) {
                throw new IllegalStateException(
                    "GitHub response lacks textual " + field);
            }
            return value.textValue();
        }

        private static long requireNodeLong(JsonNode node, String field) {
            JsonNode value = node.get(field);
            if (value == null || !value.canConvertToLong()) {
                throw new IllegalStateException(
                    "GitHub response lacks integer " + field);
            }
            return value.longValue();
        }
    }

    private static final class DeleteFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        DeleteFailure(IOException cause) {
            super(cause);
        }
    }
}
