# Claims and evidence registry

This registry is normative for the working manuscript. A central claim may appear in the Abstract, Results, or Conclusion only when its required evidence is retained and its status authorizes the exact bounded wording used there.

| ID | Proposed claim | Required evidence | Current status |
|---|---|---|---|
| C1 | Regelsuche forms executable rule candidates without a target expression or expected answer during formation. | Target-visibility audit; Research Brief; candidate-lineage roots; source-surface checks. | `SUPPORTED_EXISTING_METHOD` |
| C2 | Candidate lineage is content-addressed from observations through qualification. | Campaign manifest; mining evidence; observation lineage; qualification binding. | `SUPPORTED_EXISTING_METHOD` |
| C3 | Validation, counterexamples, project novelty, proof, utility, promotion, and Public Evidence are independent fail-closed axes. | Versioned schemas; release profile matrix; negative contract tests. | `SUPPORTED_EXISTING_METHOD` |
| C4 | Regelsuche executed the complete frozen candidate-independent benchmark with all configured campaigns and case slots retained. | #383 v2 execution index; challenge roots; clean-run and container verification. | `SUPPORTED_BOUNDED_383` |
| C5 | Regelsuche outperforms information-equivalent open-target baselines on one or more preregistered metrics. | Complete #235 open-target matrices and uncertainty/failure accounting. | `PENDING_235` |
| C6 | Specific discovery components and controller decisions causally contribute under fixed information and budgets. | At least three discovery and two controller ablations from #235. | `PENDING_235` |
| C7 | The TRAIN-derived reusable macros improve selected frozen held-out tasks without correctness regression under the candidate-independent macro track. | #383 macro formation, paired Best-First evidence, four-campaign batch and v2 binding. | `SUPPORTED_BOUNDED_383` |
| C8 | Discovery cost amortizes over a frozen downstream task stream, or no break-even is observed for the complete lifecycle. | Complete vector cost ledger and #384 end-to-end amortization report. | `PARTIAL_384` |
| C9 | The evaluated benchmark artifact is independently reproducible through the retained checkout-local and pinned-container contracts. | Independent reproduction artifact and #383 v2 clean-run bindings. | `SUPPORTED_EXISTING_ARTIFACT` |
| C10 | Expert judgments agree with the interestingness ranking. | Real blinded study from #332/#389. | `OPTIONAL_PENDING_389` |
| C11 | A candidate is externally novel mathematics. | Complete external novelty protocol and decision from #391. | `NOT_AUTHORIZED` |

## Rules

1. `SUPPORTED_EXISTING_METHOD` authorizes a methods description, not a new broad empirical conclusion.
2. `SUPPORTED_BOUNDED_383` authorizes only the exact candidate-independent benchmark or macro-track wording bound to the generated #383 table.
3. `SUPPORTED_EXISTING_ARTIFACT` authorizes a reproducibility statement about the retained artifact contracts, not independent replication by an unaffiliated research group.
4. `PARTIAL_384` may report vector costs and the bounded macro reference, but not a complete lifecycle break-even.
5. `PENDING_*` claims may be discussed as hypotheses or evaluation questions only.
6. `NOT_AUTHORIZED` claims must not appear as positive findings.
7. A negative or null result can satisfy an evaluation dependency when the preregistered protocol is complete and the null result is reported transparently.
8. Every quantitative sentence must identify its generated table, figure, or artifact root.
