# Installed Discovery CLI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run an authenticated installed SPI v2 Discovery provider through the existing App `domains` command and retain installation-bound provenance in canonical run evidence.

**Architecture:** The Discovery SDK owns its typed extension point and catalog adaptation; the App owns configured authority/installation activation and exact host exports. One installed runtime remains open for the complete synchronous list/run/export operation. Existing classpath and v1 paths keep their behavior.

**Tech Stack:** Java 25, current Maven/Gradle reactor, generic Extension SPI v2, existing Discovery runner, real JUnit 6.0.3, current signed distribution fixtures.

**Spec:** `docs/superpowers/specs/2026-11-10-installed-extension-discovery-design.md`

## Global Constraints

- Baseline is accepted main `d191fc8abb998c14ea7bc5965401eaf696e8fc2a`, canonical CI `42825000269` SUCCESS.
- Java 25; no new module, AST, dependency framework, signing authority or trust-on-first-use policy.
- Standard point: `ExtensionPoint.of("discovery.domain-provider", DiscoveryDomainProvider.class)`.
- Managed activation uses SPI `2`, host core `1.0.0`, one current-authority selection and the existing authenticated installed closure.
- Existing `regelsuche.plugin-package-config/v1`, 262144-byte strict/no-follow configuration reader, root/TLS/deadline/scope policy remain authoritative.
- Seed bound remains 1 MiB with strict UTF-8; budgets remain `small` and `tiny`; evidence export remains `CREATE_NEW`.
- Operational/request failure code is `1`; outcome codes remain `0,2,3,4,5,6` for CONFIRMED, REFUTED, BUDGET_EXHAUSTED, INCONCLUSIVE, UNSUPPORTED, INVALID_SEED.
- Host-owned extension provenance uses the existing protected `sdk.provider.*` namespace. Signed code does not receive mathematical authority.
- No classpath fallback on installed-mode failure; no authority mutation, installation request, recovery receipt selection or automatic refresh in this command.
- Preserve public Config/Authority/Selection records, legacy catalog/CLI canonical behavior and generic runtime/extension module dependency direction.
- No CI retries or extra workflows for unchanged code; full qualification follows one independently reviewed coherent package.

## Review Focus

1. A contributor in artifact A returns a domain-provider implementation from admitted dependency B: retain both hashes without falsely asserting they are identical (Tasks 1 and 4).
2. Two accepted contributions reuse a provider ID or domain revision: fail deterministically before selecting an ambiguous domain, even with distinct extension contribution IDs (Task 1).
3. Authority advances between commands or becomes unavailable: one frozen command never mixes generations; the next command observes current authority and never returns stale evidence (Task 4).
4. Provider execution/evidence rendering loads its own resource late: keep staging/classloader alive through export, and release resources on all failures (Task 4).
5. Plugin payload tries to forge installation provenance, or a plugin references an App class outside the declared type tokens: reject rather than broaden the export/provenance boundary (Tasks 1 and 4).

---

### Task 1: typed Discovery point, catalog adapter and retained extension origin

**Files:**
- Create `regelsuche-discovery-sdk/src/main/java/de/regelsuche/sdk/discovery/DiscoveryExtensionPoints.java`.
- Create `regelsuche-discovery-sdk/src/main/java/de/regelsuche/sdk/discovery/DiscoveryExtensionProvenance.java`.
- Modify `regelsuche-discovery-sdk/src/main/java/de/regelsuche/sdk/discovery/DiscoveryDomainCatalog.java`.
- Modify `regelsuche-discovery-sdk/src/main/java/de/regelsuche/sdk/discovery/ProviderDomain.java`.
- Modify `regelsuche-discovery-sdk/pom.xml`, `regelsuche-discovery-sdk/build.gradle`.
- Modify `app/src/test/java/de/regelsuche/architecture/ArchitectureBoundariesTest.java` and `docs/dependency-rules.md` for the exact new SDK → extension-api edge.
- Create `regelsuche-discovery-sdk/src/test/java/de/regelsuche/sdk/discovery/DiscoveryExtensionCatalogTest.java`.

