# Sealed held-out reveal handling

Status: private reveal-envelope, strict external-file boundary and
content-addressed authorization contract for #533 and #521

The public repository may contain held-out commitments, split references and
reveal-authorization evidence. It must not contain concrete VALIDATION or FINAL
TEST expressions before the corresponding reveal stage.

`EvolutionRewriteProgramHeldOutRevealBundle` represents the private material
from which public commitments are derived.

## Public surface

Without opening a bundle, callers can obtain only:

- study and split identity;
- the bundle content hash;
- `EvolutionSplitManifest.CaseReference` values containing identities and hashes;
- `EvolutionRewriteProgramHeldOutCommitment` containing per-case and whole-bundle
  commitments;
- `EvolutionRewriteProgramHeldOutSplitReferences`, a canonical public root over
  the hash-only manifest references;
- `EvolutionRewriteProgramHeldOutRevealAuthorization`, which contains only
  prerequisite artifact hashes and terminal-stage metadata.

The commitment and split-reference JSON contain no concrete input, target or
assumption text. Changing any private value changes the reveal entry, complete
reveal and all derived public artifact identities.

## Strict loader and public exporter

`EvolutionRewriteProgramHeldOutRevealCodec` reads a private JSON file through
strict Jackson DTOs with unknown-field rejection. It reconstructs every runtime
`RevealCase` and the complete bundle, causing all per-case and root hashes to be
recomputed before the file is accepted.

Its public exporter writes exactly two separate files:

1. the held-out commitment;
2. the hash-only split references.

Writes use a temporary file followed by an atomic replace where supported. The
trusted private writer restricts its output to owner read/write permissions on
POSIX filesystems. Private values are never written to stdout by the codec.

A caller must keep private input and output paths outside ordinary repository,
CI-artifact and web-publication directories. The codec enforces content and
output separation; deployment configuration remains responsible for filesystem
and secret-store isolation.

The public split-reference schema is:

[`regelsuche-evolution-rewrite-program-held-out-split-references-v1.schema.json`](schemas/regelsuche-evolution-rewrite-program-held-out-split-references-v1.schema.json)

## Opening policy

A reveal can be opened only with both:

1. the exact frozen public commitment;
2. content-addressed prerequisite evidence for the split.

### VALIDATION

The authorization is created from:

- the exact `EvolutionStudyPlan`;
- its `EvolutionSplitManifest`;
- a terminal `EvolutionPopulationEngine.PopulationRun` bound to that study;
- the exact VALIDATION commitment.

Every declared population terminal outcome denotes that TRAIN execution has
finished and is retained. No VALIDATION selection or FINAL TEST artifact may be
present in this authorization.

### FINAL TEST

The authorization is created from:

- the exact study plan and split manifest;
- a frozen `EvolutionValidationSelection` with a selected configuration;
- the exact `EvolutionFinalTestSuite`;
- an `EvolutionFinalTestReservation` binding that selection and suite;
- the exact FINAL TEST commitment.

The suite case-material hashes must equal the committed private reveal-entry
hashes. A substituted selection, suite, reservation, study, split or commitment
is rejected before concrete material is returned.

`OpenedReveal` has no public constructor. Ordinary callers cannot fabricate an
opened value and bypass `open(...)`.

## Private artifact boundary

The strict private schema is:

[`regelsuche-evolution-rewrite-program-held-out-reveal-bundle-v1.schema.json`](schemas/regelsuche-evolution-rewrite-program-held-out-reveal-bundle-v1.schema.json)

The public authorization schema is:

[`regelsuche-evolution-rewrite-program-held-out-reveal-authorization-v1.schema.json`](schemas/regelsuche-evolution-rewrite-program-held-out-reveal-authorization-v1.schema.json)

The schemas are public; real reveal JSON is not. A real reveal file must:

- live outside the repository and TRAIN-visible classpath;
- be supplied only to the stage-specific trusted loader;
- be content-addressed and matched against the public commitment before use;
- be stored with owner-only permissions where the filesystem supports POSIX
  permissions;
- never be written to ordinary CI logs or preregistration artifacts;
- become publishable for reproduction only after the relevant stage permits it.

Private writes set restrictive permissions on the temporary file before atomic
publication. A permission failure therefore cannot publish a new unprotected
private file or replace a previously valid target.

The repository contract does not include actual flagship held-out cases. Tests
use synthetic fixtures whose values have no held-out status.

## Canonicalization

Every private case binds normalized:

- input and target expression;
- assumption signature;
- exact and alpha-normalized signatures;
- family identity;
- difficulty tier;
- expected terminal class.

Case order and assumption input order do not change the bundle identity.
Duplicate case, input, target, exact, alpha or reveal-entry identities are
rejected.

## Optimization boundary

An optimized evaluator or transformation backend receives held-out values only
through the same authorized opened bundle as the reference backend. Performance
experiments cannot receive an earlier reveal or a wider information surface.

## Claim boundary

This layer secures reveal identity, strict loading, hash-only public derivation,
public prerequisite evidence and the API opening boundary. It does not encrypt
external storage, manage an external secret store, generate real held-out cases,
execute a case, select a candidate or consume FINAL TEST. Exactly-once
consumption remains enforced by the existing final-test executor and ledger.
