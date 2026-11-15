# Claim-Bounded Autonomous Symbolic Rule Discovery with Machine-Checkable Evidence

**Working methods-paper foundation — not an archival result release**

## Abstract

Symbolic systems can generate transformation candidates, validate identities, search for counterexamples, and accelerate later problem solving. These capabilities are often reported through one overloaded success label, making it difficult to distinguish candidate formation from validation, proof, novelty, utility, and reproducibility. We present the Regelsuche evidence architecture for target-free symbolic rule discovery. The system derives executable rule candidates from self-generated search observations and retains separate, fail-closed artifacts for lineage, validation, counterexamples, project-internal novelty, proof strength, held-out utility, lifecycle disposition, and claim authorization. We evaluate two claims that can be tested on retained public evidence. Changing how a controller allocated candidate slots left the surviving output unchanged in a one-round campaign. In a separate recurrence experiment, production and both grammar controls found models for a TRAIN sequence, but none transferred to the VALIDATION or TEST cases. These observations bound what the system demonstrated on the two protocols. Broader performance and end-to-end cost claims remain pending. [Controller outcomes](generated/public-controller-results.md) · [Recurrence outcomes](generated/public-recurrence-results.md)

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

The registered public corpus separates cases used for formation from cases reserved for evaluation. The split labels describe roles within a public development corpus. A human who already knows a published case could select a grammar or search bound favorable to it. Restricting formation inputs and registering the comparison plan limit what the program can use during execution. They cannot remove prior knowledge from the experimental design. Independent sealed cases would be needed to assess performance on previously unseen material. The generated split table counts each challenge directly from the case manifest, checks each case identity against its contents and binds the corpus to its freeze receipt. The table tests the stated information policy. Determining what a particular learner actually consumed requires that learner's execution receipts. [Corpus splits and declared access](generated/public-corpus-splits.md)

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

The clean package build regenerates the tables and figure, verifies the source tree and executable modes, and renders the PDF. Offline replay also checks each file against the archive inventory. These checks test the manuscript and its public evidence at a named code revision. An independent scientific reproduction requires a separately identified execution and its receipt. [Archive verification](../docs/paper-foundation-verification.md)

The independent artifact in #387 must execute without undocumented maintainer knowledge, undeclared network access, or mutable external inputs. Corrective changes receive new immutable artifact identities.

## 9. Results

The complete primary evaluation remains pending. The following results derive from two public comparisons with different units of observation.

### Retrospective controller comparison

The public one-round campaign compared the production feedback controller with two fixed policies. The comparison tests whether reallocating candidate slots between two mining batches changes which conjecture survives. The paired-observation batch supplies a negative control for diversity of support. The allocation figure shows how each policy divided the available slots. The table reports each batch's outputs and rejected clusters, the executed positive and negative holdouts, the next decisions and the stopping reason. The resource table retains the original units for every stage. [Generated tables and source revision](generated/public-controller-results.md) · [Allocation figure](generated/public-controller-allocation.svg)

The feedback controller found no eligible continuation after qualification. Both fixed policies stopped at the horizon specified in their configurations. The observed agreement therefore leaves the value of feedback on campaigns with competing continuations open. Equal slots in one mining stage also leave open how resources should be allocated between stages. The historical observations were public before this comparison was planned. [Experiment protocol](../docs/autopilot-controller-comparison.md)

### Open-target recurrence formation and reuse

The production fitter solved for recurrence coefficients from two TRAIN prefixes. The deterministic and randomized controls proposed coefficients from the same bounded grammar. Each arm froze its model set before scoring the six public cases. Agreement with an observed prefix and agreement with its subsequent terms were both required. All three arms repeatedly fit the same geometric TRAIN sequence. None of their frozen sets confirmed any VALIDATION or TEST case. The randomized arm also produced an empty campaign. Its duplicate draws and every case in that campaign remain in the denominators. A fixed cap on submitted candidates leaves the solvers free to incur different internal work. The experiment measures reuse within a bounded recurrence grammar. [Generated formation and per-split outcomes](generated/public-recurrence-results.md) · [Independent replay and experimental design](../docs/track-c-recurrence-comparison.md)

### Outstanding primary evaluation

Required result families include:

- campaign and candidate terminal accounting;
- split and leakage audits;
- positive, negative, unsupported, and incomplete evaluation;
- baseline and ablation comparisons;
- per-case paired utility;
- cumulative amortization and break-even;
- reproduction matrix;
- claim-status matrix;
- failure taxonomy.

No primary result may be copied manually into the manuscript.

## 10. Limitations

The public controller replay offers no choice among eligible follow-up branches. Agreement among the three policies leaves their behavior in a longer campaign unresolved. In the recurrence comparison, a finite geometric prefix admits several coefficient tuples. Agreement with its finite continuation tests those predictions, while an infinite-sequence identity would require a proof. Repeated campaigns on the same cases also share their mathematical content. The reported slots cannot serve as independent samples of mathematical families. [Controller outcomes](generated/public-controller-results.md) · [Recurrence outcomes](generated/public-recurrence-results.md)

Both studies measure performance under fixed information, grammars and budgets. Neither collects independent human judgments, tests the protected flagship corpus or establishes external novelty. End-to-end savings require a task stream frozen before evaluation, per-task paired search receipts and a complete discovery-cost ledger. We draw no break-even conclusion from these public comparisons. [Limitations and claim boundaries](limitations.md)

