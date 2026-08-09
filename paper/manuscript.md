# Claim-Bounded Autonomous Symbolic Rule Discovery with Machine-Checkable Evidence

**Working methods-paper foundation — not an archival result release**

## Abstract

Symbolic systems can generate transformation candidates, validate identities, search for counterexamples, and accelerate later problem solving. These capabilities are often reported through one overloaded success label, making it difficult to distinguish candidate formation from validation, proof, novelty, utility, and reproducibility. We present the Regelsuche evidence architecture for target-free symbolic rule discovery. The system derives executable rule candidates from self-generated search observations and retains separate, fail-closed artifacts for lineage, validation, counterexamples, project-internal novelty, proof strength, held-out utility, lifecycle disposition, and claim authorization. A separately preregistered public systems showcase has additionally exercised the fail-closed boundary in practice: its one-attempt v1 TRAIN run produced no freeze-eligible terminal candidate, so candidate freeze, public randomness, and FINAL TEST were never reached. This null systems result is not evidence of self-improvement, but it demonstrates that an unmet candidate-formation gate can terminate the protocol before hidden test material exists. This working manuscript defines the methods and evaluation protocol. Final quantitative conclusions are intentionally deferred until the preregistered candidate-independent benchmark, information-parity baselines, ablations, end-to-end cost analysis, and independent reproduction are complete.

## 1. Research question

The primary research question is:

> Can a target-free symbolic system form executable reusable rules from self-generated search observations and evaluate formation, validation, counterexamples, project-internal novelty, proof strength, held-out utility, and reproducibility as separate fail-closed evidence axes?

The question concerns autonomous symbolic rule formation and evidence architecture. It does not presume externally novel mathematics.

## 2. Contributions under evaluation

The manuscript will evaluate the following bounded contributions:

1. a target-free pipeline from a versioned Research Brief and seed generation to executable candidate rules;
2. exact candidate lineage across generation, aggregate mining, validation, and qualification;
3. explicit separation of mathematical validity, project novelty, external novelty, proof, interestingness, utility, promotion, and Public Evidence;
4. leakage-resistant boundaries between candidate formation, selection, qualification, and final TEST evaluation;
5. complete positive, negative, counterexample, unsupported, incomplete, and zero-output accounting;
6. paired held-out utility under identical information and search budgets;
7. canonical evidence that can be reproduced across clean runs and pinned containers;
8. domain-neutral discovery contracts demonstrated on distinct mathematical object types;
9. machine-readable authorization of exact claims rather than a single `discovered` flag.

The final paper will retain only contributions whose evidence requirements in `claims-and-evidence.md` are satisfied.

## 3. Threat model and scientific boundaries

Regelsuche must generate the search observations and candidate lineage itself. Oracles, computer algebra systems, SMT solvers, and theorem provers may validate or refute frozen candidates; they may not provide hidden targets, expected answers, reference rules, final TEST labels, or post-hoc success annotations during formation.

The evaluation treats the following as independent axes:

- candidate formation;
- mathematical validation;
- counterexample search;
- project-internal novelty;
- external mathematical novelty;
- symbolic or formal proof strength;
- expert-rated interestingness;
- downstream search utility;
- promotion;
- Public Evidence eligibility.

A stronger axis cannot be inferred from a weaker one. In particular, project-internal novelty is not external novelty, absence of a found counterexample is not proof, and search acceleration is not mathematical importance.

## 4. System overview

The target-free production path is organized as a content-addressed evidence graph:

```text
Research Brief
  -> generator and seed identities
  -> untargeted search observations
  -> aggregate candidate formation
  -> executable candidate compilation
  -> positive and negative validation
  -> counterexample search
  -> project-novelty assessment
  -> proof obligation and solver result
  -> conservative lifecycle handoff
  -> independent qualification and paired reuse
  -> claim-bounded release profile
```

Every transition retains source identities, configured and executed resources, terminal outcomes, and canonical content hashes. Missing mandatory evidence blocks the corresponding claim.

## 5. Candidate-independent experimental design

The final evaluation will use the corpus and split protocol from #383, informed by the evaluator-backed challenge portfolio in #390. Corpus construction, structural clusters, split assignments, budgets, metrics, and thresholds must be frozen before evaluated campaigns run.

The design requires:

- multiple independent structural clusters;
- disjoint generation/TRAIN, selection/VALIDATION, and final TEST families and structural signatures;
- positive, negative, boundary, unsupported, and no-result cases;
- retention of every configured campaign, including zero-output and disproved branches;
- fixed policies for multiple valid outputs and ambiguous candidates;
- no selective reruns after TEST inspection.

## 6. Baselines and ablations

Comparisons are track-scoped under #235. Target-directed search, hidden-rule rediscovery, open-target formation, cross-family transfer, equality validation, proof, and campaign control are not collapsed into one leaderboard.