**Interfaces:**
- Produces `DiscoveryExtensionPoints.DOMAIN_PROVIDERS` with the exact point/type from the spec.
- Produces `DiscoveryDomainCatalog.fromExtensions(ExtensionCatalog)` and `Registration.extensionProvenance(): Optional<DiscoveryExtensionProvenance>`.
- Produces immutable `DiscoveryExtensionProvenance(ExtensionPoint<DiscoveryDomainProvider>, ExtensionDescriptor, ExtensionOrigin, String catalogSha256)`.
- Existing `fromProviders`, `load`, four-component Registration constructor, `artifact()` and `RegelsucheDiscovery.forRegistration` remain usable.

- [x] **Step 1: Write `DiscoveryExtensionCatalogTest` cases.** `retainsInstalledOriginWithoutGrantingProof` asserts all nine extension properties, actual provider artifact and `NOT_EVALUATED` authority/proof semantics. `originChangesEvidenceIdentityButNotCandidate` asserts changed installation/catalog identity changes evidence hash but not candidate/outcome. `rejectsProviderAndDomainCollisionsAcrossContributions` asserts the same duplicate semantics as `fromProviders`. `rejectsIncompatibleApiBeforeDomains` uses a failing `domains()` sentinel. `rejectsReservedOriginOverride` supplies `sdk.provider.extension.trustEvidenceSha256` in a domain payload and expects rejection. `preservesDistinctContributorAndProviderArtifactIdentity` uses distinct valid metadata hashes without asserting a fake cryptographic admission.
- [x] **Step 2: Run these focused tests and retain the actual RED reason.** Missing new API compilation is acceptable initial RED; do not label that a runtime regression. No product implementation before the recorded failure.
- [x] **Step 3: Add the point/provenance model and refactor one shared catalog normalization path.** Preserve provider callbacks' existing API-check-before-domains ordering, deterministic sorting and duplicate rules. Wrap domains with actual artifact observation plus optional immutable extension metadata; inject the nine spec properties under the already reserved prefix. Keep legacy properties unchanged.
- [x] **Step 4: Declare only SDK → extension-api in Maven, Gradle, exact architecture inventory and dependency documentation.** Do not change the extension runtime's inward-only dependency or broaden the inventory assertion.
- [x] **Step 5: Run `DiscoveryExtensionCatalogTest`, `DiscoverySdkTest`, `ProviderProvenanceTest`, and the 13-test `ArchitectureBoundariesTest` with freshly compiled current sources.** Expected: no failures; legacy evidence assertions unchanged. A known local Maven BOM-resolution failure is not a result; use the documented current-source Java25/JUnit harness if still required.
- [x] **Step 6: Commit the complete adapter/dependency slice.** Suggested message: `feat(discovery): consume typed extension catalogs with provenance`.

### Task 2: share existing configured package-session construction

**Files:**
- Create `app/src/main/java/de/regelsuche/plugin/PluginPackageSession.java`.
- Modify `app/src/main/java/de/regelsuche/plugin/PluginPackageCommand.java`.
- Modify `app/src/test/java/de/regelsuche/plugin/PluginPackageCommandTest.java` only where assertions are needed for the extracted boundary.

**Interfaces:**
- Produces package-private `PluginPackageCommand.readConfiguration(Path): Config` using the exact existing reader/parser.
- Produces package-private `PluginPackageSession.open(Config, Map<String,String>)`, `scope(): Scope`, `transactions(): PluginDistributionTransactions`, and `close()`.
- A package-private construction/factory seam can supply an already configured fixture transaction client and owned closeable resource for composed command tests. No public arbitrary-admission capability is added.

