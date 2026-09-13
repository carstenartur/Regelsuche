# Authenticated plugin distribution client

`PluginDistributionClient` implements a Java client for one managed Java-plugin
JAR dependency closure. It retrieves an explicitly selected immutable catalog
revision, authenticates trust state and the index, verifies downloaded artifacts
and publisher provenance assertions, and installs a complete immutable generation.
Updates, removal and retained rollback use the same external activation boundary.

This is a client library in `de.regelsuche.plugin`, not a hosted catalog or a new
CLI command. It does not load plugin classes, run package hooks, import rules,
prove build reproducibility, provide cross-client transparency, or publish external
examples. Rule packages and knowledge packs remain discoverable by the existing
index/resolver but are rejected by this network installer. Existing index,
resolution and preload contracts retain their existing meanings and policies.

## Operator inputs and trusted state

The operator supplies all of these independently of the downloaded payloads:

- a pinned `PluginTrustStore` containing the trust-authority root keys;
- the expected trust-domain identifier;
- an explicit allowlist of HTTPS origins and transport deadlines;
- finite metadata, artifact, total-byte, artifact-count and history-traversal budgets;
- a private POSIX installation directory on a filesystem supporting atomic directory
  rename and file/directory `fsync`;
- a `PluginCheckpointAuthority` backed by trusted state outside the rollback boundary
  of the local package directory.

**There is no production checkpoint-authority implementation or permissive default.**
An ordinary JSON file, an atomic local-file rename, a signed local checkpoint,
process memory, or a checksum stored beside the cache does not provide rollback
protection. The interface is an explicit deployment dependency. If its protection
model is unavailable, a deployment must not construct the client with a substitute.

The authority owns a single pair:

| Field | Meaning |
| --- | --- |
| `installationHash` | Hash of the selected immutable generation evidence |
| `checkpoint` | Last accepted trust-domain, sequence and trust-revision hash |

The pair is changed through one durable, linearizable compare-and-set. Its provider
must authenticate callers, isolate the correct installation/domain, never restore
old accepted state, and provide bounded read/commit operations. An absent or failed
read must throw, never silently return genesis. A false return **or exception** from
`compareAndSet` must mean that call did not commit. A remote provider must reconcile
an ambiguous timeout using its transaction/idempotency mechanism before returning;
a provider that cannot satisfy this contract cannot safely implement the interface.
The Java client cannot independently prove these external storage guarantees.

The authority's generation hash authenticates every retained file, the previous
generation reference and the trust checkpoint. Local directory rollback, modified
metadata, missing files, wrong pinned roots and symlinks therefore fail closed when
the client reads active state. Every `active()` and `readArtifact()` call consults
the authority. There is no local `active` pointer with weaker authority.

## Retrieval and verification

`Sources` pins index ID, revision and content hash alongside the four metadata
URIs. The client does not infer a newer catalog from an arbitrary signed response.
An operator or a future catalog-discovery service must select that immutable source
revision. The existing index has no globally monotonic index sequence; this API
makes the selected revision explicit instead of claiming freshness it cannot prove.

One install/update performs the following chain:

1. Read the authoritative generation/checkpoint and validate any existing generation.
2. Fetch working trust state and its signed revision. Reuse the existing root-pinned
   `PluginTrustStoreRevisionVerifier` with the authoritative previous checkpoint.
   Genesis, immediate-successor sequence, predecessor hash, domain, root-key status,
   store hash and Ed25519 signature checks all remain required. Replayed revisions,
   forks and gaps are rejected. **Every install/update consumes an immediate successor
   trust revision**, even if its working trust-store contents are unchanged.
3. Fetch index and detached signature, reject ambiguous JSON, and use the existing
   verifier to obtain a `VerifiedIndex`. Match the explicit source revision pin.
4. Resolve the request and its dependency closure with the existing deterministic
   resolver. Reject incompatible/unresolved or oversized closures and unsupported
   artifact kinds before downloading their artifacts.
5. Download every selected artifact, signature manifest and provenance envelope from
   its index-bound HTTPS URI. Verify through `PluginArtifactVerifier.snapshot` under
   `REQUIRE_VERIFIED`, and additionally match the exact index hash and publisher.
   Store the returned verified snapshot bytes, never recopy an unverified source.
6. Require a valid publisher provenance assertion for the exact index entry and
   artifact. Write the complete canonical evidence and retained files into a private
   generation; force file contents and directory entries, and atomically rename it.
7. Compare-and-set the authoritative pair as the final activation step.

