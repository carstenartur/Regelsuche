package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

class MavenContainerImagePolicyContractTest {
    private static final ObjectMapper JSON = new ObjectMapper(
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build()
    );

    private static final String POLICY_SCHEMA =
        "regelsuche.quality.container-image-policy/v1";
    private static final String REPORT_SCHEMA =
        "regelsuche.quality.container-image-report/v1";
    private static final String VISUAL_POLICY_SCHEMA =
        "regelsuche.visual-regression-policy/v1";
    private static final Path POLICY_PATH = Path.of(
        "config/quality/container-image-policy.json"
    );
    private static final Path REPORT_PATH = Path.of(
        "build/reports/quality/container-image-report.json"
    );
    private static final Path VISUAL_POLICY_PATH = Path.of(
        "app/src/e2eTest/resources/screenshots/visual-regression-policy.json"
    );
    private static final Path APP_BUILD_PATH = Path.of("app/build.gradle");
    private static final String VISUAL_DOCKERFILE =
        "Dockerfile.visual-regression";

    private static final Set<String> POLICY_FIELDS = Set.of(
        "schema", "policy", "files"
    );
    private static final Set<String> IGNORED_DIRECTORY_NAMES = Set.of(
        ".git", ".gradle", "build", "node_modules", "out", "target"
    );
    private static final Pattern FROM_PATTERN = Pattern.compile(
        "^\\s*FROM\\s+(?:--platform=\\S+\\s+)?(\\S+)"
            + "(?:\\s+AS\\s+(\\S+))?\\s*$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern DIGEST_PATTERN = Pattern.compile(
        "@sha256:[0-9a-f]{64}$"
    );
    private static final Pattern EXACT_VERSION_PATTERN = Pattern.compile(
        "[0-9]+\\.[0-9]+\\.[0-9]+"
    );
    private static final Pattern PLAYWRIGHT_VERSION_PATTERN = Pattern.compile(
        "[0-9]+\\.[0-9]+\\.[0-9]+"
    );
    private static final Pattern PLAYWRIGHT_DEPENDENCY_PATTERN =
        Pattern.compile(
            "com[.]microsoft[.]playwright:playwright:"
                + "([0-9]+[.][0-9]+[.][0-9]+)"
        );

    @Test
    void repositoryContainerImagesSatisfyTheCheckoutOwnedContract()
            throws IOException {
        Path root = repositoryRoot();
        Policy policy = parsePolicy(parseStrict(root.resolve(POLICY_PATH)));
        Evaluation evaluation = evaluate(root, policy);
        writeReport(root.resolve(REPORT_PATH), evaluation);

        assertTrue(
            evaluation.violations().isEmpty(),
            () -> "Container image policy violations:\n"
                + String.join("\n", evaluation.violations())
        );
        assertEquals("PASSED", evaluation.status());
        assertEquals(policy.files().size(), evaluation.files().size());
        System.out.println(
            "containerImagePolicyStatus=" + evaluation.status()
        );
        System.out.println(
            "containerImagePolicyFiles=" + evaluation.files().size()
        );
        System.out.println(
            "containerImagePolicyReport=" + root.resolve(REPORT_PATH)
        );
    }

    @Test
    void strictJsonRejectsDuplicateKeysAndTrailingValues(
        @TempDir Path temporary
    ) throws IOException {
        Path duplicate = temporary.resolve("duplicate.json");
        Files.writeString(
            duplicate,
            "{\"schema\":\"first\",\"schema\":\"second\"}",
            StandardCharsets.UTF_8
        );
        expectInvalid(() -> parseStrict(duplicate), "duplicate");

        Path trailing = temporary.resolve("trailing.json");
        Files.writeString(
            trailing,
            "{\"schema\":\"first\"} {}",
            StandardCharsets.UTF_8
        );
        expectInvalid(() -> parseStrict(trailing), "trailing JSON content");
    }

