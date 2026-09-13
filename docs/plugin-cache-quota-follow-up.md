# Plugin cache quota: isolated follow-up to #1004

## Scope and present status

The owner requested that the pre-existing aggregate cache-retention defect be
handled separately from the already large lifecycle PR #1004. That PR was merged
as `3f0a3d8979e6d3f81d415514a969101ef7bf9f39` after its exact-head CI passed.

The defect exists on its predecessor main
`c4772cb86b30659f350936d23da1cc04af4d470c`: `PluginInstallationStore.persist`
retains immutable generations before activation, and
`docs/plugin-distribution-client.md` explicitly leaves accumulated historical
generations and disk quotas to operator policy. Resolving the original review
thread means scope transfer, not that this defect was fixed:
https://github.com/carstenartur/Regelsuche/pull/1004#discussion_r4000397582

**This initial draft adds characterization/protection tests only. It does not
implement a quota, garbage collection or a changed installation contract.**
The tests have not been executed locally in this session: both execution-tool
attempts failed with transport timeouts. Source inspection is not a test result.
The follow-up's own CI results must be inspected before claiming they pass.

## Added executable cases

`PluginCacheRetentionCharacterizationTest` uses the existing signed HTTPS fixture
and actual Java installation/transaction code. The authorities are explicit
failure-injection models, not real database durability tests.

- Three distinct rejected legacy installs retain three inactive generations and
  increasing payload bytes while authoritative state remains empty.
- Three distinct unknown submissions also grow the cache without confirmed active
  state. This cannot be treated as permission to delete possibly committed data.
- A lost acknowledgement after an actual model commit leaves all generation file
  hashes intact across restart, receipt recovery and offline idempotent replay.

These are small finite characterization/protection cases, not evidence that a
quota is enforced. If admission or safe collection changes the retained behavior,
the characterization assertions must be replaced by the explicit quota contract;
they are not a requirement to preserve the defect.

## Proposed correction within this PR

Add an explicit aggregate cache admission policy, distinct from existing
per-download and per-generation `Limits.totalBytes`. Count retained files,
installation metadata and in-flight staging/reservations. Make the policy's byte
and entry limits finite, validated and documented; do not reinterpret old limits
or hashes. Coordinate admission for cooperating clients and processes sharing the
same installation root, including restart and leftover staging. A local quota
must not be presented as protection from a malicious installation owner or as an
exact physical-filesystem-block limit.

Prefer rejecting new writes before exceeding the aggregate budget over adding
an unsafe automatic collector. Keep authority-referenced generations, rollback
history and unresolved-operation evidence intact. Reads, receipt recovery and
idempotent replay of retained generations must remain available when the quota is
full. Newly admitting bytes and validating an already retained generation are
different operations. No authority reset or automatic trust rollback is allowed.

## Required acceptance tests before marking ready

1. Explicit exact-limit and one-byte-over-limit cases; aggregate entry limits;
   zero additional payload for an already retained identical generation.
2. Repeated rejected/unknown operations eventually refuse new admission without
   changing accepted state or exceeding the declared storage contract.
3. Two clients and two processes racing for the last capacity cannot both reserve
   it. Interrupted preparation and process restart cannot lose accounting.
4. Full-cache reads, lost-acknowledgement recovery, offline replay and retained
   rollback evidence remain correct; no possibly committed generation is deleted.
5. Invalid limits, overflow, symlinks, unsupported locking and damaged accounting
   fail closed for new writes without weakening existing receipt validation.
6. Run the focused client/transaction/recovery suites and full exact-head CI.

No symbol-scoring changes, learning policy, benchmark threshold changes, database
schema expansion or release-platform refactoring belong in this follow-up.
