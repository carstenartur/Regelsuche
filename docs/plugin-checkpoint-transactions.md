# Recoverable plugin checkpoint transactions

The explicit `plugins package` command connects the existing authenticated JAR
distribution client to an externally provisioned PostgreSQL authority. It supports
install/update, whole-closure removal, retained rollback, status and operation
recovery. It performs no catalog discovery, automatic latest selection, package
hooks or runtime activation.

The old `PluginCheckpointAuthority` boolean-CAS contract and all original
trust/index/artifact/installation v1 encodings retain their meanings. The new
`PluginCheckpointTransactions` port is necessary because a lost remote commit
answer cannot always be resolved within a finite deadline.

## Outcome and recovery contract

Choose and retain a unique operation ID **before** invoking a mutating command.
Identifiers contain 1–128 ASCII letters, digits, dots, underscores or hyphens,
starting with a letter or digit.

Each operation binds the installation ID, trust domain, pinned-root hash, operation
ID, complete request-intent hash, expected generation/checkpoint pair and new pair.
PostgreSQL serializes operations on the provisioned slot. The terminal decision
receipt and selected pair are written in the same transaction, with synchronous
commit requested by the JDBC provider. Generation files are fully prepared by the
original verifiers and forced to disk before submission.

| Outcome | Meaning | CLI exit |
| --- | --- | --- |
| `COMMITTED`, local evidence available | The authority acknowledged this operation, or its immutable receipt was recovered | 0 |
| `COMMITTED`, local evidence missing/invalid | The operation committed; the local generation cannot currently be read | 5 |
| `REJECTED` | This operation lost its whole-pair CAS; its terminal rejection is retained | 4 |
| `OUTCOME_UNKNOWN` | Submission or lookup could not establish a terminal decision | 3 |
| Configuration/preparation/authority access error | This invocation could not complete; recover the original ID to inspect any earlier invocation | 2 |

An unknown outcome does not authorize retrying under a new ID, resetting the
checkpoint or claiming that active state is unchanged. `recover` only reads the
external ledger. A missing receipt remains unknown because a submission might
still be in flight.

Repeating the same action with the same ID first checks the complete retained
intent. A terminal receipt is returned without downloading again or consuming
another trust revision. Another intent cannot reuse that ID. If no receipt exists,
the original native verification/preparation path runs before a new submission;
the SQL transaction reconciles concurrent submissions of the complete operation.
Recovery never submits a rehashed local pending file. A historical receipt remains
available after later operations supersede its generation.

The new intent boundary rejects unpaired UTF-16 code units before hashing or
lookup, including URI values admitted by Java's URI class. UTF-8 replacement
therefore cannot alias a distinct intent. Valid Unicode retains its existing
intent bytes; the original artifact and installation v1 codecs are unchanged.

A confirmed receipt and local availability are separate facts. Cache deletion,
tampering or restore cannot turn a committed operation into a rejection. Active
reads still consult the authority and validate the selected generation's full
retained file map.

The coordinator opens the local cache only when it needs local evidence. A new
process can therefore recover the remote decision even if the cache directory
structure is damaged. Every confirmed commit, including a direct submission,
reads the retained generation before reporting `AVAILABLE`; preparation alone
does not establish availability after the remote answer. Invalid local evidence
remains `MISSING_OR_INVALID`, while active reads and new mutations still refuse it.

## Provisioning and ownership

Use a separately administered PostgreSQL database outside the rollback/restore
boundary of the package directory. Keep its authenticated client role and pinned
installation/root identity under operator control. PostgreSQL is not protection
against privileged database administrators, disabled durable storage or restoring
the authority database itself. Those operational guarantees need deployment,
backup and recovery controls.

The administrator executes
`app/src/main/resources/plugin-distribution/checkpoint-postgresql-v1.sql` once
under a dedicated schema owner. The script creates private tables and functions
and revokes public access in one transaction. It is deliberately not an automatic
migration or a client startup action.

Provision a distinct login role and one slot using administrator credentials.
For example, after arranging authentication for `checkpoint_client` through the
operator's secret-management process:

```sql
GRANT USAGE ON SCHEMA plugin_checkpoints TO checkpoint_client;
GRANT EXECUTE ON FUNCTION
  plugin_checkpoints.read_state(text,text,text),
  plugin_checkpoints.lookup_operation(text,text,text,text),
  plugin_checkpoints.submit_operation(text)
TO checkpoint_client;
```

Insert the installation ID, trust domain, actual pinned-root content hash and
`client_role` into `plugin_checkpoints.slots`. The initial empty generation and
null checkpoint come only from this explicit administrator provisioning. The
ordinary client performs no DDL and never creates a missing row.

Do not grant clients table INSERT/UPDATE/DELETE/TRUNCATE/TRIGGER, column-level
INSERT/UPDATE on either authority table, schema CREATE, owner membership or
privileged administrative roles. The functions authenticate
`SESSION_USER`, scope it to the provisioned slot, and reject such unsafe grants,
including reachable role memberships. Direct SQL cannot use ordinary client
credentials to overwrite heads, forge receipts or reset a slot. Clients receive
only the three EXECUTE entry points; internal helpers are not granted.