    @Test
    void policyParsingFailsClosedButPreservesRepeatedStageImages() {
        ObjectNode unknown = validPolicyDocument("example/image:1.2.3");
        unknown.put("unexpected", true);
        expectInvalid(
            () -> parsePolicy(unknown),
            "container image policy unknown="
        );

        ObjectNode wrongSchema = validPolicyDocument("example/image:1.2.3");
        wrongSchema.put("schema", "unsupported");
        expectInvalid(() -> parsePolicy(wrongSchema), "unsupported policy schema");

        ObjectNode traversal = validPolicyDocument("example/image:1.2.3");
        ObjectNode traversalFiles = (ObjectNode) traversal.get("files");
        JsonNode declaration = traversalFiles.remove(VISUAL_DOCKERFILE);
        traversalFiles.set("../Dockerfile", declaration);
        expectInvalid(
            () -> parsePolicy(traversal),
            "must be normalized and relative"
        );

        ObjectNode empty = validPolicyDocument("example/image:1.2.3");
        ((ObjectNode) empty.get("files")).removeAll();
        expectInvalid(() -> parsePolicy(empty), "policy has no Dockerfiles");

        String repeatedImage = "example/image:1.2.3";
        ObjectNode repeated = validPolicyDocument(repeatedImage);
        ((ArrayNode) ((ObjectNode) repeated.get("files"))
            .get(VISUAL_DOCKERFILE)).add(repeatedImage);
        Policy parsed = parsePolicy(repeated);
        assertEquals(
            List.of(repeatedImage, repeatedImage),
            parsed.files().get(VISUAL_DOCKERFILE)
        );
    }

    @Test
    void dockerfileParserRetainsExternalStagesAndSkipsAliases(
        @TempDir Path temporary
    ) throws IOException {
        Path dockerfile = temporary.resolve("Dockerfile");
        Files.writeString(
            dockerfile,
            """
            FROM --platform=linux/amd64 eclipse-temurin:21.0.11_10-jdk-noble AS build
            FROM build AS reused
            FROM mcr.microsoft.com/playwright/java:v1.60.0-noble@sha256:69df3611cec973e9f7d2c7a9d9d75d9f9553261eb8484ffe37a52af608f9f766 AS runtime
            """,
            StandardCharsets.UTF_8
        );
        assertEquals(
            List.of(
                "eclipse-temurin:21.0.11_10-jdk-noble",
                "mcr.microsoft.com/playwright/java:v1.60.0-noble@sha256:69df3611cec973e9f7d2c7a9d9d75d9f9553261eb8484ffe37a52af608f9f766"
            ),
            externalImages(dockerfile)
        );

        Files.writeString(
            dockerfile,
            "FROM image:1.2.3 AS build extra\n",
            StandardCharsets.UTF_8
        );
        expectInvalid(
            () -> externalImages(dockerfile),
            "unsupported FROM syntax"
        );

        Files.writeString(
            dockerfile,
            "# no base image\nRUN true\n",
            StandardCharsets.UTF_8
        );
        expectInvalid(
            () -> externalImages(dockerfile),
            "Dockerfile has no external base image"
        );
    }

    @Test
    void imageIdentityRejectsFloatingAndPartialTags() {
        assertTrue(isDigestOrExactVersion(
            "example/image@sha256:" + "a".repeat(64)
        ));
        assertTrue(isDigestOrExactVersion(
            "eclipse-temurin:21.0.11_10-jdk-noble"
        ));
        assertTrue(isDigestOrExactVersion(
            "registry:5000/example/image:v1.60.0-noble"
        ));

        assertFalse(isDigestOrExactVersion("example/image"));
        assertFalse(isDigestOrExactVersion("example/image:latest"));
        assertFalse(isDigestOrExactVersion("example/image:21-jdk"));
        assertFalse(isDigestOrExactVersion("registry:5000/example/image"));
        assertFalse(isDigestOrExactVersion(
            "example/image@sha256:" + "A".repeat(64)
        ));
    }

    @Test
    void sourceTreeScanNeedsNeitherGitNorGeneratedDirectories(
        @TempDir Path temporary
    ) throws IOException {
        Files.writeString(
            temporary.resolve("Dockerfile"),
            "FROM example/image:1.2.3\n",
            StandardCharsets.UTF_8
        );
        Files.createDirectories(temporary.resolve("reproduction"));
        Files.writeString(
            temporary.resolve("reproduction/Dockerfile.reproduction"),
            "FROM example/image:1.2.3\n",
            StandardCharsets.UTF_8
        );
        for (String ignored : List.of(".git", ".gradle", "build", "target")) {
            Files.createDirectories(temporary.resolve(ignored));
            Files.writeString(
                temporary.resolve(ignored).resolve("Dockerfile.generated"),
                "FROM example/image:9.9.9\n",
                StandardCharsets.UTF_8
            );
        }
        assertEquals(
            List.of(
                "Dockerfile",
                "reproduction/Dockerfile.reproduction"
            ),
            sourceTreeDockerfiles(temporary)
        );
    }

