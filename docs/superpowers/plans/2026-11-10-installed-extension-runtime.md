# Installed extension runtime implementation plan

> **For agentic workers:** Execute the authorized implementation inline with superpowers:executing-plans and obtain an independent final review. Do not pause between reversible implementation steps.

**Goal:** Activate a genuinely verified installed SPI v2 artifact through a production host adapter, without a caller-written admission callback.

**Architecture:** App owns distribution and trust, so App acquires one immutable verified closure and adapts it to the existing generic runtime. No dependency from generic runtime back to App is introduced.

**Tech Stack:** Java 25, native signed distribution/checkpoint APIs, Extension SPI v2, Maven/JUnit and existing loopback HTTPS fixtures.

**Spec:** The user's continuation request and the acceptance contracts below. Detailed source analysis is retained in the continuation ledger's `extension-runtime-design.md`.

## Design and global constraints

- Read the selected external authority exactly once per activation. Load and
  revalidate that generation's complete closure; copy all artifact bytes before
  exposing a runtime. A later update affects the next activation, not this snapshot.
- Reuse existing trusted index, artifact signature, provenance and resolver checks.
  Compare regenerated closure evidence to the selected installation. Retain its
  checkpoint; do not restart trust-revision validation as genesis.
- Add `PluginDistributionClient.installedSnapshot()` and the corresponding
  transaction facade method. An absent genesis differs from a committed empty
  installation. Public snapshot objects defensively copy bytes and have no public
  trust-asserting constructor.
- Add app-owned `InstalledExtensionRuntime.open(client, options)` and
  `open(transactions, options)`, plus catalog, installation and close accessors.
  Each runtime owns one bounded unit of host work. No refresh method or implicit
  fallback to an old installation is included.
- Options carry actual host core compatibility version, parent loader and exact
  exported host types. Disable classpath scanning and unmanaged external paths.
  Default to the current ExtensionApi core compatibility version.
- Before class loading, require API 2 and the same host core version as the
  authenticated resolution request. Its signed min/max bounds and dependency
  selection have already been rechecked. Reject mismatches conservatively.
- Admission returns only immutable selected bytes with artifact hash, nonempty
  installation trust hash and a stable installation/artifact source reference.
  Selector paths are lookup keys, never file inputs.
- Publisher-authenticated Java executes with process privileges. This is no
  sandbox or continuous revocation service; existing caller references cannot be
  revoked by closing a runtime.
- Keep v1 behavior, generic runtime reload semantics and all existing policies.
  Java 25 remains the core; no new ULR module or Python mathematics.

Using raw cache paths or separate active/readArtifact calls was rejected because
of generation/byte races. Reusing the v1 runtime does not implement the v2 service
contract. An app adapter preserves the existing dependency direction.

## Review focus

1. Concurrent authority changes cannot mix metadata or bytes across generations.
2. Historical committed transaction receipts cannot authorize current activation.
3. Corrupt/missing metadata, revoked signatures and incompatible signed bounds
   reject before external provider initialization.
4. Removal and authority failure cannot reopen a stale managed catalog.
5. Returned byte arrays, dependency JARs and host-type exports preserve the
   generic runtime's existing isolation contract.

## Task 1: Complete verified activation

**Files:** create `app/.../plugin/InstalledPluginSnapshot.java` and
`InstalledExtensionRuntime.java`; extend `PluginDistributionClient` and
`PluginDistributionTransactions`; add direct app Extension Runtime dependencies
to both build definitions; add focused tests using `PluginDistributionFixtures`;
document the actual embedded activation API and its lifecycle.

- [ ] Write failing tests for a genuinely signed API-2 external provider installed
  over loopback HTTPS, then activated offline and invoked through the real catalog.
- [ ] Implement a single-selection immutable closure read with complete retained
  trust/index/resolution/artifact/provenance binding, including empty state.
- [ ] Adapt the verified closure to the generic runtime; reject incompatible API
  or core resolution before any provider initialization; own and close staging.
- [ ] Test defensive byte copies, authority read count/change, update/remove/
  rollback, stale transaction receipt, cache corruption, authority failure,
  signed bounds mismatch and manifest class-path rejection using real compiled
  external providers and signatures.
- [ ] Run affected distribution, transaction, trust runtime and new bridge tests
  with Java25; retain actual source/JUnit evidence if Maven cannot resolve local
  dependencies. Existing canonical CI must qualify the current published head.
- [ ] Document and independently review the complete diff, fix material findings,
  and retain exact source/evidence binding before integration.

The completed slice provides installed-package activation to embedded hosts.
Discovery extension-point migration, existing App-command migration and data-only
rule-pack installation remain separate contracts; a Runnable fixture proves
activation, not those broader migrations.
