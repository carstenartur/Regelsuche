# Released SDK Maven consumer qualification

The acceptance check has two independent inputs: the **current published
Central SDK 0.5.3**, consumed with the unchanged `hello-rule-java25` defaults,
and the **historical SDK 0.5.2 ZIP**, which continues to qualify the three
external Java examples and the data-only factorization rule package.

From a checkout with JDK 25 and Maven 3.9.9 or newer in the 3.x line:

```bash
mvn -Preleased-sdk-consumers -pl regelsuche-quality verify
```

The full Maven profile includes the same JUnit qualification. Maven records the
actual toolchain versions, builds each external example, inspects its
Surefire reports and runtime dependency graph, and executes its CLI. The
hash-bound ZIP may also be supplied via `REGELSUCHE_RELEASED_SDK_ZIP`.
A Git-free source archive supplies its exact checkout identity via
`REGELSUCHE_PREPARATION_REVISION`.

## Current Central default

`ReleasedSdkConsumersIT.qualifyCurrentCentralConsumerWithUnmodifiedDefaults`
copies the complete current `hello-rule-java25` project outside the checkout.
It runs the documented Maven build without SDK version, namespace or repository
overrides, using empty user/global settings and a fresh dependency cache.
The actual two JUnit cases and the CLI result must pass. The runtime graph must
contain the four SDK modules at `io.github.carstenartur:0.5.3`, and their bytes
must match the published hashes retained in `released-central-sdk-0.5.3.json`.
Missing JARs, different versions/namespaces and changed bytes reject the run.

The resulting directory ends in `-central` under the evidence root below. Its
receipt retains the release/source identity, source inputs, toolchain, commands,
test reports, runtime hashes and CLI output. This additive check does not change
the 0.5.2 archive URL, hashes or historical expected results.

## Newly packaged SDK consumers

The Student SDK archive qualification additionally extracts its actual generated
archive into a fresh directory and runs `hello-rule-java25` and
`checked-math-java25` through Maven `clean verify`, without SDK overrides. Each
gets a fresh Maven cache. The latter runs seven mathematical result checks and all
seven executable examples. Resolved SDK coordinates must match the archive version,
and copied runtime JAR hashes must match its compatibility descriptor. The
`maven-consumers.json` receipt and raw logs accompany the existing archive parity
and Gradle-consumer evidence. This qualifies local archive consumption, not
publication or a Sandbox source rewrite.

## Historical 0.5.2 inputs and isolation

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

The released Discovery consumer loads the provider through the 0.5.2 classpath
SPI. The generated starter uses the same public SDK entry points. Its custom
Java package and Maven coordinates were exercised in the original qualification.
[PR #1190](https://github.com/carstenartur/Regelsuche/pull/1190)

The released greeting example uses the generic Extension SPI v2 on the trusted
classpath.
The authenticated installed-provider path uses the later source tree and its
SPI-v2 Discovery contribution. See the installation and evidence contracts in
the [Discovery SDK guide](java-discovery-sdk.md#installierte-spi-v2-domänen-ausführen-aktueller-quellstand).

The classpath catalogue includes the provider's absolute CodeSource URL in its
origin, so a different private build directory legitimately changes its hash.
A hash recorded by a separate `javac` smoke run is not a universal expected
Maven-build hash. For this trusted-classpath example the artifact/trust hash
fields are empty: its catalogue identity does not attest provider class bytes.
External-admission origins instead bind their admitted artifact/trust hashes in
the manifest; this does not turn a catalogue ID into a publisher signature or
mathematical proof. No stub Maven runner or success fixture is used.

### Data-only rule package

The verifier copies `factorization.regelsuche` and its portable export profile
outside the checkout. Java 25 runs the parser check with exactly
`regelsuche-plugin-api` and `regelsuche-core` on its classpath, both from the
verified SDK ZIP. The stable `RuleFileParser` must return the two expected
bidirectional rules and both typed profiles. An incomplete rule without a
replacement must fail parsing. The separate [published CLI recipe](rule-files.md#portables-faktorisierungspaket-mit-052)
checks import, activation and export through the application distribution.

## Evidence and CI

Every invocation creates a new directory below
`build/reports/released-sdk-maven-consumers/`. Its `qualification.json` records
checkout revision, exact tool versions, archive hash/checksum count, all copied
source hashes, original command/exit records, JUnit counts and case names,
runtime coordinates/JAR hashes, CLI output and final status. The `rulePackage`
record binds the data and verifier hashes, both release JAR hashes, the actual
Java command and its result. Raw Maven output, Surefire and CLI logs are
retained. `PASSED` requires all three Maven consumers and the data-only parser
check. Failure exits nonzero and retains `FAILED` evidence.

The product job runs this Failsafe verification in both full Maven reactors, from
the checkout and from its Git-free source archive. Each run retains its own
reports and command logs. The focused profile runs the same qualification.

The hosted [reference run on main](https://github.com/carstenartur/Regelsuche/actions/runs/44804422507)
used the original producer. An independent Maven lifecycle against the same
release reproduced all 12 test identities and all source, dependency and JAR
hashes for the three consumers. Both data-only parser outputs were identical.
The greeting catalogue passed its within-build identity check on both producers.
