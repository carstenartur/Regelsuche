# Installed extensions in the existing Discovery CLI

## Intent and current state

Enable a real user workflow to list and run Discovery domains contributed by an authenticated installed SPI v2 plugin. Use the existing App `domains` command, SDK domain contract, bounded runner and canonical evidence export. This is the next integration slice after PR #1167; an activated test `Runnable` alone did not make a user-facing Discovery command consume installed extensions.

Baseline: accepted main `d191fc8abb998c14ea7bc5965401eaf696e8fc2a`, whose canonical CI `42825000269` succeeded. This document is a design, not an implementation or test result.

Current source establishes these seams:

- `CliRouter` already dispatches `domains` to `DiscoveryCli.execute(args, out, ClassLoader)`.
- `DiscoveryCli` already supports provider selection, bounded UTF-8 seed input, named budgets, outcome exit codes and non-overwriting evidence export.
- `DiscoveryDomainCatalog.fromProviders` normalizes providers and domains, rejects duplicate provider IDs and domain revisions, checks Discovery API compatibility before `domains()`, and attaches host-observed provider JAR/directory identity.
- `ProviderDomain` reserves every `sdk.provider.*` evidence property and rechecks its observed code artifact before execution/evidence adaptation.
- `InstalledExtensionRuntime` selects one current authority state and activates its complete admitted closure. Its `installation()` metadata and catalog remain a single frozen activation snapshot; a new open observes updates/removal. It owns temporary staged JARs until close.
- `PluginPackageCommand` already parses a strict bounded `regelsuche.plugin-package-config/v1` document and creates the PostgreSQL authority, transport and transactional distribution client. Those configuration/security rules must be reused rather than duplicated.

## Chosen approach and alternatives

The implementation choice was made by the engineering team under the user's continuing instruction to close the remaining gaps; this is not described as a separately user-approved design.

1. **Selected: add an installed source to the existing `domains` workflow.** Introduce the already proposed standard `discovery.domain-provider` extension point, adapt the admitted catalog to the existing domain catalog, and run the same SDK command engine. It delivers a complete executable user path without changing domain algorithms or starting another application.
2. Replace every legacy provider/PluginRuntime path immediately. This touches unrelated transformation commands, Workbench and v1 consumers and would make this package substantially larger. The current slice preserves those entry points.
3. Add only another embedded adapter method. This would be smaller but would leave the identified missing application integration unresolved.

There is no new module, AST, execution engine, marketplace, signing authority or trust-on-first-use mechanism.

## User command and observable behavior

The App accepts:

```text
domains installed --config /absolute/package-config.json list
domains installed --config /absolute/package-config.json run provider-id domain-id@revision campaign-id seed.txt evidence.json [small|tiny]
```

`installed`, `--config`, and its path form an exact prefix. Unknown/duplicate/misplaced options, wrong argument counts, invalid exact domain selectors and unknown budgets are rejected. `small` is the existing default; `tiny` retains its existing documented numeric bounds. No automatic install, latest-version selection or classpath fallback occurs.

The configuration is the same v1 document used by `plugins package`. `sources` and `selection` may be null for activation because they authorize installation requests, not current activation. A signed installed closure must still have been resolved for SPI `2` and host core `1.0.0`, as required by the existing bridge.

`list` prints the selected installation hash (or an explicit no-installation indication), then deterministic entries containing provider ID/version, exact domain ID/revision, contribution ID and the admitted contributor artifact/trust hashes. A committed empty installation remains distinguishable from pre-genesis absence. Both can list successfully with zero domains; `run` cannot select a missing domain.

`run` selects both provider ID and exact `domain-id@revision`, performs the existing bounded Discovery execution, writes the original canonical evidence using `CREATE_NEW`, and prints the existing outcome and evidence hash. Configuration/authority/activation/selection/input/export failures return `1`. Semantic outcomes retain the SDK codes: `CONFIRMED=0`, `REFUTED=2`, `BUDGET_EXHAUSTED=3`, `INCONCLUSIVE=4`, `UNSUPPORTED=5`, `INVALID_SEED=6`. In particular an unavailable authority must not masquerade as semantic `REFUTED`.

Legacy `domains list [provider.class]` and `domains run provider.class ...` remain compatible and explicit. Existing plugin package commands retain their existing output/exit-code contracts.

## SDK contract and provenance

Add `DiscoveryExtensionPoints.DOMAIN_PROVIDERS` in `regelsuche-discovery-sdk`:

```java
public static final ExtensionPoint<DiscoveryDomainProvider> DOMAIN_PROVIDERS =
    ExtensionPoint.of("discovery.domain-provider", DiscoveryDomainProvider.class);
```

