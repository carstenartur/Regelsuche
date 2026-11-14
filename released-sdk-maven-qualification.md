# Released SDK Maven consumer qualification

The preferred Maven entry has a separate, reproducible acceptance check. It
qualifies the **current complete external examples against the actual published
SDK 0.5.2 ZIP**, rather than rebuilding SDK modules from the source reactor.

From a checkout with JDK 25 and Maven **3.9.9 or newer from the 3.x line**:

```bash
bash gradle/run-released-sdk-maven-consumers.sh
```

The thin checkout-owned adapter runs the unit contracts, then the verifier.
It can also be invoked outside the repository directory; it finds its own root.
The verifier uses the existing `mvn` executable. `--maven /absolute/path/to/mvn`
selects another installed Maven. It records the precise Maven and Java versions
and rejects Maven outside the supported range or Java other than 25. It neither
downloads a new build tool nor changes the existing product build gates.
Python performs build/file orchestration only; the mathematical implementations
and consumer tests remain Java.

## Inputs and isolation

The download URL is fixed to
[regelsuche-sdk-0.5.2.zip](https://github.com/carstenartur/Regelsuche/releases/download/v0.5.2/regelsuche-sdk-0.5.2.zip).
The verifier requires the published outer SHA-256
`0746b8c6088539c6db3d228131952b30e8066ac8569215b6a508d08df601d9cb`
and verifies all **4,034** internal checksum entries and complete archive
coverage before extracting. Duplicate entries, traversal paths, symlinks,
unlisted files and excessive archive sizes fail closed.

The three current example directories are copied into a private temporary directory
**outside the source checkout**. Their inputs are hashed; stale `target`, `build`
and Gradle cache outputs are excluded. Each consumer gets a separate initially
empty Maven cache and empty explicit user/global settings. There is no reactor
build, source substitution for SDK JARs, or warm runner dependency cache.
The actual copied SDK runtime JAR bytes must match the verified release.

## Acceptance gates

For each copied consumer, the verifier runs real Maven `clean package`, reads
actual Surefire XML, resolves the runtime dependency graph and runs its Java CLI.

| Consumer | Required existing JUnit cases | Exact Regelsuche runtime module set |
| --- | --- | --- |
| `hello-rule-java25` | positive identity/replay and negative-operand test | Core, Egraph, Search, Plugin API — four modules |
| `extension-runtime-java25` | typed greeting and stable catalogue reload test | Core, Extension API, Extension Runtime — three modules |
| `geometric-sequence-domain-java25` | provider discovery, typed input, confirmed and refuted cases, budget exhaustion, invalid input and canonical evidence | Core, Egraph, Search, Validation, Discovery, Discovery SDK — six modules |

Missing expected test cases, zero tests, failures, errors or skips reject the
qualification. Runtime closures must use product version 0.5.2 and exclude app,
Spring, Hibernate and persistence. The resolved dependency graph must match the
copied runtime JAR set. The hello-rule CLI output must match its exact concrete
rule/replay/negative-case result. The extension CLI must print the exact extension,
origin and greeting plus one canonical lowercase `sha256:<64hex>` catalogue ID.
It is run twice from the same actual Maven build; complete outputs and catalogue
IDs must match, alongside the required JUnit reload-identity test. The geometric
sequence CLI must find the provider through ServiceLoader, confirm multiplier
2, retain the counterexample for multiplier 1 and print an evidence hash.
Two executions of that same build must agree on the full output and evidence hash.
Missing, malformed or unstable IDs reject qualification.

The classpath catalogue includes the provider's absolute CodeSource URL in its
origin, so a different private build directory legitimately changes its hash.
A hash recorded by a separate `javac` smoke run is not a universal expected
Maven-build hash. For this trusted-classpath example the artifact/trust hash
fields are empty: its catalogue identity does not attest provider class bytes.
External-admission origins instead bind their admitted artifact/trust hashes in
the manifest; this does not turn a catalogue ID into a publisher signature or
mathematical proof. No stub Maven runner or success fixture is used.

## Evidence and CI

Every invocation creates a new directory below
`build/reports/released-sdk-maven-consumers/`. Its `qualification.json` records
checkout revision, exact tool versions, archive hash/checksum count, all copied
source hashes, original command/exit records, JUnit counts and case names,
runtime coordinates/JAR hashes, CLI output and final status. Raw combined Maven
output, raw Surefire reports and CLI output remain alongside it, including on
failure. `PASSED` is written only after all three consumers satisfy every gate;
a failed run exits nonzero and retains `FAILED` evidence.

The existing Maven product job invokes the adapter, keeping assertions and
qualification orchestration out of the workflow itself. It runs **before** the unchanged
`mvn -Pfull,sdk-release verify`. Its existing always-run artifact upload includes
the dedicated evidence directory. No new workflow or relaxed gate is introduced.

The earlier [recorded Java execution](demos/README.md) demonstrated that the
released binaries could compile/run the examples; it did **not** establish Maven
model resolution, JUnit execution or the resolved runtime closure. This check
closes those distinct acceptance requirements when it completes successfully.
Local attempts still fail at Central DNS in the restricted workspace; that
failure is retained rather than converted into a successful qualification.
The runner has network access, but a successful CI receipt must be inspected
before claiming that the Maven entry has passed.
