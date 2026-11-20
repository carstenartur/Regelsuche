# Exact continuation quotient and arena transport

The opt-in `QUOTIENT_DISCOVERY` profile uses the existing BestFirst executor with
`CONTINUATION_QUOTIENT_V1`. The no-argument constructor and existing profiles keep
their historical identity. An additional `ARENA_APPLICATION_HISTORY_V1` control
changes value storage while keeping the application-history policy.

## Value, syntax and continuation

Each run owns a bounded `ExprValueArena`. Structural keys, rather than a digest
alone, authorize local value equality. The node key consists of the local value
handle and the normalized assumption list. The policy and transformation engine
are fixed for that owner. Unsupported expressions use their exact syntax as the
fallback key. Capacity exhaustion is an error, not permission to merge values.

The node retains a separate observation for each exact source syntax. This is
necessary because an arbitrary AST rule can distinguish two AC-equivalent
notations. Each observation keeps non-dominated labels over depth, expansion
count and checked primitive lineage. A shallower, less expansion-heavy or newly
checked arrival can still continue. Depth and expansion bounds make the label
set finite; the visited-state budget bounds admissions. Selection and replacement
are deterministic. Complete application histories remain on path records for
replay, but do not define the new node key.

Expression-local successor lists are frozen on first use and reused within that
run. This contract assumes a state-local transformation engine. Hidden mutable
engine state is not a supported continuation controller. Historical repeated
application identifiers do not prohibit a new legal edge under this policy;
resource dominance terminates already visited cycles instead. Durable project
memory remains observational and cannot prune a fresh run.

`HISTORICAL_HASH_V1` preserves existing priority tie-breaking. The comparison
below explicitly selects `SYNTAX_V1` for all three identity controls so a change
in digest format does not silently change their ranking. The diagnostic records
both policy and tie order. The retained historical reports are unchanged.

## Public comparison

The 14-case #620 corpus is fixed by SHA-256
`53bff974a9ecb101b85e01f202d095fb2a99f5f33127ed7ec0e4ade2685ac422`.
All original depth, visited-state, expansion, candidate and beam bounds are
preserved. Source and exact target strings are formatted through the existing
parser/formatter before execution. The target remains visible as a diagnostic;
this is not a fresh held-out or target-free discovery experiment.

Every case runs against both reference and prepared AST engines and all three
identity policies. Two additional production-rule controls exercise independent
factor sites and nested powers. A separately constructed reference engine
replays every retained primitive edge. Replay work is recorded separately from
search work. Arena-only and historical controls must have identical ordered
paths under the shared tie order, or the comparison fails.

The two backends produce the following identical totals on the 14 historical
cases (the additional controls are excluded from these totals):

| Identity | Reached targets | Visited labels | Distinct syntax, summed by case | Engine calls | Generated proposals |
| --- | ---: | ---: | ---: | ---: | ---: |
| Historical application history | 7/14 | 2,353 | 271 | 915 | 4,252 |
| Arena with application history | 7/14 | 2,353 | 271 | 915 | 4,252 |
| Arena with continuation quotient | 7/14 | 441 | 413 | 314 | 1,790 |

The binomial-expansion case changes from 600 to 81 visited labels and 841 to 259
proposals, with 76 versus 78 distinct expressions. The reverse difference of
squares changes from 600 to 219 labels, but proposals **increase** from 909 to 946
while distinct syntax increases from 70 to 196. Neither declared production
control saves engine calls. All target successes and failures remain visible.

[The complete 96-row artifact](continuation-quotient/comparison.json) has SHA-256
`95283b036c53031e950712931f10cfd58fa32298694b489eabaed075258bbc8a`.
These are mechanical counts under the stated budgets, not elapsed-time,
allocation, JVM-heap or general speedup measurements. More syntax reached under
a visited-label cap is not an additional independently qualified #663 result.

## Persistent values and accounting

`ExprValueSnapshot` transports a versioned reachable DAG. Snapshot indices refer
only to that file. Import re-interns each structural node into the receiving
owner and requires a root digest supplied by the caller's source binding.
Snapshots include exact rational atoms, ordered roles and AC multiplicities.
Byte decoding checks text, edge and node lengths before allocation. Import
rejects cycles, forward references, duplicate nodes, unreachable nodes,
different root bindings and exhausted capacities; rejected imports roll back.

The codec preserves UTF-16 code units exactly. Equivalent AC grouping and
different arena allocation orders produce the same bytes. A 4,001-node shared
power DAG round-trips with linear snapshot storage and one cached digest per
node. No recursively expanded child-key string is needed by this transport.

`structuralBytes` counts the declared node payload: 12 bytes per node, two per
atom code unit and four per edge integer. It excludes JVM objects, map storage,
syntax, path labels and digest caches. `encodedSize` counts the actual framed
transport bytes. Neither quantity is presented as measured heap usage.

## Verification and reproduction

```sh
mvn -pl regelsuche-experiments -am \
  -Dtest=ExprValueArenaTest,ExprValueSnapshotTest,BestFirstContinuationQuotientTest,BestFirstMemoryContinuationTest,ContinuationQuotientComparisonTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

The comparison is written to
`regelsuche-experiments/target/continuation-quotient/comparison.json` and must match
the retained bytes. The same JUnit suite participates in checkout and source
archive verification. Hosted qualification and independent review are recorded
on the implementation PR; local component results alone do not establish the
complete #661/#696 roadmap or container reproduction.
