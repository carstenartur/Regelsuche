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
The older application plugin and Discovery paths are not migrated by this first
foundation slice. This is not a claim that both extension systems should remain.

## Catalog identity

Catalog metadata is normalized deterministically and hashed as UTF-8 JSON.
Malformed UTF-16 surrogate sequences are rejected instead of being silently
replaced during encoding. Valid supplementary characters are preserved; the
Unicode contract test pins an independently calculated exact manifest/hash pair.
A catalog hash describes the metadata and declared provenance, not the mutable
state of arbitrary Java implementation objects or proof of mathematical validity.

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
Cryptographic admission implementation and application/Discovery migration remain
subsequent architecture slices.