## 11. Related work

### Rewriting and equality saturation

Tate et al. accumulate equalities between program fragments and then choose an implementation using a cost model. The `egg` library makes this approach extensible through e-class analyses that attach domain-specific facts to equivalence classes. These methods explain how a system can find a cheaper expression using rules it already knows. Regelsuche's shared-fragment comparison therefore fixes the visible rules and measures extraction cost and certificate verification. Learning a reusable rule from the search requires a separate experiment with that rule withheld. [Tate et al., POPL 2009](https://www.cs.cornell.edu/~ross/publications/eqsat/) · [Willsey et al., POPL 2021](https://doi.org/10.1145/3434304)

### Synthesis and conjecture formation

In syntax-guided synthesis, a grammar defines candidate implementations and a logical specification defines correctness. Alur et al. describe solvers that use counterexamples to refine candidate programs. The information supplied to the solver determines which Regelsuche comparison it can enter. A solver given a desired input-output relation can address a target-directed task. An open-target enumerative baseline must receive only the grammar, observations and budget available to the discovery system while candidates are formed. [Alur et al., FMCAD 2013](https://doi.org/10.1109/FMCAD.2013.6679385)

QuickSpec takes a collection of pure functions and proposes algebraic laws by testing expressions built from them. This is a closer comparison for the origin of a conjecture. An equation that survives those tests is a candidate for further investigation. In the proposed evaluation, the record of how a candidate was generated is retained even when a later counterexample invalidates it. Coverage, refutation and formal proof can therefore have different denominators. [Claessen, Smallbone and Hughes, 2010](https://doi.org/10.1007/978-3-642-13977-2_3)

### Mathematical discovery and proof

Davies et al. used learned predictions and feature attribution to guide mathematicians studying relationships between knot invariants. Fawzi et al. supplied AlphaTensor with a specific matrix-multiplication tensor and searched for efficient decompositions over a finite set of factors. The former study identifies the human contribution to formulating a conjecture. The latter specifies the mathematical task before the search begins and evaluates the resulting algorithms against an explicit operation or hardware cost. For a proposed Regelsuche discovery, this distinction calls for recording who supplied the question, which target was visible and how reuse was measured. [Davies et al., Nature 2021](https://doi.org/10.1038/s41586-021-04086-x) · [Fawzi et al., Nature 2022](https://doi.org/10.1038/s41586-022-05172-4)

Lean checks an elaborated proof term against a theorem type in a particular environment. A meaningful comparison with a proof assistant starts once the candidate, premises and translation have been fixed. Regelsuche's checked Lean bridge binds the resulting theorem to the requested statement and audits its transitive axiom dependencies. A process that only generated tactic text has reached an earlier stage. [de Moura and Ullrich, CADE 2021](https://doi.org/10.1007/978-3-030-79876-5_37) · [Lean language reference](https://lean-lang.org/doc/reference/latest/Elaboration-and-Compilation/) · [Regelsuche proof bridge](../docs/proof-bridge.md)

### Holdout reuse and artifact evaluation

Dwork et al. examine how choosing each analysis in response to earlier results can compromise inference on reused data. They give a privacy-based mechanism for adaptive access to a holdout. The protocol in Section 5 addresses the same source of leakage with a different design. Validation selects the configuration, then a single final evaluation records every configured case. A crash after the final data have been revealed is a reportable outcome, since rerunning with a different configuration would let that disclosure influence selection. [Dwork et al., Science 2015](https://doi.org/10.1126/science.aaa9375)

ACM's artifact policy distinguishes permanent availability, an audit of the software and data, and independent validation of reported results. For this study, two clean runs can test whether the supplied procedure emits identical canonical bytes. The separate reproduction record must identify the independent team, the artifacts it used and which claims it reproduced. An archive identifier establishes where to retrieve a version. [ACM, Artifact Review and Badging v1.1](https://www.acm.org/publications/policies/artifact-review-and-badging-current)

## 12. Ethics, tool use and provenance

The controller comparison analyses machine-generated search observations. Its inputs, configuration and rejection receipts are part of the source artifact. Automated generation of the manuscript tables preserves the configured policies, including unsuccessful mining branches. Proof and novelty decisions in other studies require their own evidence. No expert labels, participant quotations or unpublished candidate statements enter this comparison.

The repository distributes the implementation and associated documentation under the MIT license. Its third-party notices identify separate component licenses. The public controller matrix is bound to an exact revision and an uncompressed SHA-256 digest in the generated report. The manuscript and its declared source files are independently checked against the paper manifest. Automated assistance was used to prepare parts of the software, tests and manuscript. The authors' individual contributions, conflicts of interest, funding statements and approval for deposit remain pending. [Repository license](../LICENSE) · [Third-party notices](../THIRD_PARTY_NOTICES.md) · [Paper manifest](paper-artifact-manifest.json)

## 13. Conclusion

The public comparisons distinguish forming a candidate from choosing a continuation and transferring a frozen candidate to another case. The controller retained the same output under every allocation. The recurrence formers recovered a TRAIN pattern but had no confirmed transfer on the registered VALIDATION or TEST cases. Retaining the rejected attempts, empty campaign and unchanged controller outcomes makes both conclusions reproducible from their source records. The full open-target and cross-family baseline and ablation comparisons remain open. [Controller results](generated/public-controller-results.md) · [Recurrence results](generated/public-recurrence-results.md)