- [x] **Step 1: Pin session-construction behavior in focused tests.** Preserve duplicate/coerced/trailing/oversized/symlink configuration rejection, missing credentials and JDBC URL-policy rejection, and the existing unknown recovery result without printing the environment secret or JDBC URI. Add a cleanup assertion if extraction introduces an additional acquisition failure path.
- [x] **Step 2: Record the focused RED for the new session API or resource behavior.** Do not claim unchanged existing tests are new failing regressions.
- [x] **Step 3: Extract only current setup/ownership.** Reuse current scope construction, PostgreSQL connect policy, transport allowlist/deadlines and transactional client parameters. Preserve public records, package actions, outputs and existing exit codes; close owned transport on setup failures and on command completion.
- [x] **Step 4: Run `PluginPackageCommandTest` and relevant existing transaction tests.** Expected: the original output/error contracts pass unchanged; setup failure does not leak resources or credentials. Do not run a new full PostgreSQL/Docker suite unless an actual JDBC behavior changes.
- [x] **Step 5: Commit.** Suggested message: `refactor(plugins): share configured package session ownership`.

### Task 3: run the existing SDK CLI against an explicit catalog

**Files:**
- Modify `regelsuche-discovery-sdk/src/main/java/de/regelsuche/sdk/discovery/cli/DiscoveryCli.java`.
- Modify `regelsuche-discovery-sdk/src/test/java/de/regelsuche/sdk/discovery/DiscoveryCliTest.java`.

**Interfaces:**
- Consumes `DiscoveryDomainCatalog` and its provenance-bearing registrations.
- Produces `DiscoveryCli.execute(String[] args, PrintStream out, DiscoveryDomainCatalog catalog)` for `list` and `run provider-id domain-id@revision campaign-id seed.txt evidence.json [small|tiny]`.
- Existing `execute(String[], PrintStream, ClassLoader)` remains the classpath entry point and shares seed/run/export/outcome code.

- [x] **Step 1: Add tests for explicit-catalog list/run.** Assert an exact provider-ID and domain-revision match is required; a wrong provider ID cannot execute a matching domain belonging to another provider. Assert confirmed output code0, tiny-budget code3, all existing outcome mapping, existing-file protection, malformed UTF-8 and >1MiB seed rejection.
- [x] **Step 2: Run for RED on the absent overload, then implement it.** Keep selection-source differences separate from one common bounded execution/export implementation. Retain the legacy provider-class list/help and classpath loading behavior.
- [x] **Step 3: Run the complete `DiscoveryCliTest` with current SDK/core sources.** Expected: old and new command paths pass, with no overwritten evidence and no duplicate provider selection shortcut.
- [x] **Step 4: Commit.** Suggested message: `feat(discovery): execute CLI requests from explicit catalogs`.

### Task 4: wire the installed App command and prove the composed workflow

**Files:**
- Create `app/src/main/java/de/regelsuche/plugin/InstalledDiscoveryCommand.java`.
- Create `app/src/main/java/de/regelsuche/plugin/InstalledDiscoveryHostTypes.java`.
- Modify `app/src/main/java/de/regelsuche/cli/CliRouter.java`.
- Create `app/src/test/java/de/regelsuche/plugin/InstalledDiscoveryCommandTest.java`.
- Create `app/src/test/java/de/regelsuche/plugin/InstalledDiscoveryFixture.java`; reuse `PluginDistributionFixtures`, `PluginDistributionClientTest.publish` and transaction-ledger fixtures where possible.
- Modify the relevant existing CLI routing test if present; otherwise create `app/src/test/java/de/regelsuche/cli/InstalledDiscoveryRoutingTest.java`.
- Update `docs/java-discovery-sdk.md`, `docs/generic-extensions.md`, `docs/plugin-distribution-client.md`, `docs/plugin-checkpoint-transactions.md` and the relevant README capability/entry paragraph. Keep remaining Workbench/v1/data-pack migration boundaries explicit.

**Interfaces:**
- Consumes Task1 point/catalog/provenance, Task2 configured session, Task3 explicit-catalog CLI.
- Produces public `InstalledDiscoveryCommand.run(String[] args, PrintStream out, Map<String,String> environment)`; its arguments start with `--config`, then path, then list/run arguments after `CliRouter` removes `installed`.
- Produces one immutable, explicitly enumerated host-type set containing exactly the spec's Discovery API tokens. No reflective/package blanket export.
- The package-private command test seam replaces external session construction only; production routing uses the real configuration factory.