    @Test
    void declarationsVisualBindingAndReportFailClosed(
        @TempDir Path temporary
    ) throws IOException {
        String image =
            "mcr.microsoft.com/playwright/java:v1.60.0-noble@sha256:"
                + "a".repeat(64);
        createVisualFixture(temporary, image, "1.60.0", "1.60.0");
        Policy policy = new Policy(
            "fixture",
            Map.of(VISUAL_DOCKERFILE, List.of(image))
        );

        Evaluation passing = evaluate(temporary, policy);
        assertTrue(
            passing.violations().isEmpty(),
            passing.violations().toString()
        );
        Path firstReport = temporary.resolve("first/report.json");
        Path secondReport = temporary.resolve("second/report.json");
        writeReport(firstReport, passing);
        writeReport(secondReport, passing);
        assertEquals(
            Files.readString(firstReport, StandardCharsets.UTF_8),
            Files.readString(secondReport, StandardCharsets.UTF_8)
        );
        JsonNode report = parseStrict(firstReport);
        assertEquals(REPORT_SCHEMA, report.path("schema").asText());
        assertEquals("PASSED", report.path("status").asText());

        createVisualFixture(temporary, image, "1.60.0", "1.59.0");
        Evaluation mismatch = evaluate(temporary, policy);
        assertTrue(
            mismatch.violations().stream().anyMatch(message ->
                message.contains("Playwright dependency versions do not match")),
            mismatch.violations().toString()
        );

        Policy undeclared = new Policy(
            "fixture",
            Map.of("Dockerfile.other", List.of("example/image:1.2.3"))
        );
        Evaluation classification = evaluate(temporary, undeclared);
        assertTrue(
            classification.violations().stream().anyMatch(message ->
                message.contains("tracked Dockerfiles missing from policy")),
            classification.violations().toString()
        );
        assertTrue(
            classification.violations().stream().anyMatch(message ->
                message.contains("policy Dockerfiles missing from checkout")),
            classification.violations().toString()
        );
    }

    private static Evaluation evaluate(Path root, Policy policy)
            throws IOException {
        List<String> tracked = sourceTreeDockerfiles(root);
        Set<String> trackedSet = new TreeSet<>(tracked);
        Set<String> declaredSet = new TreeSet<>(policy.files().keySet());
        List<String> violations = new ArrayList<>();

        Set<String> missingFromPolicy = new TreeSet<>(trackedSet);
        missingFromPolicy.removeAll(declaredSet);
        if (!missingFromPolicy.isEmpty()) {
            violations.add(
                "tracked Dockerfiles missing from policy: "
                    + String.join(", ", missingFromPolicy)
            );
        }

        Set<String> missingFromCheckout = new TreeSet<>(declaredSet);
        missingFromCheckout.removeAll(trackedSet);
        if (!missingFromCheckout.isEmpty()) {
            violations.add(
                "policy Dockerfiles missing from checkout: "
                    + String.join(", ", missingFromCheckout)
            );
        }

        Set<String> common = new TreeSet<>(trackedSet);
        common.retainAll(declaredSet);
        List<FileEvaluation> files = new ArrayList<>();
        Map<String, List<String>> actualByFile = new TreeMap<>();
        for (String relative : common) {
            List<String> expected = policy.files().get(relative);
            List<String> actual = externalImages(root.resolve(relative));
            actualByFile.put(relative, actual);
            List<String> rowViolations = new ArrayList<>();
            if (!actual.equals(expected)) {
                rowViolations.add(
                    "external image sequence differs: expected "
                        + expected
                        + ", found "
                        + actual
                );
            }
            for (String reference : actual) {
                if (!isDigestOrExactVersion(reference)) {
                    rowViolations.add(
                        "floating or insufficiently versioned image reference: "
                            + reference
                    );
                }
            }
            for (String violation : rowViolations) {
                violations.add(relative + ": " + violation);
            }
            files.add(new FileEvaluation(
                relative,
                expected,
                actual,
                rowViolations.isEmpty() ? "PASSED" : "FAILED",
                List.copyOf(rowViolations)
            ));
        }

        VisualEvaluation visual = evaluateVisualContract(
            root,
            policy.files(),
            actualByFile
        );
        for (String violation : visual.violations()) {
            violations.add("visual regression: " + violation);
        }
        return new Evaluation(
            violations.isEmpty() ? "PASSED" : "FAILED",
            tracked,
            List.copyOf(files),
            visual,
            List.copyOf(violations)
        );
    }

