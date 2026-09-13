# Independent finite validation of retained domain exports

`DomainDownstreamValidation` is an ordinary productive consumer of the existing
immutable `DomainExportWorkspace`. Its new v1 contract supports exactly the
existing `integer-sequence-finite-difference@v1` and
`integer-sequence-linear-recurrence@v1` descriptors. It does not load providers,
infer a replacement model, translate sequences into expression rewrites, or
authorize a later lifecycle stage.

The original finite-difference domain, export, evidence, handoff and workspace
canonical bytes remain unchanged. Adding the existing recurrence domain to the
workspace dispatch uses the same complete descriptor comparison, typed evidence
reconstruction, resource checks and exact retained-byte repository boundary.

## Execution and binding

1. A verified immutable workspace is required. The consumer accepts no decoded
   candidate, caller-supplied `RunResult`, claimed checker status or receipt as
   source authority.
2. Before replay, it admits at most one call, a source payload of at most 512
   UTF-16 characters, and the existing replay budget ceiling
   `(depth=8, states=256, successors=1024, candidates/state=128,
   candidate attempts=128, counterexample attempts=4096)`. The original budget
   is passed unchanged. Oversized sources remain readable; validation returns
   `INCONCLUSIVE` without invoking the runner.
3. The actual existing runner must reproduce the **whole** canonical source
   evidence. The consumer recomputes the selected candidate, certificate object
   and rendered-certificate identities through the original codecs and
   renderer. Only this execution can privately issue the typed replay
   observation. A freshly rehashed false execution trace still fails replay.
4. Independent finite checks consume those bound selected objects. Source runs
   without a selected candidate retain their original outcome and return
   `NOT_EVALUATED_NO_SELECTED_CANDIDATE`. The source does not retain failed
   candidate bodies; they are not reconstructed from hashes or metrics.
5. A separate `regelsuche.domain-downstream-validation/v1` receipt binds the
   manifest semantic hash **and exact byte hash**, verification, workspace,
   descriptor, seed, evidence, lifecycle handoff and all three artifact byte
   hashes/lengths. Verified selected-object hashes and the separately hashed
   check are explicit. No timestamp, host path or mutable global selection
   contributes authority.

## The two independent mathematical checks

| Domain | Independent checker | Retained-data and witness semantics |
|---|---|---|
| Finite difference | `regelsuche.newton-binomial-finite-check/v1` computes `sum(j=0..min(n,k), binomial(n,j) * initialDifference[j])` with core `ExactRational`. | Index zero is the first observed value. Every observed and holdout value and the witness term at that index must agree; order, initial differences, partition counts and the original finite evidence-strength marker must bind. This does not call the domain's advancing-difference generator. |
| Linear recurrence | `regelsuche.recurrence-residual-finite-check/v1` computes exact residuals `a[n] - sum(j=0..k-1, c[j] * a[n-j-1])` using core `ExactRational`, independently of the domain's private `Rational`, RREF and generator. | The first `k` values are retained seed identities, not newly proved recurrence equations. Subsequent rows use one joined observed/holdout sequence. At the first holdout index `m`, the window is `[m-k,m)`; at the next index it is `[m-k+1,m+1)`. The window never resets at the partition boundary. The original model, counts, seed witness and canonical witness terms must bind. |

The historical domain evaluators assume their observed-prefix counterexample
search already ran. Repeating an evaluator alone can confirm a deliberately
damaged observed prefix whose holdout still matches its generator. The new
checks reject those prefixes directly. This is covered for both domains.

Rows distinguish `retained`, independently `computed`, `residual`, and `witness`.
A retained-data mismatch produces an exact finite counterexample index. A
damaged witness with otherwise agreeing retained data is `INVALID_WITNESS`,
not a fabricated counterexample to that candidate. A partial check can retain a
found finite counterexample but remains `INCONCLUSIVE`, never confirmed.

## Work and admission

The checker fragment admits at most 256 retained terms, orders 1 through 8,
and 2048 candidate/witness scalar entries. All input collections are admitted
by size before joining or converting; operand widths are checked before exact
conversion. Witness text is bounded and compared with independently produced
canonical text, never parsed to drive the calculation.

The default check budget is 256 term attempts, 20,000 exact scalar operation
dispatches and a conservative 1024-bit scalar bound. Callers may lower these
budgets; hard maxima are 256, 100,000 and 4096 respectively. A single execution
retains its consumed prefix. Every multiply, divide, add or subtract is admitted
before calling core arithmetic; conservative numerator/denominator width bounds
are checked before that call. A refused operation is not added to executed
dispatches. A started term is counted even if arithmetic refusal prevents its
completed row. `complete` requires every retained term's row.

