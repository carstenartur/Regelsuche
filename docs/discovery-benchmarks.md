# Comparative Discovery Benchmarks

Issue #235 defines comparative evidence by capability and information regime. The benchmark layer must not collapse target-directed search, hidden-rule rediscovery, open-target conjecture generation, transfer, equality validation, proof and autonomous campaign control into one leaderboard.

The authoritative report schema is `regelsuche.comparative-benchmark/v1`. Every report carries the fixed policy:

```text
NO_UNIVERSAL_SCORE_TRACK_SCOPED_CLAIMS_ONLY
```

## First executable slice

The initial suite `comparative-baselines-initial/v1` measures three separate tracks.

### Target-directed search

Three internal Regelsuche strategies receive exactly the same:

- input expressions;
- visible exact targets;
- default rewrite inventory;
- depth, state, expansion, candidate and beam budgets.

The configured strategies are deterministic BestFirst, the project’s AStar variant and Beam search. The report records target reachability, explored states, generated successors, path length and engine invocations separately. These results do not imply a universal strategy ordering, and runtime is deliberately excluded from canonical capability evidence.

### Equality validation

Three backends receive the same two real-polynomial equality statements with no assumptions and the same requested symbolic-certificate strength:

1. Regelsuche’s exact rational polynomial normal form;
2. external SymPy CAS, pinned in the verification environment;
3. external Z3, with proof-object retrieval on confirmed equalities.

Validation is not discovery. A backend that only checks equality is not scored as a failed conjecture generator. Likewise, a successful search path is not proof. Only the Z3 result may report `FORMAL_CERTIFICATE_RETAINED`; internal normal-form and SymPy certificates remain validation evidence.

The SymPy adapter rejects assumptions, division, calls and unsupported exponent forms before process invocation. A missing executable, timeout, translation rejection and mathematical refutation remain distinct outcomes.

### Target-free simplification competition

The equality-validation track uses external systems as *validators*: they receive both sides of a statement and decide equality. That role can never produce information about how Regelsuche compares as a rewriting system, because a validator is not attempting the task.

The `SIMPLIFICATION_COMPETITION` track therefore uses an external system as a *competitor*. Two configurations receive the input expression and nothing else:

1. Regelsuche untargeted best-first search over the default rewrite inventory, run through `SearchProblem.withoutTarget()`;
2. external SymPy in its native `simplify` role, pinned in the same verification environment.

Neither competitor receives the reference simplest form, so the parity manifest `target-free-simplification/v1` sets `targetVisible=false` and `hiddenReferenceVisible=false`. The reference form stored on each case is the shared judge's answer key.

Both competitors are scored by exactly one judge: Regelsuche's `ExpressionCanonicalizer`. A competitor reaches a case when the canonical hash of its produced expression equals the canonical hash of the reference form. Using the same judge for both sides prevents surface-syntax differences — for example SymPy's `**` versus Regelsuche's `^` — from being scored as mathematical differences. The judge is a normalizer, not an oracle: it never tells a competitor what to produce.

Recorded case assumptions, such as the nonzero denominator of `(x^2 - 1) / (x - 1)`, are retained as evidence but are **not** injected into either competitor. This is declared as the configuration limitation `RECORDED_CASE_ASSUMPTIONS_ARE_NOT_INJECTED` and is tracked as a coverage gap. A competitor that cancels a symbolic factor must therefore derive the side condition itself: `ast_cancel_division_factor` emits `x - 1 != 0` as a step assumption instead of consuming the recorded one.

Reaching a simplest form is neither discovery nor proof. The track carries the limitations `SEVEN_SMALL_ALGEBRAIC_CASES_ONLY`, `NO_RUNTIME_OR_SCALABILITY_CLAIM`, `SHARED_JUDGE_IS_THE_REGELSUCHE_CANONICALIZER` and `REACHING_A_SIMPLEST_FORM_IS_NOT_DISCOVERY_OR_PROOF`.

#### Retained outcome

The current retained outcome is still a loss for Regelsuche and is published unchanged:

| Case | Family | Regelsuche untargeted best-first | SymPy `simplify` |
|---|---|---|---|
| `(x + 0) * 1` → `x` | identity | reached | reached |
| `x * 0 + y` → `y` | annihilator | reached | reached |
| `(a + b) * (a + b)` → `(a + b) ^ 2` | power-folding | reached | reached |
| `x + x` → `2 * x` | linear-combination | reached | reached |
| `(2 * x + 4) / 2` → `x + 2` | rational-reduction | reached | reached |
| `(x^2 - 1) / (x - 1)` → `x + 1` | rational-cancellation | reached | reached |
| `(x^3 - 1) / (x - 1)` → `x ^ 2 + x + 1` | polynomial-division | **not reached** | reached |

Regelsuche reaches six of seven reference forms, SymPy reaches seven of seven. The track claim `target-free-simplification-head-to-head` is therefore still retained with status `NEGATIVE`.

#### What the first retained loss diagnosed