    private static VisualEvaluation evaluateVisualContract(
        Path root,
        Map<String, List<String>> declared,
        Map<String, List<String>> actualByFile
    ) throws IOException {
        List<String> violations = new ArrayList<>();
        ObjectNode visual = requireObject(
            parseStrict(root.resolve(VISUAL_POLICY_PATH)),
            "visual regression policy"
        );
        if (!VISUAL_POLICY_SCHEMA.equals(optionalText(visual, "schema"))) {
            violations.add("unsupported visual regression policy schema");
        }

        JsonNode environmentValue = visual.get("environment");
        ObjectNode environment;
        if (environmentValue instanceof ObjectNode object) {
            environment = object;
        } else {
            violations.add(
                "visual regression environment must be an object"
            );
            environment = JSON.createObjectNode();
        }

        String image = optionalText(environment, "containerImage");
        String version = optionalText(environment, "playwrightVersion");
        if (image == null || image.isBlank()) {
            violations.add("visual regression containerImage is missing");
        }
        if (version == null
                || !PLAYWRIGHT_VERSION_PATTERN.matcher(version).matches()) {
            violations.add("visual regression playwrightVersion is invalid");
        }

        if (image != null && !image.isBlank()) {
            if (!List.of(image).equals(declared.get(VISUAL_DOCKERFILE))) {
                violations.add(
                    "visual policy containerImage differs from container-image-policy"
                );
            }
            if (!List.of(image).equals(actualByFile.get(VISUAL_DOCKERFILE))) {
                violations.add(
                    "visual policy containerImage differs from Dockerfile.visual-regression"
                );
            }
        }
        if (image != null && version != null
                && !image.contains(":v" + version + "-")) {
            violations.add(
                "visual container image tag does not match playwrightVersion"
            );
        }

        List<String> dependencyVersions = playwrightDependencyVersions(
            root.resolve(APP_BUILD_PATH)
        );
        if (dependencyVersions.isEmpty()) {
            violations.add(
                "no Playwright Java dependency found in app/build.gradle"
            );
        } else if (version == null
                || !dependencyVersions.equals(List.of(version))) {
            violations.add(
                "Playwright dependency versions do not match visual policy: "
                    + "policy="
                    + version
                    + ", dependencies="
                    + dependencyVersions
            );
        }

        return new VisualEvaluation(
            VISUAL_POLICY_PATH.toString().replace('\\', '/'),
            image,
            version,
            dependencyVersions,
            violations.isEmpty() ? "PASSED" : "FAILED",
            List.copyOf(violations)
        );
    }

