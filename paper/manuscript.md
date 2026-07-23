# Claim-Bounded Autonomous Symbolic Rule Discovery with Machine-Checkable Evidence

**Working methods-paper evaluation — not an archival result release**

## Abstract

Symbolic systems can generate transformation candidates, validate identities, search for counterexamples, and accelerate later problem solving. These capabilities are often reported through one overloaded success label, making it difficult to distinguish candidate formation from validation, proof, novelty, utility, and reproducibility. We present the Regelsuche evidence architecture for target-free symbolic rule discovery. The system derives executable rule candidates from self-generated search observations and retains separate, fail-closed artifacts for lineage, validation, counterexamples, project-internal novelty, proof strength, held-out utility, lifecycle disposition, and claim authorization. A preregistered candidate-independent benchmark has now been executed completely across three structural challenges: all 12 configured campaigns and 72 frozen case slots are retained, including 20 no-result slots and zero correctness regressions. The present manuscript reports this bounded execution result and the evidence architecture. Information-parity baseline comparisons, required ablations, complete lifecycle amortization, external mathematical novelty, and expert-rated interestingness remain open and are not inferred from the benchmark.

## 1. Research question

The primary research question is:

> Can a target-free symbolic system form executable reusable rules from self-generated search observations and evaluate formation, validation, counterexamples, project-internal novelty, proof strength, held-out utility, and reproducibility as separate fail-closed evidence axes?

The question concerns autonomous symbolic rule formation and evidence architecture. It does not presume externally novel mathematics.

## 2. Contributions under evaluation

The manuscript evaluates the following bounded contributions:

1. a target-free pipeline from a versioned Research Brief and seed generation to executable candidate rules;
2. exact candidate lineage across generation, aggregate mining, validation, and qualification;
3. explicit separation of mathematical validity, project novelty, external novelty, proof, interestingness, utility, promotion, and Public Evidence;
4. leakage-resistant boundaries between candidate formation, selection, qualification, and final TEST evaluation;
5. complete positive, negative, counterexample, unsupported, incomplete, and zero-output accounting;
6. paired held-out utility under identical information and search budgets;
7. canonical evidence reproduced across clean runs and pinned containers;
8. domain-neutral discovery contracts demonstrated on distinct mathematical object types;
9. machine-readable authorization of exact claims rather than a single `discovered` flag.

The candidate-independent execution result supports only the bounded claims listed as `SUPPORTED_BOUNDED_383` in `claims-and-evidence.md`. Comparative superiority and complete amortization remain under evaluation.

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

The benchmark from #383 was preregistered and frozen before evaluated execution. It contains 18 cases across three independent challenges:

- rational assumption-sensitive rewrites;
- finite-difference and linear-recurrence sequence models;
- reusable search macros.

Each challenge contains two cases in each of TRAIN, VALIDATION, and TEST. Four deterministic campaigns are configured per challenge. Candidate formation can read only the declared TRAIN formation surface; targets, expected answers, hidden references, TEST labels, and post-hoc family labels remain unavailable during formation.

The execution retains positive, negative, unsupported, ambiguous, refuted-model, and no-result outcomes. Multiple candidate forms remain separate where a mathematical prefix admits more than one compatible model. No campaign or case was removed after TEST inspection.

## 6. Candidate-independent benchmark results

The generated table `paper/generated/candidate-independent-benchmark.md` is produced from the canonical `regelsuche.candidate-independent-benchmark-execution/v2` artifact. It binds every number to the benchmark execution content hash.

Across the three challenges, all 12 configured campaigns and all 72 frozen case slots were executed. The aggregate contains 52 successful case slots, 20 retained no-result slots, 120 challenge-native detailed evidence rows, and zero correctness regressions.

These totals are accounting results, not a universal cross-domain success score. The challenges have different candidate forms and evaluation semantics:

- the sequence challenge confirms every frozen case by at least one preregistered candidate form while retaining a refuted alternative model where applicable;
- the rational challenge reaches direct, affine, and parameterized factor-cancellation families while retaining literal-square, partial-fraction, and nested-division no-results;
- the macro challenge forms three TRAIN-derived reusable macros and records selected held-out improvements, neutral successful pairs, and explicit no-results without correctness regression.

