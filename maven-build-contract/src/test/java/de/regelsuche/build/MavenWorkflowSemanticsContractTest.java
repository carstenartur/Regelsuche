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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

class MavenWorkflowSemanticsContractTest {
    private static final ObjectMapper JSON = new ObjectMapper(
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build()
    );

    private static final String POLICY_SCHEMA =
        "regelsuche.workflow-semantics-policy/v2";
    private static final String GOVERNANCE_SCHEMA =
        "regelsuche.github-merge-governance-policy/v1";
    private static final Set<String> POLICY_FIELDS = Set.of(
        "schema",
        "maximumWorkflowCount",
        "verificationWorkflows",
        "platformWorkflows"
    );
    private static final Set<String> GOVERNANCE_FIELDS = Set.of(
        "schema",
        "defaultBranch",
        "requiredStatusCheck",
        "createEventCheckContext",
        "routineBypassActors",
        "currentUserCanBypass",
        "requiredApprovingReviewCount",
        "requiredReviewThreadResolution"
    );
    private static final Set<String> REQUIRED_STATUS_FIELDS = Set.of(
        "context",
        "integrationId",
        "strictUpToDate"
    );

    private static final List<NamedPattern> TEXT_PATTERNS = List.of(
        named(
            "inline-interpreter-heredoc",
            "\\b(?:python3?|node|ruby|perl)\\s+-\\s*<<"
        ),
        named(
            "manual-docker-lifecycle",
            "\\bdocker\\s+(?:build|run|compose)\\b"
        ),
        named(
            "fixed-host-port",
            "(?:^|\\s)-p\\s+\\d{2,5}:\\d{2,5}(?:\\s|$)"
        ),
        named("github-service-fixture", "^\\s+services:\\s*$"),
        named(
            "direct-repository-script",
            "\\b(?:python3?|bash)\\s+(?:scripts/|reproduction/)"
        )
    );
    private static final List<String> ASSERTION_PREFIXES = List.of(
        "grep ",
        "test ",
        "cmp ",
        "diff ",
        "git diff ",
        "jq -e ",
        "[ ",
        "[[ "
    );
    private static final Pattern RUN_DIRECTIVE = Pattern.compile(
        "^(\\s*)(?:-\\s*)?run:\\s*(.*)$"
    );
    private static final Pattern GRADLE_INVOCATION =
        Pattern.compile("\\./gradlew\\b");
    private static final Pattern CI_TASK_ON_INVOCATION = Pattern.compile(
        "(?:\\bciCheck\\b|\\$\\{?REGELSUCHE_CI_TASK\\}?)"
    );

    // Transitional host drivers are frozen, not blessed as Maven authority.
    // Each retained line may occur at most once and can disappear during migration.
    private static final Set<String> LEGACY_PYTHON_RUN_LINES = Set.of(
        "python3 -B scripts/run-jmh-precision-study-v1.py",
        "python3 -B scripts/report-jmh-precision-study-v1.py",
        "python3 -B scripts/run-jmh-precision-study-v2.py",
        "python3 -B scripts/report-jmh-precision-study-v2.py"
    );
    private static final Pattern DIRECT_HOST_INTERPRETER = Pattern.compile(
        "(?<![A-Za-z0-9_.-])"
            + "(?:python(?:[23](?:\\.\\d+)?)?|pypy(?:[23](?:\\.\\d+)?)?"
            + "|pip(?:[23](?:\\.\\d+)?)?|pipx|uvx?|node(?:js)?|npm|npx|pnpm|yarn|corepack|deno|bunx?"
            + "|perl|ruby|pwsh|powershell)(?=\\s+|$|[;&|<>])"
    );

    // These four folded YAML commands are retained only until their Maven
    // replacements are authoritative. Match the entire folded scalar, not
    // just its first physical line: extra arguments must not evade the guard.
    private static final Set<String> LEGACY_FOLDED_PYTHON_COMMANDS = Set.of(
        "python3 -B scripts/run-jmh-precision-study-v1.py"
            + " --replicate \"$REGELSUCHE_STUDY_REPLICATE\""
            + " --job-start-epoch \"$REGELSUCHE_STUDY_JOB_START\""
            + " --output \"build/reports/jmh-precision-study-v1/"
            + "replicate-$REGELSUCHE_STUDY_REPLICATE\"",
        "python3 -B scripts/report-jmh-precision-study-v1.py"
            + " --replicate retained/jmh-precision-study-v1-replicate-1"
            + " --replicate retained/jmh-precision-study-v1-replicate-2"
            + " --replicate retained/jmh-precision-study-v1-replicate-3"
            + " --output build/reports/jmh-precision-study-v1/analysis",
        "python3 -B scripts/run-jmh-precision-study-v2.py"
            + " --replicate \"$REGELSUCHE_STUDY_REPLICATE\""
            + " --job-start-epoch \"$REGELSUCHE_STUDY_JOB_START\""
            + " --output \"build/reports/jmh-precision-study-v2/"
            + "replicate-$REGELSUCHE_STUDY_REPLICATE\"",
        "python3 -B scripts/report-jmh-precision-study-v2.py"
            + " --replicate retained/jmh-precision-study-v2-replicate-1"
            + " --replicate retained/jmh-precision-study-v2-replicate-2"
            + " --replicate retained/jmh-precision-study-v2-replicate-3"
            + " --output build/reports/jmh-precision-study-v2/analysis"
    );

