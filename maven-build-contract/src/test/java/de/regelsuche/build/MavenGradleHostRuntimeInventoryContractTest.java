package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Legacy shell authority budget for #749. This is an inventory, not permission
 * to add Python, Gradle or another host language to the Maven reactor.
 * A legacy line can be removed without relaxing this contract. The remaining
 * shell authorities must be retired before Maven-only can be claimed.
 */
class MavenGradleHostRuntimeInventoryContractTest {
  private static final Pattern HOST_RUNTIME = Pattern.compile(
      "(?i)\\b(?:python\\d*(?:\\.\\d+)?|pypy\\d*(?:\\.\\d+)?|pip\\d*|pipx|uvx?|node|npm|npx|pnpm|yarn|corepack|deno|bun|perl|ruby|"
          + "powershell|pwsh|gradlew|schema_python|pilot_python|python_executable)\\b");

  // Exact lines from the authoritative gradle/*.sh scripts on main bc05c85c8976287f82b4a2eb99bcfdf38ebb6863.
  // No glob or wildcard may silently authorize new executions.
  private static final Map<String, List<String>> RETAINED_LEGACY_LINES = Map.ofEntries(
      Map.entry("run-checked-schema-comparison.sh", List.of(
          "schema_python=build/typed-external-polynomial-venv/bin/python",
          "\"$schema_python\" -m external_polynomial_comparison.run_schema \\",
          "\"$schema_python\" -m external_polynomial_comparison.run_schema \\"
      )),
      Map.entry("run-external-polynomial-comparison.sh", List.of(
          "python3 -m venv build/external-polynomial-venv",
          "pilot_python=build/external-polynomial-venv/bin/python",
          "\"$pilot_python\" -m pip install sympy==1.14.0 mpmath==1.3.0",
          "\"$pilot_python\" -m unittest discover -s scripts/sympy_rule_reference -p 'test_*.py' -v",
          "\"$pilot_python\" -m unittest discover -s scripts/external_polynomial_comparison -p 'test_*.py' -v",
          "env -u GITHUB_SHA ./gradlew --no-daemon --no-configuration-cache --max-workers=2 \\",
          "\"$pilot_python\" -m external_polynomial_comparison.run \\",
          "\"$pilot_python\" -m external_polynomial_comparison.run \\"
      )),
      Map.entry("run-historical-sympy-comparison.sh", List.of(
          "python3 -m venv build/historical-sympy-venv",
          "pilot_python=build/historical-sympy-venv/bin/python",
          "\"$pilot_python\" -m pip install sympy==1.14.0 mpmath==1.3.0",
          "\"$pilot_python\" -B -c 'from pathlib import Path; root=Path.cwd(); deps=(root/\"regelsuche-learning/target/historical-runtime-classpath.txt\").read_text().strip(); (root/\"build/historical-classpath.txt\").write_text(str(root/\"regelsuche-learning/target/classes\")+\":\"+deps+\"\\n\")'",
          "\"$pilot_python\" -B -m external_polynomial_comparison.run_historical \\",
          "\"$pilot_python\" -B -m external_polynomial_comparison.run_historical --verify \\"
      )),
      Map.entry("run-knowledge-lifecycle-improvement.sh", List.of(
          "python_executable=\"${PYTHON_EXECUTABLE:-python3}\"",
          "\"$python_executable\" -m venv \"$venv\"",
          "\"$venv/bin/python\" -m pip install 'sympy==1.14.0' 'mpmath==1.3.0'",
          "\"$venv/bin/python\" -c 'from pathlib import Path; p=Path(\"regelsuche-learning\"); Path(\"build/knowledge-lifecycle-classpath.txt\").write_text(str((p/\"target/classes\").resolve())+\":\"+(p/\"target/knowledge-lifecycle-classpath.txt\").read_text().strip())'",
          "PYTHONPATH=scripts \"$venv/bin/python\" -B -m external_polynomial_comparison.run_knowledge_lifecycle \\",
          "PYTHONPATH=scripts \"$venv/bin/python\" -B -m external_polynomial_comparison.run_knowledge_lifecycle --verify --output \"$output\""
      )),
      Map.entry("run-native-polynomial-comparison.sh", List.of(
          "python3 -m venv build/native-polynomial-venv",
          "pilot_python=build/native-polynomial-venv/bin/python",
          "\"$pilot_python\" -m pip install sympy==1.14.0 mpmath==1.3.0",
          "env -u GITHUB_SHA ./gradlew --no-daemon --no-configuration-cache --max-workers=2 \\",
          "\"$pilot_python\" -m external_polynomial_comparison.run_native \\",
          "\"$pilot_python\" -m external_polynomial_comparison.run_native --verify \\"
      )),
      Map.entry("run-typed-external-polynomial-comparison.sh", List.of(
          "python3 -m venv build/typed-external-polynomial-venv",
          "pilot_python=build/typed-external-polynomial-venv/bin/python",
          "\"$pilot_python\" -m pip install sympy==1.14.0 mpmath==1.3.0",
          "\"$pilot_python\" -m unittest discover -s scripts/external_polynomial_comparison -p 'test_*.py' -v",
          "env -u GITHUB_SHA ./gradlew --no-daemon --no-configuration-cache --max-workers=2 \\",
          "\"$pilot_python\" - <<'PY'",
          "\"$pilot_python\" -m external_polynomial_comparison.run_typed \\",
          "\"$pilot_python\" -m external_polynomial_comparison.run_typed \\",
          "\"$pilot_python\" -m external_polynomial_comparison.audit_typed \\"
      ))
  );

