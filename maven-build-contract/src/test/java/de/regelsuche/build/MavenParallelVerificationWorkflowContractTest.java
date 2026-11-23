package de.regelsuche.build;

import static de.regelsuche.build.MavenPomTestSupport.directChild;
import static de.regelsuche.build.MavenPomTestSupport.directChildText;
import static de.regelsuche.build.MavenPomTestSupport.directChildTexts;
import static de.regelsuche.build.MavenPomTestSupport.parse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

class MavenParallelVerificationWorkflowContractTest {
  private static final ObjectMapper YAML = new ObjectMapper(YAMLFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
  private static final String PLAYWRIGHT_VERSION = "1.65.0";
  private static final String FULL_MAVEN_COMMAND =
      "mvn --batch-mode --no-transfer-progress -Pfull,sdk-release,web-workbench-image verify";
  private static final String GIT_FREE_AUTHORITY = "maven-git-free-verification";
  private static final String COVERAGE_PROFILE = "verify-cross-authority-coverage";
  private static final String PUBLISH_COVERAGE = "regelsuche.coverage.publish";
  private static final String COVERAGE_MAVEN_COMMAND =
      "mvn --batch-mode --no-transfer-progress -pl regelsuche-quality"
          + " -P" + COVERAGE_PROFILE + " -D" + PUBLISH_COVERAGE + "=true -DskipTests verify";
  private static final String COVERAGE_STEP = "Verify complete cross-authority coverage and render standard JaCoCo pages";
  private static final String ISOLATED_COVERAGE_REPORT = "regelsuche.coverage.isolatedReport";
  private static final String COVERAGE_MAIN =
      "de.regelsuche.quality.coverage.CoverageRegressionMain";

  private static final Set<String> SHOWCASE_AUTHORITIES = Set.of(
      "gradle-verification", "jmh-verification", "sympy-runtime-verification");
  private static final Set<String> REQUIRED_ROUTINE_AUTHORITIES = Set.of(
      "maven-product-verification", GIT_FREE_AUTHORITY, "external-polynomial-comparison",
      "typed-external-polynomial-comparison", "checked-proofs", "native-scalar-reproduction",
      "maven-polynomial-qualification", "maven-native-polynomial-study", "maven-student-sdk-archive-qualification");
  private static final Pattern FAILURE_TERM =
      Pattern.compile("needs\\.([A-Za-z][A-Za-z0-9_-]*)\\.result != 'success'");
  private static final String ROUTINE_FAILURE_GROUP = " || (github.event_name != 'create' && (";
  private static final String REJECTION_STEP = "Reject incomplete verification authority";

  @Test
  void onlyObsoletePullRequestsCancelRunningVerification() throws IOException {
    JsonNode workflow = YAML.readTree(repositoryRoot().resolve(
        ".github/workflows/gradle.yml").toFile());
    assertEquals("${{ github.event_name == 'pull_request' }}",
        workflow.path("concurrency").path("cancel-in-progress").asText(),
        "main and manually repeated ancestor runs must complete their acceptance evidence");
  }

  @Test
  void reportPublicationRequiresCurrentMainAndSuccessfulRemoteLookup() throws IOException {
    JsonNode workflow = YAML.readTree(repositoryRoot().resolve(
        ".github/workflows/gradle.yml").toFile());
    List<JsonNode> steps = workflow.path("jobs").path("publish-pages")
        .path("steps").valueStream().toList();
    JsonNode publish = steps.getLast();
    assertTrue(publish.path("uses").asText().startsWith("peaceiris/actions-gh-pages@"));
    assertEquals("${{ steps.current-main.outputs.current == 'true' }}",
        publish.path("if").asText(), "a superseded main run must not publish stale reports");
    JsonNode guard = steps.get(steps.size() - 2);
    assertEquals("current-main", guard.path("id").asText());
    assertFalse(guard.has("continue-on-error"));
    String script = guard.path("run").asText();
    assertTrue(script.startsWith("set -euo pipefail\n"),
        "failed remote lookup must stop publication, not reuse a stale local ref");
    assertTrue(script.contains("git ls-remote --exit-code origin refs/heads/main"));
    assertTrue(script.contains("if [[ \"$remote_sha\" == \"$GITHUB_SHA\" ]]; then"));
    assertTrue(script.contains("echo \"current=true\" >> \"$GITHUB_OUTPUT\""));
    assertTrue(script.contains("echo \"current=false\" >> \"$GITHUB_OUTPUT\""));
  }

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
        "  " + GIT_FREE_AUTHORITY + ":\n");
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
    String sympyProducer = Files.readString(root.resolve(
        "regelsuche-math-sympy/src/maven-test/java/de/regelsuche/math/sympy/SympyRuntimeContainerIT.java"));
    assertTrue(sympyProducer.contains("retainCoverageInputs(root, target);"),
        "isolated worker must retain its own tested classes, sources and execution data");
    assertTrue(sympyProducer.contains("target.resolve(\"site/jacoco/inputs\")"),
        "the independent artifact must retain renderable JaCoCo inputs");
    assertTrue(sympyProducer.contains("target.resolve(\"jacoco.exec\")"));
    assertTrue(sympyProducer.contains("inputs.resolve(\"execution-data/jacoco.exec\")"));