Transport always requests identity encoding, accepts only HTTP 200, rejects encoded
responses, and follows **zero redirects**. Every comma-separated coding in every
`Content-Encoding` header must be `identity` (case-insensitive). The allowlist binds
scheme, host and port, with TLS certificate/hostname verification against the JVM roots or explicit
operator-supplied TLS roots. The response subscriber enforces the byte cap for both
known and chunked lengths; the total response deadline includes a body stalled after
headers. Cancellation interrupts timed-out requests. Credentials, fragments, local
files and origins outside the allowlist are rejected. Proxy/DNS policy belongs to
the operator's network environment.

Transport alone has an explicit opt-in for literal loopback HTTP (`127.0.0.1` or
`::1`) to support local protocol tests. Package installation still requires HTTPS,
and the existing artifact-index URI contract is unchanged. The committed loopback
TLS keystore is public **test-only material**, not an operator trust root.

A transaction allows at most `4 + 3 * maximumArtifacts` requests, with both a
per-response deadline and byte limit. `totalBytes` bounds downloaded bytes and,
separately, all retained generation file bytes, including `installation.json`
exactly once on install and load. The manifest's actual stored byte length counts
on load. `metadataBytes` independently bounds generated metadata and installation
evidence. `maximumHistory` bounds retained ancestry traversal. These are payload
bounds, not an exact JVM heap-usage estimate. Historical
generations accumulate until an operator manages retention; removal is logical and
does not reclaim rollback history. Disk quotas remain operator policy.

## Lifecycle and failure behavior

| Operation | Package state | Trust state |
| --- | --- | --- |
| `install(sources, request)` | Atomically installs/replaces the whole resolved closure | Requires genesis or the next authenticated revision |
| `remove()` | Selects a new empty generation, retaining previous generations | Keeps the current checkpoint and working store |
| `rollback(generationHash)` | Reproduces a retained ancestor's closure and creates a new generation | Rechecks index, artifact and provenance signatures under the current working store; never rolls trust back |
| `readArtifact(identityHash)` | Returns the exact retained snapshot bound to active evidence | Requires a successful authority read and matching retained hashes |

Rollback targets must be reachable through the bounded previous-generation chain.
Unrelated staged generations cannot become rollback targets. Current revocations
and curator/publisher key status are applied again; an older generation is not
permission to restore a revoked artifact. An explicitly selected older index in a
new install request remains an operator choice, not an automatic rollback. The
client treats a root-directory slot as one closure; removing individual dependencies
or coordinating several independently managed closures is outside this API.

Download, parse, resolution, signature, provenance, size, filesystem and pre-commit
failures do not change the authoritative pair. Staging cleanup is best effort.
A failure after generation rename but before authority commit can leave an inactive
immutable generation. Retrying the same operation verifies that generation and
forces its directory entries before attempting activation. The client never falls
back from `ATOMIC_MOVE` or directory syncing to a weaker installation method.

Concurrent operations may stage simultaneously. Only the compare-and-set that still
matches the complete previously read pair can win. A stale update cannot overwrite
a concurrent removal, trust successor or rollback. A rejected operation itself
changes no active state; another concurrent successful operation can of course
change it. A process crash before commit leaves the prior generation selected; a
crash after a successful durable commit leaves the fully prepared generation selected.
This relies on the filesystem's advertised sync/rename semantics and the external
authority contract, not on a simulated guarantee of power-loss-resistant storage.

The installation root and its ancestors must remain under trusted operator control.
The root and generation container require POSIX mode `0700`; staging directories are
created with that mode. Symlink path components and non-regular retained files are
rejected, and file reads use `NOFOLLOW_LINKS`. A privileged attacker or another process
with the same credentials can deny service or race filesystem operations; this is
not an isolation boundary against the installation owner. Use `readArtifact()` for
verified byte handoff. Passing a cache path directly to an unrelated loader does not
inherit this API's snapshot guarantees. Runtime loading and activation validation
still require their own existing preload/runtime gates and are not attempted here.

## Evidence and provenance formats

Each generation is stored at `generations/<installation-content-hash-hex>/`.
`installation.json` uses `regelsuche.plugin-installation/v1`, with operation,
previous/rollback-source generation hashes, current checkpoint, pinned-root and
working-store hashes, source index identities, resolution identity, artifact
identities and hashes of all retained files. The file map binds the working store,
trust revision/verification/checkpoint, original source index/signature, current
index/artifact verification evidence, resolver receipt, provenance and JAR snapshots.
Original admission history remains reachable through previous/rollback references.

`regelsuche.plugin-distribution-retrieval/v1` records the requested source pin and
exact wire hashes/byte counts for successful network fetches. Remove/rollback receipts
state `NOT_PERFORMED`; rollback identifies the retained source generation. The old
resolver receipt still says its own resolution did not perform network, trust or
installation. Those claims are intentionally not rewritten.

