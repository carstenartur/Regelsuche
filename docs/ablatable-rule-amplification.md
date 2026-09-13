# Separately ablatable rule amplification

The `regelsuche.ablatable-rule-preparation/v1` authority composes the existing
declarative matcher, exact quotient preparer, concrete native cancellation rule
and #718 local bridge. It receives only a scalar source, declared assumptions,
the complete visible rule inventory and bounded policy. A case ID, family,
reference output or expected verdict cannot enter its formation API.

This is a new contract for #730. The 11-case
`sympy-three-family-safe-preparation-matrix-v2`, its
`regelsuche.sympy-rule-amplification/v2` observations, the earlier Pythagorean
pilot and the V3/V4 product authorities remain unchanged. In particular, the
historical pilot's arithmetic-AC matches remain historical **direct** matches;
they are not relabelled as structural matches under this new contract.

## Profiles fixed before formation

Every profile has its own canonical configuration and SHA-256 identity, binding
the repository revision, complete principal/preparation inventory hashes,
recognition, guards, budgets and measurement contracts. Each executes afresh
from the same source. Stages run in this order for unresolved principals only;
the first independently verified candidate for each principal is retained.

The new amplification configuration now also binds `principalExecutionOrder`
and `preparationExecutionOrder`. The existing inventory fingerprints identify
contents independently of order, while tight work budgets make execution order
observable. The runner preserves the supplied lists; it does not sort execution
to obtain an identity. This deliberately corrects the still-unqualified
amplification configuration hashes. Previous receipts are not relabelled, and
the historical attempt/v2/V3/V4 authorities keep their existing bytes.
The local amplification JSON codec escapes unpaired UTF-16 codeunits before
UTF-8 hashing and transport, so distinct permitted rule IDs cannot collapse
through replacement encoding. Valid Unicode and existing inventory fingerprints
keep their bytes; no global historical codec or mathematical domain is changed.

| Profile | Admitted authority |
| --- | --- |
| `DIRECT_ONLY` | Structural matching at AST occurrences; no AC or preparation |
| `THEORY_MATCHING` | Direct first, then arithmetic AC for scalar addition/multiplication; no rewrite preparation |
| `SAFE_EXACT_PREPARATION` | Earlier stages, then exact univariate integer-polynomial quotient preparation at visible division occurrences, concrete native cancellation and the unchanged visible principal |
| `SAFE_PREPARATION_PLUS_LOCAL_BRIDGE` | Earlier stages, then the existing bounded #718 search over admitted concrete preparation rules, followed by that same principal |

The exact stage asks `RulePreparationPlanner` to prove `P = A*B` for an already
visible `P/A`. It retains that planner's original application, bindings,
residual, assumptions and certificate. It executes the real
`ast_cancel_division_factor` on `(A*B)/A`, lifts the result to the original
occurrence, and replays the selected visible SymPy principal. Native planner
and cancellation IDs are never renamed into SymPy IDs. One exact quotient is
prepared per candidate; chaining multiple exact quotient preparations is not
part of v1. The local bridge can retain multiple concrete cancellations.

The new candidate projection distinguishes `exactPreparationStepIds` from
`primitiveRuleIds`. The exact quotient preparation is a solver-backed step;
the actual cancellation and visible principal are concrete primitive rewrites.
The nested historical application keeps its original list and hash. Evidence
retains full source/terminal/result expressions, occurrence paths, rule/schema
hashes, bindings, guard decisions and replay certificates. Verification repeats
formation and compares the entire run, including every retained work counter.

## Porting decomposition and guards

All three rules are existing declarative knowledge-pack entries, not newly
imported CAS algorithms. Their mathematical kernels and retained provenance are:

| Principal | Mathematical identity and direction | Source domain and guard | Existing source attribution and role |
| --- | --- | --- | --- |
| `sympy.trig.pythagorean` | `sin(X)^2 + cos(X)^2 -> 1` | Scalar complex arguments; equal bound argument; no additional identity guard | `sympy-trigonometry.rules.yaml`, SymPy 1.14.0 commit `fe935ceb303891d1f8bea4c03b19fd9ec9464b02`, BSD-3-Clause; terminal algebraic identity |
| `sympy.poly.factor.diff_squares` | `A^2-B^2 -> (A-B)*(A+B)` | Commuting scalar complex operands; equal bindings replayed | `sympy-polynomial.rules.yaml`, retained source version `1.14`, `sympy/polys/polytools.py`; manually reimplemented factor identity |
| `sympy.rational.partial_fraction.telescoping` | `1/(A*(A+1)) -> 1/A-1/(A+1)` | Original denominator, `A`, and `A+1` nonzero | `sympy-rational.rules.yaml`, retained source version `1.14`, `sympy/simplify/radsimp.py`; manually reimplemented unit-step partial-fraction identity |

The latter two entries do not identify a unique upstream function that directly
implements only that identity. Their original metadata is preserved; the
separately executed algorithm functions below have their own exact provenance.
For trigonometry, upstream `TR5` and `TR6` in `simplify/fu.py` are directed
sin-square and cos-square replacements. Upstream `fu` additionally traverses
and compares alternatives with its own objective; that strategy is an external
algorithm, not a single declarative native rewrite or primitive proof.

