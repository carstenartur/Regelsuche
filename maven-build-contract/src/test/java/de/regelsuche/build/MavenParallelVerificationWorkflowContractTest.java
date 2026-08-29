package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MavenParallelVerificationWorkflowContractTest {
  private static final String PLAYWRIGHT_VERSION = "1.60.0";
  private static final String FULL_MAVEN_COMMAND =
      "mvn --batch-mode --no-transfer-progress -Pfull verify";

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
        "  maven-product-verification:\n");
    String maven = section(
        workflow,
        "  maven-product-verification:\n",
        "  verification:\n");
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
    assertTrue(gradle.contains(
        "./gradlew --no-daemon -p playwright-bootstrap "
            + "installPlaywrightHostDependencies --console=plain"));
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

    assertTrue(jmh.contains("actions/checkout@"),
        "the JMH authority must start from an independent checkout");
    assertTrue(jmh.contains("gradle/actions/setup-gradle@"));
    assertTrue(jmh.contains(
        "bash gradle/run-isolated-jmh-authority.sh"));
    assertFalse(jmh.contains("-PseparateJmhAuthority=true"),
        "the benchmark runner must not disable its own task graph");
    assertFalse(jmh.contains(FULL_MAVEN_COMMAND));
    assertFalse(jmh.contains("needs:"),
        "JMH must start concurrently rather than wait for correctness or Maven");
    assertTrue(jmh.contains("if-no-files-found: error"),
        "missing benchmark evidence must fail the isolated authority");

    assertTrue(maven.contains("actions/checkout@"),
        "the Maven authority must start from a separate fresh checkout");
    assertTrue(maven.contains("cache: maven"),
        "only the Maven dependency repository should be restored");
    assertTrue(maven.contains("-f playwright-bootstrap/pom.xml"));
    assertTrue(maven.contains(
        "org.codehaus.mojo:exec-maven-plugin:3.5.0:java"));
    assertTrue(maven.contains(FULL_MAVEN_COMMAND),
        "the complete product and Docker contract must remain unchanged");
    assertFalse(maven.contains("needs: gradle-verification"),
        "Maven must begin concurrently rather than wait for Gradle");
    assertFalse(maven.contains("actions/download-artifact@"),
        "Maven must not consume Gradle build output or test evidence");
    assertFalse(maven.contains("./gradlew"),
        "the Maven authority must not execute or depend on the Gradle reactor");
    assertEquals(1, occurrences(workflow, FULL_MAVEN_COMMAND),
        "the full Maven contract must execute exactly once per workflow run");

    String compactConvergence = compact(convergence);
    assertTrue(compactConvergence.contains(
        "needs: [gradle-verification, jmh-verification, "
            + "maven-product-verification]"));
    assertTrue(compactConvergence.contains(
        "needs.gradle-verification.result != 'success' || "
            + "needs.jmh-verification.result != 'success' || "
            + "(github.event_name != 'create' && "
            + "needs.maven-product-verification.result != 'success')"),
        "the stable required check must reject any incomplete authority");
    assertTrue(convergence.contains("run: exit 1"),
        "an incomplete authority set must fail rather than become skipped-success");

    int rejection = convergence.indexOf(
        "- name: Reject incomplete verification authority");
    int checkout = convergence.indexOf("- uses: actions/checkout@");
    int coverage = convergence.indexOf(
        "python3 -B scripts/verify-coverage-regression.py --root \"$PWD\"");
    assertTrue(rejection >= 0 && checkout > rejection && coverage > checkout,
        "cross-authority coverage may run only after every required producer passed");
    assertTrue(convergence.contains("'repository-verification'"));
    assertTrue(convergence.contains("name: jmh-verification"));
    assertTrue(convergence.contains(
        "path: build/authority-evidence/jmh"));
    assertTrue(convergence.contains(
        "sympy-runtime-checkout/regelsuche-math-sympy/build/reports/"
            + "jacoco/test/jacocoTestReport.xml"));
    assertTrue(convergence.contains("test ! -e \"$canonical_report\""),
        "a stale or duplicate SymPy report must be rejected");
    assertTrue(convergence.contains(
        "install -D -m 0644 \"$isolated_report\" \"$canonical_report\""));
    assertTrue(convergence.contains("name: coverage-verification"));
    assertTrue(convergence.contains("if-no-files-found: error"),
        "the converged coverage report must be retained fail closed");
    assertFalse(convergence.contains("./gradlew"));
    assertFalse(convergence.contains("mvn "));

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
  void jmhSeparationMovesOnlyTheUnchangedBenchmarkAuthority()
      throws IOException {
    Path root = repositoryRoot();
    String settings = Files.readString(root.resolve("settings.gradle"));
    String separation = Files.readString(root.resolve(
        "gradle/separated-jmh-authority.gradle"));
    String entrypoint = Files.readString(root.resolve(
        "gradle/run-isolated-jmh-authority.sh"));

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
    assertTrue(separation.contains("tasks.register('jmhAuthority')"));
    assertTrue(separation.contains(
        "dependsOn 'verifyJmhRegression',\n"
            + "        'verifyJmhBenchmark',\n"
            + "        'verifyJmhAllocationBenchmark',\n"
            + "        ':regelsuche-math-sympy:"
            + "verifySymPyFactorizationBenchmark'"));
    assertFalse(separation.contains("warmup"));
    assertFalse(separation.contains("iteration"));
    assertFalse(separation.contains("fork"));
    assertFalse(separation.contains("include"));
    assertFalse(separation.contains("exclude"),
        "the separation layer must not alter benchmark selection or policy");

    assertTrue(entrypoint.contains("set -euo pipefail"));
    assertTrue(entrypoint.contains("exec ./gradlew"));
    assertTrue(entrypoint.contains("jmhAuthority"));
    assertTrue(entrypoint.contains("--no-configuration-cache"));
    assertFalse(entrypoint.contains("-PseparateJmhAuthority=true"));
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
