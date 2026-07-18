# Claims and evidence registry

This registry is normative for the working manuscript. A central claim may appear in the Abstract, Results, or Conclusion only when its required evidence is retained and its status is `SUPPORTED`.

| ID | Proposed claim | Required evidence | Current foundation status |
|---|---|---|---|
| C1 | Regelsuche forms executable rule candidates without a target expression or expected answer during formation. | Target-visibility audit; Research Brief; candidate-lineage roots; source-surface checks. | `SUPPORTED_EXISTING_METHOD` |
| C2 | Candidate lineage is content-addressed from observations through qualification. | Campaign manifest; mining evidence; observation lineage; qualification binding. | `SUPPORTED_EXISTING_METHOD` |
| C3 | Validation, counterexamples, project novelty, proof, utility, promotion, and Public Evidence are independent fail-closed axes. | Versioned schemas; release profile matrix; negative contract tests. | `SUPPORTED_EXISTING_METHOD` |
| C4 | Autonomous rule discovery succeeds on a candidate-independent preregistered corpus. | #390 challenge portfolio and completed #383 benchmark. | `PENDING_383` |
| C5 | Regelsuche outperforms information-equivalent open-target baselines on one or more preregistered metrics. | Complete #235 open-target matrices and uncertainty/failure accounting. | `PENDING_235` |
| C6 | Specific discovery components and controller decisions causally contribute under fixed information and budgets. | At least three discovery and two controller ablations from #235. | `PENDING_235` |
| C7 | Retained rules provide held-out utility without correctness regression. | Candidate-independent paired task evidence from #383/#384. | `PENDING_383_384` |
| C8 | Discovery cost amortizes over a frozen downstream task stream, or no break-even is observed. | Complete vector cost ledger and #384 amortization report. | `PENDING_384` |
| C9 | The archival result is independently reproducible. | External receipt from #387. | `PENDING_387` |
| C10 | Expert judgments agree with the interestingness ranking. | Real blinded study from #332/#389. | `OPTIONAL_PENDING_389` |
| C11 | A candidate is externally novel mathematics. | Complete external novelty protocol and decision from #391. | `NOT_AUTHORIZED` |

## Rules

1. `SUPPORTED_EXISTING_METHOD` authorizes a methods description, not a new broad empirical conclusion.
2. `PENDING_*` claims may be discussed as hypotheses or evaluation questions only.
3. `NOT_AUTHORIZED` claims must not appear as positive findings.
4. A negative or null result can satisfy an evaluation dependency when the preregistered protocol is complete and the null result is reported transparently.
5. Every final quantitative sentence must identify its generated table, figure, or artifact root.
