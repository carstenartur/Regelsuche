# Java verification of retained release evidence

Issue #749 adds `de.regelsuche.release.ReleaseReadinessEvidenceVerifier` in
`regelsuche-release`. It reads an existing qualified evidence root and verifies
the current release contract without executing a campaign, regenerating evidence,
running a qualification study or publishing a release.

The required release decision now uses this Java verifier. Its JUnit suite retains
all 26 original Python controls and nine additional verifier controls. The root
Gradle gates and both reproduction scripts call the same Java authority. Their
Gradle and Docker producers remain in place; this tranche does not complete issue
#749's broader Maven-only build migration.

## Maven production and verification parity

The first lifecycle-integration step now also uses current Maven production:

1. `HiddenRulePilotCampaignTest` writes the unchanged twenty-case report and its
   separate runtime report below `app/target/reports/hidden-rule-pilot`.
2. The final `regelsuche-quality-aggregate` module explicitly depends on App and
   Release. Its required `verify-qualified-release-readiness` execution at
   `verify` calls the existing `ReleaseReadinessRunner.runQualified` with that
   actual App report. The existing pinned 1/2/4 campaign executions and
   qualification work are unchanged.
3. The runner writes to
   `regelsuche-quality-aggregate/target/reports/release-readiness-qualified`.
   The existing Java verifier validates that root against `docs/schemas` before
   `mavenReleaseReadinessStatus=VALID` is printed.

Both `mvn verify` and `mvn -Pfull verify` reach this same required execution.
This is not an opt-in profile or an additional verification implementation.
The execution retains the current 600-second Maven/JUnit time bound and explicitly
enables native access. Missing native access or an unsupported platform fails.

Module-local Maven `initialize` executions remove only the two named prior Maven
evidence directories, without following symbolic links or cleaning other build
outputs. Each fileset starts at the declared checkout root and includes only its
exact output directory and descendants. This keeps module, `target` and `reports`
components inside the non-following scan: putting the output itself at the fileset
root would follow an already symbolic ancestor before the scanner could refuse
it. The producer refuses missing Maven output configuration, an output path
outside its exact `target` location, and symbolic-link traversal. The aggregate
refuses missing/empty/nonregular current input, symbolic paths, and existing output
before starting generation. In particular, `-DskipTests verify` cannot reuse an
earlier success: initialization removes that input and the mandatory verifier
fails because no current App report was produced. Neither component falls back
to `build` output. These admission checks do not claim an atomic filesystem
snapshot against concurrent mutation.

The original Gradle producer paths and retained 48-file fixture are unchanged.
Independent review accepted Maven-production parity before the Python release
decision, its controls and its two identity/binding helpers were removed. The
shared Python `release_readiness_files.py` remains owned by the separate
quality-acceptance collector. Historical receipts retain their original bytes and
describe their original source revisions, including the then-existing Python files.

The finite parity controls run the actual App producer, qualified runner, existing
Java verifier and package audit through Maven `verify`. Before retirement, the
unchanged Python verifier and all 26 Python controls ran on that produced root. The
qualified root is compared byte for byte with the original 48-file fixture;
the separate runtime report is not treated as deterministic evidence. Actual
Maven re-execution with skipped producers challenges freshness and checks that
Gradle and unrelated Maven outputs survive. This provides no new performance,
resource-ledger verification, execution authentication or Maven-only completion
claim; the existing verifier limits below still apply.

## Required Java decision and retained-root reading

The root `verifyReleaseReadinessEvidence` task retains the repository test gate and
delegates to `:regelsuche-release:verifyQualifiedReleaseReadinessJava`. This JavaExec
consumer directly depends on the existing qualified producer, so verification
cannot race generation as a sibling dependency. The producer's ordering after
repository tests, hidden-rule input, JVM inputs and cache admission are unchanged.
`testReleaseReadinessEvidence` delegates to the actual Release JUnit task.

All four local/container decisions in the two reproduction scripts invoke
`ReleaseReadinessEvidenceVerifier` through the explicitly built
`regelsuche-release/build/install/regelsuche-release/lib/*` classpath. The standalone
script adds `:regelsuche-release:installDist` to its existing producer invocation;
the Gradle Docker task has the same explicit packaging dependency. The Gradle task
supplies its Release-module Java-25 toolchain launcher as
`REGELSUCHE_RELEASE_VERIFIER_JAVA`. A standalone caller sets `JAVA_HOME` or that
explicit launcher variable. Both scripts inspect the selected JVM's specification
version and require exactly 25; they do not select an arbitrary `java` from `PATH`.
Each CLI call enables native access and passes the checkout schema directory.
Missing libraries, an unsupported platform or a failed verification stop the
script before it can report byte-identical reproduction. Docker production and
the final byte comparison remain the shell owner's responsibility.

Maven CI retains both new `target/reports` trees. The release workflow uploads them
immediately after Maven verification, before a subsequent assembly lifecycle can
clean those paths. These uploads retain evidence and make no independent decision.