The final open-target evaluation will include at least:

- an information-equivalent enumerative or grammar-based rule-induction baseline;
- a randomized valid-candidate baseline;
- the unmodified Regelsuche production configuration;
- at least three discovery-component ablations;
- at least two campaign-controller ablations.

All configurations must receive the same information and budgets within a track. Unsupported capabilities remain visible.

## 7. Utility and amortization

Paired held-out evaluation compares baseline search with search using exactly one frozen retained candidate. Inputs, targets, inventory, strategy, and budgets remain identical. Correctness regressions are blocking.

The end-to-end study in #384 additionally accounts for generation, search, mining, validation, counterexample, novelty, proof, and qualification cost. Vector resource accounting is authoritative. Optional scalar profiles must be preregistered and accompanied by sensitivity analysis. The result must state a reproducible break-even index or `NO_BREAK_EVEN_OBSERVED`.

## 8. Reproducibility

The archival artifact will distinguish:

- exact byte reproduction;
- semantic reproduction through canonical roots;
- non-reproduction with retained mismatches.

The independent artifact in #387 must execute without undocumented maintainer knowledge, undeclared network access, or mutable external inputs. Corrective changes receive new immutable artifact identities.

## 9. Results

Primary benchmark tables and figures are intentionally absent from this foundation revision. They will be generated from retained evidence after #383, #235, and #384 freeze their evaluated outputs.

### 9.1 Public fail-closed protocol stress test

A smaller public proof-carrying self-improvement showcase was run separately from the publication-grade evaluation. Its purpose was to freeze one learned human-readable rewrite program before any hidden FINAL TEST material existed, and only then derive a future holdout from externally verifiable public randomness.

The one-attempt v1 authority was consumed exactly once. The checkout-owned `ciCheck` completed successfully and the real TRAIN population executed. At deterministic terminal selection, however, no retained alternative satisfied the preregistered freeze-eligibility policy. Candidate formation therefore terminated before a candidate freeze could be published.

The retained boundary is:

```text
TRAIN:                 EXECUTED
eligible selection:    NOT PRODUCED
candidate freeze:      NOT CREATED
public randomness:     NOT CONSUMED
FINAL TEST seed:       NOT DERIVED
FINAL TEST cases:      NOT GENERATED
FINAL TEST execution:  NOT RUN
showcase claim:         NOT ESTABLISHED
```

This is a candidate-formation null result, not a hidden-test failure. It supports only claim C12 in `claims-and-evidence.md`: the protocol stopped before hidden test material existed when its earlier frozen eligibility gate was unmet. It does not support self-improvement, baseline superiority, held-out utility, external novelty, or mathematical importance. The consumed v1 run is immutable and is not rerun or reconstructed through later TRAIN-only characterization.

This stress test also exposed engineering requirements for future experiments: null terminal selection must retain complete per-candidate blockers, TRAIN-only characterization must be explicitly separated from one-attempt authority, and population/mutation execution semantics must be versioned so scheduler changes cannot silently preserve an old study identity. Those corrections are methods infrastructure, not a reinterpretation of v1.

### 9.2 Pending primary evaluation

Required primary result families remain:

- campaign and candidate terminal accounting;
- split and leakage audits;
- positive, negative, unsupported, and incomplete evaluation;
- baseline and ablation comparisons;
- per-case paired utility;
- cumulative amortization and break-even;
- reproduction matrix;
- claim-status matrix;
- failure taxonomy.

No primary quantitative result may be copied manually into the manuscript.

## 10. Limitations

The mandatory limitations are maintained in `limitations.md`. The final paper must discuss candidate simplicity, benchmark scope, evaluator coverage, external novelty status, reviewer status, compute bounds, and the distinction between symbolic validation and formal proof. The public showcase-v1 null result additionally demonstrates that deterministic candidate formation can fail before held-out evaluation; later improvements to training, scheduling, or preflight are new methods versions and cannot retroactively alter that result.

## 11. Related work

The final related-work review will cover symbolic term rewriting, equality saturation, inductive and grammar-guided program synthesis, automated conjecture generation, theorem proving, algorithm and mathematical discovery systems, benchmark leakage, and reproducible artifact evaluation.

Candidate generation must be distinguished from candidate validation and proof. A system that validates an equality is not treated as a failed open-target generator, and a generator receives no proof credit it did not produce.

## 12. Conclusion

This foundation defines a falsifiable, claim-bounded evaluation of autonomous symbolic rule discovery. The public showcase-v1 null result provides one concrete demonstration of the fail-closed stage boundary without establishing the stronger empirical claims under evaluation. The final conclusion will be written only after the candidate-independent experiments, comparisons, amortization study, and independent artifact reproduction are frozen.