    assertTrue(maven.contains("actions/checkout@"),
        "the Maven authority must start from a separate fresh checkout");
    assertTrue(maven.contains("cache: maven"),
        "only the Maven dependency repository should be restored");
    assertTrue(maven.contains("-f playwright-bootstrap/pom.xml"));
    assertTrue(maven.contains(
        "org.codehaus.mojo:exec-maven-plugin:3.5.0:java"));
    assertTrue(maven.contains(FULL_MAVEN_COMMAND),
        "the complete checkout reactor must also run the Web Workbench image suite");
    assertFalse(maven.contains("-Pfull,web-workbench-image"),
        "Web Workbench must not rerun the upstream reactor");
    assertFalse(maven.contains("needs:"),
        "Maven must begin concurrently rather than wait for another authority");
    assertFalse(maven.contains("actions/download-artifact@"),
        "Maven must not consume another authority's output");
    assertFalse(maven.contains("./gradlew"),
        "the Maven authority must not execute or depend on the Gradle reactor");
    assertEquals(1, occurrences(workflow, FULL_MAVEN_COMMAND),
        "the full Maven contract must execute exactly once per workflow run");

    JsonNode jobs = YAML.readTree(workflow).required("jobs");
    assertRequiredAuthorities(jobs);
    assertEquals("playwright-bootstrap/pom.xml", mavenCacheInputs(jobs.required("gradle-verification")));
    String fullCacheInputs = "**/pom.xml\n.mvn/maven.config";
    assertEquals(fullCacheInputs, mavenCacheInputs(jobs.required("maven-product-verification")));
    assertEquals(fullCacheInputs, mavenCacheInputs(jobs.required(GIT_FREE_AUTHORITY)));

