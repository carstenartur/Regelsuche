# Claim-Bounded Autonomous Symbolic Rule Discovery with Machine-Checkable Evidence

**Working methods-paper foundation — not an archival result release**

## Abstract

Symbolic systems can generate transformation candidates, validate identities, search for counterexamples, and accelerate later problem solving. These capabilities are often reported through one overloaded success label, making it difficult to distinguish candidate formation from validation, proof, novelty, utility, and reproducibility. We present the Regelsuche evidence architecture for target-free symbolic rule discovery. The system derives executable rule candidates from self-generated search observations and retains separate, fail-closed artifacts for lineage, validation, counterexamples, project-internal novelty, proof strength, held-out utility, lifecycle disposition, and claim authorization. The public comparisons cover candidate formation, held-out reuse, component ablations and campaign allocation. The tested sequence models did not transfer to the held-out cases, and the learned bridge added no transformations to native search. The feedback controller and uniform allocation completed the same lineages on both continuation panels. Each scheduler recovered 19 of 20 rules with the historical validator and 13 with the exact validator. The results describe the registered public panels. Broader performance and end-to-end cost remain open. [Generated public comparisons](generated/public-discovery-comparisons.md)

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

The public open-target comparison uses the corpus and split protocol from #383 and the evaluator-backed challenge portfolio in #390. The case payloads, structural clusters and split assignments were public before the comparative algorithms ran. Each prospective comparison registered its formation inputs, resource bounds and scoring rules before measurement.

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

For each open-target form, the public comparison ran the production former, an ordered enumeration or grammar policy, and a seeded random policy on identical permitted TRAIN inputs. Each arm froze its selected candidates before evaluation. Discovery ablations and controller allocation use separate public panels with their own assumptions and resource accounts. [Generated comparative tables](generated/public-discovery-comparisons.md)

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

The complete primary evaluation remains pending. The public experiments below report candidate formation, held-out reuse, paired search utility and campaign completion in their respective units. The public comparison report also contains the target-directed search, simplification and equality-validation results with their task-specific limits. [Public comparison report](../research/benchmarks/comparative-v2/report.json)

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

### Open-target formation, transfer and controller allocation

Four frozen comparisons cover the three challenges and four candidate forms in the public eighteen-case corpus. Neither sequence form transferred to a VALIDATION or TEST case. All rational arms reached the same two symbolic difference-of-squares TEST tasks and missed the two nested-reciprocal TEST tasks. The macro arms improved a TRAIN path and recorded no held-out improvement. Each campaign repeated the same public evaluation cases. The historical inventory audit shows how different definitions of novelty affect interpretation. A macro classified as new by the seven-rule checker matched a pattern emitted by an earlier project run through different operations. [Evidence-generated formation, evaluation and historical inventory tables](generated/public-discovery-comparisons.md)

On a separate bridge panel, eight source-only searches formed a coefficient-gap bridge. Native factorization reached both intended held-out targets with or without that bridge. The bridge matcher emitted no moves. On the seven-stage controller panel, receipt-driven and uniform scheduling completed the same lineages using the same native work. The static policy completed fewer lineages when it had already allocated its chains to the first batch. The panel contained one distinct conjecture. [Paired transfer table and controller allocation figure](generated/public-discovery-comparisons.md)

The archived component comparison used the public fixture revision and pre-#1245 counterexample validator pinned by its protocol. Under that validator, production pooled 23 observations and formed five candidates. Per-observation mining formed none. Removing project-novelty scheduling changed four scores and left the selected order unchanged at all 47 prefix budgets. Disabling counterexample-guided refinement produced no difference in terminal dispositions. The separate unguarded-log challenge inferred three positivity guards but produced no refuting witness or revised hypothesis in any arm. This null control limits conclusions about refinement under that validator. [Matched discovery-component ablations](generated/public-discovery-comparisons.md)

Repeating the component protocol with the exact-arithmetic validator preserved candidate formation and all ranked prefixes. One rational cancellation candidate became inconclusive because the evaluator found no defined scalar pair within the configured bounds. This accounts for one terminal check in each of the three nonempty arms. The four other candidates kept their bounded no-counterexample outcomes. In the separate twenty-task hidden-rule replay, each search recovered 13 rules under the exact validator and 19 under the historical validator. Each had zero false positives among the negative holdouts it actually ran (26 exact, 38 historical). [Results and denominators by validator version](generated/public-discovery-comparisons.md)

## 10. Limitations

The public controller replay offers no choice among eligible follow-up branches. Agreement among the three policies leaves their behavior in a longer campaign unresolved. In the recurrence comparison, a finite geometric prefix admits several coefficient tuples. Agreement with its finite continuation tests those predictions, while an infinite-sequence identity would require a proof. Repeated campaigns on the same cases also share their mathematical content. The reported slots cannot serve as independent samples of mathematical families. [Controller outcomes](generated/public-controller-results.md) · [Recurrence outcomes](generated/public-recurrence-results.md)

Each public study uses its own fixed information, grammar and budget. Independent human judgments, protected flagship evaluation and external novelty remain outside the public result scope. End-to-end savings require a task stream frozen before evaluation, per-task paired search receipts and a complete discovery-cost ledger. We draw no break-even conclusion from these public comparisons. [Limitations and claim boundaries](limitations.md)

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

The four frozen form comparisons show where selected candidates transfer. The rational arms reached the same bounded TEST tasks, while neither sequence fitting nor the macro comparison established held-out improvement. The bridge panel produced a null result even though native search reached the intended targets. The seven-stage controller comparison tied uniform allocation on native work and lifecycle completions. These outcomes concern the registered public panels, primitive inventories and resource ceilings. Claims about amortized discovery cost, external novelty and independent qualification require their own evidence. [Generated comparison results](generated/public-discovery-comparisons.md)