  // Checkout-owned verification wrappers are transitional authority, not additional
  // Maven-approved producers. Freeze exact invocation lines from main@99de5c049fae.
  // Removing a legacy driver is allowed; adding, editing or duplicating one is not.
  private static final Map<String, List<String>> RETAINED_SCRIPT_WRAPPER_LINES = Map.ofEntries(
      Map.entry("scripts/freeze-flagship-rewrite-program.sh", List.of(
          "python3 - \"${repository_root}\" \"${validation_private}\" \"${final_test_private}\" <<'PY'",
          "validation_private=$(python3 -c \\",
          "final_test_private=$(python3 -c \\",
          "exec ./gradlew \\"
      )),
      Map.entry("scripts/run-capability-status-verification.sh", List.of(
          "PYTHON=\"$VENV/bin/python\"",
          "PIP=\"$VENV/bin/pip\"",
          "if [[ ! -x \"$PYTHON\" ]]; then",
          "python3 -m venv \"$VENV\"",
          "if ! \"$PYTHON\" -c 'import importlib.metadata as m; raise SystemExit(0 if m.version(\"jsonschema\") == \"4.25.1\" else 1)' 2>/dev/null; then",
          "\"$PIP\" install --disable-pip-version-check --quiet jsonschema==4.25.1",
          "./gradlew \\",
          "\"$PYTHON\" scripts/generate-capability-status.py \\",
          "\"$PYTHON\" scripts/verify-capability-status.py \\"
      )),
      Map.entry("scripts/run-comparative-benchmarks-verification.sh", List.of(
          "exec ./gradlew --no-daemon verifyComparativeBenchmarks \"$@\""
      )),
      Map.entry("scripts/run-domain-generic-qualification-verification.sh", List.of(
          "PYTHON=\"$VENV/bin/python\"",
          "PIP=\"$VENV/bin/pip\"",
          "if [[ ! -x \"$PYTHON\" ]]; then",
          "python3 -m venv \"$VENV\"",
          "if ! \"$PYTHON\" -c 'import importlib.metadata as m; raise SystemExit(0 if m.version(\"jsonschema\") == \"4.25.1\" else 1)' 2>/dev/null; then",
          "\"$PIP\" install --disable-pip-version-check --quiet jsonschema==4.25.1",
          "./gradlew \\",
          "\"$PYTHON\" scripts/verify-domain-generic-qualification.py \\",
          "./gradlew \\",
          "\"$PYTHON\" scripts/verify-domain-generic-qualification.py \\"
      )),
      Map.entry("scripts/run-generic-discovery-domains-verification.sh", List.of(
          "PYTHON=\"$VENV/bin/python\"",
          "PIP=\"$VENV/bin/pip\"",
          "if [[ ! -x \"$PYTHON\" ]]; then",
          "python3 -m venv \"$VENV\"",
          "if ! \"$PYTHON\" -c 'import importlib.metadata as m; raise SystemExit(0 if m.version(\"jsonschema\") == \"4.25.1\" else 1)' 2>/dev/null; then",
          "\"$PIP\" install --disable-pip-version-check --quiet jsonschema==4.25.1",
          "./gradlew \\",
          "\"$PYTHON\" scripts/verify-generic-discovery-domains.py \\",
          "./gradlew \\",
          "\"$PYTHON\" scripts/verify-generic-discovery-domains.py \\"
      )),
      Map.entry("scripts/run-jmh-benchmark-verification.sh", List.of(
          "exec ./gradlew --no-daemon verifyJmhBenchmark \"$@\""
      )),
      Map.entry("scripts/run-release-readiness-verification.sh", List.of(
          "./gradlew \\"
      )),
      Map.entry("scripts/run-solver-ir-verification.sh", List.of(
          "PYTHON=\"$VENV/bin/python\"",
          "PIP=\"$VENV/bin/pip\"",
          "if [[ ! -x \"$PYTHON\" ]]; then",
          "python3 -m venv \"$VENV\"",
          "if ! \"$PYTHON\" -c 'import importlib.metadata as m; raise SystemExit(0 if m.version(\"jsonschema\") == \"4.25.1\" else 1)' 2>/dev/null; then",
          "\"$PIP\" install --disable-pip-version-check --quiet jsonschema==4.25.1",
          "./gradlew \\",
          "\"$PYTHON\" scripts/verify-solver-ir-evidence.py \\"
      )),
      Map.entry("scripts/run-solver-portfolio-verification.sh", List.of(
          "PYTHON=\"$VENV/bin/python\"",
          "PIP=\"$VENV/bin/pip\"",
          "if [[ ! -x \"$PYTHON\" ]]; then",
          "python3 -m venv \"$VENV\"",
          "if ! \"$PYTHON\" -c 'import importlib.metadata as m; raise SystemExit(0 if m.version(\"jsonschema\") == \"4.25.1\" else 1)' 2>/dev/null; then",
          "\"$PIP\" install --disable-pip-version-check --quiet jsonschema==4.25.1",
          "./gradlew \\",
          "./gradlew \\",
          "\"$PYTHON\" scripts/verify-solver-portfolio-evidence.py \\"
      )),
      Map.entry("scripts/verify-python-client.sh", List.of(
          "task_reports=\"$task_root/build/reports/python-client\"",
          "task_work_root=\"$task_root/build/tmp/python-client-validation\"",
          "python3 -m venv \"$task_work/build-venv\"",
          "\"$task_work/build-venv/bin/python\" -m pip wheel \"$task_root/python\" --no-deps --wheel-dir \"$task_work/wheels\" > \"$task_reports/build.log\" 2>&1",
          "python3 -m venv \"$task_work/venv\"",
          "\"$task_work/venv/bin/python\" -m pip install --no-index \"${task_wheels[0]}\" > \"$task_reports/install.log\" 2>&1",
          "\"$task_work/venv/bin/python\" -m unittest discover -s \"$task_root/python/tests\" -v > \"$task_reports/unit.log\" 2>&1",
          "\"$task_work/venv/bin/python\" -m regelsuche verify \"$task_root/docs/examples/linear-solution.json\" > \"$task_reports/offline.json\"",
          "task_distribution=\"$(python3 - \"$task_root\" \"$task_work\" <<'PY'",
          "REGELSUCHE_TEST_LAUNCHER=\"$task_distribution/bin/regelsuche\" \"$task_work/venv/bin/python\" \"$task_root/python/tests/live_client.py\" > \"$task_reports/live.json\"",
          "\"$task_work/venv/bin/python\" -m regelsuche verify \"$task_reports/java-example.json\" > \"$task_reports/java-example-verification.json\"",
          "printf '%s\\n' 'Python wheel, offline proof, live Java server and external Java example verified.'"
      ))
  );

