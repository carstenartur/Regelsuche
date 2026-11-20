package de.regelsuche.build;

import static de.regelsuche.build.MavenPomTestSupport.directChild;
import static de.regelsuche.build.MavenPomTestSupport.directChildText;
import static de.regelsuche.build.MavenPomTestSupport.directChildTexts;
import static de.regelsuche.build.MavenPomTestSupport.parse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

class MavenParallelVerificationWorkflowContractTest {
  private static final ObjectMapper YAML = new ObjectMapper(YAMLFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
  private static final String PLAYWRIGHT_VERSION = "1.65.0";
  private static final String FULL_MAVEN_COMMAND =
      "mvn --batch-mode --no-transfer-progress -Pfull,sdk-release verify";
  private static final String COVERAGE_PROFILE = "verify-cross-authority-coverage";
  private static final String PUBLISH_COVERAGE = "regelsuche.coverage.publish";
  private static final String COVERAGE_MAVEN_COMMAND =
      "mvn --batch-mode --no-transfer-progress -pl regelsuche-quality"
          + " -P" + COVERAGE_PROFILE + " -D" + PUBLISH_COVERAGE + "=true -DskipTests verify";
  private static final String COVERAGE_STEP = "Verify complete cross-authority coverage and render standard JaCoCo pages";
  private static final String ISOLATED_COVERAGE_REPORT = "regelsuche.coverage.isolatedReport";
  private static final String COVERAGE_MAIN =
      "de.regelsuche.quality.coverage.CoverageRegressionMain";

  @Test
  void independentAuthoritiesRunInParallelAndConvergeFailClosed()
      throws IOException {
    Path root = repositoryRoot();
    String workflow = Files.readString(root.resolve(
        ".github/workflows/gradle.yml"));
    String coverageGate = Files.readString(root.resolve(
        "gradle/quality-gates.gradle"));
    String gradle = section(
        workflow,
        "  gradle-verification:\n",
        "  jmh-verification:\n");
    String jmh = section(
        workflow,
        "  jmh-verification:\n",
        "  sympy-runtime-verification:\n");
    String sympy = section(
        workflow,
        "  sympy-runtime-verification:\n",
        "  maven-product-verification:\n");
    String maven = section(
        workflow,
        "  maven-product-verification:\n",
        "  external-polynomial-comparison:\n");
    String convergence = section(
        workflow,
        "  verification:\n",
        "  publish-pages:\n");
    int publicationStart = workflow.indexOf("  publish-pages:\n");
    assertTrue(publicationStart >= 0,
        "missing workflow section publish-pages");
    String publication = workflow.substring(publicationStart);

    assertTrue(gradle.contains("actions/checkout@"),
        "the Gradle authority must start from its own checkout");
    assertTrue(gradle.contains("-f playwright-bootstrap/pom.xml"),
        "the checkout authority must install browser prerequisites via Maven");
    assertTrue(gradle.contains(
        "org.codehaus.mojo:exec-maven-plugin:3.5.0:java"),
        "the independent pinned Playwright CLI must remain the bootstrap");
    assertTrue(gradle.contains("cache: maven"),
        "the checkout authority must cache its Maven bootstrap dependencies");
    assertFalse(gradle.contains("installPlaywrightHostDependencies"),
        "the checkout authority must no longer run the Gradle browser bootstrap");
    assertTrue(gradle.contains(
        "./gradlew --no-daemon --no-configuration-cache "
            + "-PseparateJmhAuthority=true "
            + "\"$REGELSUCHE_CI_TASK\""));
    assertFalse(gradle.contains(FULL_MAVEN_COMMAND),
        "Maven must not remain serialized behind the Gradle authority");
    assertFalse(gradle.contains("jmhAuthority"),
        "the correctness runner must not execute the isolated benchmark graph");

    assertTrue(coverageGate.contains(
        "providers.gradleProperty(\n    'separateSympyRuntimeAuthority'"));
    assertTrue(coverageGate.contains(
        "REGELSUCHE_SEPARATE_SYMPY_RUNTIME_AUTHORITY"));
    assertTrue(coverageGate.contains(
        "def deferCoverageToWorkflowConvergence ="));
    assertTrue(coverageGate.contains(
        "separateSympyRuntimeCoverageAuthorization "
            + "== 'required-by-workflow'"));
    assertTrue(coverageGate.contains(
        "onlyIf {\n        !deferCoverageToWorkflowConvergence\n    }"),
        "only the workflow-authorized split may defer the aggregate gate");

    assertTrue(jmh.contains("name: Isolated JMH authority"));
    assertTrue(jmh.contains("actions/checkout@"),
        "the JMH authority must start from an independent checkout");
    assertFalse(jmh.contains("gradle/actions/setup-gradle@"));
    assertFalse(jmh.contains("apt-get"));
    assertTrue(jmh.contains("--fail-at-end -Pjmh-qualification"));
    assertTrue(jmh.contains("-pl regelsuche-jmh,regelsuche-sympy-jmh -am -DskipTests verify"));
    assertTrue(jmh.contains("JmhWarmupMigrationTest,JmhRegressionV3VerifierTest"));
    assertFalse(jmh.contains("run-isolated-sympy-runtime-authority.sh"),
        "SymPy runtime tests must not remain serialized behind JMH");
    assertFalse(jmh.contains("sympy-runtime-checkout"));
    assertFalse(jmh.contains("-PseparateJmhAuthority=true"),
        "the benchmark runner must not disable its own task graph");
    assertFalse(jmh.contains(FULL_MAVEN_COMMAND));
    assertFalse(jmh.contains("needs:"),
        "JMH must start concurrently rather than wait for another authority");
    assertTrue(jmh.contains("name: jmh-verification"));
    assertTrue(jmh.contains("if-no-files-found: error"),
        "missing benchmark evidence must fail the isolated authority");

    assertTrue(sympy.contains("name: Isolated SymPy runtime authority"));
    assertTrue(sympy.contains("actions/checkout@"),
        "the SymPy authority must start from an independent checkout");
    assertFalse(sympy.contains("gradle/actions/setup-gradle@"));
    assertFalse(sympy.contains("apt-get"));
    assertTrue(sympy.contains(
        "mvn --batch-mode --no-transfer-progress -Pfull -pl regelsuche-math-sympy -am verify"));
    assertFalse(sympy.contains("gradle/run-isolated-sympy-runtime-authority.sh"));
    assertFalse(sympy.contains("run-isolated-jmh-authority.sh"));
    assertFalse(sympy.contains("needs:"),
        "SymPy must start concurrently rather than wait for JMH");
    assertFalse(sympy.contains(FULL_MAVEN_COMMAND));
    assertTrue(sympy.contains("name: sympy-runtime-verification"));
    assertTrue(sympy.contains(
        "regelsuche-math-sympy/target/failsafe-reports/**"));
    assertTrue(sympy.contains(
        "regelsuche-math-sympy/target/reports/sympy-runtime/**"));
    assertTrue(sympy.contains(
        "regelsuche-math-sympy/target/site/jacoco/**"));
    assertTrue(sympy.contains("if-no-files-found: error"),
        "missing runtime evidence must fail the isolated authority");

    assertTrue(maven.contains("actions/checkout@"),
        "the Maven authority must start from a separate fresh checkout");
    assertTrue(maven.contains("cache: maven"),
        "only the Maven dependency repository should be restored");
    assertTrue(maven.contains("-f playwright-bootstrap/pom.xml"));
    assertTrue(maven.contains(
        "org.codehaus.mojo:exec-maven-plugin:3.5.0:java"));
    assertTrue(maven.contains(FULL_MAVEN_COMMAND),
        "the complete product and Docker contract must remain unchanged");
    assertFalse(maven.contains("needs:"),
        "Maven must begin concurrently rather than wait for another authority");
    assertFalse(maven.contains("actions/download-artifact@"),
        "Maven must not consume another authority's output");
    assertFalse(maven.contains("./gradlew"),
        "the Maven authority must not execute or depend on the Gradle reactor");
    assertEquals(1, occurrences(workflow, FULL_MAVEN_COMMAND),
        "the full Maven contract must execute exactly once per workflow run");

    String compactConvergence = compact(convergence);
    assertTrue(compactConvergence.contains(
        "needs: [gradle-verification, jmh-verification, "
            + "sympy-runtime-verification, maven-product-verification, "
            + "external-polynomial-comparison, typed-external-polynomial-comparison, checked-proofs]"));
    assertTrue(compactConvergence.contains(
        "needs.gradle-verification.result != 'success' || "
            + "needs.jmh-verification.result != 'success' || "
            + "needs.sympy-runtime-verification.result != 'success' || "
            + "(github.event_name != 'create' && "
            + "(needs.maven-product-verification.result != 'success' || "
            + "needs.external-polynomial-comparison.result != 'success' || "
            + "needs.typed-external-polynomial-comparison.result != 'success' || "
            + "needs.checked-proofs.result != 'success'))"),
        "the stable required check must reject any incomplete authority");
    assertTrue(convergence.contains("run: exit 1"),
        "an incomplete authority set must fail rather than become skipped-success");

    int rejection = convergence.indexOf(
        "- name: Reject incomplete verification authority");
    int checkout = convergence.indexOf("- uses: actions/checkout@");
    int coverage = convergence.indexOf("- name: " + COVERAGE_STEP);
    int sympyDownload = convergence.indexOf("- name: Download isolated SymPy coverage evidence");
    assertTrue(rejection >= 0 && checkout > rejection && sympyDownload > checkout
        && coverage > sympyDownload,
        "coverage must follow successful producers, checkout and the isolated evidence download");
    JsonNode coverageStep = YAML.readTree(workflow).path("jobs").path("verification").path("steps")
        .valueStream().filter(step -> COVERAGE_STEP.equals(step.path("name").asText()))
        .findFirst().orElseThrow();
    assertEquals(COVERAGE_MAVEN_COMMAND, coverageStep.path("run").asText().strip());
    assertFalse(coverageStep.has("if"), "the coverage decision must run for every converged authority set");
    assertFalse(coverageStep.has("continue-on-error"), "a failed coverage decision must fail convergence");
    assertTrue(convergence.contains("'repository-verification'"));
    assertTrue(convergence.contains("name: sympy-runtime-verification"));
    assertTrue(convergence.contains(
        "path: build/authority-evidence/sympy"));
    assertFalse(convergence.contains("test -f"),
        "evidence assertions must remain checkout-owned");
    assertFalse(convergence.contains("test ! -e"),
        "staleness assertions must remain checkout-owned");
    assertFalse(convergence.contains("install -D"),
        "evidence joining must remain checkout-owned");
    assertTrue(coverageGate.contains("mainClass = '" + COVERAGE_MAIN + "'"));
    assertTrue(convergence.contains("name: coverage-verification"));
    assertTrue(convergence.contains(
        "needs.sympy-runtime-verification.result == 'success'"),
        "coverage retention must require the independent runtime authority");
    assertTrue(convergence.contains("if-no-files-found: error"),
        "the converged coverage report must be retained fail closed");
    assertFalse(convergence.contains("./gradlew"));
    assertFalse(convergence.contains("scripts/generate-ci-pages.py"));
    assertEquals(1, occurrences(convergence, "mvn "),
        "convergence must run only the focused Maven coverage verification");

    assertTrue(publication.contains("needs: verification"),
        "publication must wait for all converged authorities");
    assertTrue(publication.contains("name: repository-verification"));
    assertTrue(publication.contains("name: jmh-verification"),
        "published benchmark pages must come from the isolated authority");
    assertTrue(publication.contains("name: coverage-verification"),
        "published coverage must come from the converged authority set");
    assertTrue(workflow.contains(
        "github.event_name == 'create' && "
            + "'Showcase train-freeze authority v1' || "
            + "'Checkout-local ciCheck'"),
        "the existing merge-governance check context must remain stable");
    assertFalse(workflow.contains(" --exclude-task "));
    assertFalse(workflow.contains(" -x "),
        "moving work to required jobs must not use Gradle task exclusion");
  }

  @Test
  void coverageJoinUsesTheSameJavaDecisionWithMandatoryFailsafeReplay() throws Exception {
    Path root = repositoryRoot();
    Element pom = parse(root.resolve("regelsuche-quality/pom.xml")).getDocumentElement();
    Element profile = requiredChild(directChild(pom, "profiles"), "profile", "id", COVERAGE_PROFILE);
    assertEquals("build/authority-evidence/sympy/site/jacoco/jacoco.xml",
        directChildText(directChild(profile, "properties"), ISOLATED_COVERAGE_REPORT));
    Element plugins = directChild(directChild(profile, "build"), "plugins");
    Element failsafe = requiredChild(plugins, "plugin", "artifactId", "maven-failsafe-plugin");
    Element execution = requiredChild(directChild(failsafe, "executions"),
        "execution", "id", "verify-retained-split-coverage");
    assertEquals(List.of("integration-test", "verify"), directChildTexts(directChild(execution, "goals"), "goal"));

    Element configuration = directChild(execution, "configuration");
    assertEquals("de.regelsuche.quality.coverage.IsolatedCoverageInputTest#verifyRetainedSplitCoverage",
        directChildText(configuration, "test"));
    assertEquals("false", directChildText(configuration, "skipTests"));
    assertEquals("true", directChildText(configuration, "failIfNoTests"));
    assertEquals("true", directChildText(configuration, "failIfNoSpecifiedTests"));
    Element properties = directChild(configuration, "systemPropertyVariables");
    assertEquals("${maven.multiModuleProjectDirectory}", directChildText(properties, "regelsuche.repositoryRoot"));
    assertEquals("${" + ISOLATED_COVERAGE_REPORT + "}", directChildText(properties, ISOLATED_COVERAGE_REPORT));
    assertEquals("${" + PUBLISH_COVERAGE + "}", directChildText(properties, PUBLISH_COVERAGE));

    String replay = compact(Files.readString(root.resolve(
        "regelsuche-quality/src/test/java/de/regelsuche/quality/coverage/IsolatedCoverageInputTest.java")));
    assertTrue(replay.contains("String isolatedReport = System.getProperty(\"" + ISOLATED_COVERAGE_REPORT + "\");"));
    int decision = replay.indexOf("assertEquals(0, CoverageRegressionMain.run(new String[]{\"--root\", folder,"
        + " \"--isolated-report\", isolatedReport}, System.out, System.err));");
    assertTrue(decision >= 0,
        "the Maven replay must pass the isolated report to the shared Java verifier and reject a nonzero exit");
    assertTrue(replay.indexOf("if (Boolean.getBoolean(\"" + PUBLISH_COVERAGE + "\"))", decision) > decision);
    assertTrue(replay.indexOf("CoveragePages.publish(root, root.resolve(\"public/coverage\"), inputs)", decision) > decision);
  }

  @Test
  void ciCoversStackedTypedAndLearnedPullRequests() throws IOException {
    JsonNode workflow = YAML.readTree(repositoryRoot().resolve(
        ".github/workflows/gradle.yml").toFile());
    List<String> branches = workflow.path("on").path("pull_request").path("branches")
        .valueStream().map(JsonNode::asText).toList();
    for (String base : List.of("main", "codex/learned-scheduling-v1",
        "codex/typed-external-source-only-comparison", "codex/typed-primitive-candidate-reuse",
        "codex/learned-schema-utility")) {
      assertTrue(branches.stream().anyMatch(pattern -> FileSystems.getDefault()
          .getPathMatcher("glob:" + pattern).matches(Path.of(base))),
          () -> "CI must run for pull requests into " + base);
    }
  }

  @Test
  void typedComparisonRunsTheCheckoutOwnedProtocolAndRetainsEveryOutcome()
      throws IOException {
    JsonNode workflow = YAML.readTree(repositoryRoot().resolve(
        ".github/workflows/gradle.yml").toFile());
    JsonNode comparison = workflow.path("jobs").path(
        "typed-external-polynomial-comparison");
    assertTrue(comparison.isObject(),
        "the typed comparison must run within the required CI workflow");
    assertEquals("github.event_name != 'create'", comparison.path("if").asText());
    assertEquals("ubuntu-22.04", comparison.path("runs-on").asText());
    assertEquals(30, comparison.path("timeout-minutes").asInt());
    assertFalse(comparison.has("needs"),
        "the typed comparison must start independently of the other authorities");
    assertFalse(comparison.has("continue-on-error"));

    List<JsonNode> steps = comparison.path("steps").valueStream().toList();
    JsonNode checkout = steps.stream().filter(step -> step.path("uses").asText()
        .startsWith("actions/checkout@")).findFirst().orElseThrow();
    assertEquals(0, checkout.path("with").path("fetch-depth").asInt(-1));
    assertEquals("${{ github.event.pull_request.head.sha || github.sha }}",
        checkout.path("with").path("ref").asText());

    List<JsonNode> commands = steps.stream().filter(step -> step.has("run")).toList();
    assertEquals(List.of(
        "bash gradle/run-typed-external-polynomial-comparison.sh",
        "bash gradle/run-checked-schema-comparison.sh",
        "bash gradle/run-native-polynomial-comparison.sh"),
        commands.stream().map(command -> command.path("run").asText()).toList(),
        "all three versioned checkout-owned comparisons must run, in prerequisite order");
    for (JsonNode command : commands) {
      assertFalse(command.has("if"));
      assertFalse(command.has("continue-on-error"));
    }
    String schemaRunner = Files.readString(repositoryRoot().resolve(
        "gradle/run-checked-schema-comparison.sh"));
    assertTrue(schemaRunner.contains("set -euo pipefail"));
    assertTrue(schemaRunner.contains("build/typed-external-polynomial-venv/bin/python"));
    assertTrue(schemaRunner.contains("-m external_polynomial_comparison.run_schema"));
    assertTrue(schemaRunner.contains("--verify --output build/reports/learned-schema-efficiency-v2"),
        "the new comparison must independently verify every retained lifecycle receipt");

    JsonNode upload = steps.stream().filter(step -> step.path("uses").asText()
        .startsWith("actions/upload-artifact@")).findFirst().orElseThrow();
    assertEquals("${{ always() && !cancelled() }}", upload.path("if").asText());
    assertEquals("typed-external-polynomial-${{ github.event.pull_request.head.sha || github.sha }}",
        upload.path("with").path("name").asText());
    assertEquals(List.of(
        "build/reports/typed-external-polynomial-comparison/**",
        "build/reports/learned-schema-efficiency-v2/**",
        "build/reports/native-polynomial-comparison/**",
        "regelsuche-search/build/test-results/**",
        "regelsuche-learning/build/test-results/**"),
        upload.path("with").path("path").asText().lines().toList());
    assertEquals("error", upload.path("with").path("if-no-files-found").asText());
  }

  @Test
  void jmhSeparationMovesOnlyTheUnchangedBenchmarkAuthority()
      throws IOException {
    Path root = repositoryRoot();
    String settings = Files.readString(root.resolve("settings.gradle"));
    String separation = Files.readString(root.resolve(
        "gradle/separated-jmh-authority.gradle"));
    assertFalse(Files.exists(root.resolve("gradle/run-isolated-jmh-authority.sh")));

    int sympyPolicy = settings.indexOf(
        "gradle/sympy-factorization-verification.gradle");
    int separatedPolicy = settings.indexOf(
        "gradle/separated-jmh-authority.gradle");
    assertTrue(sympyPolicy >= 0 && separatedPolicy > sympyPolicy,
        "the separation boundary must configure already registered tasks");

    assertTrue(separation.contains(
        "providers.gradleProperty(\n    'separateJmhAuthority')"));
    assertTrue(separation.contains("'verifyJmhRegression'"));
    assertTrue(separation.contains("'verifyJmhBenchmark'"));
    assertTrue(separation.contains("'runJmhAllocationBenchmark'"));
    assertTrue(separation.contains("'verifyJmhAllocationBenchmark'"));
    assertTrue(separation.contains(
        "'verifySymPyFactorizationBenchmark'"));
    assertFalse(separation.contains("tasks.register('jmhAuthority')"));
    assertFalse(separation.contains("warmup"));
    assertFalse(separation.contains("iteration"));
    assertFalse(separation.contains("fork"));
    assertFalse(separation.contains("include"));
    assertFalse(separation.contains("exclude"),
        "the separation layer must not alter benchmark selection or policy");


  }

  @Test
  void mavenBootstrapUsesTheSamePinnedPlaywrightCliAsGradleAndTheApp()
      throws IOException {
    Path root = repositoryRoot();
    String appBuild = Files.readString(root.resolve("app/build.gradle"));
    String gradleBootstrap = Files.readString(root.resolve(
        "playwright-bootstrap/build.gradle"));
    String mavenBootstrap = Files.readString(root.resolve(
        "playwright-bootstrap/pom.xml"));

    String coordinate =
        "com.microsoft.playwright:playwright:" + PLAYWRIGHT_VERSION;
    assertEquals(2, occurrences(appBuild, coordinate));
    assertTrue(gradleBootstrap.contains(
        "playwrightVersion = '" + PLAYWRIGHT_VERSION + "'"));
    assertTrue(mavenBootstrap.contains(
        "<playwright.version>" + PLAYWRIGHT_VERSION
            + "</playwright.version>"));
    assertTrue(mavenBootstrap.contains(
        "<exec-maven-plugin.version>3.5.0"
            + "</exec-maven-plugin.version>"));
    assertTrue(mavenBootstrap.contains(
        "<mainClass>com.microsoft.playwright.CLI</mainClass>"));
    assertTrue(mavenBootstrap.contains("<argument>install-deps</argument>"));
    assertTrue(mavenBootstrap.contains("<argument>chromium</argument>"));
    assertFalse(mavenBootstrap.contains("<modules>"),
        "the host bootstrap must remain outside the production Maven reactor");
  }

  private static String section(String text, String start, String end) {
    int from = text.indexOf(start);
    assertTrue(from >= 0, () -> "missing workflow section " + start.strip());
    int to = text.indexOf(end, from + start.length());
    assertTrue(to > from, () -> "missing workflow section " + end.strip());
    return text.substring(from, to);
  }

  private static String compact(String text) {
    return text.replaceAll("\\s+", " ").trim();
  }

  private static Element requiredChild(Element parent, String tag, String key, String value) {
    assertNotNull(parent, "missing parent for " + tag + " " + value);
    for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (child instanceof Element element && tag.equals(element.getLocalName())
          && value.equals(directChildText(element, key))) {
        return element;
      }
    }
    throw new AssertionError("missing " + tag + " " + value);
  }

  private static int occurrences(String text, String value) {
    int count = 0;
    int offset = 0;
    while ((offset = text.indexOf(value, offset)) >= 0) {
      count++;
      offset += value.length();
    }
    return count;
  }

  private static Path repositoryRoot() {
    String configured = System.getProperty("regelsuche.repositoryRoot");
    assertNotNull(configured,
        "Maven must expose maven.multiModuleProjectDirectory to tests");
    return Path.of(configured).toAbsolutePath().normalize();
  }
}