The SDK gains a direct API dependency on `regelsuche-extension-api`, declared in both Maven and Gradle and in the exact architecture inventory. The extension API/runtime gain no Discovery or App dependency.

Add `DiscoveryDomainCatalog.fromExtensions(ExtensionCatalog catalog)`. It consumes only registrations for that exact point and class token. Share normalization and catalog construction with `fromProviders` so the same provider-ID, API-revision, domain-revision, sorting and duplicate rejection rules remain effective. Two contributions with distinct contribution IDs do not get to evade duplicate provider IDs or duplicate domain revisions.

Add immutable `DiscoveryExtensionProvenance(ExtensionPoint<DiscoveryDomainProvider> point, ExtensionDescriptor descriptor, ExtensionOrigin origin, String catalogSha256)`, with nonnull validated inputs, the exact standard point, and a SHA-256 catalog identity. Add `Registration.extensionProvenance()` returning `Optional<DiscoveryExtensionProvenance>` without changing the existing four record components/constructor. Existing `artifact()` continues to report the host-observed artifact of the actual `DiscoveryDomainProvider` implementation class.

The adapter is metadata composition, not an admission authority: arbitrary manually constructed `ExtensionCatalog` or provenance records do not become authenticated by passing them to the SDK. The App obtains its catalog only from the installed runtime and checks the selected-installation relationship before calling any Discovery provider's `domains()`.

Retain extension identity in protected evidence properties:

```text
sdk.provider.extension.pointId
sdk.provider.extension.contributionId
sdk.provider.extension.sourceKind
sdk.provider.extension.sourceId
sdk.provider.extension.sourceVersion
sdk.provider.extension.sourceReference
sdk.provider.extension.artifactSha256
sdk.provider.extension.trustEvidenceSha256
sdk.provider.extension.catalogSha256
```

Use the existing `sdk.provider.*` reservation for these properties. A domain cannot replace them through its own evidence adapter. Provider-supplied `provenance()` remains a separate declared reference and cannot override observed origin fields. Existing classpath-provider evidence and its canonical bytes remain unchanged.

The generic contribution origin names the JAR defining the contributing `RegelsuchePlugin`. The Discovery provider object may be implemented by another admitted dependency JAR. Preserve both identities honestly: existing `sdk.provider.artifactSha256` is the actual domain-provider implementation artifact; `sdk.provider.extension.artifactSha256` is the contributor origin supplied by the runtime. They need not be equal. The installation trust hash binds the complete admitted closure. A two-artifact regression must pin this distinction.

Cryptographic admission is not mathematical authority. Preserve `sdk.provider.authority=NOT_EVALUATED`, the runner's existing proof-status semantics, domain budgets and outcome semantics. No provider callback receives a stronger mathematical status because its JAR is signed.

## Application composition and lifetime

Add package-private `PluginPackageSession implements AutoCloseable` in `de.regelsuche.plugin` to own the existing configuration-created distribution transport and transactional client. It exposes its immutable `Scope` and `PluginDistributionTransactions` to the two App command implementations. `open(PluginPackageCommand.Config, Map<String,String>)` uses the current PostgreSQL factory/TLS/root/scope/deadline policy unchanged; `close()` closes its owned transport. Preserve the existing public nested Config/Authority/Selection records. Extract a package-private `PluginPackageCommand.readConfiguration(Path)` using the existing strict JSON/no-follow/262144-byte reader; do not create a second parser.

`PluginPackageCommand.run` uses this session but otherwise retains its actions, outputs, secret redaction and operation semantics. A narrow package-private factory seam may replace only external session construction in command tests; the normal public entry point always uses the production factory. It must not expose an arbitrary trusted snapshot or artifact admission callback publicly.

Add `InstalledDiscoveryCommand.run(String[] args, PrintStream out, Map<String,String> environment)`. `CliRouter` sends `domains installed ...` to it; all other `domains` arguments still go to the existing SDK CLI. The command:

1. validates its exact command prefix/shape and explicit configuration;
2. opens one package session;
3. opens exactly one `InstalledExtensionRuntime` with the fixed Discovery host-type set;
4. checks every relevant contribution is external, has a nonempty artifact hash, and has a trust-evidence hash equal to this runtime's selected installation hash;
5. constructs the SDK catalog and runs the requested list or execution operation;
6. completes evidence serialization/export while the runtime is still open;
7. closes the runtime before closing the package session, including every exceptional path.

Do not call `active()` or reopen the runtime for display/selection: obtain installation metadata from the same runtime. A whole command makes one current-authority selection read. Do not authorize activation using a previous `recover()` result or operation receipt. No provider/domain/run object escapes the command after close.

