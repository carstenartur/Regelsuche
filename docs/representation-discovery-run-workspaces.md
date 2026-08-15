# Immutable representation-discovery run workspaces

`RepresentationDiscoveryRunWorkspace` is the backend identity contract for the
first #669 product slice. It does not redesign the Workbench yet. It prevents
existing graph, candidate, replay, radar, proof and export surfaces from being
silently correlated through mutable global state.

## One immutable Run ID

A workspace content-addresses:

- domain-neutral canonical input identity, display form and assumptions;
- the exact representation-discovery information track and boundary;
- formation rule inventory, Knowledge-Pack selection and known-structure
  catalog commitments;
- search strategy, profile, objective, budget and deterministic seed;
- backend/solver identities;
- terminal/current state and exact terminal reason;
- configured and consumed canonical work;
- a separate non-authoritative runtime-diagnostics identity;
- every supported product-artifact role;
- repository commit and application revision;
- parent/relation evidence for duplicated or continued runs;
- the fixed observability-only claim boundary.

`runId` equals the canonical workspace `contentHash`. A change to any bound field
therefore creates a new run rather than mutating historical evidence.

## Domain-neutral input with an expression convenience path

`RunInput` binds a domain ID, input schema, canonical domain-content hash,
display text and assumptions. This permits later #224 sequence or other domain
workspaces without flattening their mathematical input into an authoritative
expression string.

For the current expression-rewrite domain,
`RunInput.expression(...)` additionally:

1. parses through the production `ExpressionParser`;
2. stores the deterministic `ExpressionFormatter` output;
3. sorts/deduplicates assumptions;
4. independently recomputes the expression-input hash in the constructor.

A forged expression hash or non-normalized display form is rejected.

## Explicit artifact matrix

Every workspace must contain exactly one entry for every role:

```text
SEARCH_GRAPH
REPRESENTATION_CANDIDATES
CANDIDATE_DOSSIERS
PATH_REPLAY
RULE_RADAR
PROOF_OBLIGATIONS
EXPORT_BUNDLE
PROGRESS_LEDGER
```

Each role is one of:

```text
AVAILABLE
NOT_PRODUCED
UNSUPPORTED
FAILED
```

There is no implicit missing value. `AVAILABLE` binds a schema and target
content hash. Non-available roles bind a deterministic sentinel target hash and
a visible reason. Duplicate or missing roles fail closed.

`requireArtifact(role, schema)` returns only an available artifact with the
expected schema. It throws for unavailable or incompatible data, so a future UI
cannot silently combine a current graph with an old proof or candidate dossier.

## Historical revision relations

Three relations are explicit:

- `ROOT` — independent run without parent evidence;
- `DUPLICATED_ONE_PARAMETER` — same input, exactly one changed plan field,
  new `CREATED` outcome and all artifact roles reset to `NOT_PRODUCED`;
- `CONTINUATION` — same input and plan, a new non-`CREATED` outcome and new
  artifact matrix while the parent remains immutable.

The duplication factory compares all visible plan parameters and rejects both
zero changes and two-or-more changes. The changed field name, parent Run ID and
parent plan hash are included in the new Run ID.

## Honest progress and terminal state

`RunOutcome` keeps canonical mechanical work separate from runtime diagnostics.
It requires:

```text
0 <= consumedWork <= configuredWork
```

`BUDGET_EXHAUSTED` must consume the complete declared budget. A new duplicated
run is exactly `CREATED / NOT_STARTED` with zero work. Runtime evidence has its
own hash and cannot substitute for the canonical work ledger.

The terminal vocabulary distinguishes:

```text
CREATED
RUNNING
COMPLETED
BUDGET_EXHAUSTED
NO_RESULT
CANCELLED
FAILED
UNSUPPORTED
```

## Immutable active selection

`RunSelection` binds candidate, state, edge, AST occurrence and proof-obligation
selection to one exact Run ID. It is a separate content-addressed value and does
not mutate the historical workspace. This is the backend basis for keeping the
same candidate selected across cockpit, graph, replay, rule-radar and proof
views.

## Test boundary

The focused contract tests characterize:

- canonical order independence for assumptions, backends and artifact lists;
- stable canonical JSON and Run ID;
- successful same-run artifact and selection correlation;
- exactly-one-parameter duplication;
- immutable continuation revisions;
- missing/duplicate artifact rejection;
- explicit unavailable and incompatible artifact errors;
- forged artifact, input, plan, outcome, revision, selection and workspace
  identities;
- work-budget balance and runtime/canonical-work separation;
- expression normalization and a generic non-expression input.

## Claim boundary

This contract establishes run correlation, explicit artifact availability and
reproduction identity. It does not establish mathematical truth, proof,
external novelty, interestingness, usefulness or search superiority.

The next product slice is to expose the immutable workspace and selection
through the existing API, then adapt the current Workbench views to carry and
check the Run ID before rendering asynchronous responses.
