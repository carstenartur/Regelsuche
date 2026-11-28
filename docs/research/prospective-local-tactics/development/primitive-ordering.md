# Learning primitive rule order from verified proofs

This optional source-only solver observes the first primitive step and each adjacent pair in a training proof. It ranks the next rule by frequency for each predecessor. An unseen predecessor uses overall frequencies and ties retain inventory order. At each query state it tries the primitives in rank order, takes the first proposal of the first applicable rule and pays for every attempted provider. The ordinary verifier checks each edge during search and independently replays the selected proof.

The fixed training corpus consists of the 20 earlier paired lessons and the first three public primitive inputs in each of the previous A and B streams. Every lesson was solved again. A total of 26 successful proofs supplied 93 primitive steps. Training paid 3,973 units to read inputs, 7,145 for solving and replay, 1,450 to form the orders and 512 to load them. The learned and ablated policies each start with 13,080 spent.

| Development panel | Inputs | Ordered before deadline | Free greedy before deadline | Subtree before deadline | Ordered cost | Free greedy cost | Sustained cumulative saving versus free greedy |
|---|---:|---:|---:|---:|---:|---:|---|
| MIXED | 1,024 | 1,011 | 965 | 931 | 530,910 | 556,742 | From input 345 |
| C | 512 | 416 | 416 | 416 | 256,472 | 263,648 | From input 333 |
| D | 512 | 512 | 512 | 512 | 190,470 | 176,622 | Never on this stream |

Each panel has its own cold start and a deadline of 524,288 work units. The free greedy arm uses the same single-successor heuristic and subtree counter in inventory order. DROP_ORDER uses that same neutral order and pays the learned arm's setup. SUBTREE uses the established primitive frontier with the same occurrence counter. On MIXED all four arms eventually solve all 1,024 tasks. The costs include search, final proof replay and the independent polynomial audit. The sustained saving is the first one-based input after which the ordered arm stays strictly cheaper through the last input. The development panels had already been published.

The two rotations produce identical bytes for all 19 receipts. The JSON report records the component costs, all paired comparisons and the SHA-256 digests of the raw ledgers. [primitive-ordering.json](primitive-ordering.json)

## Reproduction

The Maven reactor accepts the following opt-in test invocation from the repository root. Use a fresh output directory for each rotation.

```sh
mvn -pl regelsuche-learning -am test \
  -Dtest=LearnedPrimitiveOrderingTest,PrimitiveOrderingStudyTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dordering.research="$PWD/docs/research" \
  -Dordering.output="$PWD/ordering-development-0" -Dordering.rotation=0
```

Repeat with rotation 1 and a second directory. In the prospective phase add `-Dordering.official=<official-run-directory>` and `-Dlocal.freeze=<frozen-commit>`. That invocation scores the official E inputs already recorded by the main study. The training corpus stays fixed. All runs emit full primitive witnesses and per-input costs.

In Java, `LearnedPrimitiveOrdering.fit(genome, lessons)` returns the ordered rule model, individual lesson receipts, acquisition work and formation work. Load it with `LearnedPrimitiveOrdering.load(genome, fit.model())`, charge `loadWork()`, then call `solve(task)`. The neutral control comes from `LearnedPrimitiveOrdering.untrained(genome)`. The learned order applies to native node-count goals. Other objectives keep the ordinary checked solver.