- [x] **Step 1: Write the real external provider fixture and first end-to-end test.** Compile Java25 SPI v2 plugin/domain-provider bytes absent from the host classpath; use the real domain builder and a resource read during domain execution/evidence adaptation. Sign/install over loopback HTTPS through current transaction fixtures. Disable artifact transport. Invoke installed command logic for list and for a bounded search reaching a real candidate/certificate; assert codes, exported canonical evidence and one authority read per invocation.
- [x] **Step 2: Record genuine RED before the new host command/route exists.** Reuse focused harness dependencies without packaging fixture provider classes on the host classpath.
- [x] **Step 3: Implement exact routing, prefix parsing, session/runtime nesting and installed-origin checks.** Obtain installation metadata only from that runtime; validate every relevant origin's EXTERNAL kind and exact selected-installation trust hash before provider catalog callbacks. Keep runtime open through complete evidence writing. Report operational failures as code1 with a stable category/class, no config/JDBC/password/nested-driver output. Use `InstalledDiscoveryHostTypes` with explicit class constants and the existing bridge defaults for SPI/core.
- [x] **Step 4: Add composed boundary tests.** (a) Contributor JAR A returns provider in admitted dependency B: assert distinct observed-provider and contributor hashes and one shared installation hash. (b) Authority update/removal/unavailability between commands: new current snapshot or explicit failure, no stale successful evidence. (c) Corrupt selected cache rejected before provider initialization. (d) Forbidden App type access remains rejected. (e) Provider failure and output-file collision release runtime/session; late JAR-resource access succeeds before close. (f) Tiny budget preserves code3 and origin metadata. (g) Empty genesis vs committed removal are distinguishable in list output. Preserve legacy provider IDs/domain collision behavior from Task1.
- [ ] **Step 5: Exercise actual `CliRouter` dispatch.** Valid new prefix with deliberately invalid explicit config reaches the installed-command error category; legacy `domains list/run` still route to existing classpath behavior. Successful signed-provider tests use only an external-authority construction seam, not a fake catalog/runner/loader.
- [x] **Step 6: Run the focused new command/SDK tests plus existing `InstalledExtensionRuntimeTest`, `PluginPackageCommandTest`, transaction tests and `ArchitectureBoundariesTest`.** All current-source compilation and actual outcomes are retained. Do not rerun unrelated benchmark/browser/database suites locally merely to increase counts.
- [x] **Step 7: Update user documentation with the two exact commands, package-config reuse, provider-ID selection, installed evidence fields and Java trust/lifetime semantics.** State that this is a source capability until an appropriate release is actually published; do not imply a previous SDK ZIP gained new classes.
- [x] **Step 8: Commit this coherent host integration.** Suggested message: `feat(app): run installed discovery providers from the domains command`.

## Final qualification and publication

- [x] Review the whole branch against the spec, including the five Review Focus cases, current tree and exact changed files.
- [x] Independent reviewer inspects the implementation and actual focused test evidence; fix demonstrated issues without widening unrelated scope or weakening gates.
- [x] Verify the final diff is clean and branch is based on qualified main; reconcile any new upstream changes explicitly.
- [x] Publish one PR for this installed-Discovery package and allow its normal canonical CI to run once on the complete reviewed head. Qualify that exact head before Ready; no manual duplicate workflow or unchanged-code retry.
- [x] Record actual CI, review and integration state. Respect effective GitHub requirements; do not claim a merge/deployment or all remaining extension migrations complete unless actually observed.

## Plan self-review

Spec coverage: typed point/origin and collision rules → Task1; configured authority/security reuse → Task2; shared bounded CLI behavior → Task3; real App entry point, exact host types, one snapshot/lifetime and signed-JAR acceptance → Task4. All five Review Focus cases have explicit tests. No task assumes a public trust DTO grants authority, no caller callback is described as hard-bounded, and no source-only test is described as canonical CI. The plan deliberately omits Workbench/v1/data-pack/catalog refresh work rather than making this first real host integration depend on every later migration.