  @Test
  void repositoryVerificationWrappersCannotGainUndeclaredHostRuntimeCommands() throws IOException {
    Path root = MavenPomTestSupport.repositoryRoot().resolve("scripts");
    try (Stream<Path> files = Files.walk(root)) {
      for (Path file : files.filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".sh")).toList()) {
        String relative = root.relativize(file).toString().replace('\\', '/');
        rejectNewRuntimeLines("scripts/" + relative,
            Files.readString(file, StandardCharsets.UTF_8), RETAINED_SCRIPT_WRAPPER_LINES);
      }
    }
  }

  @Test
  void rootScriptInventoryRejectsNewDuplicatedAndChangedHostDrivers() {
    String script = "scripts/run-jmh-benchmark-verification.sh";
    String retained = "exec ./gradlew --no-daemon verifyJmhBenchmark \"$@\"";
    rejectNewRuntimeLines(script, retained, RETAINED_SCRIPT_WRAPPER_LINES);
    assertThrows(AssertionError.class,
        () -> rejectNewRuntimeLines(script, retained + "\n" + retained,
            RETAINED_SCRIPT_WRAPPER_LINES));
    assertThrows(AssertionError.class,
        () -> rejectNewRuntimeLines(script, "exec ./gradlew --no-daemon newGate",
            RETAINED_SCRIPT_WRAPPER_LINES));
    for (String newHost : List.of("python3 -m pip install package",
        "node verification.js", "npm run test", "exec ./gradlew check")) {
      assertThrows(AssertionError.class,
          () -> rejectNewRuntimeLines("scripts/new-verification.sh", newHost,
              RETAINED_SCRIPT_WRAPPER_LINES));
    }
  }

  @Test
  void existingShellAdaptersCannotGainUndeclaredHostRuntimeCommands() throws IOException {
    Path root = MavenPomTestSupport.repositoryRoot().resolve("gradle");
    try (Stream<Path> files = Files.walk(root)) {
      for (Path file : files.filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".sh")).toList()) {
        String relative = root.relativize(file).toString().replace('\\', '/');
        rejectNewRuntimeLines(relative, Files.readString(file, StandardCharsets.UTF_8));
      }
    }
  }

  @Test
  void newScriptCannotIntroducePythonNodeOrGradleAuthorities() {
    for (String command : List.of(
        "python3 -m unittest discover",
        "env -u GITHUB_SHA /usr/bin/python3 check.py",
        "NODE_ENV=test node ./verify.js",
        "npm run test",
        "exec ./gradlew test",
        "pilot_python=/tmp/venv/bin/python",
        "pilot_python=\\${HOST_RUNTIME:-python3}",
        "pwsh -File build.ps1",
        "pypy3 -m unittest tests",
        "uv run python -m unittest tests",
        "uvx my-test-runner",
        "pipx run check-package",
        "pnpm test",
        "yarn test",
        "corepack pnpm test",
        "bun run test",
        "deno test")) {
      assertThrows(AssertionError.class,
          () -> rejectNewRuntimeLines("new-authority.sh", "#!/bin/bash\n" + command + "\n"),
          "new runtime must not be silently authorized: " + command);
    }
  }

  @Test
  void executableShebangsCannotSmuggleUnapprovedHostRuntimes() {
    for (String shebang : List.of(
        "#!/usr/bin/env python3",
        "#!/usr/bin/python3",
        "#!/usr/bin/env node",
        "#!/usr/bin/env -S uv run python",
        "#!/usr/bin/env pypy3",
        "#!/usr/bin/env bun")) {
      String script = shebang + "\n" + "echo fixture\n";
      assertThrows(AssertionError.class,
          () -> rejectNewRuntimeLines("scripts/new-verification.sh", script,
              RETAINED_SCRIPT_WRAPPER_LINES), shebang);
      assertThrows(AssertionError.class,
          () -> rejectNewRuntimeLines("new-authority.sh", script), shebang);
    }
    // Ordinary shell shebangs and non-executable comments stay permitted.
    rejectNewRuntimeLines("scripts/new-verification.sh",
        "#!/usr/bin/env bash\n# python3 -m do_not_execute\necho fixture\n",
        RETAINED_SCRIPT_WRAPPER_LINES);
    rejectNewRuntimeLines("new-authority.sh",
        "#!/bin/sh\n# node do_not_execute.js\necho fixture\n");
  }

  @Test
  void legacyLinesCannotBeDuplicatedOrReplacedByUnreviewedCommands() {
    String oldCommand = "python3 -m venv build/typed-external-polynomial-venv";
    rejectNewRuntimeLines("run-typed-external-polynomial-comparison.sh", oldCommand);
    assertThrows(AssertionError.class,
        () -> rejectNewRuntimeLines("run-typed-external-polynomial-comparison.sh",
            oldCommand + "\n" + oldCommand));
    assertThrows(AssertionError.class,
        () -> rejectNewRuntimeLines("run-typed-external-polynomial-comparison.sh",
            "python3 -m unittest discover -s scripts -p new_suite.py -v"));
  }

  private static void rejectNewRuntimeLines(String script, String contents) {
    rejectNewRuntimeLines(script, contents, RETAINED_LEGACY_LINES);
  }

  private static void rejectNewRuntimeLines(String script, String contents,
      Map<String, List<String>> retainedLegacyLines) {
    List<String> remaining = new ArrayList<>(
        retainedLegacyLines.getOrDefault(script, List.of()));
    for (String original : contents.split("\\R")) {
      String line = original.strip();
      if (line.isEmpty() || (line.startsWith("#") && !line.startsWith("#!"))
          || !HOST_RUNTIME.matcher(line).find()) {
        continue;
      }
      assertTrue(remaining.remove(line),
          () -> script + " adds, alters or duplicates an undeclared host runtime command: " + line);
    }
  }
}