    // No new Python/Node/Perl/PowerShell host drivers may enter the release
    // workflow while migration to Maven/Java is still incomplete. These
    // exact existing commands are transitional, not approved build authority.
    private static final Set<String> LEGACY_RELEASE_HOST_COMMANDS = Set.of(
        "python3 .github/scripts/update-release-metadata.py \"$VERSION\" --check",
        "python3 -B scripts/package-student-sdk.py \\",
        "python3 -B scripts/package-optimization-sdk.py \\"
    );

    @Test
    void repositoryWorkflowsSatisfyTheCheckoutOwnedContract()
            throws IOException {
        VerificationResult result = verify(repositoryRoot());

        assertEquals(2, result.workflowCount());
        assertEquals(1, result.verificationWorkflowCount());
        assertEquals(1, result.platformWorkflowCount());
        assertEquals("Checkout-local ciCheck", result.requiredCheck());
        System.out.println(
            "workflowSemantics=VERIFIED workflows="
                + result.workflowCount()
                + "/"
                + result.maximumWorkflowCount()
                + " verification="
                + result.verificationWorkflowCount()
                + " platform="
                + result.platformWorkflowCount()
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
    void policyAndGovernanceDriftFailClosed() {
        ObjectNode unknownPolicy = validPolicyDocument();
        unknownPolicy.put("unexpected", true);
        expectInvalid(
            () -> parsePolicy(unknownPolicy),
            "workflow policy unknown="
        );

        ObjectNode unsortedPolicy = validPolicyDocument();
        ((ArrayNode) unsortedPolicy.get("verificationWorkflows"))
            .add("another.yml");
        expectInvalid(
            () -> parsePolicy(unsortedPolicy),
            "verificationWorkflows must be sorted and unique"
        );

        ObjectNode excessivePolicy = validPolicyDocument();
        excessivePolicy.put("maximumWorkflowCount", 1);
        expectInvalid(
            () -> parsePolicy(excessivePolicy),
            "classified workflows exceed maximumWorkflowCount"
        );

        ObjectNode weakStrictness = validGovernanceDocument();
        ((ObjectNode) weakStrictness.get("requiredStatusCheck"))
            .put("strictUpToDate", false);
        expectInvalid(
            () -> parseGovernance(weakStrictness),
            "strictUpToDate must remain true"
        );

        ObjectNode bypass = validGovernanceDocument();
        ((ArrayNode) bypass.get("routineBypassActors"))
            .add("repository-role:admin");
        expectInvalid(
            () -> parseGovernance(bypass),
            "routineBypassActors must be an empty array"
        );

        ObjectNode collision = validGovernanceDocument();
        collision.put("createEventCheckContext", "Checkout-local ciCheck");
        expectInvalid(
            () -> parseGovernance(collision),
            "create-event check context must differ"
        );
    }

    @Test
    void workflowClassificationFailsClosed() {
        Policy policy = validPolicy();
        expectInvalid(
            () -> verifyClassification(
                policy,
                List.of("extra.yml", "gradle.yml", "release.yml")
            ),
            "too many workflows"
        );
        expectInvalid(
            () -> verifyClassification(
                policy,
                List.of("extra.yml", "gradle.yml")
            ),
            "unclassified=[extra.yml] stale=[release.yml]"
        );
        expectInvalid(
            () -> verifyClassification(policy, List.of("gradle.yml")),
            "unclassified=[] stale=[release.yml]"
        );
    }

    @Test
    void workflowHostRuntimeInventoryRejectsNewAndDuplicateDrivers() {
        MergeGovernance governance = validGovernance();
        String workflow = validWorkflow();
        String allowed = "python3 -B scripts/generate-ci-pages.py --coverage-only";

        assertViolation(
            workflow + "\n          " + allowed + "\n",
            governance,
            "undeclared-host-runtime"
        );
        for (String forbidden : List.of(
                "python3 -B scripts/verify-cross-authority-coverage.py",
                "python3 -B scripts/new-check.py",
                "/usr/bin/python3 -B scripts/new-check.py",
                "env python3 -c 'print(1)'",
                "node scripts/new-check.mjs",
                "npm test",
                "pypy3 -B scripts/new-check.py",
                "python2 -B scripts/new-check.py",
                "pip3.13 install package",
                "pip2.7 install package",
                "nodejs scripts/new-check.js",
                "bunx eslint .",
                "uv run python3 scripts/new-check.py",
                "uvx check-tool",
                "pip3 install package",
                "pipx run check-tool",
                "pnpm test",
                "yarn test",
                "corepack pnpm test",
                "deno test",
                "bun run scripts/new-check.js",
                "- run: " + allowed)) {
            assertViolation(
                workflow + "\n          " + forbidden + "\n",
                governance,
                "undeclared-host-runtime"
            );
        }
        assertViolation(
            workflow + "\n          " + allowed + "\n          " + allowed + "\n",
            governance,
            "undeclared-host-runtime"
        );
        assertNoViolation(
            workflow + "\n          # python3 -B scripts/new-check.py\n",
            governance,
            "undeclared-host-runtime"
        );
    }

    @Test
    void releaseWorkflowHostRuntimeInventoryRejectsNewAndDuplicateDrivers() {
        String command =
            "python3 .github/scripts/update-release-metadata.py \"$VERSION\" --check";
        String release = "jobs:\n  release:\n    steps:\n      - run: |\n          "
            + command + "\n";

        assertFalse(hasCategory(scanPlatformHostRuntimes("release.yml", release),
            "undeclared-host-runtime"));
        assertFalse(hasCategory(scanPlatformHostRuntimes("release.yml",
            release + "          # node scripts/ignored.mjs\n"),
            "undeclared-host-runtime"));
        for (String extra : List.of(
                command,
                "python3 .github/scripts/update-release-metadata.py \"$NEXT_VERSION\" --check",
                "python3 -B scripts/undeclared-release.py",
                "/usr/bin/python3 -B scripts/undeclared-release.py",
                "env python3 -B scripts/undeclared-release.py",
                "node scripts/undeclared-release.mjs",
                "npm run release",
                "pypy3 -B scripts/undeclared-release.py",
                "python2 -B scripts/undeclared-release.py",
                "pip3.13 install package",
                "pip2.7 install package",
                "nodejs scripts/undeclared-release.js",
                "bunx eslint .",
                "uv run python3 scripts/undeclared-release.py",
                "uvx build-wheel",
                "pipx run package",
                "pnpm run release",
                "yarn release",
                "corepack pnpm release",
                "deno run release.ts",
                "bun run release.ts",
                "pwsh scripts/undeclared-release.ps1")) {
            String changed = release + "          " + extra + "\n";
            assertTrue(hasCategory(scanPlatformHostRuntimes(
                "release.yml", changed), "undeclared-host-runtime"), extra);
        }
        assertTrue(hasCategory(scanPlatformHostRuntimes("release.yml",
            release + "      - run: npm test\n"), "undeclared-host-runtime"));
    }

    @Test
    void releaseRuntimeGuardParsesYamlBlockHeadersWithCommentsAndIndentIndicators() {
        String grandfathered =
            "python3 .github/scripts/update-release-metadata.py \"$VERSION\" --check";
        for (String header : List.of(
                "|", "|-", "|2", "|2-", "|-2", ">+2",
                "| # retained", "|- # retained", "|2 # retained",
                "|2- # retained", "|-2 # retained", ">+2 # retained")) {
            String release = "jobs:\n  release:\n    steps:\n      - run: "
                + header + "\n          " + grandfathered + "\n";
            assertEquals(List.of(grandfathered),
                workflowRunCommands(release.lines().toList()), header);
            assertFalse(hasCategory(scanPlatformHostRuntimes("release.yml", release),
                "undeclared-host-runtime"), header);
            String unauthorized = release.replace(grandfathered,
                "python3 scripts/undeclared-release.py");
            assertTrue(hasCategory(scanPlatformHostRuntimes("release.yml", unauthorized),
                "undeclared-host-runtime"), header);
        }
    }

    @Test
    void foldedYamlRunScalarsCannotHideSplitHostRuntimeCommands() {
        String prefix = "jobs:\n  release:\n    steps:\n      - run: ";
        for (String header : List.of(">", ">-", ">+2", ">-2 # comment")) {
            String unauthorized = prefix + header
                + "\n          python3\n          -B scripts/new-release.py\n";
            assertEquals(
                List.of("python3 -B scripts/new-release.py"),
                workflowRunCommands(unauthorized.lines().toList()),
                header
            );
            assertTrue(hasCategory(scanPlatformHostRuntimes(
                "release.yml", unauthorized), "undeclared-host-runtime"),
                header
            );
        }
        String noFold = prefix + "|\n          python3\n"
            + "          -B scripts/new-release.py\n";
        assertEquals(List.of("python3", "-B scripts/new-release.py"),
            workflowRunCommands(noFold.lines().toList()));
        // A standalone interpreter is a host runtime too; a blank line
        // in a folded scalar does not make it an approved command.
        assertTrue(hasCategory(scanPlatformHostRuntimes("release.yml", noFold),
            "undeclared-host-runtime"));
        String separated = prefix + ">-\n          python3\n\n"
            + "          -B scripts/new-release.py\n";
        assertTrue(hasCategory(scanPlatformHostRuntimes(
            "release.yml", separated), "undeclared-host-runtime"));
    }

    @Test
    void foldedYamlCannotHideNewHostRuntimesInEitherWorkflow() {
        MergeGovernance governance = validGovernance();
        for (String tool : List.of("pypy3", "python2", "pip3.13", "nodejs", "bunx",
                "uv", "uvx", "pnpm",
                "yarn", "corepack", "deno", "bun", "pipx")) {
            String folded = "jobs:\n  verification:\n    steps:\n"
                + "      - run: >-\n          " + tool
                + "\n          --version\n";
            assertViolation(validWorkflow() + "\n" + folded,
                governance, "undeclared-host-runtime");
            assertTrue(hasCategory(scanPlatformHostRuntimes(
                "release.yml", folded), "undeclared-host-runtime"), tool);
        }
    }

    @Test
    void foldedVerificationCommandsRequireExactLegacyArguments() {
        MergeGovernance governance = validGovernance();
        String prefix = validWorkflow()
            + "\n      - run: >-\n          python3\n"
            + "          -B scripts/collect-quality-acceptance-evidence.py\n";
        assertViolation(
            prefix + "          --unapproved-option\n",
            governance,
            "undeclared-host-runtime"
        );
        String retired = validWorkflow()
            + "\n      - run: >-\n"
            + "          python3 -B scripts/collect-quality-acceptance-evidence.py\n"
            + "          --root \"$PWD\" --require-complete\n";
        assertViolation(retired, governance, "undeclared-host-runtime");
        String splitExecutable = validWorkflow()
            + "\n      - run: >-\n          python3\n"
            + "          scripts/new-check.py\n";
        assertViolation(splitExecutable, governance, "undeclared-host-runtime");
    }

    @Test
    void shellControlOperatorsCannotBypassTheHostRuntimeInventory() {
        MergeGovernance governance = validGovernance();
        // A shell does not require whitespace before operators or redirects.
        // Earlier inventory checks accepted these undeclared host commands.
        for (String invocation : List.of(
                "python3<<'PY'",
                "node<<<'console.log(1)'",
                "perl; echo continuing",
                "python3&&echo continuing",
                "python3||echo failed",
                "python3|cat",
                "/usr/bin/python3>/tmp/result",
                "nodejs<test.js",
                "pip3.13<<<'list'")) {
            assertViolation(validWorkflow() + "\n      - run: "
                + invocation + "\n", governance, "undeclared-host-runtime");
            String release = "jobs:\n  release:\n    steps:\n      - run: "
                + invocation + "\n";
            assertTrue(hasCategory(scanPlatformHostRuntimes("release.yml", release),
                "undeclared-host-runtime"), invocation);
        }
        assertNoViolation(validWorkflow()
            + "\n      - run: echo python3-helper\n",
            governance, "undeclared-host-runtime");
    }

    @Test
    void workflowTextGuardRejectsEveryForbiddenSemantic() {
        Map<String, String> samples = new LinkedHashMap<>();
        samples.put(
            "inline-interpreter-heredoc",
            "      - run: python3 - <<'PY'"
        );
        samples.put(
            "manual-docker-lifecycle",
            "      - run: docker build ."
        );
        samples.put(
            "fixed-host-port",
            "      - run: docker run -p 18080:8080 image"
        );
        samples.put("github-service-fixture", "    services:");
        samples.put(
            "direct-repository-script",
            "      - run: bash scripts/check.sh"
        );
        samples.put(
            "workflow-owned-assertion",
            "          grep expected output.txt"
        );

        MergeGovernance governance = validGovernance();
        for (Map.Entry<String, String> sample : samples.entrySet()) {
            assertViolation(
                validWorkflow() + "\n" + sample.getValue() + "\n",
                governance,
                sample.getKey()
            );
        }

        assertViolation(
            validWorkflow().replace("./gradlew", "echo"),
            governance,
            "missing-gradle-entrypoint"
        );
        assertViolation(
            validWorkflow()
                + "\n      - run: ./gradlew test\n"
                + "      - run: ./gradlew check\n",
            governance,
            "too-many-gradle-entrypoints"
        );
        assertViolation(
            workflow(
                "Checkout-local ciCheck",
                "Showcase train-freeze authority v1",
                "./gradlew ciCheck && ./gradlew test && ./gradlew check"
            ),
            governance,
            "too-many-gradle-entrypoints"
        );

        String misleadingCiText = workflow(
            "Checkout-local ciCheck",
            "Showcase train-freeze authority v1",
            "./gradlew test"
        ) + "\n    env:\n      COMMAND: ./gradlew ciCheck\n";
        assertViolation(
            misleadingCiText,
            governance,
            "missing-ci-entrypoint"
        );
        String commentedCiText = workflow(
            "Checkout-local ciCheck",
            "Showcase train-freeze authority v1",
            "./gradlew test"
        ) + """

                  - run: |
                      # ./gradlew ciCheck
                      echo no-op
            """;
        assertViolation(
            commentedCiText,
            governance,
            "missing-ci-entrypoint"
        );
        assertNoViolation(
            workflow(
                "Checkout-local ciCheck",
                "Showcase train-freeze authority v1",
                "./gradlew \"$REGELSUCHE_CI_TASK\""
            ),
            governance,
            "missing-ci-entrypoint"
        );

        assertViolation(
            validWorkflow().replace(
                "Showcase train-freeze authority v1' || 'Checkout-local ciCheck",
                "Wrong create check' || 'Wrong required check"
            ),
            governance,
            "required-check-create-collision"
        );
    }

    private static VerificationResult verify(Path root) throws IOException {
        Path workflows = root.resolve(".github/workflows");
        Policy policy = parsePolicy(parseStrict(root.resolve(
            "config/workflow-semantics-policy.json"
        )));
        MergeGovernance governance = parseGovernance(parseStrict(root.resolve(
            "config/github-merge-governance-policy.json"
        )));
        List<String> actual = workflowNames(workflows);
        verifyClassification(policy, actual);

        List<Violation> violations = new ArrayList<>();
        for (String workflow : policy.verificationWorkflows()) {
            violations.addAll(scanVerificationWorkflow(
                workflow,
                Files.readString(
                    workflows.resolve(workflow),
                    StandardCharsets.UTF_8
                ),
                governance
            ));
        }
        for (String workflow : policy.platformWorkflows()) {
            violations.addAll(scanPlatformHostRuntimes(
                workflow,
                Files.readString(
                    workflows.resolve(workflow),
                    StandardCharsets.UTF_8
                )
            ));
        }
        if (!violations.isEmpty()) {
            throw invalid(
                "verification workflows contain checkout-owned semantics:\n"
                    + violations.stream()
                        .map(Violation::toString)
                        .reduce((left, right) -> left + "\n" + right)
                        .orElse("none")
            );
        }
        return new VerificationResult(
            actual.size(),
            policy.maximumWorkflowCount(),
            policy.verificationWorkflows().size(),
            policy.platformWorkflows().size(),
            governance.requiredStatusCheckContext()
        );
    }

    private static Policy parsePolicy(JsonNode value) {
        ObjectNode document = requireObject(value, "workflow policy");
        requireExactFields(document, POLICY_FIELDS, "workflow policy");
        requireEquals(
            POLICY_SCHEMA,
            requiredText(document, "schema", "workflow policy"),
            "unsupported workflow policy schema"
        );

        int maximum = requiredInt(
            document,
            "maximumWorkflowCount",
            "workflow policy"
        );
        if (maximum < 1) {
            throw invalid("maximumWorkflowCount must be a positive integer");
        }
        List<String> verification = requiredTextArray(
            document,
            "verificationWorkflows",
            "workflow policy"
        );
        List<String> platform = requiredTextArray(
            document,
            "platformWorkflows",
            "workflow policy"
        );
        requireSortedUnique(verification, "verificationWorkflows");
        requireSortedUnique(platform, "platformWorkflows");

        Set<String> overlap = new TreeSet<>(verification);
        overlap.retainAll(platform);
        if (!overlap.isEmpty()) {
            throw invalid(
                "workflows have two ownership classifications: " + overlap
            );
        }
        if (verification.size() + platform.size() > maximum) {
            throw invalid(
                "classified workflows exceed maximumWorkflowCount"
            );
        }
        if (verification.isEmpty()) {
            throw invalid("at least one verification workflow is required");
        }
        return new Policy(maximum, verification, platform);
    }

    private static MergeGovernance parseGovernance(JsonNode value) {
        ObjectNode document = requireObject(
            value,
            "merge-governance policy"
        );
        requireExactFields(
            document,
            GOVERNANCE_FIELDS,
            "merge-governance policy"
        );
        requireEquals(
            GOVERNANCE_SCHEMA,
            requiredText(document, "schema", "merge-governance policy"),
            "unsupported merge-governance policy schema"
        );

        ObjectNode required = requireObject(
            document.get("requiredStatusCheck"),
            "requiredStatusCheck"
        );
        requireExactFields(
            required,
            REQUIRED_STATUS_FIELDS,
            "requiredStatusCheck"
        );

        String defaultBranch = requiredText(
            document,
            "defaultBranch",
            "merge-governance policy"
        );
        String requiredContext = requiredText(
            required,
            "context",
            "requiredStatusCheck"
        );
        int integrationId = requiredInt(
            required,
            "integrationId",
            "requiredStatusCheck"
        );
        boolean strictUpToDate = requiredBoolean(
            required,
            "strictUpToDate",
            "requiredStatusCheck"
        );
        String createContext = requiredText(
            document,
            "createEventCheckContext",
            "merge-governance policy"
        );
        ArrayNode bypassActors = requireArray(
            document.get("routineBypassActors"),
            "routineBypassActors"
        );
        String currentUserCanBypass = requiredText(
            document,
            "currentUserCanBypass",
            "merge-governance policy"
        );
        int requiredReviewCount = requiredInt(
            document,
            "requiredApprovingReviewCount",
            "merge-governance policy"
        );
        boolean threadResolution = requiredBoolean(
            document,
            "requiredReviewThreadResolution",
            "merge-governance policy"
        );

        if (requiredContext.equals(createContext)) {
            throw invalid(
                "create-event check context must differ from the required merge check"
            );
        }
        if (integrationId <= 0) {
            throw invalid(
                "required status integrationId must be a positive integer"
            );
        }
        if (!strictUpToDate) {
            throw invalid("strictUpToDate must remain true");
        }
        if (!bypassActors.isEmpty()) {
            throw invalid("routineBypassActors must be an empty array");
        }
        if (!"never".equals(currentUserCanBypass)) {
            throw invalid("currentUserCanBypass must be 'never'");
        }
        if (requiredReviewCount < 0) {
            throw invalid(
                "requiredApprovingReviewCount must be non-negative"
            );
        }
        if (!threadResolution) {
            throw invalid(
                "requiredReviewThreadResolution must remain true"
            );
        }
        return new MergeGovernance(
            defaultBranch,
            requiredContext,
            integrationId,
            strictUpToDate,
            createContext,
            currentUserCanBypass,
            requiredReviewCount,
            threadResolution
        );
    }

    private static void verifyClassification(
        Policy policy,
        List<String> actual
    ) {
        if (actual.size() > policy.maximumWorkflowCount()) {
            throw invalid(
                "too many workflows: "
                    + actual.size()
                    + " present, maximum is "
                    + policy.maximumWorkflowCount()
            );
        }
        List<String> classified = Stream.concat(
            policy.verificationWorkflows().stream(),
            policy.platformWorkflows().stream()
        ).sorted().toList();
        if (!classified.equals(actual)) {
            Set<String> unclassified = new TreeSet<>(actual);
            unclassified.removeAll(classified);
            Set<String> stale = new TreeSet<>(classified);
            stale.removeAll(actual);
            throw invalid(
                "workflow classifications differ: unclassified="
                    + unclassified
                    + " stale="
                    + stale
            );
        }
    }

    private static List<String> workflowNames(Path directory)
            throws IOException {
        if (!Files.isDirectory(directory)) {
            throw invalid(
                "workflow directory does not exist: " + directory
            );
        }
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .filter(name ->
                    name.endsWith(".yml") || name.endsWith(".yaml"))
                .sorted()
                .toList();
        }
    }

    private static List<Violation> scanVerificationWorkflow(
        String workflow,
        String text,
        MergeGovernance governance
    ) {
        List<String> lines = text.lines().toList();
        List<Violation> violations = new ArrayList<>();
        Set<String> retainedLegacyCommands = new TreeSet<>();

        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            for (NamedPattern named : TEXT_PATTERNS) {
                if (named.pattern().matcher(line).find()) {
                    violations.add(new Violation(
                        workflow,
                        index + 1,
                        named.category(),
                        line.strip()
                    ));
                }
            }
            String stripped = line.strip();
            // The workflow still contains explicitly retained Python jobs.
            // Detect new host interpreters (including -B and absolute paths)
            // and duplicate invocations of a grandfathered command.
            if (!stripped.startsWith("#")
                    && DIRECT_HOST_INTERPRETER.matcher(line).find()
                    && (!LEGACY_PYTHON_RUN_LINES.contains(stripped)
                        || !retainedLegacyCommands.add(stripped))) {
                violations.add(new Violation(
                    workflow,
                    index + 1,
                    "undeclared-host-runtime",
                    stripped
                ));
            }
            if (ASSERTION_PREFIXES.stream().anyMatch(
                    stripped::startsWith)) {
                violations.add(new Violation(
                    workflow,
                    index + 1,
                    "workflow-owned-assertion",
                    stripped
                ));
            }
        }

        List<String> commandLines = workflowRunCommands(lines);
        // Folded YAML joins physical lines with spaces before the shell runs.
        // The physical-line inventory above cannot see a split interpreter.
        for (String command : commandLines) {
            if (DIRECT_HOST_INTERPRETER.matcher(command).find()
                    && !LEGACY_PYTHON_RUN_LINES.contains(command)
                    && !LEGACY_FOLDED_PYTHON_COMMANDS.contains(command)) {
                violations.add(new Violation(
                    workflow, 1, "undeclared-host-runtime", command
                ));
            }
        }
        long invocationCount = commandLines.stream()
            .mapToLong(command -> GRADLE_INVOCATION.matcher(command)
                .results()
                .count())
            .sum();
        if (invocationCount == 0) {
            violations.add(new Violation(
                workflow,
                1,
                "missing-gradle-entrypoint",
                "no checkout-local Gradle invocation found"
            ));
        }
        if (invocationCount > 2) {
            violations.add(new Violation(
                workflow,
                1,
                "too-many-gradle-entrypoints",
                "found "
                    + invocationCount
                    + " Gradle invocations; expected at most two"
            ));
        }
        boolean invokesCiTask = commandLines.stream()
            .filter(command -> GRADLE_INVOCATION.matcher(command).find())
            .anyMatch(command ->
                CI_TASK_ON_INVOCATION.matcher(command).find());
        if (!invokesCiTask) {
            violations.add(new Violation(
                workflow,
                1,
                "missing-ci-entrypoint",
                "no Gradle invocation runs ciCheck or REGELSUCHE_CI_TASK"
            ));
        }

        String compact = text.replaceAll("\\s+", " ").trim();
        String expectedJobName =
            "${{ github.event_name == 'create' && '"
                + governance.createEventCheckContext()
                + "' || '"
                + governance.requiredStatusCheckContext()
                + "' }}";
        if (!compact.contains(expectedJobName)) {
            int jobNameLine = 1;
            for (int index = 0; index < lines.size(); index++) {
                if (lines.get(index).startsWith("    name:")) {
                    jobNameLine = index + 1;
                    break;
                }
            }
            violations.add(new Violation(
                workflow,
                jobNameLine,
                "required-check-create-collision",
                "verification job must give create events a distinct check name: "
                    + expectedJobName
            ));
        }
        return List.copyOf(violations);
    }