    int rejection = convergence.indexOf(
        "- name: Reject incomplete verification authority");
    int checkout = convergence.indexOf("- uses: actions/checkout@");
    int coverage = convergence.indexOf("- name: " + COVERAGE_STEP);
    int sympyDownload = convergence.indexOf("- name: Download isolated SymPy coverage evidence");
    assertTrue(rejection >= 0 && checkout > rejection && sympyDownload > checkout
        && coverage > sympyDownload,
        "coverage must follow successful producers, checkout and the isolated evidence download");
    JsonNode coverageStep = jobs.required("verification").required("steps")
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
    JsonNode coverageRetention = jobs.required("verification").required("steps")
        .valueStream().filter(step -> "coverage-verification".equals(step.path("with").path("name").asText()))
        .findFirst().orElseThrow();
    assertTrue(compact(coverageRetention.required("if").asText()).contains(
        "(github.event_name == 'create' || (needs.maven-product-verification.result == 'success' && "
            + "needs." + GIT_FREE_AUTHORITY + ".result == 'success'))"),
        "routine coverage retention must require both Maven authorities");
    assertTrue(convergence.contains("if-no-files-found: error"),
        "the converged coverage report must be retained fail closed");
    assertFalse(convergence.contains("./gradlew"));
    assertFalse(convergence.contains("scripts/generate-ci-pages.py"));
    JsonNode collection = jobs.required("verification").required("steps")
        .valueStream().filter(step -> "Collect complete checkout-owned quality acceptance evidence".equals(step.path("name").asText()))
        .findFirst().orElseThrow();
    assertEquals("mvn --batch-mode --no-transfer-progress -pl regelsuche-release -am "
        + "-Dtest=QualityAcceptancePublicationTest -Dsurefire.failIfNoSpecifiedTests=false "
        + "-Dregelsuche.qualityAcceptance.collect=true verify", collection.required("run").asText().strip());
    assertFalse(collection.has("if"));
    assertFalse(collection.has("continue-on-error"));
    assertEquals(2, occurrences(convergence, "mvn "), "coverage and collection must be the only converged Maven invocations");

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
  void fullMavenEvidenceIsRetainedBeforeAnotherCheckoutLifecycleCanReplaceIt() throws IOException {
    var workflow = YAML.readTree(Files.readString(repositoryRoot().resolve(".github/workflows/gradle.yml")));
    var steps = workflow.required("jobs").required("maven-product-verification")
        .required("steps").valueStream().toList();
    var product = steps.stream().filter(step -> FULL_MAVEN_COMMAND.equals(step.path("run").asText().strip()))
        .findFirst().orElseThrow();
    assertFalse(steps.stream().anyMatch(step ->
        step.path("run").asText().contains("-Dregelsuche.qualityAcceptance.registerArchive=true")),
        "the checkout reactor must run independently of archive preparation");

    var retention = steps.stream()
        .filter(step -> "maven-full-reactor-verification".equals(step.path("with").path("name").asText()))
        .findFirst().orElseThrow(() -> new AssertionError("missing full-reactor report artifact"));
    int produced = steps.indexOf(product);
    int retained = steps.indexOf(retention);
    assertTrue(produced < retained, "the artifact must come from the full product lifecycle");
    assertFalse(steps.subList(produced + 1, retained).stream()
        .anyMatch(step -> step.path("run").asText().contains("mvn ")),
        "an intervening Maven lifecycle can clean the first run's reports");
    assertTrue(retention.path("uses").asText().startsWith("actions/upload-artifact@"));
    assertTrue(retention.path("if").asText().contains("always()"), "retain reports from failed runs as well");
    var paths = retention.required("with").required("path").asText().lines().map(String::strip).toList();
    assertTrue(paths.containsAll(List.of("**/target/surefire-reports/**", "**/target/failsafe-reports/**",
        "**/target/jacoco.exec", "**/target/site/jacoco/**")), "retain the original test and JaCoCo evidence");
  }

  @Test
  void gitFreeLifecycleStartsWithoutWaitingForTheCheckoutAndOwnsItsSource() throws IOException {
    var jobs = YAML.readTree(Files.readString(repositoryRoot().resolve(".github/workflows/gradle.yml"))).required("jobs");
    var archive = jobs.get(GIT_FREE_AUTHORITY);
    assertNotNull(archive, "the complete Git-free lifecycle must be an independent CI job");
    assertEquals("github.event_name != 'create'", archive.required("if").asText());
    assertFalse(archive.has("needs"), "archive verification must start in parallel with checkout verification");
    assertFalse(archive.has("continue-on-error"));
    assertEquals("${{ github.sha }}", archive.required("env").required("REGELSUCHE_AUTHORITY_GITHUB_SHA").asText());
    var steps = archive.required("steps").valueStream().toList();
    assertFalse(steps.stream().anyMatch(step -> step.path("uses").asText().startsWith("actions/download-artifact@")),
        "the archive must be prepared from its own checkout");
    assertFalse(steps.stream().anyMatch(step -> step.has("continue-on-error")));
    var checkout = steps.stream().filter(step -> step.path("uses").asText().startsWith("actions/checkout@"))
        .findFirst().orElseThrow();
    assertEquals("${{ github.sha }}", checkout.required("with").required("ref").asText());
    assertEquals("0", checkout.required("with").required("fetch-depth").asText());
    var registration = steps.stream().filter(step -> "source_archive".equals(step.path("id").asText()))
        .findFirst().orElseThrow();
    assertTrue(steps.indexOf(checkout) < steps.indexOf(registration));
    var registrationCommand = registration.required("run").asText();
    assertTrue(registrationCommand.contains("-DdisableXmlReport=true")
        && registrationCommand.contains("-Dsurefire.useFile=false")
        && registrationCommand.contains("-Djacoco.skip=true"),
        "registration-only output must not be retained as reactor evidence");
  }

