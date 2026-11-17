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
      "(?i)\\b(?:python\\d*(?:\\.\\d+)?|pip\\d*|node|npm|npx|perl|ruby|"
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
      Map.entry("run-isolated-jmh-authority.sh", List.of(
          "exec ./gradlew \\"
      )),
      Map.entry("run-isolated-sympy-runtime-authority.sh", List.of(
          "exec ./gradlew \\"
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
      Map.entry("run-released-sdk-maven-consumers.sh", List.of(
          "python3 -m unittest discover -s scripts -p test_released_sdk_maven_consumers.py -v",
          "exec python3 scripts/verify-released-sdk-maven-consumers.py \"$@\""
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
        "pwsh -File build.ps1")) {
      assertThrows(AssertionError.class,
          () -> rejectNewRuntimeLines("new-authority.sh", "#!/bin/bash\n" + command + "\n"),
          "new runtime must not be silently authorized: " + command);
    }
  }

  @Test
  void legacyLinesCannotBeDuplicatedOrReplacedByUnreviewedCommands() {
    String oldCommand = "python3 -m unittest discover -s scripts -p test_released_sdk_maven_consumers.py -v";
    rejectNewRuntimeLines("run-released-sdk-maven-consumers.sh", oldCommand);
    assertThrows(AssertionError.class,
        () -> rejectNewRuntimeLines("run-released-sdk-maven-consumers.sh",
            oldCommand + "\n" + oldCommand));
    assertThrows(AssertionError.class,
        () -> rejectNewRuntimeLines("run-released-sdk-maven-consumers.sh",
            "python3 -m unittest discover -s scripts -p new_suite.py -v"));
  }

  private static void rejectNewRuntimeLines(String script, String contents) {
    List<String> remaining = new ArrayList<>(
        RETAINED_LEGACY_LINES.getOrDefault(script, List.of()));
    for (String original : contents.split("\\R")) {
      String line = original.strip();
      if (line.isEmpty() || line.startsWith("#") || !HOST_RUNTIME.matcher(line).find()) {
        continue;
      }
      assertTrue(remaining.remove(line),
          () -> script + " adds, alters or duplicates an undeclared host runtime command: " + line);
    }
  }
}