    private static List<String> playwrightDependencyVersions(Path path)
            throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        Matcher matcher = PLAYWRIGHT_DEPENDENCY_PATTERN.matcher(
            Files.readString(path, StandardCharsets.UTF_8)
        );
        Set<String> versions = new TreeSet<>();
        while (matcher.find()) {
            versions.add(matcher.group(1));
        }
        return List.copyOf(versions);
    }

    private static List<String> sourceTreeDockerfiles(Path root)
            throws IOException {
        List<String> result = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(
                Path directory,
                BasicFileAttributes attributes
            ) {
                if (!directory.equals(root)
                        && IGNORED_DIRECTORY_NAMES.contains(
                            directory.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(
                Path file,
                BasicFileAttributes attributes
            ) {
                if (attributes.isRegularFile()
                        && file.getFileName().toString()
                            .startsWith("Dockerfile")) {
                    result.add(root.relativize(file).normalize().toString()
                        .replace('\\', '/'));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        result.sort(String::compareTo);
        return List.copyOf(result);
    }

    private static List<String> externalImages(Path path)
            throws IOException {
        Set<String> aliases = new HashSet<>();
        List<String> images = new ArrayList<>();
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (!line.stripLeading().toUpperCase(Locale.ROOT)
                    .startsWith("FROM ")) {
                continue;
            }
            Matcher matcher = FROM_PATTERN.matcher(line);
            if (!matcher.matches()) {
                throw invalid(
                    "unsupported FROM syntax in "
                        + path
                        + ":"
                        + (index + 1)
                        + ": "
                        + line
                );
            }
            String reference = matcher.group(1);
            String alias = matcher.group(2);
            if (!aliases.contains(reference)) {
                images.add(reference);
            }
            if (alias != null) {
                aliases.add(alias);
            }
        }
        if (images.isEmpty()) {
            throw invalid(
                "Dockerfile has no external base image: " + path
            );
        }
        return List.copyOf(images);
    }

    private static boolean isDigestOrExactVersion(String reference) {
        if (DIGEST_PATTERN.matcher(reference).find()) {
            return true;
        }
        String image = reference.split("@", 2)[0];
        int slash = image.lastIndexOf('/');
        int colon = image.lastIndexOf(':');
        if (colon <= slash) {
            return false;
        }
        String tag = image.substring(colon + 1);
        return !tag.isBlank()
            && !"latest".equalsIgnoreCase(tag)
            && EXACT_VERSION_PATTERN.matcher(tag).find();
    }

    private static Policy parsePolicy(JsonNode value) {
        ObjectNode document = requireObject(
            value,
            "container image policy"
        );
        requireExactFields(document, POLICY_FIELDS, "container image policy");
        if (!POLICY_SCHEMA.equals(requiredText(
                document,
                "schema",
                "container image policy"))) {
            throw invalid("unsupported policy schema");
        }
        String policyText = requiredText(
            document,
            "policy",
            "container image policy"
        );
        ObjectNode files = requireObject(
            document.get("files"),
            "container image policy files"
        );
        if (files.isEmpty()) {
            throw invalid("policy has no Dockerfiles");
        }

        Map<String, List<String>> declarations = new TreeMap<>();
        files.fields().forEachRemaining(entry -> {
            String path = entry.getKey();
            requireRelativePolicyPath(path);
            ArrayNode values = requireArray(
                entry.getValue(),
                "image declaration for " + path
            );
            if (values.isEmpty()) {
                throw invalid("invalid image declaration for " + path);
            }
            List<String> images = new ArrayList<>(values.size());
            for (JsonNode image : values) {
                if (!image.isTextual() || image.asText().isBlank()) {
                    throw invalid(
                        "invalid image declaration for " + path
                    );
                }
                images.add(image.asText());
            }
            declarations.put(path, List.copyOf(images));
        });
        return new Policy(policyText, Map.copyOf(declarations));
    }

    private static void requireRelativePolicyPath(String value) {
        if (value.isBlank()) {
            throw invalid("Dockerfile policy path must not be blank");
        }
        Path path = Path.of(value);
        if (path.isAbsolute() || path.normalize().startsWith("..")
                || value.contains("\\")) {
            throw invalid(
                "Dockerfile policy path must be normalized and relative: "
                    + value
            );
        }
        String normalized = path.normalize().toString().replace('\\', '/');
        if (!normalized.equals(value)) {
            throw invalid(
                "Dockerfile policy path must be normalized and relative: "
                    + value
            );
        }
    }

    private static JsonNode parseStrict(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw invalid("missing JSON file: " + path);
        }
        try (JsonParser parser = JSON.getFactory().createParser(
                path.toFile())) {
            JsonNode document = JSON.readTree(parser);
            if (document == null) {
                throw invalid("empty JSON document: " + path);
            }
            if (parser.nextToken() != null) {
                throw invalid("trailing JSON content: " + path);
            }
            return document;
        } catch (JsonProcessingException exception) {
            throw invalid(
                "invalid JSON "
                    + path
                    + ": "
                    + exception.getOriginalMessage(),
                exception
            );
        }
    }

    private static void writeReport(Path path, Evaluation evaluation)
            throws IOException {
        ObjectNode report = JSON.createObjectNode();
        report.put("schema", REPORT_SCHEMA);
        report.put("policy", POLICY_PATH.toString().replace('\\', '/'));
        report.put("status", evaluation.status());
        ArrayNode tracked = report.putArray("trackedDockerfiles");
        evaluation.trackedDockerfiles().forEach(tracked::add);

        ArrayNode files = report.putArray("files");
        for (FileEvaluation file : evaluation.files()) {
            ObjectNode row = files.addObject();
            row.put("path", file.path());
            ArrayNode expected = row.putArray("expectedImages");
            file.expectedImages().forEach(expected::add);
            ArrayNode actual = row.putArray("externalImages");
            file.externalImages().forEach(actual::add);
            row.put("status", file.status());
            ArrayNode violations = row.putArray("violations");
            file.violations().forEach(violations::add);
        }

        VisualEvaluation visual = evaluation.visualRegressionContract();
        ObjectNode visualNode = report.putObject(
            "visualRegressionContract"
        );
        visualNode.put("policy", visual.policy());
        putNullable(visualNode, "containerImage", visual.containerImage());
        putNullable(
            visualNode,
            "playwrightVersion",
            visual.playwrightVersion()
        );
        ArrayNode dependencyVersions = visualNode.putArray(
            "dependencyVersions"
        );
        visual.dependencyVersions().forEach(dependencyVersions::add);
        visualNode.put("status", visual.status());
        ArrayNode visualViolations = visualNode.putArray("violations");
        visual.violations().forEach(visualViolations::add);

        ArrayNode violations = report.putArray("violations");
        evaluation.violations().forEach(violations::add);
        writeJson(path, report);
    }

    private static void putNullable(
        ObjectNode object,
        String field,
        String value
    ) {
        if (value == null) {
            object.putNull(field);
        } else {
            object.put(field, value);
        }
    }

    private static void writeJson(Path path, JsonNode value)
            throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(
            path,
            JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value)
                + "\n",
            StandardCharsets.UTF_8
        );
    }

    private static ObjectNode validPolicyDocument(String image) {
        ObjectNode policy = JSON.createObjectNode();
        policy.put("schema", POLICY_SCHEMA);
        policy.put("policy", "fixture");
        policy.putObject("files")
            .putArray(VISUAL_DOCKERFILE)
            .add(image);
        return policy;
    }

    private static void createVisualFixture(
        Path root,
        String image,
        String policyVersion,
        String dependencyVersion
    ) throws IOException {
        Files.writeString(
            root.resolve(VISUAL_DOCKERFILE),
            "FROM " + image + "\n",
            StandardCharsets.UTF_8
        );
        Files.createDirectories(root.resolve(VISUAL_POLICY_PATH).getParent());
        ObjectNode visual = JSON.createObjectNode();
        visual.put("schema", VISUAL_POLICY_SCHEMA);
        ObjectNode environment = visual.putObject("environment");
        environment.put("containerImage", image);
        environment.put("playwrightVersion", policyVersion);
        writeJson(root.resolve(VISUAL_POLICY_PATH), visual);
        Files.createDirectories(root.resolve(APP_BUILD_PATH).getParent());
        Files.writeString(
            root.resolve(APP_BUILD_PATH),
            "e2eTestImplementation 'com.microsoft.playwright:playwright:"
                + dependencyVersion
                + "'\n",
            StandardCharsets.UTF_8
        );
    }

    private static void requireExactFields(
        ObjectNode value,
        Set<String> expected,
        String context
    ) {
        Set<String> actual = new TreeSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        Set<String> unknown = new TreeSet<>(actual);
        unknown.removeAll(expected);
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        if (!unknown.isEmpty() || !missing.isEmpty()) {
            throw invalid(
                context + " unknown=" + unknown + " missing=" + missing
            );
        }
    }

    private static ObjectNode requireObject(
        JsonNode value,
        String context
    ) {
        if (!(value instanceof ObjectNode object)) {
            throw invalid(context + " must be a JSON object");
        }
        return object;
    }

    private static ArrayNode requireArray(JsonNode value, String context) {
        if (!(value instanceof ArrayNode array)) {
            throw invalid(context + " must be a JSON array");
        }
        return array;
    }

    private static String requiredText(
        ObjectNode object,
        String field,
        String context
    ) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw invalid(context + " field " + field + " must be text");
        }
        return value.asText();
    }

    private static String optionalText(ObjectNode object, String field) {
        JsonNode value = object.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static void expectInvalid(
        Executable operation,
        String fragment
    ) {
        IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            operation
        );
        assertTrue(
            failure.getMessage().toLowerCase().contains(
                fragment.toLowerCase()
            ),
            () -> "expected <"
                + fragment
                + "> in <"
                + failure.getMessage()
                + ">"
        );
    }

    private static Path repositoryRoot() {
        String configured = System.getProperty("regelsuche.repositoryRoot");
        assertTrue(
            configured != null && !configured.isBlank(),
            "Maven must expose maven.multiModuleProjectDirectory to tests"
        );
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(
            "container image policy invalid: " + message
        );
    }

    private static IllegalArgumentException invalid(
        String message,
        Throwable cause
    ) {
        return new IllegalArgumentException(
            "container image policy invalid: " + message,
            cause
        );
    }

    private record Policy(
        String policyText,
        Map<String, List<String>> files
    ) { }

    private record FileEvaluation(
        String path,
        List<String> expectedImages,
        List<String> externalImages,
        String status,
        List<String> violations
    ) { }

    private record VisualEvaluation(
        String policy,
        String containerImage,
        String playwrightVersion,
        List<String> dependencyVersions,
        String status,
        List<String> violations
    ) { }

    private record Evaluation(
        String status,
        List<String> trackedDockerfiles,
        List<FileEvaluation> files,
        VisualEvaluation visualRegressionContract,
        List<String> violations
    ) { }
}
