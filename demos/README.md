# Recorded public entry demo

Download [recorded-discovery.html](recorded-discovery.html), then open it in a
browser. The file contains all CSS, JavaScript and recorded evidence; viewing it
needs neither a server nor a network connection. Navigation links use absolute GitHub URLs so a standalone downloaded file can
open the repository documentation and do not change the recorded evidence.

The first two panels use the **existing historical**
[Sophie-Germain discovery evidence](../generated/discovery/sophie-germain/evidence.json).
They display its actual `withoutMacroRun` / `withMacroRun` paths, rules and
state counts. The complete original JSON object is embedded together with the
SHA-256 of its source file. The recorded oracle is `UNAVAILABLE`, validation is
`PASS` and ablation status is `DEGRADED`; those statuses are retained visibly.
This is an inspectable recording, not independent mathematical verification.
It is separate from the newer [autonomous walkthrough](../autonomous-discovery-walkthrough.md).

The SDK panel uses [released-sdk-recording.json](released-sdk-recording.json):
actual stdout from Java 25 compiling and running the current hello-rule source
against the released SDK 0.5.2 binaries. The archive's SHA-256 was checked against
GitHub release metadata and all 4,034 internal checksums were verified. The
minimal execution used the core, egraph, search and plugin-api JARs plus the
included `META-INF/services` descriptor; it did not use reactor classes. `javac`
reported one missing Jackson annotation warning on that minimal classpath;
compilation and execution both exited zero. The unmodified consumer from the separate released optimisation ZIP also
compiled and ran against its actual `-all.jar` with exit code zero; its seven
output lines and exact internal version are retained in the recording metadata.
That run applies to the archive's source revision, not current `main`.
The production Maven route resolves
third-party dependencies rather than relying on that diagnostic shortcut.

**The Maven package attempt did not pass:** Central DNS failed while resolving
the released parent POM's imported JUnit, Testcontainers and Netty BOMs. The
recording does not claim a green Maven/JUnit suite. Re-run the actual independent
[Maven consumer](../../examples/external-consumers/hello-rule-java25/README.md)
in an environment with Central access for that qualification.

To run the existing gallery generator, use a separate disposable checkout,
because this command writes the generated gallery paths:

```bash
./gradlew :app:generateDiscoveryGallery
```

This executes that checkout's implementation. Its output may change
from this historical recording as shared rules evolve. Preserve the old
recording and name a new version when updating evidence; do not overwrite a
historical result to imply a new evaluation. The original source JSON and its
existing generator/verification contracts are unchanged by this presentation.

To reproduce the minimal released-binary smoke run, copy the whole current
hello-rule directory outside the checkout, enter that directory and run with
JDK 25 (POSIX shell; this is diagnostic execution, not a Maven/JUnit substitute):

```bash
SDK_REPOSITORY=/absolute/path/regelsuche-sdk-0.5.2/repository
SDK_CP="$SDK_REPOSITORY/de/regelsuche/regelsuche-core/0.5.2/regelsuche-core-0.5.2.jar"
for module in regelsuche-egraph regelsuche-search regelsuche-plugin-api; do
  SDK_CP="$SDK_CP:$SDK_REPOSITORY/de/regelsuche/$module/0.5.2/$module-0.5.2.jar"
done
mkdir -p target/smoke
javac -cp "$SDK_CP" -d target/smoke src/main/java/example/HelloRule.java
cp -R src/main/resources/. target/smoke/
java -cp "target/smoke:$SDK_CP" example.HelloRule
```

To regenerate or verify only this presentation from the retained inputs:

```bash
python3 docs/demos/render-recorded-demo.py
python3 docs/demos/render-recorded-demo.py --check
```

The renderer performs no mathematical computation. Its source-hash check
rejects silently presenting a changed SDK example as the old recorded execution.
To update the consumer recording, run the example against the intended released
archive, capture its real stdout and tool diagnostics, update its source/archive
hashes and qualification limitations, then regenerate the HTML.

The demo is ready to attach as one HTML file. A `github.com/.../blob/...html`
link displays source on GitHub; it is not a hosted live demo. No new hosting,
outreach messages or human trial is claimed here.