The new guard contract is stricter than the historical characterization pilot.
The complete original source domain is checked before any stage. Every symbolic
denominator must have its explicit nonzero declaration; composite products are
not inferred from factor declarations. Required terminal/principal guards must
already follow from those exact declared facts or exact numeric constants.
Unknown, false and conflicting facts cannot authorize; even a supplied
`0 != 0` cannot authorize a zero denominator. No assumptions are introduced.

The closed input language admits scalar symbols, exact rational literals,
arithmetic, nonnegative integer powers through 31, and unary `sin`/`cos`.
Sources have at most 4,096 characters and 256 AST nodes; parenthesis depth is
bounded at 48. Matrix AC, logarithms, branch-sensitive powers and general
functions are outside this contract. The normalized source expression is the
frozen formation source; this does not claim preservation of original text.

## Separate SymPy operations

`regelsuche.sympy-named-operation/v1` uses the existing embedded GraalPy 25.1.3
runtime with pinned SymPy 1.14.0 and mpmath 1.3.0. Each row invokes exactly one
predeclared function once, with its pinned defaults. No result selects another
operation, and no fallback or best-of portfolio is used.

| Operation | Pinned function | Invocation policy |
| --- | --- | --- |
| `TRIGSIMP` | `sympy.simplify.trigsimp.trigsimp` | `inverse=False`; default matching method |
| `FU` | `sympy.simplify.fu.fu` | Default `(L(expr), expr.count_ops())` objective; its internal strategy remains external |
| `FACTOR` | `sympy.polys.polytools.factor` | Default `deep=False`; no caller-selected generators/domain |
| `CANCEL` | `sympy.polys.polytools.cancel` | Default `_signsimp=True`; no caller-selected generators/domain |
| `TOGETHER` | `sympy.polys.rationaltools.together` | Default `deep=False`, `fraction=True` |
| `APART` | `sympy.polys.partfrac.apart` | Default `full=False`; no caller-selected variable/domain |

The configuration binds the adapter script hash, version pins, closed IR,
60-second invocation timeout and operation. A completed payload records the
actual module/function and SHA-256 of its installed source file. The IR uses
explicit unevaluated constructors, with exact rational-literal construction
declared as preprocessing. It never evaluates an input Python program.
Nonzero scalar symbols use `zero=False`, avoiding an extra real-domain claim.
Composite facts retain the original source domain but are not injected as
unproved SymPy predicates; explicit zero declarations are unsupported by this
adapter. Runtime failures, timeout and unsupported operations retain their own
status and never become an unchanged-input success. Primitive proof and
internal SymPy work remain unavailable.

## Work and post-freeze qualification

`regelsuche.amplification-logical-work/v1` records bounded dispatch, matcher,
guard, preparation, bridge search/recomputation and replay events. It retains
all work in each completed bounded batch; a batch may cross its retention limit,
in which case no candidate is admitted afterward. It is not interior-operation
measurement of the principal or bridge verifier.

The [exact preparation work contract](exact-polynomial-preparation-observed-work.md)
supplies actual typed arithmetic operations, operand-width charges, rendering,
verification and certificate SHA-256 work. One run-owned authority cumulatively
admits every plan, internal verification and independent verification across
all occurrences. The default exact budget is 2,000,000 declared units; refused
charges remain separate from the consumed prefix. Budget or technical failure
stops later stages without admitting a partial application. Per-call deltas and
global before/after snapshots are retained together. Logical and exact units
are never added into one purported matched-total-work value.

Source parsing/projection, surrounding-tree lifting, principal internals,
bridge verifier internals, observation-envelope hashing and SymPy internals
remain explicitly unmeasured. The measured exact boundary does not imply that
JDK internals or CPU instructions are counted. Consequently
`comparativeMatchedWorkGate=BLOCKED_UNAVAILABLE_INTERNAL_WORK` and
`comparativeGainClaim=NOT_AUTHORIZED` remain mandatory.

`AblatableRuleAmplificationExperiment` writes a canonical plan binding all four
profiles, all six operations, sources and a hash of the unopened qualification
payload. It then freezes **every** source-index/profile and source-index/operation
row, including failures, before qualification. The CLI persists and rereads the
complete freeze before its first qualification-file read. Native independent
replay precedes label opening. Qualification can compare only the already
retained candidate for the named principal and the already retained output of
each named operation; it cannot select a better candidate or operation.

The report retains staged coverage and increments, family denominators,
separate logical/exact profile work and increments, separate verification work,
preparation depth, AST growth, primitive/exact step counts, reuse, guard outcomes,
false positives and failures. Reference reach uses the existing canonical form
comparison, not a new equivalence oracle. No incremental reach is an explicit
`NULL_NO_INCREMENTAL_COVERAGE` outcome. Both null and positive public control
observations retain the comparative-work and reproduction gates.

