# Complete typed-search fixture: retained development run

This exercises the frontier, canonical typed transport, candidate generation,
and independent primitive replay for four zero eliminations. Each setup checks
equality of the ENTIRE encoded scan/indexed result, including ordered evidence,
events, states and work. All six inventories reach the goal in four steps: five
reached states, ten generated proposals, four consumed and six unconsumed;
primitive/search/verification work is 10/45/18 (73 total). The common budget is
four primitive/search steps, 100 states and 10,000 work units. This simple
confluent fixture does not demonstrate better branching decisions or new reach.

The prepared engine/problem and index are reused; construction is outside the
timed query and is measured separately in the kernel study. No matching-cost
credit is subtracted from the search ledger. Both modes retain the same ledger.

One complete run: 12 combinations, two forks, three 1 s warm-up iterations and
five 1 s measurements per fork, one thread and 256 MiB heap on the same shared
host. All combinations, wide errors and regressions are retained. No narrower
profile was chosen after observing the results.

For 4096 sparse function rules, the observed query means are 103.484 ms scanned
and 0.735 ms indexed. For 4096 same-operator rules they are 39.326 ms scanned and
58.171 ms indexed. Several errors are very wide, including intervals larger
than the estimate: this is exploratory throughput evidence, not a stable
latency guarantee or a general speedup. The index stays opt-in.

| Operation | Inventory shape | Unrelated rules | Time ± JMH error (µs/op) |
| --- | --- | ---: | ---: |
| indexed | SPARSE_FUNCTIONS | 16 | 8880.234 ± 14237.158 |
| indexed | SPARSE_FUNCTIONS | 256 | 2423.600 ± 3059.357 |
| indexed | SPARSE_FUNCTIONS | 4096 | 735.079 ± 495.842 |
| indexed | SAME_OPERATOR | 16 | 1147.908 ± 741.697 |
| indexed | SAME_OPERATOR | 256 | 5816.329 ± 3266.395 |
| indexed | SAME_OPERATOR | 4096 | 58171.241 ± 38562.590 |
| scan | SPARSE_FUNCTIONS | 16 | 2642.593 ± 2908.566 |
| scan | SPARSE_FUNCTIONS | 256 | 8509.512 ± 2745.269 |
| scan | SPARSE_FUNCTIONS | 4096 | 103484.292 ± 27369.951 |
| scan | SAME_OPERATOR | 16 | 2341.794 ± 2229.488 |
| scan | SAME_OPERATOR | 256 | 2463.094 ± 498.733 |
| scan | SAME_OPERATOR | 4096 | 39326.385 ± 15838.781 |

Raw samples: [search.json](search.json). Setup audit: [search-fixtures.txt](search-fixtures.txt).
Protocol/source identity: [search-provenance.json](search-provenance.json).
See [reproduction](../../README.md) and the separate [kernel runs](RESULTS.md).
