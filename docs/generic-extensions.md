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
checks each provider's code source before instantiation. Library dependencies
must be explicitly included in the admitted artifact set.

A failed reload leaves the exact published catalog and external resources usable.
A successful reload publishes a new catalog but does not invalidate previously
returned snapshots. Published external generations remain alive until the owning
`ExtensionRuntime.close()`. Hosts must finish all uses of contributed objects
before closing the runtime. Rejected candidate resources are cleaned immediately;
closing a runtime also closes retired loaders and removes their private staged
JARs, without deleting caller-owned source files.

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