    private static List<Violation> scanPlatformHostRuntimes(
        String workflow,
        String text
    ) {
        List<String> lines = text.lines().toList();
        List<Violation> violations = new ArrayList<>();
        Set<String> alreadyUsed = new TreeSet<>();
        for (String command : workflowRunCommands(lines)) {
            if (!DIRECT_HOST_INTERPRETER.matcher(command).find()) {
                continue;
            }
            if (LEGACY_RELEASE_HOST_COMMANDS.contains(command)
                    && alreadyUsed.add(command)) {
                continue;
            }
            int lineNumber = 1;
            for (int index = 0; index < lines.size(); index++) {
                if (lines.get(index).contains(command)) {
                    lineNumber = index + 1;
                    break;
                }
            }
            violations.add(new Violation(
                workflow,
                lineNumber,
                "undeclared-host-runtime",
                command
            ));
        }
        return List.copyOf(violations);
    }

    private static List<String> workflowRunCommands(List<String> lines) {
        List<String> commands = new ArrayList<>();
        Integer blockIndent = null;
        StringBuilder foldedCommand = null;

        for (String line : lines) {
            if (blockIndent != null) {
                String stripped = line.strip();
                if (stripped.isEmpty()) {
                    // A blank line in a folded scalar preserves a newline.
                    // Do not join unrelated shell commands across it.
                    if (foldedCommand != null && foldedCommand.length() > 0) {
                        commands.add(foldedCommand.toString());
                        foldedCommand.setLength(0);
                    }
                    continue;
                }
                int indentation = leadingWhitespace(line);
                if (indentation > blockIndent) {
                    if (!stripped.startsWith("#")) {
                        if (foldedCommand == null) {
                            commands.add(stripped);
                        } else {
                            if (foldedCommand.length() > 0) {
                                foldedCommand.append(' ');
                            }
                            foldedCommand.append(stripped);
                        }
                    }
                    continue;
                }
                if (foldedCommand != null && foldedCommand.length() > 0) {
                    commands.add(foldedCommand.toString());
                }
                foldedCommand = null;
                blockIndent = null;
            }

            Matcher directive = RUN_DIRECTIVE.matcher(line);
            if (!directive.matches()) {
                continue;
            }
            String command = directive.group(2).strip();
            if (command.matches("[|>](?:[1-9][+-]?|[+-][1-9]?)?(?:\\s+#.*)?")) {
                blockIndent = directive.group(1).length();
                foldedCommand = command.startsWith(">")
                    ? new StringBuilder() : null;
            } else if (!command.isEmpty() && !command.startsWith("#")) {
                commands.add(command);
            }
        }
        if (foldedCommand != null && foldedCommand.length() > 0) {
            commands.add(foldedCommand.toString());
        }
        return List.copyOf(commands);
    }

