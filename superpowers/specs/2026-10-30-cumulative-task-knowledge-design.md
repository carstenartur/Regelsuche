# Cumulative task knowledge

## Intent and accepted scope
Connect the existing checked-schema generalizer and task solver into an immutable,
persistable sequence of knowledge generations. Solved tasks, not handwritten
shortcut patterns, supply new examples. A later generation can use previously
learned schemata while solving its lessons. Maintain the existing search and
polynomial proof authority. No second learner, search kernel or weakened gate.

## Approach
Considered: retain every rule (uncontrolled application overhead), result-only
memoization (no transfer), or proof-checked generational learning with validation.
Use the third. Reuse CheckedLearnedSchemaModel's exact formation pipeline for
actual task solutions; preserve its old entry point and serialized revisions.
A new CumulativeTaskKnowledge coordinates fixed-generation lesson execution,
formation, validation, and explicit publication of an immutable successor.

## Invariants
- All lessons in a batch use the same parent. Only completed, independently
  replayed, within-budget, unconditional solutions contribute examples.
- A single learned step alone is reuse, not a newly learned composition.
- Existing schema IDs are deduplicated and preserved; the existing 32-schema
  cap remains. Bounds, unsupported cases and failed attempts stay explicit.
- Separate lesson and validation expressions using the existing exact polynomial
  alpha-identity exclusion, including its measured work. Validation is for
  selection, never a test-set superiority claim.
- BASELINE, KNOWLEDGE_FIRST and AUTOMATIC are typed choices. AUTOMATIC selects a
  validated arm by bounded syntax features and declared goal, never by a task
  name or expected output. Unknown/custom-goal profiles use the baseline.
- Selection consumes the same query budget; no refund or second fallback budget.
- Candidate adoption cannot lose a solved validation task, degrade unsuccessful
  results, and, at unchanged success, must reduce aggregate work relative to the previous
  automatic policy. Strict work benefit or additional solved tasks is required; otherwise preserve the
  old mathematics. Failed/no-change batches still retain their measured costs.
- Persist content-addressed snapshots with parent identity, model and selection
  policy. Loading re-proves the model. Metadata/hash is not mathematical authority.
  Existing files are never overwritten; size/field checks reject malformed data.
- Generation acquisition costs, restore/preparation costs and query costs remain
  separate. Declared historical logical units are not full JVM time/heap accounting.

## Boundaries
Only the existing unconditional rational-polynomial fragment can be learned.
Node-count goals are sufficient-task criteria, not algebraic optimality.
No UI/background worker, arbitrary automatic decomposition, unbounded library,
or universal monotonic speedup guarantee. Corpus-wide economic gain requires
fresh held-out and lifecycle measurement, not merely a smaller macro depth.
