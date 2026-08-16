# Comparative Discovery Benchmarks

Issue #235 defines comparative evidence by capability and information regime. The benchmark layer must not collapse target-directed search, hidden-rule rediscovery, open-target conjecture generation, transfer, equality validation, proof and autonomous campaign control into one leaderboard.

The authoritative report schema is `regelsuche.comparative-benchmark/v1`. Every report carries the fixed policy:

```text
NO_UNIVERSAL_SCORE_TRACK_SCOPED_CLAIMS_ONLY
```

## Executable suite

The suite `comparative-baselines-initial/v2` measures three separate tracks.

### Target-directed search

Three internal Regelsuche strategies receive exactly the same input expressions, visible exact targets, default rewrite inventory and search budgets. The configured strategies are deterministic BestFirst, the project’s AStar variant and Beam search.

The report records target reachability, explored states, generated successors, path length and engine invocations separately. These results do not imply a universal strategy ordering, and runtime is deliberately excluded from canonical capability evidence.

### Equality validation

Three backends receive the same two real-polynomial equality statements with no assumptions and the same requested symbolic-certificate strength:

1. Regelsuche’s exact rational polynomial normal form;
2. external SymPy CAS, pinned in the verification environment;
3. external Z3, with proof-object retrieval on confirmed equalities.

Validation is not discovery. A backend that only checks equality is not scored as a failed conjecture generator. Likewise, a successful search path is not proof. Only the Z3 result may report `FORMAL_CERTIFICATE_RETAINED`; internal normal-form and SymPy certificates remain validation evidence.

The SymPy adapter rejects assumptions, division, calls and unsupported exponent forms before process invocation. A missing executable, timeout, translation rejection and mathematical refutation remain distinct outcomes.

### Target-free simplification competition

The equality-validation track uses external systems as validators: they receive both sides of a statement and decide equality. That role cannot show how Regelsuche compares as a rewriting system. The `SIMPLIFICATION_COMPETITION` track therefore executes three target-free configurations:

1. Regelsuche untargeted best-first search over the default rewrite inventory, through `SearchProblem.withoutTarget()`;
2. a deterministic seeded randomized-valid rewrite control over the same production inventory and search budget;
3. external SymPy in its native `simplify` role, pinned in the same verification environment.

Neither configuration receives the pinned reference form. The parity manifest `target-free-simplification/v1` therefore sets `targetVisible=false` and `hiddenReferenceVisible=false`.

Each configuration contributes exactly one output. SymPy contributes its native `simplify` result. Both internal configurations select one state from their target-free search using the same fixed policy that is part of the configuration hash: lowest `ExpressionScorer` total, then normalized expression text, then search depth. A reference-shaped state that was merely visited but not selected does not count as a hit.

Both selected outputs are compared by the same surface judge, `ExpressionCanonicalizer`. A case is reached when the canonical hash of the selected expression equals the canonical hash of the pinned reference form. This avoids treating notation differences such as SymPy’s `**` and Regelsuche’s `^` as different results.

The reference is intentionally called a **pinned reference form**, not “the simplest form”. The current benchmark does not define a universal simplicity ordering and therefore cannot claim that a different equivalent output is worse in general.

#### Seeded randomized-valid control

The control is reported as `SystemKind.ABLATION`, not as an external system and not as the ordinary Regelsuche configuration. It changes only the bounded scheduling order of production-valid rewrites.

A fresh `RandomMonteCarloSearchStrategy` delegate is created for every case. The frozen base seed is combined with the stable canonical source hash, so repeated calls on the same runner and independent clean runners consume the same per-case random stream. The base seed, derivation policy and delegate identity are part of the configuration policy hash.

The complete contract and claim boundary are documented in [Deterministic randomized-valid simplification control](randomized-valid-simplification-control.md).

#### Assumptions and current validation boundary

Cases may declare assumptions such as `x - 1 != 0` for rational cancellation. The selected Regelsuche and randomized-control paths retain the side conditions emitted by their rewrite steps; an output only counts when all emitted conditions occur in the case’s canonical assumption contract.

The external adapter can bind symbol-scoped assumptions directly. Composite declarations such as `x - 1 != 0` cannot currently be represented as SymPy symbol assumptions and remain visible as a configuration limitation. An independent assumption-aware validator for every produced output is therefore still a machine-readable coverage gap. The benchmark does not claim that this gap is already closed.

#### Retained primary head-to-head outcome

The pinned corpus contains seven algebraic cases. The primary Regelsuche-versus-SymPy result remains unchanged and is not rewritten merely because an additional control is added:

| Case | Family | Regelsuche untargeted best-first | SymPy `simplify` |
|---|---|---|---|
| `(x + 0) * 1` → `x` | identity | reached | reached |
| `x * 0 + y` → `y` | annihilator | reached | reached |
| `(a + b) * (a + b)` → `(a + b) ^ 2` | power-folding | reached | reached |
| `x + x` → `2 * x` | linear-combination | reached | reached |
| `(2 * x + 4) / 2` → `x + 2` | rational-reduction | reached | reached |
| `(x^2 - 1) / (x - 1)` → `x + 1` | rational-cancellation | reached | reached |
| `(x^3 - 1) / (x - 1)` → `x ^ 2 + x + 1` | polynomial-division | **not reached** | reached |

