# Formation with and without cross-observation support

On the public #355 campaign, production formed one conjecture using observations from both generator families. Restricting the same miner to support within individual observations yielded zero. The preregistered rejection pair formed zero candidates in both arms. Its two observations refer to two of the original twelve.

| Formation decision | Observations | Allowed candidate slots | Production candidates / rejected groups | Single-observation candidates / rejected groups |
| --- | ---: | ---: | ---: | ---: |
| Full batch | 12 | 11 | 1 / 0 | 0 / 12 |
| Rejection pair | 2 | 1 | 0 / 1 | 0 / 2 |

The unchanged generation stage generated 240 states and explored 228. Both arms used the same two mining dispatches. Each dispatch executed the miner twice to compare snapshot replay with live observations. The remaining 11 production and 12 single-observation candidate slots were recorded as skipped. Production rejected the pair for insufficient alpha-distinct support. All 14 isolated support groups retained their observation IDs and the reason `support-count<2`. A single observation cannot satisfy the existing two-observation admission threshold, so this result measures the dependency of the current formation policy on shared support. It provides no comparison of conjecture quality or execution time.

The [protocol](open-target-fan-in-control.md) was committed in [6e3c4e73](https://github.com/carstenartur/Regelsuche/commit/6e3c4e733d308fa0457045b3bcfc1a7567a84538) before running the new arm. The production generation identity, both decisions and both complete receipts reproduced the [previous campaign matrix](evidence/autopilot-controller-comparison-v1.json.gz). Two independent Java 25 processes reproduced all 89,216 uncompressed bytes of the [raw comparison](evidence/open-target-fan-in-v1.json.gz), SHA-256 `0f509de1739a0916ba8e6f13b9739214fc02f90d98be64206e83acdf8899986a`.

From the repository root:

```sh
./gradlew :regelsuche-autopilot:installDist
regelsuche-autopilot/build/install/regelsuche-autopilot/bin/regelsuche-autopilot --compare-formation-fan-in build/reports/fan-in.json
gzip -dc docs/evidence/open-target-fan-in-v1.json.gz | cmp - build/reports/fan-in.json
```