  @Test
  void mavenPagesReplayTheFullReactorFilesInsteadOfLaterReports() throws IOException {
    var workflow = YAML.readTree(Files.readString(repositoryRoot().resolve(".github/workflows/gradle.yml")));
    var steps = workflow.required("jobs").required("maven-product-verification")
        .required("steps").valueStream().toList();
    var snapshot = steps.stream().filter(step -> "full_maven_reports".equals(step.path("id").asText()))
        .findFirst().orElseThrow(() -> new AssertionError("missing full-reactor report snapshot"));
    var product = steps.stream().filter(step -> FULL_MAVEN_COMMAND.equals(step.path("run").asText().strip()))
        .findFirst().orElseThrow();
    int produced = steps.indexOf(product);
    int captured = steps.indexOf(snapshot);
    assertTrue(produced < captured, "capture the reports after the full product run");
    assertFalse(steps.subList(produced + 1, captured).stream()
        .anyMatch(step -> step.path("run").asText().contains("mvn ")),
        "capture the reports before any Maven reinitialization");
    assertTrue(snapshot.path("run").asText().contains("**/target/surefire-reports/TEST-*.xml"));
    assertTrue(snapshot.path("run").asText().contains("**/target/failsafe-reports/TEST-*.xml"));
    var replay = steps.stream()
        .filter(step -> step.path("run").asText().contains("-Dregelsuche.tests.mavenPages=true"))
        .findFirst().orElseThrow();
    assertEquals("${{ steps.full_maven_reports.outputs.root }}",
        replay.required("env").required("REGELSUCHE_MAVEN_REPORT_ROOT").asText());
    assertTrue(replay.path("run").asText().contains("-Dregelsuche.tests.mavenReportRoot=${REGELSUCHE_MAVEN_REPORT_ROOT:?}"));
  }

  @Test
  void workbenchImageEvidenceMustComeFromTheUnmodifiedCheckoutSnapshot() throws Exception {
    var workflow = YAML.readTree(Files.readString(repositoryRoot().resolve(".github/workflows/gradle.yml")));
    var steps = workflow.required("jobs").required("maven-product-verification")
        .required("steps").valueStream().toList();
    var snapshot = steps.stream().filter(step -> "full_maven_reports".equals(step.path("id").asText()))
        .findFirst().orElseThrow();
    var proof = steps.stream().filter(step -> "verify_workbench_evidence".equals(step.path("id").asText()))
        .findFirst().orElseThrow(() -> new AssertionError("the checkout must verify the executed Workbench image suite"));
    assertTrue(steps.indexOf(snapshot) < steps.indexOf(proof));
    assertFalse(proof.has("if") || proof.has("continue-on-error"));
    assertEquals("${{ steps.full_maven_reports.outputs.root }}",
        proof.required("env").required("REGELSUCHE_MAVEN_REPORT_ROOT").asText());
    assertEquals("mvn --batch-mode --no-transfer-progress -pl maven-build-contract -Pverify-workbench-report "
        + "\"-Dregelsuche.tests.mavenReportRoot=${REGELSUCHE_MAVEN_REPORT_ROOT:?}\" "
        + "test", proof.required("run").asText().strip());

    var pom = parse(repositoryRoot().resolve("maven-build-contract/pom.xml")).getDocumentElement();
    var profile = requiredChild(directChild(pom, "profiles"), "profile", "id", "verify-workbench-report");
    var surefire = requiredChild(directChild(directChild(profile, "build"), "plugins"),
        "plugin", "artifactId", "maven-surefire-plugin");
    var selector = directChild(surefire, "configuration");
    assertStrictWorkbenchReportSelector(selector);
    for (String flag : List.of("failIfNoTests", "failIfNoSpecifiedTests")) {
      var permissive = (Element) selector.cloneNode(true);
      directChild(permissive, flag).setTextContent("false");
      assertThrows(AssertionError.class, () -> assertStrictWorkbenchReportSelector(permissive));
    }
    var missingTest = (Element) selector.cloneNode(true);
    missingTest.removeChild(directChild(missingTest, "test"));
    assertThrows(AssertionError.class, () -> assertStrictWorkbenchReportSelector(missingTest));
  }