New distribution JSON serializations use lexicographically sorted object-property
names, compact Jackson JSON, UTF-8 and one terminal LF. Array order is retained;
artifacts use dependency-first resolution order. The installation content hash is
SHA-256 of this serialization after removing its top-level `contentHash` field,
including the terminal LF. Each retained file hash covers its exact stored bytes.
Existing embedded contract files keep their original canonical encodings.

`regelsuche.plugin-artifact-provenance/v1` is a new, strict Ed25519 envelope. Its
signed fields, in this exact order, are:

1. `schema`
2. `artifactIdentityHash`
3. `artifactSha256`
4. `provenanceUri`
5. `sourceUri`
6. `sourceRevision`
7. `sourceSha256`
8. `buildRecipeSha256`
9. `publisherId`
10. `keyId`
11. `algorithm`

Each UTF-8 field name and value is preceded by its unsigned 32-bit big-endian byte
length. `signatureBase64` is canonical padded Base64 for exactly 64 signature bytes
and is excluded from the signed payload. Publisher keys come exclusively from the
authenticated working trust store, never from the envelope. Its provenance URI,
entry identity, artifact hash and publisher must match the verified index entry.

This authenticates a publisher's source/build assertion. The client does **not** fetch
or rebuild the source, validate the build recipe, or establish an independently
verified source-to-binary relationship. Old index entries only bind a provenance
URI and acquire no stronger claim retrospectively. Installation requires the new
matching signed envelope; an ordinary old source link is insufficient. The signed
index binds its own entry and URI, not the content hash of a specific provenance
assertion: the retained receipt identifies the exact signed assertion used.

The strict schemas are in `docs/schemas/regelsuche-plugin-{artifact-provenance,
installation,distribution-retrieval}-v1.schema.json`. Parsers also reject duplicate
keys, trailing tokens, unknown properties and content-hash mismatches where present.

## Java integration example

The embedding operator obtains `checkpointAuthority`, `pinnedAuthorityRoots`, and
`selectedSources` through its own trusted configuration. It must provide the real
external authority described above; there is no copy-paste local fallback.

```java
try (var transport = new PluginDistributionTransport(
        Set.of(URI.create("https://catalog.example.org"),
               URI.create("https://artifacts.example.org")),
        Duration.ofSeconds(5), Duration.ofSeconds(30), false, null)) {
    var client = new PluginDistributionClient(
        Path.of("/var/lib/regelsuche/package-slot"), "community",
        pinnedAuthorityRoots, checkpointAuthority, transport,
        new PluginDistributionClient.Limits(
            2 * 1024 * 1024, 32 * 1024 * 1024, 128 * 1024 * 1024, 32, 128));
    var request = PluginArtifactResolver.ResolutionRequest.exact(
        "operator-install-1", PluginArtifactIndex.ArtifactKind.JAVA_PLUGIN,
        "algebra", "1.2.0", "0.5.0", "1", List.of("algebra"));
    PluginInstallationEvidence installed = client.install(selectedSources, request);
    byte[] admittedJar = client.readArtifact(installed.artifacts().getFirst().identityHash());
    // Pass the snapshot through the embedding runtime's separate admission boundary.
}
```

The parent of the installation directory must already exist. A working-store change
is accepted transactionally with a successful install/update; this slice does not
implement an independent background revocation feed, automatic refresh or expiry
policy. The external authority and roots must not be reset to work around missing
cache files or rejected updates. Recovery must reconcile the trusted selected
hash/checkpoint and restore the matching retained generation.

## Verification and remaining issue #104 work

JUnit tests run real loopback HTTP/HTTPS retrieval with separate generated Ed25519
root, curator and publisher keys. They cover chunked limits, stalled bodies,
redirect rejection, origin policy, exact dependency closure, all lifecycle actions,
restart, corrupted/incorrectly bound signatures, revoked rollback, replay/fork/gap,
wrong index pins, private filesystem/symlink mutation, unavailable authority and
concurrent stale commit rejection. Canonical object ordering and installation-hash
reconstruction are checked independently of the installation hash helper.

Run the client-focused tests with Java 25:

```sh
mvn -pl app -am -Dtest=PluginDistributionClientTest,PluginDistributionTransportTest,PluginArtifactProvenanceTest,PluginDistributionCanonicalJsonTest -Dsurefire.failIfNoSpecifiedTests=false test
```

They are normal application tests and run in the existing Gradle `ciCheck` authority;
there is no separate production npm or Python orchestrator. Issue #104 remains open
for a real hosted/federated catalog, a deployed externally protected checkpoint
provider, independent source-to-binary verification, runtime integration, separately
published community examples and public service operations.