Add a catalog-based `DiscoveryCli.execute(String[] args, PrintStream out, DiscoveryDomainCatalog catalog)` overload. Its `run` selector is a provider ID because installed service class names are not user selection keys. Factor shared bounded seed reading, execution, evidence writing and outcome mapping so the old classpath route does not acquire a second implementation of those behaviors. The new overload must verify the exact provider ID as well as the exact domain revision.

The new command catches configuration/authority/admission failures and reports a stable failure category plus exception class, not the config, JDBC URI, environment password, downloaded body or nested driver message. It also converts provider linkage/service loading failures into a nonzero command result without a stale fallback. Do not catch unrelated VM-fatal errors as normal domain outcomes.

## Exact host exports

Keep host export policy in the App, in package-private `InstalledDiscoveryHostTypes`, as an immutable set of actual `Class<?>` tokens. Export:

- `DiscoveryExtensionPoints`, `DiscoveryDomainProvider`, `DiscoveryApi`, `DiscoveryDomainBuilder`;
- `DiscoveryDomain`, `DiscoveryDomainDescriptor`;
- the public nested contracts/values of `DiscoveryDomain`: `IdentifiedComponent`, `StateGenerator`, `CanonicalCodec`, `Invariant`, `TransitionOperator`, `Objective`, `CandidateExtractor`, `CounterexampleGenerator`, `CandidateEvaluator`, `CertificateRenderer`, `EvidenceAdapter`, `DiscoverySeed`, `DiscoveryBudget`, `InvariantResult`, `Successor`, `ObjectiveAssessment`, `PathStep`, `CandidateContext`, `CounterexampleStatus`, `CounterexampleResult`, `EvaluationStatus`, `Evaluation`, `CertificateRendering`, `RenderedCertificate`, `DomainPayload`.

Spell out these tokens; do not export an entire package, follow arbitrary dependency/reflection closure, or export App classes, configuration/credentials, the runner or runtime implementation. Existing automatic Java platform and generic extension API exports remain owned by the generic runtime. The provider fixture must use the real builder/contract and must be absent from the host classpath, so missing required exports cannot be masked by host-loaded test implementations.

Authenticated Java retains process privileges. This classloader/type boundary is not a sandbox. The command does not promise hard CPU/memory interruption of arbitrary plugin callbacks, background authority monitoring or revocation of previously handed-out Java references.

## Acceptance and review risks

The feature is accepted only with the following composed evidence:

1. Compile a real external SPI v2 plugin/provider, sign and install it using the existing HTTPS distribution fixture and transaction authority, then remove artifact transport access. Execute the installed App command logic to list the domain and run a nontrivial bounded search that reaches a confirmed candidate/certificate and reads a JAR resource during the run. The provider is not present on the host classpath.
2. Assert one authority selection per command and that run/evidence output binds the same installation, catalog, contribution and observed provider artifact. Repeat with a two-artifact admitted closure where contributor and provider implementation hashes differ.
3. A tiny-budget run returns `3` and retains normal budget-exhaustion evidence plus the same host origin fields. Output files are never overwritten; malformed UTF-8 and >1 MiB seeds fail. Unknown provider IDs cannot select another provider's otherwise matching domain.
4. Reopen after update/removal and unavailable authority: subsequent commands see the current state, removal cannot execute the old provider, and authority/cache/signature failure produces no successful evidence or stale fallback. Test at least one selected-cache rejection before the provider initializer marker changes; reuse existing bridge tests for the exhaustive trust parser matrix.
5. SDK tests reject incompatible providers before `domains()`, duplicate provider IDs/domain revisions, and attempted reserved provenance overrides. Preserve old classpath catalog/CLI tests and evidence behavior.
6. Runtime/session cleanup occurs after successful export and on provider/export failure. A real resource read during evidence adaptation proves the runtime was not closed too early. A forbidden App host type stays unavailable to the external provider; no package whitelist is introduced.
7. The real public CLI dispatch recognizes the new prefix and routes configuration errors to the installed-command category, while the legacy path still works. Successful command integration may replace the external PostgreSQL session construction with the existing in-memory authority fixture; it must not replace signed admission, classloading, provider contribution, domain execution or evidence export. Existing PostgreSQL authority tests continue to own JDBC/TLS behavior.

No new remote workflow is needed for planning. Implementation uses focused source/test qualification, then one canonical CI run for this coherent package after source review. Known local Maven dependency-resolution failure is not a test result and should not be repeatedly retried. Do not weaken coverage, complexity or architecture gates.

## Explicit remaining scope

This package makes installed domains executable from the existing headless App command. It does not migrate Workbench/HTTP transformation routes, retire the legacy provider or v1 PluginRuntime APIs, activate data-only rule/knowledge packs, choose/download new catalogs automatically, operate a public marketplace, or establish scientific validity/usability/speedup/novelty from a successful plugin run.
