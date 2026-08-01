# Information-parity TRAIN fitness for evolved rewrite programs

Status: authoritative paired TRAIN evaluator for #521, with #527 work parity

## Comparison contract

The unit under evaluation is one exact `EvolutionRewriteProgramCandidate`:

- one executable rule genome;
- one executable strategy topology over those rules.

The comparison must isolate the value of the topology. Therefore both sides
receive:

- the same input, syntax-exact target and declared assumptions;
- the same ordinary AST rule inventory;
- every rule compiled from the candidate genome as an independently applicable
  flat rule;
- the same scorer, canonicalizer, best-first implementation and budgets.

Only the candidate side additionally receives the compiled
`ProgrammedTransformationEngine` for the topology.

```text
baseline  = ordinary rules + flat genome rules
candidate = ordinary rules + flat genome rules + learned program
```

A rule introduced by the genome can therefore help both sides. A measured gain
requires the program to compose, order, guard, repeat or prune those same rules
more effectively.

`InformationParityRewriteProgramTrainFitnessEvaluator` is the authoritative
evaluator for this contract. The earlier default-rules-only prototype is not
sufficient for topology claims because it does not give the flat baseline the
candidate genome.

## Exact path validation

Search reachability is not mathematical truth. Every adjacent expression in a
reached path is checked through the assumption-aware validation port. The
production composition supplies the exact rational-function normal-form
implementation. Generated path assumptions must be contained in the declared
assumption set.

Path status remains one of:

- `CONFIRMED`;
- `REFUTED`;
- `MISSING_ASSUMPTION`;
- `UNSUPPORTED`;
- `NOT_EVALUATED` when the target was not reached.

A case is newly solved only when:

1. the information-parity flat baseline does not reach the target;
2. the candidate reaches it;
3. an actual program transformation occurs in the retained path;
4. the full path is exactly confirmed;
5. the baseline was not denied primitive work already consumed inside the
   program.

A flat genome rule solving a task on both sides produces no newly-solved credit.
An unsafe rule that reaches the expected target string is retained as refuted
negative evidence and blocks the candidate.

## Primitive work rather than macro-edge illusion

A composed program can expose several primitive transformations as one outer
search edge. `Transformation` therefore carries structured, ordered primitive
rule lineage. A program edge containing two primitive rewrites consumes two
primitive steps before it may enter the frontier. The legacy outer edge depth
remains diagnostic only.

A shared `PrimitiveWorkBudget` freezes:

- maximum primitive steps per path;
- maximum explored outer states;
- maximum candidates admitted per state;
- maximum expanding primitive steps;
- maximum total work units.

The total-work budget reserves one unit for every possible exact retained-path
audit call. The remaining mechanical budget is enforced during search, not only
reported afterwards.

## Complete work ledger

Each baseline and candidate case retains three work layers.

### Transformation and program work

The measured transformation boundary records:

- engine and source invocations;
- source candidates;
- program-node visits;
- composed candidates;
- requirement evaluations and rejections;
- priority ordering;
- explicit pruning;
- repeat iterations and endpoints;
- alternative selections and skipped alternatives;
- duplicate candidates removed.

This work is collected even when the program yields no retained candidate or a
formed candidate is later rejected by the outer search budget.

### Outer search work

The search ledger records:

- explored and expanded states;
- generated transformations and enqueued states;
- duplicate, repeated-application and same-expression pruning;
- primitive-, expansion- and candidate-budget pruning;
- empty transformation batches;
- engine batches.

These counters consume the mechanical budget as they occur. A target state
cannot be accepted after the budget has already been exceeded.

### Exact audit work

Every exact adjacent-path comparison contributes one audit work unit. Unreached
paths contribute zero. The fixed worst-case reserve guarantees that a reached
path plus its audit remains within the same preregistered total budget.

For each side the authoritative total is:

```text
total work
  = transformation/program work
  + outer search work
  + exact path-audit calls
```

The evidence stores all components as well as the recomputed total.

## Resource attribution

Explored-state and primitive-step reductions contribute fitness only when:

- baseline and candidate paths are both reached and exactly confirmed;
- the retained candidate path actually uses a program edge;
- candidate total work does not exceed baseline total work.

Merely adding a program to the frontier can change queue pressure or tie
ordering. It receives no topology credit when the retained solution path uses
only flat rules. A shorter outer macro path also receives no resource credit if
it performs more total work.

Raw fitness components remain authoritative independently of the frozen scalar
population profile.

## Boundary enforcement

The suite candidate limit must be at least as wide as the candidate genome's
source limit. Otherwise the flat and program sources could inspect a candidate
set wider than the search policy admits, and evaluation fails closed with:

```text
SUITE_CANDIDATE_BOUND_NARROWER_THAN_GENOME_AND_PROGRAM_SOURCES
```

Compiler failure, reachability regression, refutation, missing assumptions,
unsupported exact validation, primitive-budget exhaustion, total-work
exhaustion and unsupported requested fitness components are retained as
separate evidence rather than hidden inside a scalar score.

## Characterized cancellation control

For the genome rules:

```text
(A*B)/(A*C) -> B/C   requiring A != 0
a/1         -> a
```

and the task:

```text
(x*y)/(x*1) -> y   under x != 0
```

both sides know both flat rules.

With a primitive budget of one, neither side may solve the task. The two-step
program cannot bypass the limit by presenting one outer edge.

With a primitive budget of two, both sides may solve it. The flat path uses two
outer edges and two exact audits; the program path uses one outer edge and one
exact audit, but its internal program formation work remains visible. Macro
compression by itself therefore yields neither a newly-solved case nor an
authorized resource gain.

## Claim boundary

This layer establishes paired, assumption-aware, information-parity TRAIN
fitness with matched primitive, internal, outer-search and exact-audit work. It
does not by itself establish population superiority, VALIDATION selection,
FINAL TEST utility, formal proof, external novelty or release promotion.
