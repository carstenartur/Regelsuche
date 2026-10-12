# Local reuse of qualified release evidence

Issue #514: the ordinary `runQualifiedReleaseReadinessWithHiddenRuleEvidence`
Gradle task now declares its hidden-rule report and dedicated output directory.
Its tracked inputs include the runtime classpath, application arguments, JDK
launcher and patch version, and inherited JVM options. Debugging or any inherited
`JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` or `_JAVA_OPTIONS` conservatively forces
generation, including when an opaque option remains unchanged.

An unchanged checkout may reuse the generated files. The independent
`verifyReleaseReadinessEvidence` process still executes on every invocation, as
do the mutation tests against private copies of the actual Java-generated files.
Every real generation still runs all three campaigns and three qualifications.
The production dependency on the public hidden-rule pilot test remains intact.
No shared build cache or cached verifier decision is introduced.

## Measured behavior

The two original invocations both generated the same 48 files, taking 2.031 and
1.875 seconds in the generator task. A fresh temporary checkout then exercised
the modified task in one isolated process tree:

| Control | Generator | Generator seconds | Verifier ran |
| --- | --- | ---: | --- |
| First invocation | Executed | 1.991 | Yes |
| Unchanged second invocation | Up to date | 0.047 | Yes |
| Changed output | Executed | 2.021 | Yes |
| Missing output | Executed | 2.520 | Yes |
| Changed hidden-rule input | Executed | 1.803 | Yes |
| Restored hidden-rule input | Executed | 1.848 | Yes |
| Added runtime classpath resource | Executed | 1.830 | Yes |
| Removed runtime classpath resource | Executed | 1.842 | Yes |
| Changed inherited JVM options | Executed | 1.833 | Yes |
| Same inherited JVM options | Executed | 2.371 | Yes |
| Restored JVM options | Executed | 1.858 | Yes |
| Final unchanged invocation | Up to date | 0.031 | Yes |

All twelve original controls passed, including the then-current 15 mutation tests on every invocation.
The final 48 files match the original bytes exactly. The retained
[measurement receipt](evidence/release-evidence-task-reuse-v1.json) binds the
qualified source tree, build inputs, public pilot input, individual output hashes,
task durations and log hashes. Earlier runs in the shared workspace did not
produce reliable invalidation evidence; they are excluded from this result.

These focused invocations used an explicitly retained public pilot report and
excluded the aggregate root/app tests to isolate task behavior. They do not
qualify a fresh pilot run, Docker reproduction or end-to-end CI latency. The
observed saving is about two seconds of local generator work; verification and
the new mutation checks still cost time. Independent checkout and container
reproduction continue to generate their own evidence.

## Evidence integrity

The verifier checks the existing Java root and qualification matrix identities,
exact profile inventory, claim catalog, and cross-file campaign, hidden-rule
and qualification bindings. Its hashing follows the existing Java UTF-16
ordering, list normalization and UTF-8 replacement behavior. Negative controls
include fully rehashed mutants, so recomputing a root hash cannot hide a
mismatched matrix or claim.

A follow-up review of PR #998 reproduced two gaps in the initial verifier:
symbolic roots, directories and files were followed, and several dependency
hash fields were trusted without recomputing their content identities. Changing
the release summary's `candidateCount`, a hidden-rule counter, or an in-range
qualification utility value passed under the original declared hash. The
existing qualification schema already rejected an out-of-range utility value;
that rejection did not establish content integrity.

The corrected verifier validates the existing schemas and independently
recomputes the direct root dependencies' Java identities: campaign manifest v2,
campaign release evidence v1, hidden-rule release evidence v1, qualification
evidence v1 and qualification run v1. The catalog retains its original byte
hash. The campaign v2 hash intentionally omits summary counts; those counts are
instead checked against the independently hashed release summary. Its hash
algorithm is unchanged. Qualification run summaries are checked against their
bound qualification evidence.

Reads now traverse every root/member path component using owned directory
descriptors and `O_NOFOLLOW`. JSON parsing and byte hashing use the same
immutable first-read bytes for each file. Symbolic schema paths and nonregular
members also fail. This helper requires POSIX directory-relative opens and
no-follow support; it has been exercised on Linux. Unsupported platforms return
`UNSUPPORTED_PLATFORM` and cannot report successful qualification. This is a
new platform requirement of the standalone release verifier, including its
aggregate `check` path; it does not restrict ordinary Java module tests.
Windows and other untested platforms are not qualified by these controls.

The [integrity review receipt](evidence/release-evidence-integrity-review-v1.json)
retains the original reproductions and focused follow-up checks. The earlier
twelve measurements remain generator-only invalidation/timing evidence; they
do not establish this later integrity guarantee. No generator input, output,
campaign count or qualification count changed.

These checks establish schema, canonical identity and the stated cross-file
bindings. They do not replay the hidden-rule source report, every descendant
campaign artifact or mathematical assertions, and cannot authenticate a
coherently fabricated and rehashed campaign as a real execution. Existing
scientific authorities, report schemas and independent reproductions remain
unchanged.

The [implementation plan](superpowers/plans/2026-09-13-release-evidence-task-reuse.md)
records the bounded scope. Existing Java/JUnit performance-history rendering and
the shared promotion-pipeline test fixture already address other parts of #514.