The database protects CAS, monotonic checkpoint transitions and receipt ownership.
The authenticated client remains responsible for the existing native artifact,
signature, index, provenance and trust-chain checks. A credential authorized to
submit operations is not a read-only catalog credential.

Each slot has an explicit receipt capacity, default 100000 and at most 1000000.
Capacity exhaustion fails instead of deleting old idempotency decisions.
Operation input/output is bounded to 16 KiB. The provider bounds connection,
socket, query and commit communication; each configured phase is 1–60 seconds.
A connection factory supplied by an embedding application must also bound
connection/TLS establishment. The CLI uses the strict factory with mandatory
`sslmode=verify-full`, explicit TLS root certificate and no URL query overrides.
The existing Discovery PostgreSQL/JSON-fallback facade is not used.

Operator references: PostgreSQL's [security-definer function guidance](https://www.postgresql.org/docs/16/sql-createfunction.html)
and pgJDBC's [TLS and connection settings](https://jdbc.postgresql.org/documentation/use/).

## Explicit CLI configuration

Commands:

```sh
regelsuche plugins package install --config /etc/regelsuche/packages.json --operation install-2026-09-13
regelsuche plugins package recover --config /etc/regelsuche/packages.json --operation install-2026-09-13
regelsuche plugins package status --config /etc/regelsuche/packages.json
regelsuche plugins package remove --config /etc/regelsuche/packages.json --operation remove-2026-09-13
regelsuche plugins package rollback --config /etc/regelsuche/packages.json --operation restore-2026-09-13 --target sha256:...
```

The JSON config uses `regelsuche.plugin-package-config/v1`; its strict schema is
in `docs/schemas/regelsuche-plugin-package-config-v1.schema.json`.
All fields are required; `sources` and `selection` may explicitly be null for
status/recovery/removal. Installation requires both.

- `packageDirectory`: normalized absolute private POSIX package-slot path.
- `installationId`, `trustDomain`: the separately provisioned authority scope.
- `pinnedRoots`: the actual existing `regelsuche.plugin-trust-store/v1` object,
  supplied independently of catalog responses. Its canonical v1 hash must match
  the provisioned root hash.
- `authority`: `jdbcUrl`, `user`, `passwordEnvironment`,
  `tlsRootCertificate` and `timeoutSeconds`. The URL contains only PostgreSQL
  host, optional port and database. The password is read from the explicitly named
  environment variable; neither it nor driver error contents enter output receipts.
- `allowedOrigins`, `connectTimeoutSeconds`, `responseTimeoutSeconds` and
  `limits`: the original explicit HTTPS origins and finite metadata/artifact/
  total-byte/closure/history limits. Transport deadlines are 1–60 and 1–120 seconds.
- `sources`: the existing four HTTPS metadata URIs and exact index ID, revision
  and content hash.
- `selection`: `requestId`, `componentId`, exact `version`,
  `coreVersion`, `apiVersion`, `requiredCapabilities`. It constructs the
  existing EXACT Java-plugin resolution request.

Configs are bounded to 256 KiB and reject symlink paths, duplicate fields, unknown
fields, missing fields and scalar coercion. CLI options are unique explicit
key/value pairs. Authority failure does not fall back to files, memory or genesis.
Roots, credentials, configuration and TLS files must remain operator controlled.

## Verification and remaining scope

The source includes real HTTPS/native-signature client controls, recovery after
lost answers, stale/intentionally different operations, corrupted local evidence,
CLI policy controls and v1 byte comparisons.

`PluginCheckpointPostgresTest` uses the existing digest-pinned
`PinnedPostgresContainer`. Its controls execute actual SQL permissions and
transactions, two independent concurrent connections, acknowledgement loss
**after a real commit**, direct SQL mutation rejection, unsafe grants/capacity,
a real HTTPS install and recovery from a separate JVM. The JAR fixture is explicitly
package-only; it does not claim runtime activation or an external community release.
Eight column-grant cases cover INSERT and UPDATE on both tables, directly and
through an inherited role. They first verify that PostgreSQL grants the column
privilege without granting the whole-table privilege, then check that all three
entry points reject the unsafe role and leave both state and receipts unchanged.
The test is registered in both the existing Docker source set and the Maven full
integration module and does not convert absent Docker into a successful skip.

The implementation workspace had no Docker daemon/socket or PostgreSQL runtime.
The provider controls compile; actual local execution failed with
`Could not find a valid Docker environment`. Their execution against the pinned
container remains a required CI gate. No deployed-hosted authority is claimed.

Hosted catalog publication and browse/source selection, bounded trust-chain
refresh, runtime closure handoff/activation, rule/knowledge-pack installation,
external published examples, deployed role/backup operations and full public E2E
CI still remain. Every install/update continues to require the immediate successor
trust revision. This slice does not close issue #104.