Regelsuche reaches six of seven pinned reference forms, SymPy reaches seven of seven. The track claim `target-free-simplification-head-to-head` remains `NEGATIVE`; losing evidence is not deleted or converted into a passing claim. The randomized control’s seven per-case results are retained beside the primary configurations in the canonical bundle rather than manually copied into this prose.

#### What the first retained loss diagnosed

The first bundle recorded `(2 * x + 4) / 2` and `(x^2 - 1) / (x - 1)` as not reached. Replaying the untargeted search showed that this was not a search-budget effect. Four atomic capabilities were missing, and each is now a separate rule:

- `ast_distribute_division_over_sum` — `(A ± B) / C → A / C ± B / C`;
- `ast_cancel_division_factor` — `(A * B) / A → B`, retaining `A != 0` unless the factor is a known non-zero literal;
- `ast_fold_numeric_arithmetic` — exact integer arithmetic only;
- `ast_square_literal_split` — exposes a perfect-square literal to the existing difference-of-squares rule.

No single benchmark-shaped shortcut was added for the two repaired cases.

#### Why the track keeps a loss

Closing every benchmark-exposed gap inside the measured configuration would turn the benchmark into self-confirmation. Exact univariate polynomial division is implemented as `ast_polynomial_exact_division`, but it belongs to the standard-disabled pack `core-exact-polynomial-division`.

Consequently:

- the default benchmark inventory still misses the cubic case;
- the capability can be evaluated explicitly with `--enable-pack core-exact-polynomial-division` or profile `all`;
- changing its default status requires a conscious benchmark-version and evidence update.

This keeps implementation progress separate from the frozen configuration used for the retained comparison.

## Retaining losses instead of hiding them

The publication gate is **claim consistency**, not success. A comparative benchmark that can only be published when the local system wins every case cannot produce information about competing systems.

The gate is fail-closed in these ways:

- every configured evaluation must exist — the writer requires the complete Cartesian matrix per track;
- every result must have disposition `EXECUTED`; an unavailable, timed-out or crashed system means the evidence was not produced and must not replace a prior complete bundle;
- a claim status must equal the status derived from its own track’s results;
- every measured track must carry a retained claim.

An `EXECUTED` but incorrect result is legitimate retained evidence. Deleting it would be selective reporting.

## Information parity

Each track has one `InformationParityManifest` containing visibility flags and hashes for the input corpus, inventory, budget, Research Brief, qualification split and mandatory evaluations. Each configuration references exactly one same-track manifest.

The current suite keeps all hidden-reference, family-label, TEST-label, qualification-label and review-label visibility flags false. Target visibility is true only for target-directed search and equality validation. The simplification competition sets it false.

The two internal simplification configurations share the exact same input corpus, default rewrite inventory and `SearchHeuristic` budget. Their different scheduling policies remain configuration identities, not hidden information differences.

## Canonical evidence

One run replaces the complete output directory:

```text
<run>/
  report.json
  parity-manifests/
  configurations/
  cases/
  results/
  claims/
  coverage-gaps/
```

Every configuration, case, result and claim is SHA-256 linked. Resource accounting is fail-closed:

```text
configuredWork = executedWork + skippedWork + remainingWork
completedMandatoryEvaluations <= mandatoryEvaluations
```

Publication additionally requires the complete Cartesian matrix of every configuration and every case in the same track. If even one configured evaluation is missing, the writer rejects the run before replacing prior retained evidence.

Repeated runs with the fixed environment must produce byte-identical bundles. The verifier also checks that every standalone retained object is exactly the corresponding object embedded in `report.json`.

## Explicit coverage gaps

Unmeasured work is not omitted. The report currently retains machine-readable gaps for:

- an equality-saturation competitor with exact side-condition provenance;
- a multi-domain simplification corpus;
- independent assumption-aware validation of every simplifier output;
- hidden-rule rediscovery using the #227 leakage controls;
- open-target discovery with an information-equivalent external generator;
- fully held-out cross-family transfer;
- autonomous campaign-controller comparison over the #355 Research Brief and ledger;
- discovery-component and controller ablations.

The randomized-valid simplification control is no longer a coverage gap: it is a configured seven-case matrix whose seed and scheduling policy are content-bound. A remaining gap disappears only when its required raw evidence is executed and retained under the corresponding information-parity manifest.

## Reproduction and verification

The complete benchmark contract currently runs from a normal checkout with:

```bash
bash scripts/run-comparative-benchmarks-verification.sh
```

Required host tools are a reachable Docker daemon, Python `venv` support and Z3 4.8.12. This is transitional build debt tracked by #632, not the intended final toolchain. The runner creates a build-local environment with pinned SymPy and `jsonschema`, then:

1. runs the benchmark JUnit suite;
2. writes two independent Gradle evidence bundles;
3. validates schema, parity, resource, reference and outcome invariants;
4. requires both Gradle bundles to be byte-identical;
5. builds the pinned benchmark image;
6. generates and validates a third bundle in that image;
7. requires Docker and Gradle evidence to be byte-identical.

The central GitHub CI is an environment adapter around the checkout-owned verification lifecycle. Migration of this path to the sole Maven/JUnit/Testcontainers contract remains part of #632.