    private static int leadingWhitespace(String line) {
        int count = 0;
        while (count < line.length()
                && Character.isWhitespace(line.charAt(count))) {
            count++;
        }
        return count;
    }

    private static JsonNode parseStrict(Path path) throws IOException {
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
            throw invalid(context + " must be an object");
        }
        return object;
    }

    private static ArrayNode requireArray(JsonNode value, String context) {
        if (!(value instanceof ArrayNode array)) {
            throw invalid(context + " must be an array");
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

    private static int requiredInt(
        ObjectNode object,
        String field,
        String context
    ) {
        JsonNode value = object.get(field);
        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToInt()) {
            throw invalid(
                context + " field " + field + " must be an integer"
            );
        }
        return value.intValue();
    }

    private static boolean requiredBoolean(
        ObjectNode object,
        String field,
        String context
    ) {
        JsonNode value = object.get(field);
        if (value == null || !value.isBoolean()) {
            throw invalid(
                context + " field " + field + " must be boolean"
            );
        }
        return value.booleanValue();
    }

    private static List<String> requiredTextArray(
        ObjectNode object,
        String field,
        String context
    ) {
        ArrayNode values = requireArray(
            object.get(field),
            context + " field " + field
        );
        List<String> result = new ArrayList<>(values.size());
        for (JsonNode value : values) {
            if (!value.isTextual() || value.asText().isBlank()) {
                throw invalid(
                    context
                        + " field "
                        + field
                        + " must contain non-blank text"
                );
            }
            result.add(value.asText());
        }
        return List.copyOf(result);
    }

    private static void requireSortedUnique(
        List<String> values,
        String field
    ) {
        List<String> sortedUnique = new TreeSet<>(values).stream().toList();
        if (!values.equals(sortedUnique)) {
            throw invalid(field + " must be sorted and unique");
        }
    }

    private static <T> void requireEquals(
        T expected,
        T actual,
        String message
    ) {
        if (!expected.equals(actual)) {
            throw invalid(message + ": " + actual);
        }
    }

    private static ObjectNode validPolicyDocument() {
        ObjectNode policy = JSON.createObjectNode();
        policy.put("schema", POLICY_SCHEMA);
        policy.put("maximumWorkflowCount", 2);
        policy.putArray("verificationWorkflows").add("gradle.yml");
        policy.putArray("platformWorkflows").add("release.yml");
        return policy;
    }

    private static Policy validPolicy() {
        return parsePolicy(validPolicyDocument());
    }

    private static ObjectNode validGovernanceDocument() {
        ObjectNode governance = JSON.createObjectNode();
        governance.put("schema", GOVERNANCE_SCHEMA);
        governance.put("defaultBranch", "main");
        ObjectNode required = governance.putObject("requiredStatusCheck");
        required.put("context", "Checkout-local ciCheck");
        required.put("integrationId", 15368);
        required.put("strictUpToDate", true);
        governance.put(
            "createEventCheckContext",
            "Showcase train-freeze authority v1"
        );
        governance.putArray("routineBypassActors");
        governance.put("currentUserCanBypass", "never");
        governance.put("requiredApprovingReviewCount", 0);
        governance.put("requiredReviewThreadResolution", true);
        return governance;
    }

    private static MergeGovernance validGovernance() {
        return parseGovernance(validGovernanceDocument());
    }

    private static String validWorkflow() {
        return workflow(
            "Checkout-local ciCheck",
            "Showcase train-freeze authority v1",
            "./gradlew ciCheck"
        );
    }

    private static String workflow(
        String requiredContext,
        String createContext,
        String command
    ) {
        return """
            name: CI
            jobs:
              verification:
                name: ${{ github.event_name == 'create' && '%s' || '%s' }}
                steps:
                  - run: %s
            """.formatted(createContext, requiredContext, command);
    }

    private static void assertViolation(
        String text,
        MergeGovernance governance,
        String category
    ) {
        List<Violation> violations = scanVerificationWorkflow(
            "gradle.yml",
            text,
            governance
        );
        assertTrue(
            hasCategory(violations, category),
            () -> "missing " + category + " in " + violations
        );
    }

    private static void assertNoViolation(
        String text,
        MergeGovernance governance,
        String category
    ) {
        List<Violation> violations = scanVerificationWorkflow(
            "gradle.yml",
            text,
            governance
        );
        assertFalse(
            hasCategory(violations, category),
            () -> "unexpected " + category + " in " + violations
        );
    }

    private static boolean hasCategory(
        List<Violation> violations,
        String category
    ) {
        return violations.stream().anyMatch(violation ->
            violation.category().equals(category)
        );
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

    private static NamedPattern named(String category, String pattern) {
        return new NamedPattern(category, Pattern.compile(pattern));
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
            "workflow semantics invalid: " + message
        );
    }

    private static IllegalArgumentException invalid(
        String message,
        Throwable cause
    ) {
        return new IllegalArgumentException(
            "workflow semantics invalid: " + message,
            cause
        );
    }

    private record Policy(
        int maximumWorkflowCount,
        List<String> verificationWorkflows,
        List<String> platformWorkflows
    ) { }

    private record MergeGovernance(
        String defaultBranch,
        String requiredStatusCheckContext,
        int requiredStatusCheckIntegrationId,
        boolean strictUpToDate,
        String createEventCheckContext,
        String currentUserCanBypass,
        int requiredApprovingReviewCount,
        boolean requiredReviewThreadResolution
    ) { }

    private record NamedPattern(String category, Pattern pattern) { }

    private record Violation(
        String workflow,
        int line,
        String category,
        String excerpt
    ) {
        @Override
        public String toString() {
            return workflow
                + ":"
                + line
                + ": "
                + category
                + ": "
                + excerpt;
        }
    }

    private record VerificationResult(
        int workflowCount,
        int maximumWorkflowCount,
        int verificationWorkflowCount,
        int platformWorkflowCount,
        String requiredCheck
    ) { }
}