`inputItems` counts admitted scalar entries, not inner arithmetic instructions.
`termChecks` counts admitted attempts; `scalarOperations` counts actual admitted
core operation calls. Each line preserves
`configured = executed + skipped + remaining`, with no artificial skipped
work. Bit refusal is separately identified by `refusedDimension`.

These are bounded logical dispatch counts. Core rational constructor/GCD and
primitive BigInteger internals, source runner arithmetic, encoding and hashing
are **not** fully metered. Their fields remain `UNAVAILABLE`. Additional replay
role counts are copied only from an actually returned run and kept separate
from the original source and checker counters. A replay that throws before
returning has `resources: null`, not fabricated zero work. No total-work ranking
or comparative efficiency claim follows.

## HTTP and artifact roles

`POST /api/discovery-domains/exports/{runDigest}/validate` accepts only:

```json
{"expectedWorkspaceHash":"sha256:<64 hex digits>","expectedEvidenceHash":"sha256:<64 hex digits>"}
```

The body requires `application/json`, strict duplicate-free JSON and at most
4096 bytes. Both identities must match the loaded immutable workspace before
execution; mismatch returns `409 SOURCE_BINDING_MISMATCH`. Unknown fields,
missing hashes and malformed JSON return structured `400`. Only `POST` is
registered. HTTP `200` means a receipt was produced; clients must inspect its
status, including inconclusive, unsupported, replay mismatch and technical
failure outcomes.

The response carries its content hash as an ETag, the source run ID and an
attachment filename. It is a separately downloadable result. It does not add
files to the retained source directory or change its exact four downloads.
There is no server receipt catalogue in this slice.

| Role | Actual binding |
|---|---|
| `SOURCE_LIFECYCLE_HANDOFF` | Original retained handoff schema and hash. |
| `VALIDATION_EVIDENCE`, `FINITE_COUNTEREXAMPLE_CHECK` | The actual independent check's schema and hash, or `NOT_EVALUATED` with null target when no check ran. Availability alone does not imply confirmation or completeness. |
| `PROOF_OBLIGATIONS`, `UNIVERSAL_PROOF` | `NOT_PRODUCED`, null schema and target. |
| `EXTERNAL_NOVELTY`, `PROMOTION`, `PUBLIC_EVIDENCE` | `NOT_EVALUATED`, null schema and target. |

The closed JSON schemas are
`schemas/regelsuche-domain-finite-data-check-v1.schema.json` and
`schemas/regelsuche-domain-downstream-validation-v1.schema.json`. They describe
syntax and necessary status constraints; structural schema/hash validation is
not mathematical execution authority. Only the live consumer performs the
complete source replay and independent check. Original proof, novelty,
promotion and Public Evidence statuses stay `NOT_EVALUATED`.

Agreement on the retained finite data does not prove a unique infinite
continuation, minimal recurrence order, universal theorem, external novelty or
release qualification. Generic proof/novelty/promotion/release consumers,
receipt retention and comparative multi-domain qualification remain open under
#224. No campaign, protected corpus or study is executed by this API.

## Ordinary controls

- Real source export/verification/replay/check for both existing domains;
  freshly rehashed false execution evidence; refuted sources; lowered replay
  and checker budgets.
- Both observed-prefix negatives; first and second holdout residual negatives;
  rational coefficients; model/count/canonical witness and seed-witness damage;
  term, operation and pre-conversion coefficient-width refusal.
- Complete finite differential space: orders 1–4, every initial-difference
  coefficient in `{-1,0,1}`, 360 models with 10 terms each. Advancing-difference
  reference plus original evaluator witness versus Newton sum; additionally
  one changed first holdout for each model (720 checker executions).
- Actual Java HTTP import/validate/download for both domains, foreign binding,
  malformed body, method restriction, deterministic receipt and unchanged
  source bytes/directory membership. Existing OpenAPI route/media/413 controls
  include the one additional JSON POST. The complete existing request-limit
  matrix retains its 1024-byte control limit and checks fixed and chunked
  oversized requests before lookup, including an unknown validation run.
- Offline Draft 2020-12 checks use the existing pinned networknt 2.0.7 authority
  in the release module, without new dependencies or qualification execution.
  Real receipts for both domains, refuted sources and partial checks validate;
  false completeness, missing bindings and contradictory proof roles fail.
- Full original-byte comparison for confirmed, refuted and oversized-budget
  finite sources against captures compiled before production changes.

All are ordinary focused Java 25 Maven controls. They do not initialize a
GraalPy/SymPy runtime or run Gradle, a browser, release studies or GitHub writes.
