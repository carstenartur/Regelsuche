# Retained rule-index development measurements

Both complete runs are retained below and as raw JMH JSON, including all fork
samples. Smaller is better. JMH error is reported as emitted; several intervals
are wide, so point ratios alone must not be read as established speedups.

The repeat's sparse 4096-rule case is 4394.897 ± 613.360 µs scanned versus
23.051 ± 11.103 µs indexed. The separate one-index construction operation costs
923.878 ± 210.778 µs. This is a deliberately discriminable fixture, not an
end-to-end learned-search result.

The same-operator control limits the benefit: at 4096 rules the repeat is
4318.332 ± 155.842 µs scanned versus 3055.424 ± 1220.631 µs indexed. Its small
16-rule case worsens from 29.043 ± 1.412 to 51.591 ± 46.126 µs; the 256-rule
point estimate also worsens and is uncertain. Keep the opt-in API and measure
representative inventories before changing any default.

The 500 ms pilot overlapped builds early; the 1 s repeat did not overlap
task-owned builds. Neither run uses a dedicated or pinned benchmark host.
See the parent [protocol and reproduction instructions](../../README.md).

## Pilot (500 ms iterations)

| Operation | Inventory shape | Unrelated rules | Time ± JMH error (µs/op) |
| --- | --- | ---: | ---: |
| compileIndex | SPARSE_FUNCTIONS | 16 | 1.984 ± 0.971 |
| compileIndex | SPARSE_FUNCTIONS | 256 | 28.323 ± 11.175 |
| compileIndex | SPARSE_FUNCTIONS | 4096 | 1276.217 ± 350.914 |
| compileIndex | SAME_OPERATOR | 16 | 1.222 ± 1.590 |
| compileIndex | SAME_OPERATOR | 256 | 35.235 ± 101.340 |
| compileIndex | SAME_OPERATOR | 4096 | 234.580 ± 122.828 |
| indexed | SPARSE_FUNCTIONS | 16 | 38.339 ± 37.230 |
| indexed | SPARSE_FUNCTIONS | 256 | 119.001 ± 305.298 |
| indexed | SPARSE_FUNCTIONS | 4096 | 25.178 ± 7.081 |
| indexed | SAME_OPERATOR | 16 | 38.403 ± 14.384 |
| indexed | SAME_OPERATOR | 256 | 130.577 ± 12.217 |
| indexed | SAME_OPERATOR | 4096 | 1935.833 ± 151.142 |
| scan | SPARSE_FUNCTIONS | 16 | 28.704 ± 4.154 |
| scan | SPARSE_FUNCTIONS | 256 | 253.315 ± 65.894 |
| scan | SPARSE_FUNCTIONS | 4096 | 5146.746 ± 3198.417 |
| scan | SAME_OPERATOR | 16 | 35.388 ± 12.292 |
| scan | SAME_OPERATOR | 256 | 394.665 ± 192.378 |
| scan | SAME_OPERATOR | 4096 | 8049.291 ± 4709.812 |

## Repeat (1 s iterations)

| Operation | Inventory shape | Unrelated rules | Time ± JMH error (µs/op) |
| --- | --- | ---: | ---: |
| compileIndex | SPARSE_FUNCTIONS | 16 | 1.781 ± 0.657 |
| compileIndex | SPARSE_FUNCTIONS | 256 | 31.636 ± 9.807 |
| compileIndex | SPARSE_FUNCTIONS | 4096 | 923.878 ± 210.778 |
| compileIndex | SAME_OPERATOR | 16 | 0.506 ± 0.190 |
| compileIndex | SAME_OPERATOR | 256 | 7.289 ± 2.454 |
| compileIndex | SAME_OPERATOR | 4096 | 163.072 ± 22.578 |
| indexed | SPARSE_FUNCTIONS | 16 | 20.838 ± 3.480 |
| indexed | SPARSE_FUNCTIONS | 256 | 36.470 ± 41.319 |
| indexed | SPARSE_FUNCTIONS | 4096 | 23.051 ± 11.103 |
| indexed | SAME_OPERATOR | 16 | 51.591 ± 46.126 |
| indexed | SAME_OPERATOR | 256 | 314.184 ± 428.488 |
| indexed | SAME_OPERATOR | 4096 | 3055.424 ± 1220.631 |
| scan | SPARSE_FUNCTIONS | 16 | 33.779 ± 9.713 |
| scan | SPARSE_FUNCTIONS | 256 | 233.599 ± 20.620 |
| scan | SPARSE_FUNCTIONS | 4096 | 4394.897 ± 613.360 |
| scan | SAME_OPERATOR | 16 | 29.043 ± 1.412 |
| scan | SAME_OPERATOR | 256 | 265.165 ± 19.928 |
| scan | SAME_OPERATOR | 4096 | 4318.332 ± 155.842 |