The first bundle recorded `(2 * x + 4) / 2` and `(x^2 - 1) / (x - 1)` as not reached. Replaying the untargeted search showed that this was not a search-budget effect: on `(2 * x + 4) / 2` the inventory produced **no applicable rewrite at all**, and on `(x^2 - 1) / (x - 1)` only the power/product normalisation fired. Four atomic capabilities were missing, and each is now a separate rule:

- `ast_distribute_division_over_sum` — `(A ± B) / C → A / C ± B / C`; introduces no side condition, because the input already divides by `C`;
- `ast_cancel_division_factor` — `(A * B) / A → B`, retaining `A != 0` as a step assumption unless the cancelled factor is a non-zero literal;
- `ast_fold_numeric_arithmetic` — folds a node over two integer literals, and only when a division divides exactly, so no rewrite silently introduces a rounded literal;
- `ast_square_literal_split` — `A ^ 2 - N → A ^ 2 - M ^ 2` for `N = M * M`, which makes a literal such as `1` visible to the existing atomic factor rule.

No textbook shortcut rule was added: the cancellation path is still derived step by step, and the factorisation itself remains the existing atomic `ast_square_difference_factor`.

#### Why the track keeps a loss

Closing a gap that a benchmark exposed must not turn the benchmark into a self-confirmation. The corpus therefore gained `(x^3 - 1) / (x - 1)`, which needs general polynomial division rather than a difference of two squares. This is consistent with [`limits.md`](limits.md): the rewrite inventory still has no general polynomial division. The track keeps producing information about a competing system instead of only about Regelsuche.

## Retaining losses instead of hiding them

The publication gate is **claim consistency**, not success. A comparative benchmark that can only be published when the local system wins every case cannot produce information about competing systems.

The gate is fail-closed in these ways:

- every configured evaluation must exist — the writer requires the complete Cartesian matrix per track;
- every result must have disposition `EXECUTED`; an unavailable, timed-out or crashed system means the evidence was never produced and must not replace a prior complete bundle;
- a claim status must equal the status derived from its own track's results, so a track containing an incorrect result cannot carry a `SUPPORTED` claim;
- every measured track must carry a retained claim.

An `EXECUTED` but incorrect result is legitimate retained evidence. Deleting it would be exactly the selective reporting that [`../paper/limitations.md`](../paper/limitations.md) forbids.

## Information parity

Each track has one `InformationParityManifest` containing visibility flags and hashes for the input corpus, inventory, budget, Research Brief, qualification split and mandatory evaluations. Each configuration references exactly one same-track manifest.

The first slice keeps all hidden-reference, family-label, TEST-label, qualification-label and review-label visibility flags false. Target visibility is true for the target-directed search and equality-validation tracks only, because those tracks explicitly compare target/equality-directed capabilities. The simplification-competition track sets it false: a competitor that saw the reference form would not be simplifying.

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

Publication additionally requires the complete Cartesian matrix of every configuration and every case in the same track. If even one configured evaluation is missing, the writer rejects the run before deleting or replacing any previously retained evidence.

Repeated runs with the fixed environment must produce byte-identical bundles. The verifier also checks that every standalone retained object is exactly the corresponding object embedded in `report.json`.

## Explicit coverage gaps

Unmeasured tracks are not omitted. The report currently retains machine-readable gaps for:

- an equality-saturation competitor, a randomized-valid competitor, a multi-domain corpus and an assumption-aware cancellation contract for the simplification-competition track;
- hidden-rule rediscovery using the #227 leakage controls;
- open-target discovery with an information-equivalent external generator;
- fully held-out cross-family transfer;
- autonomous campaign-controller comparison over the #355 Research Brief and ledger;
- at least three discovery-component ablations;
- at least two controller ablations.

A gap can disappear only when its required raw evidence is executed and retained under the corresponding information-parity manifest.

## Reproduction and verification

The complete benchmark contract runs from a normal checkout with:

```bash
bash scripts/run-comparative-benchmarks-verification.sh
```

Required host tools are a reachable Docker daemon, Python `venv` support and Z3 4.8.12. The runner creates a build-local environment with SymPy 1.14.0 and `jsonschema` 4.25.1 and then:

1. runs the benchmark JUnit suite;
2. writes two independent Gradle evidence bundles;
3. validates both bundles against the Draft-2020-12 schema and all parity, resource, reference, outcome and negative-schema invariants;
4. requires the two Gradle bundles to be byte-identical;
5. builds the pinned `Dockerfile.comparative-benchmarks` image;
6. generates and validates a third bundle in that image;
7. requires the Docker and Gradle bundles to be byte-identical.

The three retained roots and all diagnostics are written below:

```text
build/reports/comparative-ci/
  run-a/
  run-b/
  run-docker/
  *.log
```

The `Comparative Benchmarks` GitHub workflow only installs the declared host toolchain, invokes the same runner and publishes these files. It contains no benchmark assertions, expected score policy, schema mutations or Docker lifecycle semantics.
