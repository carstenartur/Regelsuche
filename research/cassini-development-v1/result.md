# Cassini correspondence on a frozen public development panel

The production solver returned eight nonconstant laws across six requests. All eight passed an independent exact symbolic transition and initial-value check. The law from the original Fibonacci transition and initial state matched Cassini with scale 1. In coordinates `x0=F_n`, `x1=F_(n+1)`, the emitted polynomial was `x0^2 + x0*x1 - x1^2`, with multiplier −1 and initial value −1. Substituting `F_(n+2)=x0+x1` gives `F_n*F_(n+2)-F_(n+1)^2=(-1)^(n+1)`.

| Row | Source or control | Formation | API work | Laws | Historical matches |
| --- | --- | --- | ---: | ---: | ---: |
| r0 | Original Fibonacci transition and initial state | Complete | 4350 | 1 | 1 |
| r1 | Pell transition | Complete | 4400 | 1 | 0 |
| r2 | Identity transition | Complete | 4486 | 5 | 0 |
| r3 | Original source, linear basis only | Complete | 1171 | 0 | 0 |
| r4 | Original source, one-unit budget | Budget inconclusive | 1 | 0 | 0 |
| r5 | Original transition, initial state (1,1) | Complete | 4364 | 1 | 0 |

The identity row retains every independent basis law emitted by the solver. The changed-initial-state law passes mathematical replay; the historical comparison requires the original sequence and index origin. These are previously known development examples. Disjoint-family transfer, independently blinded historical evaluation, and learned tactic use remain open.

## Recorded sequence

1. The [preregistration](https://github.com/carstenartur/Regelsuche/commit/72823ebab7cc138fa0857f98d42ab57c21af8b56) fixed the source panel, bounds, work budget, normalization, historical equivalence, and retained controls. The SHA-256 of the original source bytes is `cf7b3c01c5f83768ac8f8552d82a502e839bc5f78e9504343e7c477a0f28f7ad`.
2. Separate Java 25 processes independently generated identical candidate bytes. The [formation commit](https://github.com/carstenartur/Regelsuche/commit/d043a55d63ece9a78b9c1ec0c6229da17d929f8d) published the worker and all rows before the first correspondence run. The freeze SHA-256 is `342018a587b904af0c02efd7bd3aa09a6292ae7206f2185625993ab21d2f5711`.
3. Two separate Python processes produced identical evaluation bytes, SHA-256 `d6fa93eeeadf5594c2c2539da6b4879e5db9d30a4011b19ddcfe7c641348cbf9`. The evaluator pins both external commitments before looking at any row and reuses the independent sparse-rational judge. The original match and a scaled sign-reversal control exercise rational normalization. Tests also cover incorrect base, evolution and polynomial, altered source bytes, reordered laws and omitted laws.

All JSON files are UTF-8 with a final LF. From the repository root, with Java 25 and Python 3.11 or later:

```bash
set -eu
./gradlew -I scripts/external-polynomial-classpath.gradle :regelsuche-learning:externalPolynomialClasspath
cp_file=regelsuche-learning/build/external-polynomial-classpath.txt
panel=docs/research/cassini-development-v1
work=$(mktemp -d)
for run in a b; do
  java -XX:-UsePerfData --enable-preview -cp "$(cat "$cp_file")" \
    de.regelsuche.evolution.HistoricalComparisonWorker freeze-invariants "$panel/sources.json" > "$work/freeze-$run.json"
  cmp "$panel/candidates.json" "$work/freeze-$run.json"
  PYTHONPATH=scripts python -m external_polynomial_comparison.cassini_correspondence \
    "$panel/sources.json" "$work/freeze-$run.json" "$work/evaluation-$run.json"
  cmp "$panel/evaluation.json" "$work/evaluation-$run.json"
done
PYTHONPATH=scripts python -m unittest external_polynomial_comparison.test_cassini_correspondence
```

During local qualification the JVM wrote a shared performance-counter-file diagnostic to stdout when two container processes started simultaneously. The documented command disables those optional JVM counters and runs the two processes sequentially. It preserves the production algorithm and the complete candidate bytes.
