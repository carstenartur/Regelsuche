# Source-bound proofs for retained sequence models

## Intent and scope

Implement the missing proof consumer in issue #1020 through the existing Java
discovery workspace, persistence, HTTP API and Workbench. A retained sequence
candidate becomes an explicit rational affine model. The existing independently
checked Krylov implementation proves a scalar recurrence of that model and
evaluates a bounded set of exact model terms. A model theorem must never become
a theorem about an unspecified infinite continuation of finite input data.

This is one integrated product capability. The expression production lifecycle,
external novelty, promotion, release composition and cross-domain ablation
requirements of #1020 remain separate unfinished work.

## Design decision

Use `RecurrenceObservationKrylov` and its privately issued `VerifiedPlan`, after
the original workspace has replayed identically and its actual selected objects
have passed the existing independent finite-data check. Do not add another proof
engine, parse display strings into authority, or reuse finite agreement as proof.

The adapter supports the existing checker's one-to-three-dimensional rational
affine fragment. Linear recurrence coefficients are reversed from the source's
newest-first convention into the companion matrix's oldest-first last row.
Finite differences use successive differences as state and the highest constant
difference as an affine offset. Unsupported size or coefficient width produces
an explicit unsupported result before calling the mathematical backend.

## Binding, work and storage

- Accept only an immutable `DomainExportWorkspace`; replay supplies selected
  candidate and certificate objects, never caller-created records.
- Bind workspace, original manifest bytes, domain descriptor, input, source
  evidence, lifecycle handoff, selected candidate, certificate and model hashes.
- Keep original replay counters, finite-check work and every actual model proof,
  initial-state binding and query phase separate. Do not invent a total-work sum.
- Admit input size, coefficient widths, phase budgets and indices before work.
- Retain canonical receipts by content hash outside the immutable source export.
  Reload checks source binding and reproduces successful proof content; imported
  status text or a rehashed false certificate cannot authorize a proof.
- Proof, finite validation, original-sequence truth, novelty, promotion and
  Public Evidence remain distinct statuses.

## Product contract

Add a bound `POST .../exports/{runDigest}/model-proof` action using the existing
workspace/evidence hash request. Add a hash-addressed receipt GET. The existing
domain page shows the checked model, recurrence, assumptions, exact computed
terms, phase work and a receipt download. It clears proof state on selection
changes and rejects stale or foreign responses. All text states the model scope.

No new host tool, external service, browser dependency or publication action is
needed. Existing source artifact bytes and workspace v1 identities stay stable.
