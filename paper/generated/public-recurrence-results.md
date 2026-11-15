# Formation and fixed-rule reuse on the public recurrence cases

The arms receive the same two TRAIN prefixes and the same cap on proposals. Each campaign freezes its admitted coefficient tuples before testing them across all splits. Supported attempts and duplicate attempts can overlap. Empty counts campaigns with no admitted model.

| Arm | Campaigns | Proposals | Duplicates | Supported | Empty |
| --- | --- | --- | --- | --- | --- |
| Exact fitter | 4 | 4 | 0 | 4 | 0 |
| Enumeration | 4 | 240 | 0 | 4 | 0 |
| Seeded random | 4 | 240 | 60 | 6 | 1 |

A slot is one campaign evaluated on one case. Confirmation requires agreement with the prefix and the three recorded continuation values. Each slot receives one terminal outcome. A refuted continuation means a rule matched the prefix but failed on continuation.

| Arm | Split | Slots | Confirmed | Prefix mismatch | Refuted continuation | No model |
| --- | --- | --- | --- | --- | --- | --- |
| Exact fitter | TRAIN | 8 | 4 | 4 | 0 | 0 |
| Exact fitter | VALIDATION | 8 | 0 | 8 | 0 | 0 |
| Exact fitter | TEST | 8 | 0 | 8 | 0 | 0 |
| Enumeration | TRAIN | 8 | 4 | 4 | 0 | 0 |
| Enumeration | VALIDATION | 8 | 0 | 8 | 0 | 0 |
| Enumeration | TEST | 8 | 0 | 8 | 0 | 0 |
| Seeded random | TRAIN | 8 | 3 | 3 | 0 | 2 |
| Seeded random | VALIDATION | 8 | 0 | 6 | 0 | 2 |
| Seeded random | TEST | 8 | 0 | 6 | 0 | 2 |

The cases and their historical outcomes were public before this comparison. All confirmations refer to the same geometric TRAIN case. Candidate proposals measure submissions to validation. The three algorithms incur different internal costs per submission.

Result bytes SHA-256 `af8c65da2906b0b249bac4d895999b38b633adcad3579fe5b84bf1fb79b8c27c`. Plan bytes `sha256:f7475599a958f1ffe174ee936e00c82f267e59df03588454ab1c0f9957526ad4`. [Retained case rows and proposals](https://github.com/carstenartur/Regelsuche/blob/7c49d63e5df5ccc7f323eec850b4fc7f7fc38ff4/research/benchmarks/candidate-independent/track-c-recurrence/comparison-result.json) · [Registered comparison plan](https://github.com/carstenartur/Regelsuche/blob/95c1eefada1d2e989182e4bc92369e11c4840fc4/research/benchmarks/candidate-independent/track-c-recurrence/comparison-plan.json).
