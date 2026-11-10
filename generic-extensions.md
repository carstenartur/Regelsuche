# Generic extension foundation

The `regelsuche-extension-api` and `regelsuche-extension-runtime` modules provide
search-independent typed contributions and a headless runtime. A domain defines an
`ExtensionPoint<T>` and consumes `RegisteredExtension<T>` values from an immutable
catalog. Plugins implement `RegelsuchePlugin.descriptor()` and the single
synchronous `contribute(ExtensionContext)` callback. Runtime staging assigns the
origin; a plugin cannot supply its own origin through that callback.

The runnable external example is
[`extension-runtime-java25`](../examples/external-consumers/extension-runtime-java25/README.md).
The complete migration roadmap is in the
[approved architecture](superpowers/specs/2026-09-11-generic-extension-program-discovery-design.md).
The app-owned `InstalledExtensionRuntime` now connects authenticated installed
Java closures to this v2 runtime; see [installed package activation](#installed-package-activation).
Existing App commands, the older application plugin interface, Discovery extension
points and data-only rule/knowledge-pack installation are not migrated by this
adapter. The architecture roadmap still governs their migration.

## Catalog identity

Catalog metadata is normalized deterministically and hashed as UTF-8 JSON.
Malformed UTF-16 surrogate sequences are rejected instead of being silently
replaced during encoding. Valid supplementary characters are preserved; the
Unicode contract test pins an independently calculated exact manifest/hash pair.
A catalog hash describes the metadata and declared provenance, not the mutable
state of arbitrary Java implementation objects or proof of mathematical validity.
Classpath origin includes the provider's CodeSource URL, so moving the same class
bytes to a different absolute directory can change the catalogue identity.
Classpath origins have empty artifact/trust hashes and do not thereby attest
provider bytecode. External-admission origins instead include their admitted
artifact SHA-256 and any trust-evidence hash in the canonical manifest.

## Admission and lifetime

Explicit/classpath plugins are trusted in-process code. External JARs require a
host-supplied `PluginArtifactAdmission`. All selected artifacts are admitted as
immutable byte snapshots before creating the external classloader. The runtime
privately stages and rehashes those bytes, rejects manifest `Class-Path`, and
checks each provider's code source before instantiation. A service descriptor
may name a provider in a different admitted JAR; its contribution origin binds
the artifact that actually defines the provider class. Duplicate provider names
and providers outside the admitted artifact set remain rejected. The external loader also
checks every dependency class it returns, including lazy resolutions after catalog
publication. Non-host classes must be defined by that loader from a privately
staged admitted JAR. A parent-classpath copy is rejected before initialization,
even if another copy was admitted; dependencies cannot silently change version
because of the host classpath. Library dependencies must be explicitly included in
the admitted artifact set. External resources and service descriptors are resolved
only from those artifacts, not inherited from the parent.

External code may share platform types, exactly `StableApi` and `IncubatingApi`
by class identity, and public types in the exact `de.regelsuche.extension` API package.
Other public classes in `de.regelsuche.api` are not automatically exported.
The six-argument `ExtensionRuntimeConfig`
constructor keeps this default. To expose additional host-owned contracts, use the
seven-argument constructor with an immutable `Set<Class<?>> sharedHostTypes`.
Admission compares actual class identity, not a name or package prefix; exporting
one type does not export its siblings or resources. Such types and their host-side
implementations are explicitly trusted host code, not covered by an external JAR's
hash. This is not a claim that the catalog hash attests the transitive code closure.
Classpath/explicit plugins keep their separate trusted in-process behavior.

Numeric compatibility declarations are preserved verbatim for the strict parser.
Only a null plugin minimum-core declaration defaults to `0.0.0`; supplied empty,
whitespace-only, padded or otherwise malformed text is rejected, not repaired.

A failed reload leaves the exact published catalog and external resources usable.
A successful reload publishes a new catalog but does not invalidate previously
returned snapshots. Published external generations remain alive until the owning
`ExtensionRuntime.close()`. Hosts must finish all uses of contributed objects
before closing the runtime. Rejected candidate resources are cleaned immediately;
closing a runtime also closes retired loaders and removes their private staged
JARs, without deleting caller-owned source files.

A plugin callback may read the previously published catalog, but must not call
`reload()` or `close()` recursively on the same runtime during a reload. Those
reentrant mutations throw `IllegalStateException` before changing lifecycle state.
An uncaught callback failure rejects the outer candidate and retains the published
catalog; catching a rejected mutation does not itself invalidate the outer build.
Ordinary later reloads and idempotent close remain available.

This lifetime rule intentionally corrects the initial plan's immediate-close
step: an immutable catalog can outlive the `catalog()` call, and its objects may
load classes or resources lazily. Immediate close after a successful swap was
therefore unsafe. Retaining generations has an explicit cost: resources grow with
successful external reloads until close. Scope runtimes to a finite host unit of
work; this foundation does not yet provide reference-counted catalog leases for
indefinitely running hot-reload hosts.

Admission is a provenance/trust boundary, not a sandbox for hostile Java code.

## Installed package activation

Hosts already using the app's authenticated distribution client can call
`InstalledExtensionRuntime.open(client)` or `open(transactions)` from
`de.regelsuche.plugin`. This adapter acquires one immutable verified installed
closure and supplies the generic runtime's admission callback. It is app-owned;
the headless runtime retains its dependency direction and does not acquire an app,
transport or database dependency. Standalone hosts may continue providing their
own explicit `PluginArtifactAdmission`.

The adapter selects the external authority exactly once, revalidates the selected
generation's retained index, dependency resolution, artifact signatures and
provenance under its authenticated working store, then admits copied bytes.
Activation performs no catalog/artifact download; the external authority must
still be reachable. In a concurrent update, the selected generation supplies all
metadata and JARs. Historical committed transaction receipts cannot select an old
generation, and authority or verification failures do not open a stale catalog.

Before provider loading, the signed closure must declare API `2`, be resolved for
the exact host core compatibility version, and satisfy every signed entry's core
bounds. `InstalledExtensionRuntime.Options` defaults to
`ExtensionApi.CORE_COMPATIBILITY_VERSION` and no additional host exports. Its
`sharedHostTypes` option uses the exact class-identity export contract above.
Classpath discovery is disabled, and the adapter exposes no unmanaged path or
caller-asserted trust shortcut. Descriptor checks still run in the generic runtime;
descriptors are Java code and become available only after provider initialization.

Each external contribution's origin contains its defining artifact hash, the
selected installation hash as trust evidence, and a stable
`installed:<installationHash>/<artifactIdentityHash>` source reference. The catalog
hash therefore binds this admission metadata. Authenticated provenance does not
prove mathematical correctness or sandbox executable Java.

Use one installed runtime per bounded host unit of work. Finish contribution use
before `close()`, then open a new runtime to observe updates, removal, rollback or
newly accepted trust state. This adapter has no reload method; it does not change
the generic runtime's reload semantics described above. An empty genesis and a
committed removal both yield empty catalogs, but only the latter has installation
evidence. Already returned Java objects cannot be revoked instantly by closing a
runtime or changing the authority. Background trust refresh and automatic migration
of the running Workbench are not provided.

The [distribution client guide](plugin-distribution-client.md#activating-installed-extension-spi-v2)
contains the complete verification boundary, lifecycle and Java integration
example. The independent v2 greeting example remains a trusted-classpath consumer;
it does not itself demonstrate authenticated installed-package activation.