The following ordinary Maven tests use a frozen copy of the existing 48-file
qualified evidence root; they need neither Python nor Docker:

```sh
mvn -B -pl regelsuche-release -am \
  -Dtest=ReleaseReadinessEvidenceVerifierTest,ReleaseReadinessJavaBindingsTest,OwnedReleaseEvidenceFilesTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

The read-only Gradle adapter runs the same Java entry point against retained output:

```sh
./gradlew :regelsuche-release:verifyRetainedReleaseReadinessJava
./gradlew :regelsuche-release:verifyRetainedReleaseReadinessJava \
  -PreleaseReadinessEvidenceRoot=/absolute/path/to/retained/evidence
```

This task depends on compiled classes and has no evidence-generation dependency.
Absent evidence fails. The required generated-evidence gate uses its separate
consumer above, leaving this read-only entry point's behavior unchanged.
The public Java API is `verify(Path root, Path schemaRoot)`; the CLI accepts
`--root PATH --schemas PATH`. Success is printed only after every check and both
owned roots have closed successfully. Exceptions produce a nonzero CLI exit.

## Contract and ownership

The verifier checks the existing 18 mandatory nonempty files and all 16 unchanged
Draft 2020-12 schema/artifact pairs. As before, the resource ledger and feedback
reallocation files receive only the existing nonempty requirement here. This does
not extend verification to their descendant identities. Strict UTF-8 decoding,
duplicate-field rejection, finite JSON numbers, object roots and absence of
trailing documents precede schema and semantic validation.

The three existing evidence record constructors own campaign-summary, hidden-rule
and qualification identity checks. The original campaign v2 and qualification-run
v1 hash expressions are shared with their Java producers. Existing matrix/profile
constructors retain their canonical ordering, blocker and authority rules. The
release-run v1 hash method is reused unchanged. The catalog hash covers its exact
first-read bytes. No new hash format, threshold or schema revision is introduced.
Campaign summary counts remain separately bound because the historical campaign
v2 hash does not include them.

Every direct root reference, campaign/qualification cross-reference, complete
profile set, profile claim, derived autonomy flag and solver/proof/lifecycle binding
remains checked. The original 20-case/four-family hidden benchmark, 12/12 positive
and negative held-out qualification counts, zero refutations/regressions, beneficial
utility and explicit promotion/public non-evaluation checks remain required.
External novelty review remains blocked.

`OwnedReleaseEvidenceFiles` owns the evidence and schema directory descriptors.
Every ancestor and nested directory is opened with `openat` and `O_NOFOLLOW`.
Members use `O_NONBLOCK | O_NOFOLLOW`; `statx` checks the already-open descriptor
for regular-file type before any read. A FIFO is therefore rejected without waiting
for a writer. First-read bytes are cached and returned as defensive copies. Root
renames, replacement symlinks and later rewrites cannot redirect an owned descriptor
or replace bytes already read. The obsolete obligation path is checked without
following links, including a dangling symlink at that name.

This is the existing per-file ownership model, not an atomic filesystem snapshot.
Concurrent in-place writes during a first read are not prevented. Canonical hashes
and cross-file consistency do not authenticate a real execution: coherently
fabricated and rehashed evidence remains outside this verifier's claim. No full
descendant-artifact replay, mathematical replay or fresh qualification is claimed.
As in the original Python reader, first reads accumulate through EOF without a
fixed byte ceiling. This port does not add a new evidence-size admission threshold;
large or continually growing regular files can exhaust the verifier's resources.

## Platform boundary

The verifier requires JDK 25 on Linux AMD64, a little-endian 64-bit address ABI,
libc `openat`, `statx`, `read`, `close`, and a kernel supporting descriptor-relative
`statx(AT_EMPTY_PATH)`. Native access must be explicitly enabled with
`--enable-native-access=ALL-UNNAMED`. The module's Maven/JUnit and Gradle adapters
set that option. Unsupported platforms or missing native access fail; they never
report successful or skipped verification.

Root and member paths must belong to the default filesystem and preserve their
original filename bytes through the UTF-8 representation used by the FFM bridge.
Paths from ZIP or other filesystem providers are refused instead of interpreting
their text in the host namespace. Non-UTF-8 native names are also refused if their
replacement-character rendering could select a different file. Ordinary Unicode
paths, including an actual U+FFFD filename, remain supported.

The small isolated adapter uses public JDK FFM APIs and the fixed Linux UAPI
256-byte `statx` layout, requesting and checking `STATX_TYPE` before using its mode
field. It captures `errno`, retries interrupted opens/reads/stat calls, and closes
owned descriptors on failures. It does not use private JDK access, JNI binaries or
host C compilation. The variadic `openat` signature is explicitly declared even
though these read-only opens pass no optional mode argument.

The portable JDK `SecureDirectoryStream` supplies directory-relative access, but
its public channel API does not expose both a nonblocking member open and a
file-type query tied to that same opened member descriptor. A path-based type
check would leave a replacement race. This is why the Linux adapter is isolated
instead of weakening the current reader contract. The Python implementation's
POSIX capability boundary is broader than this qualified Java Linux ABI.

The existing Java constructors also reject inputs that do not represent their
producer types, such as floating JSON nodes for Java integer fields. The verified
equivalence scope is the retained Java evidence, original rejection controls and
unchanged contract checks; arbitrary hand-authored JSON acceptance and broader
POSIX portability have not been claimed equivalent.

## Schema dependency and verification evidence

The repository already manages Jackson 2.22.2 and had no Java JSON Schema validator.
The selected dependency is the current Jackson-2 release
`com.networknt:json-schema-validator:2.0.7`, published on 20 August 2026 under
Apache-2.0. Maven dependency management and the Gradle declaration pin that version;
the Java entry point also requires its exact runtime version metadata. The published
Central POM and actual 2.0.7 API were checked rather than assuming the current
default branch's Jackson-3 POM described the selected artifact.

The resolved graph keeps the checkout's Jackson versions and adds `itu:1.14.0`
and `slf4j-api:2.0.17` to this module. Optional Joni/GraalJS regex engines are absent.
Validation uses `SchemaRegistry` with Draft 2020-12, strict types, and format
assertions disabled to match the existing Python call. Meta-schemas are validated
from the pinned library. Checkout schemas are read through the same owned reader;
their local JSON-pointer fragment references are admitted and remote fetching is
disabled. The same admission applies to `$ref` and Draft 2020-12 `$dynamicRef`,
including nested references and external resources already cached by the registry.
A reserved-domain resolution base handles their existing relative `$id` values
without changing schema bytes. Schemas and regex patterns remain checkout-owned.

The downloaded 2.0.7 JAR SHA-256 is
`7d01cefd6f0d656666b94778c7a13ada2941aace48e76830809c0dbf61ad5be2`;
its published POM SHA-256 is
`e0286a6ed4c21958edc64545666385e60501ad2c5aa75164211edb8e397f8bbd`.
These record the reviewed artifacts; ordinary Maven/Gradle resolution remains the
existing repository dependency mechanism.

Dependency selection was checked with the actual pinned OSV-Scanner v2.5.1 and the
repository's complete Maven snapshot created `2026-09-11T22:33:37.036Z`. All nine
resolved external compile dependencies of `regelsuche-release` had complete
per-input output coverage, scanner exit 0 and no snapshot findings. The unchanged
repository policy rejects CVSS >= 7, unknown severity and scanner errors without
suppressions. This bounded selection check is not the required full-repository
inventory decision, nor a claim about later advisories; that gate still runs in CI.

The 43 focused Java controls include all 26 original named cases, every schema's
required fields and closed object, strict parsing, schema metadata, forbidden
external references, original-byte fixture integrity, nonblocking FIFO refusal,
directory replacement and a real subprocess without native access. Additional
controls reject ZIP-filesystem roots/members, lossy native filename conversion
against a qualified Unicode sibling, and external dynamic schema references while
retaining valid local dynamic references. The unchanged
Python controls passed all 26 cases before their retirement. Twelve script-process
controls exercise the actual Java CLI on retained positive and forged roots, exact
Java-version admission, unsupported native platforms and missing distribution
libraries. These controls package the current Maven/Gradle test classpath into a
temporary `lib/*`; explicit Docker and Gradle process seams supply retained fixtures.
They neither execute a new Docker reproduction nor substitute for the separate
real `installDist` CLI checks. The frozen ZIP retains exactly the original
48 files and has SHA-256
`2a827ff0e80b0db0a5a40fc3622b1980c18a39573d3613ba3fa1b047b6ea651d`;
the accompanying manifest records each original file's size and hash. The
read-only verifier port generated no new qualified evidence; the Maven parity
step above subsequently reproduces that fixture with its actual producer.

Primary references:

- [Networknt releases](https://github.com/networknt/json-schema-validator/releases)
  and [Jackson-2 migration/API notes](https://github.com/networknt/json-schema-validator/blob/master/doc/migration-2.0.0.md).
- [Published 2.0.7 POM and license declaration](https://repo.maven.apache.org/maven2/com/networknt/json-schema-validator/2.0.7/json-schema-validator-2.0.7.pom).
- [Networknt security advisories](https://github.com/networknt/json-schema-validator/security);
  absence of published project advisories does not substitute for the pinned OSV check.
- [JDK 25 SecureDirectoryStream](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/SecureDirectoryStream.html)
  and [FFM Linker options](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/foreign/Linker.Option.html).
- [Linux UAPI statx layout](https://github.com/torvalds/linux/blob/master/include/uapi/linux/stat.h)
  and the checkout's [existing vulnerability policy and scanner authority](supply-chain-vulnerability-evidence.md).
