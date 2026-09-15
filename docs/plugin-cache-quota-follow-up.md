# Aggregate plugin-cache admission

This is the isolated correction for the pre-existing retention defect from
#1004, merged as `3f0a3d8979e6d3f81d415514a969101ef7bf9f39`.
It does not delete generations or change authority, trust, or installation hashes.

## Configuration and accounting

Every new staging directory and payload write now passes a shared aggregate
admission check. Unconfigured caches adopt a persistent default of **1 GiB of
logical payload and 65,536 file/directory entries** before their first new write.
Existing over-budget caches refuse new admission rather than losing old evidence.
An operator can explicitly provision a different positive budget before use:

```java
new PluginCacheQuota(256L * 1024 * 1024, 16_384).configure(packagesDirectory);
```

All clients opening that directory use its canonical `.cache-quota` policy.
Reprovisioning the same values is idempotent; changing established values is
rejected and requires offline operator reconciliation. Old per-download and
per-generation `Limits` retain their original meanings and checks.

The quota counts retained generation metadata, artifacts, in-flight stages,
leftovers from interrupted processes, nested directories and zero-byte files.
The only exclusions are fixed control overhead: the root, its `generations`
container, the policy file (at most 128 bytes), and the zero-byte lock file.
This is a logical-size/entry bound, not a physical filesystem-block guarantee.
Transient verification/persistence copies also consume capacity; admission is
conservative and can reject an operation whose final generation alone would fit.

A bounded set of JVM locks and an operating-system file lock serialize cooperating
writers of the same root. The check and actual write occur under that lock; no
stale counter or releasable reservation can be lost across process death. Writes
are refused before their payload bytes or directories would exceed the budget.
Locking errors, malformed/noncanonical policies, overflowed values and symbolic
links fail closed. This requires the existing POSIX private-directory environment
and a filesystem providing working exclusive file locks; there is no unlocked
fallback. An owner modifying files outside this protocol is outside its scope.

## Recovery and retention

No rejected or `OUTCOME_UNKNOWN` generation is automatically collected. A lost
acknowledgement does not establish that a commit failed. Active generation reads,
receipt recovery, offline operation replay, and revalidation of an already
retained identical generation remain available when capacity is full. Retained
rollback evidence remains intact; a *new* rollback generation still needs room.
Successful staging cleanup frees capacity. Unclean shutdown leftovers remain
charged and may be removed by the operator only after reconciliation.

## Tests

`PluginCacheQuotaTest` checks exact/over-limit bytes, nested and empty entries,
last-capacity races between two threads and two real JVM processes, process death
without cleanup, restart, default provisioning, and corrupt/symlink controls.
`PluginCacheQuotaIntegrationTest` uses real signed HTTPS retrieval and the original
Java preparation paths. Repeated rejected and unknown submissions eventually
stop before authority submission; full-cache lost-ack recovery, offline replay,
artifact bytes and immutable generation hashes remain intact. Its authority is
an explicit failure-injection model, not a substitute for PostgreSQL tests.

The initial characterization cases remain useful checks below the default quota,
not a requirement to preserve unlimited growth. Complete source-head CI remains
the product qualification, including the existing client, recovery and SQL tests.
