# Learned rewrite strategies: development comparison

The learner infers rule order and branching from target-free TRAIN searches. All profiles receive the same eight rules and search budgets.

These public development cases share algebraic building blocks with TRAIN. They characterize transfer to new compositions and contexts, not independently held-out mathematical families or the flagship FINAL TEST.

## Learned program

```text
choice {
  sequence {
    source [add-zero]
    source [factor-left]
  }
  sequence {
    source [difference-product]
    sequence {
      source [square-product]
      source [cancel-addend]
    }
  }
}

```

Training search work: 188; primitive replay work: 27; exact step checks: 9.

These dimensions exclude a complete account of identity projection, compiler, parser, BigInteger and model-construction work. They do not establish amortized total-work or runtime superiority.

| Case | Profile | Selected output | Score ↓ | Search work | Primitive steps | Learned/shuffled program used | Status |
|---|---|---|---:|---:|---:|---|---|
| sum-composition | FLAT_RULES | `2 * u ^ 2` | 12 | 104 | 5 | false | FRONTIER_EXHAUSTED |
| sum-composition | LEARNED_PROGRAM | `2 * u ^ 2` | 12 | 235 | 5 | true | FRONTIER_EXHAUSTED |
| sum-composition | SHUFFLED_PROGRAM | `2 * u ^ 2` | 12 | 200 | 5 | false | FRONTIER_EXHAUSTED |
| product-composition | FLAT_RULES | `j ^ 2 * (j + 2)` | 20 | 41 | 3 | false | FRONTIER_EXHAUSTED |
| product-composition | LEARNED_PROGRAM | `j ^ 2 * (j + 2)` | 20 | 97 | 3 | true | FRONTIER_EXHAUSTED |
| product-composition | SHUFFLED_PROGRAM | `j ^ 2 * (j + 2)` | 20 | 85 | 3 | false | FRONTIER_EXHAUSTED |
| multiple-instances | FLAT_RULES | `x ^ 2 + z ^ 2 - 1 ^ 2 + 1` | 32 | 96 | 4 | false | FRONTIER_EXHAUSTED |
| multiple-instances | LEARNED_PROGRAM | `x ^ 2 + z ^ 2 - 1 ^ 2 + 1` | 32 | 239 | 4 | true | FRONTIER_EXHAUSTED |
| multiple-instances | SHUFFLED_PROGRAM | `x ^ 2 + z ^ 2 - 1 ^ 2 + 1` | 32 | 187 | 4 | false | FRONTIER_EXHAUSTED |
| compound-base | FLAT_RULES | `(u * v) ^ 2 + 7` | 14 | 41 | 3 | false | FRONTIER_EXHAUSTED |
| compound-base | LEARNED_PROGRAM | `(u * v) ^ 2 + 7` | 14 | 97 | 3 | true | FRONTIER_EXHAUSTED |
| compound-base | SHUFFLED_PROGRAM | `(u * v) ^ 2 + 7` | 14 | 85 | 3 | false | FRONTIER_EXHAUSTED |
| nested-factors | FLAT_RULES | `2 * 3 * m * (m + n)` | 25 | 228 | 2 | false | FRONTIER_EXHAUSTED |
| nested-factors | LEARNED_PROGRAM | `2 * 3 * m * (m + n)` | 25 | 412 | 2 | false | FRONTIER_EXHAUSTED |
| nested-factors | SHUFFLED_PROGRAM | `2 * 3 * m * (m + n)` | 25 | 460 | 2 | false | FRONTIER_EXHAUSTED |
| near-miss | FLAT_RULES | `x ^ 2 - y ^ 2 + y * (y + 1)` | 35 | 14 | 1 | false | FRONTIER_EXHAUSTED |
| near-miss | LEARNED_PROGRAM | `x ^ 2 - y ^ 2 + y * (y + 1)` | 35 | 34 | 1 | false | FRONTIER_EXHAUSTED |
| near-miss | SHUFFLED_PROGRAM | `x ^ 2 - y ^ 2 + y * (y + 1)` | 35 | 30 | 1 | false | FRONTIER_EXHAUSTED |
| already-simple | FLAT_RULES | `z+9` | 7 | 6 | 0 | false | NO_TRANSFORMATIONS |
| already-simple | LEARNED_PROGRAM | `z+9` | 7 | 14 | 0 | false | NO_TRANSFORMATIONS |
| already-simple | SHUFFLED_PROGRAM | `z+9` | 7 | 14 | 0 | false | NO_TRANSFORMATIONS |
| unsupported | FLAT_RULES | — | — | — | — | — | DOMAIN_UNSUPPORTED |
| unsupported | LEARNED_PROGRAM | — | — | — | — | — | DOMAIN_UNSUPPORTED |
| unsupported | SHUFFLED_PROGRAM | — | — | — | — | — | DOMAIN_UNSUPPORTED |

Lower score means the frozen project scorer prefers that output; it does not mean a universally simplest expression. Full searches, failed alternatives, primitive lineage and work are retained in the adjacent JSON files. No automatic promotion or external novelty claim is made.
