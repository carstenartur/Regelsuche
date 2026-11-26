# Conflict-aware matching bounds

## Intent

The existing Hall bound treats all omission assignments as compatible. Two
canonical Primachsen cases show that complete matchings can require mutually
exclusive earlier witnesses. Build a reusable exact matching bound in the
mathematics core; keep the derivation of arithmetic conflicts in its consumer.

## Design

Use the existing exact LP search on edge variables with nonnegativity, left
and right vertex capacity one, and a capacity-one row for each conflicting
edge pair. Export nonnegative weights on left vertices, right vertices and
conflict rows. An independent verifier checks directly that each edge has
total incident weight at least one. The sum of weights bounds every compatible
matching; integer cardinality permits flooring this rational bound.

The source is an immutable canonical bipartite graph and a canonical set of
pairs of its canonical edge indices. Preserve isolated vertices. Reject
invalid indices and self-conflicts. Duplicate conflicts carry no extra meaning.
The SDK delegates to the core without a second solver or arithmetic encoding.

## Alternatives and limits

Ordinary matching is retained as the baseline and remains preferable for large
graphs without extra conflicts. Exhaustive integer search would solve a harder
problem and is not needed for a sound bound. The LP relaxation can be loose:
four mutually conflicting disjoint edges have LP bound two although a real
matching uses at most one. No exact conflict-matching or uniform prime deadline
claim is made. The source of the classical primal/dual certificate method is
documented in the existing OpenAI Math family-380 transfer audit.

## Acceptance

Two disjoint conflicting edges must yield bound one where ordinary Hall gives
two. A triangle must retain the exact 3/2 rational bound and floor only the
integer conclusion. Compatible alternative edges must prevent false deficit.
Replay must reject changed graph/conflicts, negative or insufficient weights,
and exhausted work/memory/cancellation. All phases share one global budget.
Compare small cases with exhaustive compatible matching enumeration and test
that the four-clique is not reported as solved exactly. Java main and tests
must compile with `-Xlint:all -Werror`.