  private static void assertStrictWorkbenchReportSelector(Element selector) {
    assertEquals("MavenBuildContractTest#checkoutReportRequiresExecutedWorkbenchImageTests",
        directChildText(selector, "test"));
    assertEquals("true", directChildText(selector, "failIfNoTests"));
    assertEquals("true", directChildText(selector, "failIfNoSpecifiedTests"));
    assertEquals("true", directChildText(directChild(selector, "systemPropertyVariables"),
        "regelsuche.tests.requireWorkbenchImageReport"));
  }

  @Test
  void additionalAuthoritiesMustBeBothDependenciesAndFailClosed() throws IOException {
    var jobs = (ObjectNode) YAML.readTree(Files.readString(repositoryRoot().resolve(
        ".github/workflows/gradle.yml"))).required("jobs");
    var convergence = jobs.required("verification");
    var needs = (ArrayNode) convergence.required("needs");
    var rejection = (ObjectNode) rejectionStep(convergence);
    String original = compact(rejection.required("if").asText());
    String expanded = original.replace(")) }}", " || needs.extra-authority.result != 'success')) }}");
    jobs.putObject("extra-authority");
    needs.add("extra-authority");
    assertThrows(AssertionError.class, () -> assertRequiredAuthorities(jobs));
    rejection.put("if", expanded);
    assertRequiredAuthorities(jobs);
    rejection.put("if", expanded.replace(" || needs.extra-authority", " && needs.extra-authority"));
    assertThrows(AssertionError.class, () -> assertRequiredAuthorities(jobs));

    rejection.put("if", expanded.replace(" || needs.checked-proofs.result != 'success'", ""));
    int proofIndex = 0;
    while (!"checked-proofs".equals(needs.get(proofIndex).asText())) proofIndex++;
    needs.remove(proofIndex);
    assertThrows(AssertionError.class, () -> assertRequiredAuthorities(jobs));
  }

  private static String mavenCacheInputs(JsonNode job) {
    var setup = job.required("steps").valueStream()
        .filter(step -> step.path("uses").asText().startsWith("actions/setup-java@"))
        .findFirst().orElseThrow().required("with");
    assertEquals("maven", setup.required("cache").asText());
    return setup.required("cache-dependency-path").asText().strip();
  }

  private static void assertRequiredAuthorities(JsonNode jobs) {
    var convergence = jobs.required("verification");
    assertEquals("${{ always() && (github.event_name != 'create' || "
        + "(github.event.ref_type == 'branch' && github.event.ref == 'showcase/train-freeze-authority-v1')) }}",
        compact(convergence.required("if").asText()));
    var dependencies = convergence.required("needs");
    assertTrue(dependencies.isArray());
    var needed = new HashSet<String>();
    for (var dependency : dependencies) {
      String name = dependency.textValue();
      assertNotNull(name, "authority dependency must name a job");
      assertTrue(jobs.has(name) && needed.add(name), "undefined or duplicate authority " + name);
    }
    assertTrue(needed.containsAll(SHOWCASE_AUTHORITIES));
    assertTrue(needed.containsAll(REQUIRED_ROUTINE_AUTHORITIES));

    var rejection = rejectionStep(convergence);
    assertEquals("exit 1", rejection.required("run").asText().strip());
    assertFalse(rejection.has("continue-on-error"), "a failed rejection must fail convergence");
    String guard = compact(rejection.required("if").asText());
    assertTrue(guard.startsWith("${{ ") && guard.endsWith(")) }}"), "unexpected convergence guard");
    String[] groups = guard.substring(4, guard.length() - 5).split(Pattern.quote(ROUTINE_FAILURE_GROUP), -1);
    assertEquals(2, groups.length, "routine authorities must remain conditional on the non-showcase event");
    assertEquals(SHOWCASE_AUTHORITIES, failureTerms(groups[0]));
    needed.removeAll(SHOWCASE_AUTHORITIES);
    assertEquals(needed, failureTerms(groups[1]), "every routine dependency must fail convergence when incomplete");
  }