The benchmark aggregate does not evaluate external novelty, expert interestingness, or formal proof and does not authorize publication by itself.

## 7. Baselines and ablations

Comparisons are track-scoped under #235. Target-directed search, hidden-rule rediscovery, open-target formation, cross-family transfer, equality validation, proof, and campaign control are not collapsed into one leaderboard.

The open-target evaluation still requires:

- an information-equivalent enumerative or grammar-based rule-induction baseline;
- a randomized valid-candidate baseline;
- the unmodified Regelsuche production configuration;
- at least three discovery-component ablations;
- at least two campaign-controller ablations.

All configurations must receive the same information and budgets within a track. Unsupported capabilities remain visible. No baseline-superiority conclusion is made in the present revision.

## 8. Utility and amortization

Paired held-out evaluation compares baseline search with search using exactly one frozen retained candidate. Inputs, targets, inventory, strategy, and budgets remain identical. Correctness regressions are blocking.

The macro track provides bounded candidate-independent paired evidence: selected held-out binomial tasks are solved with fewer explored states and candidate evaluations, while other successful pairs remain neutral and unsupported cross-family tasks remain no-results. This supports the exact bounded utility claim C7; it does not establish end-to-end amortization.

The cost study in #384 retains vector resources without implicit conversion. Its current phase reports 36,000 configured versus 756 executed explored states, 7,200 configured versus 760 executed candidate evaluations, and 1,200 configured proof attempts with none executed. For one macro campaign reference, 13 formation candidate evaluations amortize at the first downstream task, whereas 25 formation states do not amortize within the twelve-task stream. Counterexample, novelty, proof, and qualification costs are incomplete, so the authoritative overall lifecycle status remains `NOT_ESTABLISHED`.

## 9. Reproducibility

The artifact distinguishes:

- exact byte reproduction;
- semantic reproduction through canonical roots;
- non-reproduction with retained mismatches.

The benchmark execution is reproduced through checkout-local Gradle contracts and a pinned independent container artifact. Reproduction includes the frozen corpus, all challenge runs, aggregate roots, schemas, independent recomputation, and negative mutation tests. This establishes the repository's retained artifact contract; it is not represented as an unaffiliated replication study.

## 10. Remaining result dependencies

The following primary result families remain pending:

- information-parity baseline and ablation comparisons;
- complete end-to-end cost and amortization;
- any empirical expert-interestingness result;
- any external mathematical novelty decision;
- final archival release and DOI.

Failures, null results, unsupported cases, and incomplete evidence remain reportable results rather than grounds for post-hoc removal.

## 11. Limitations

The mandatory limitations are maintained in `limitations.md`. The final paper must discuss candidate simplicity, benchmark scope, evaluator coverage, external novelty status, reviewer status, compute bounds, and the distinction between symbolic validation and formal proof.

The retained production candidate is elementary algebra. The candidate-independent benchmark broadens structural coverage but remains a finite, project-defined corpus. Complete execution does not imply universal theorem discovery, mathematical importance, or superiority over information-equivalent alternatives.

## 12. Related work

The final related-work review will cover symbolic term rewriting, equality saturation, inductive and grammar-guided program synthesis, automated conjecture generation, theorem proving, algorithm and mathematical discovery systems, benchmark leakage, and reproducible artifact evaluation.

Candidate generation must be distinguished from candidate validation and proof. A system that validates an equality is not treated as a failed open-target generator, and a generator receives no proof credit it did not produce.

## 13. Conclusion

Regelsuche demonstrates a content-addressed, fail-closed architecture for target-free symbolic rule formation and has completed a preregistered candidate-independent benchmark across three structural challenges without dropping no-result cases or conflating evidence axes. The bounded benchmark execution is now reportable. Comparative superiority, complete lifecycle break-even, externally novel mathematics, and expert-rated importance remain open questions and are deliberately excluded from the present conclusion.
