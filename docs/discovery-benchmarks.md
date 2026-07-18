# Comparative Discovery Benchmarks

Issue #235 defines comparative evidence by capability and information regime. The benchmark layer must not collapse target-directed search, hidden-rule rediscovery, open-target conjecture generation, transfer, equality validation, proof and autonomous campaign control into one leaderboard.

The authoritative report schema is `regelsuche.comparative-benchmark/v1`. Every report carries the fixed policy:

```text
NO_UNIVERSAL_SCORE_TRACK_SCOPED_CLAIMS_ONLY
```

## First executable slice

The initial suite `comparative-baselines-initial/v1` measures two separate tracks.

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

## Information parity

Each track has one `InformationParityManifest` containing visibility flags and hashes for the input corpus, inventory, budget, Research Brief, qualification split and mandatory evaluations. Each configuration references exactly one same-track manifest.

The first slice keeps all hidden-reference, family-label, TEST-label, qualification-label and review-label visibility flags false. Target visibility is true only because both measured tracks explicitly compare target/equality-directed capabilities.

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
