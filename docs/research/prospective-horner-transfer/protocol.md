# Prospective transfer to a held-out family of Horner expressions

## Question and decision

Can a frozen model of independently checked local rewrites finish more unseen polynomial simplification tasks within the same cumulative logical work as primitive search? The primary checkpoint is **131,072 units**. A positive result requires strictly more verified completions than both the primitive solver and the same prepared solver with learned providers removed. A tie or loss fails the primary criterion. Report the earlier learned implementation as a fourth arm. A separate claim for the implementation change requires an improvement over that arm. Report the result even if the primary criterion fails.

## Separation and freeze

Reuse the previously published two training sources, three validation sources, primitive inventory, learning decisions and seven checked schemata from [the distributive study](../prospective-distributive-coverage/README.md). Retraining on these five sources must reproduce the published receipt and canonical snapshot. Use the receipt's actual acquisition cost, including lessons, formation, validation and split checks. No new lessons, gate changes or promotion decisions are allowed. The old distributive panel is development evidence. The prospective family below contains a quadratic Horner chain with three distinct coefficient subproblems. The learning algorithm receives no such chain in training or validation. The held-out family is synthetic and deliberately chosen to include repeated opportunities for transfer.

Publish this protocol first. Publish the complete evaluator, product implementation and hashes of the preserved training artifacts in a subsequent freeze commit **before generating or solving any FINAL case**. Use the full lowercase Git SHA of that first published freeze commit as the only seed. Publish the SHA with the results. No seed selection, reroll, early stop, per-case parameter choice, learning during FINAL, or changes conditional on FINAL outcomes. An invalid run must be disclosed.

## Fixed generator

Each source is a Horner chain `(A*r+B)*s+C`. The reduced coefficients are `(a+n)*b`, `e*f` and `i*j`. A selected coefficient `u*v` is presented as `(u*((v-p)-q)+u*q)+u*p`. Its two additional variables are respectively `(c,d)`, `(g,h)` and `(k,l)`. With zero selected coefficients, wrap one coefficient in an addition of zero. Every request consists of a source, an empty assumption list and a **17-node upper bound**. The search gets neither a target expression nor the coefficient mask.

There are 32 blocks of four tasks. Let zero-based position `t = 4*block + slot`; the coefficient constant is `n=101+t`. For each block sort the distinct counts 0, 1, 2, 3 by lexicographic unsigned SHA-256 of the UTF-8 string `seed/count/block/count` (numeric tie breaker). Use the count at position `slot`. For each task order coefficient indices 0, 1, 2 by the same rule with input `seed/position/t/index`. Select the first count indices in that order, or the first index for the zero control. Replay all positions in increasing order in every arm. Publish the expressions and verify that no polynomial alpha identity crosses TRAIN, GATE or FINAL and no FINAL identity repeats. The 128 members are related variants of a single family.

## Measurement

Use the same search limits in all arms: 32 primitive steps, search depth 32, 200,000 theory units, 512 states and 8,192 total work units per query, with the five-argument budget constructor's standard complexity debt. Each arm processes the same uninterrupted ordered stream. It pays for every attempt, including failure and an over-budget prefix. BASE uses the ordinary primitive solver. PRIOR uses the earlier occurrence-selected, routed solver. REGEN uses the independently regenerated-witness implementation with the frozen model. DROP uses REGEN with the learned providers removed after routing and keeps its solver configuration and preparation. All three model arms pay the full recorded cost of acquisition and one full model load/preparation before the stream starts. Record each component separately.

The cumulative clock adds actual search, selection, independent witness replay and a separate exact polynomial equivalence audit of the returned incumbent on **every** attempt, including failures. Count a completion only after all this work and only if the solver reached the node bound within its fixed query budget with complete accounting and an accepted witness. Abort and report an invalid run for an incomplete ledger or failed equivalence check. A deadline crossing during a task gives no partial credit. Reading these checkpoints from one monotone uninterrupted ledger permits an identical stopping rule for all arms. Report solved counts also at 16,384, 32,768, 65,536, 262,144 and after all 128 attempts, plus full cost and outputs. These extra checkpoints are descriptive.

Logical counters measure the search and mathematical checks. Parsing, common fixture construction, JSON, split diagnostics, JVM and I/O are outside the logical unit model. Report elapsed times separately without interpreting them as the primary measure. No elapsed-time claim or claim about an external population follows from these synthetic coefficient variants.

## Reproduction

Run the frozen evaluator in two fresh JVMs with reversed arm order. Require byte-identical canonical per-task ledgers including input, output, cumulative work, exact audit cost and witness fingerprint, and identical checkpoint tables. Publish all four arms, including any unsuccessful cases. The original distributive study stays reproducible in its published form.