The new public corpus has 16 cases across trigonometry, polynomials and rational
expressions. Each family includes direct, obscured, exact-preparation-positive,
near-miss and guard-negative controls; trigonometry also separates AC ordering.
This is development evidence, not a held-out or flagship result.

## Local and CI reproduction transport

The Python transport invokes the same Java authority locally or in CI. No
workflow chooses candidates or implements mathematical qualification. The
explicit classpath task is not connected to `check`, `ciCheck` or the historical
study task; ordinary tests do not launch a new comparative study. Input transport
copies qualification as bounded opaque bytes without parsing its labels.

Actual new embedded-runtime controls live only in
`regelsuche-math-sympy`'s `SymPyNamedOperationEngineTest`. They follow that
module's existing `separateSympyRuntimeAuthority`/`sympyRuntimeAuthority` test
and JaCoCo graph without exclusions or changed gates. The experiment protocol
test creates an engine, immediately closes it, and then obtains bound
`UNAVAILABLE` receipts. `execute` checks `closed` before constructing
`GraalPySymPyRuntime`; construction and configuration hashing only read the fixed
script resource. Search tests use native executors. No new app or experiment
test starts GraalPy alongside the parallel JMH lane. The explicit experiment
CLI starts the runtime only when its `run` command is invoked separately.

On the same clean committed revision, prepare dependencies and a fresh public
plan once, before coordinating the actual new runs:

```bash
./gradlew :regelsuche-experiments:writeAmplificationRuntimeClasspath
python3 scripts/run-rule-amplification.py plan \
  --classpath-file regelsuche-experiments/build/amplification-runtime-classpath.txt \
  --output build/amplification-inputs
```

Transfer those exact three input files to **two genuinely separate clean
hosts**, compile the same checkout on each, and explicitly run on each host:

```bash
python3 scripts/run-rule-amplification.py host \
  --classpath-file regelsuche-experiments/build/amplification-runtime-classpath.txt \
  --inputs build/amplification-inputs --output build/amplification-host
```

For the container, build the committed archive with the checked-in Dockerfile's
digest-pinned Java 25 base. This build is an explicit dependency/build operation;
the actual container run has networking disabled:

```bash
git archive --format=tar HEAD -o build/amplification-source.tar
AMPLIFICATION_REVISION=$(git rev-parse HEAD)
AMPLIFICATION_SOURCE_SHA=$(sha256sum build/amplification-source.tar | cut -d ' ' -f 1)
docker build -f reproduction/Dockerfile.amplification \
  --build-arg REGELSUCHE_REVISION="$AMPLIFICATION_REVISION" \
  --build-arg REGELSUCHE_SOURCE_SHA256="$AMPLIFICATION_SOURCE_SHA" \
  --iidfile build/amplification-image-id .
python3 scripts/run-rule-amplification.py container \
  --image "$(cat build/amplification-image-id)" \
  --inputs build/amplification-inputs --output build/amplification-container
```

The outer adapter retains actual `docker image inspect` identity and the source
revision label. The receipt binds all five canonical authority files, the clean
revision and a hash of the actual compiled Regelsuche classes. Host identity is
the hash of the observed machine ID, not a user-provided label. These are local
execution observations, not remote attestation or downloaded-ZIP verification.
The source archive hash is checked inside the image build. A repeated process
on one host does not satisfy the two-host condition.

After transferring the two retained host bundles and the container bundle:

```bash
python3 scripts/verify-rule-amplification-reproduction.py \
  retained/host-a retained/host-b retained/container \
  --output build/amplification-reproduction.json --require-conclusive
```

The verifier requires every file, exact complete source-index/profile/operation
and replay-row sets, hash/source/configuration/outcome/work bindings, distinct
host observations, immutable image inspection, identical compiled authority
and byte-identical plan/sources/qualification/freeze/report. It refuses missing,
duplicated, substituted, noncanonical or symlinked evidence. Identical technical
or budget failures remain `REPRODUCED_INCONCLUSIVE`; `--require-conclusive`
writes that diagnostic and fails. Even `REPRODUCED` authorizes no matched-work
or comparative-gain claim.

Native report status rows must preserve the frozen run's typed stage, matcher
and observed-operation failure/budget decisions, and their complete histogram
must equal `statuses`. Source symbol names never count as status evidence.
Rehashing a contradictory row or aggregate cannot turn an inconclusive native
execution into conclusive reproduction. This check binds recorded availability;
it does not add a mathematical reference oracle or a new work threshold.

## Acceptance still requiring coordinated evidence

Focused Java controls exercise all native public families, real pinned calls to
all six named SymPy functions, staged guards, cumulative exact-budget refusal,
historical runtime compatibility, complete freeze ordering, tamper rejection and
the null-result path. Python controls exercise reproduction validation with
explicit synthetic fixtures; they are never presented as real study receipts.

No two-host/container study has been executed by this coding slice. Fresh full
CI can qualify implementation integration; #730's reproducibility requirement
still needs the coordinated three-environment execution above. Complete work
comparison additionally needs the explicitly missing ordinary work authorities.
No efficiency ranking, broader family claim, protected study or product-default
change follows from these development controls.