  private static JsonNode rejectionStep(JsonNode convergence) {
    return convergence.required("steps").valueStream()
        .filter(step -> REJECTION_STEP.equals(step.path("name").asText())).findFirst().orElseThrow();
  }

  private static Set<String> failureTerms(String group) {
    var names = new HashSet<String>();
    for (String term : group.split(Pattern.quote(" || "), -1)) {
      var failure = FAILURE_TERM.matcher(term);
      assertTrue(failure.matches(), "authority must be an independent non-success rejection: " + term);
      assertTrue(names.add(failure.group(1)), "duplicate authority " + term);
    }
    return names;
  }

  @Test
  void studentArchiveUsesCleanMavenStagingAndRetainsOnlyQualificationEvidence() throws IOException {
    var jobs = YAML.readTree(repositoryRoot().resolve(".github/workflows/gradle.yml").toFile()).required("jobs");
    var job = jobs.required("maven-student-sdk-archive-qualification");
    assertEquals("github.event_name != 'create'", job.required("if").asText());
    var steps = job.required("steps");
    assertEquals("${{ github.event.pull_request.head.sha || github.sha }}", steps.get(0).required("with").required("ref").asText());
    assertEquals("false", steps.get(0).required("with").required("persist-credentials").asText());
    String commands = steps.get(2).required("run").asText();
    int stage = commands.indexOf("-Psdk-release");
    int packageArchive = commands.indexOf("#packageConfiguredRepository");
    int qualify = commands.indexOf("#qualifyConfiguredArchive");
    assertTrue(stage >= 0 && packageArchive > stage && qualify > packageArchive);
    assertTrue(commands.substring(stage, packageArchive).contains("-DskipTests clean install"));
    assertTrue(commands.contains("work=$(mktemp -d \"$RUNNER_TEMP/student-sdk-archive/work.XXXXXXXX\")"));
    assertTrue(commands.contains("-Dregelsuche.studentSdk.qualify=true"));
    assertFalse(commands.contains("git tag") || commands.contains("gh release") || commands.contains("git push"));
    var artifact = steps.get(3).required("with");
    assertEquals("${{ runner.temp }}/student-sdk-archive/evidence", artifact.required("path").asText());
    assertEquals("error", artifact.required("if-no-files-found").asText());
  }

  @Test
  void nativePolynomialPilotUsesTheSameSourceAndRetainsIndependentReplay() throws IOException {
    var jobs = YAML.readTree(Files.readString(repositoryRoot().resolve(".github/workflows/gradle.yml"))).required("jobs");
    var pilot = jobs.required("maven-polynomial-qualification");
    var steps = pilot.required("steps");
    var checkout = steps.required(0).required("with");
    var legacyCheckout = jobs.required("external-polynomial-comparison").required("steps").required(0).required("with");
    assertEquals(legacyCheckout.required("ref"), checkout.required("ref"));
    assertEquals(0, checkout.required("fetch-depth").intValue(), "proof of freeze ancestry needs the actual Git history");
    var producer = steps.valueStream().filter(step -> step.has("run")).findFirst().orElseThrow();
    assertEquals("mvn --batch-mode --no-transfer-progress -Ppolynomial-comparison -pl regelsuche-release -am "
        + "-Dtest=ExternalPolynomialComparisonWorkerTest,PolynomialSourceProofTest,PolynomialRuntimeClasspathTest,PolynomialComparisonArchiveTest "
        + "-Dsurefire.failIfNoSpecifiedTests=false verify", producer.required("run").asText().strip());
    assertFalse(producer.has("if"));
    assertFalse(producer.has("continue-on-error"));
    var retention = steps.required(steps.size() - 1);
    assertTrue(retention.required("if").asText().contains("always()"));
    assertEquals("error", retention.required("with").required("if-no-files-found").asText());
    assertTrue(retention.required("with").required("path").asText().contains("target/reports/polynomial-comparison/**"));
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
