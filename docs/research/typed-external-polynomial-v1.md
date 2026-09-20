# Typed source-only external development comparison

## Question and scope

Does the actual typed trace learner improve equivalent polynomial representations
or reduce fully accounted execution costs relative to the same primitive rules,
a fixed ranking, a TRAIN-selected primitive ranking and paid SymPy controls?
This adds an executable comparison, not a claim that its answer is positive.

The source-only protocol was committed before execution in
`c7f0f6cfc69e1b45b37497109bf5a11686d08f9a`.
`config/benchmarks/typed-external-polynomial-v1.json` has SHA-256
`ccf2d953cbc43b99f9eb9ec5abe3512bcd9eb8df63a3ae2ccbf8c09264d7078e`.
It contains 24 polynomial sources, three deliberately unsupported controls,
nine profiles and three repetitions: 729 retained rows, not 729 independent
mathematical tasks. These are public, project-authored development compositions,
not independently blinded families or the protected FINAL TEST.

The old `external-polynomial-v1.json`, its worker, runner and historical results
remain unchanged. The new workflow does not weaken or replace any existing gate.

## Profiles and information

| Profile | Inventory and selection | Actual training billed |
|---|---|---|
| BASE | Existing eight primitives, inventory order | None |
| EXPERT | Same primitives, existing fixed default history-policy weights with empty history | None |
| PRIMITIVE_SELECTED | Same primitives, existing TRAIN-selected policy | Target-free formation, typed TRAIN histories, memory updates and all policy trials |
| LEARNED_NAIVE | Primitives plus actually admitted typed learned programs, inventory order | Target-free formation |
| LEARNED_RANKED | Same learned inventory, independently TRAIN-selected policy | Formation, typed TRAIN histories, memory updates and all policy trials |
| SYMPY_SIMPLIFY | SymPy 1.14.0 simplify | None |
| SYMPY_FACTOR | SymPy 1.14.0 factor | None |
| SYMPY_CANCEL | SymPy 1.14.0 cancel | None |
| SYMPY_PORTFOLIO | Executes identity, simplify, factor, cancel and horner before selection | All attempts paid |

`EXPERT` is a configuration label, not a claim of the strongest possible expert.
The unchanged `TraceStrategyTransferExample` supplies the primitive inventory,
TRAIN inputs and formation limits. Policy training reuses the existing
`TypedLearningWorkStudy.train` implementation: its targets are endpoints
actually found by the target-free TRAIN learner, not evaluation reference answers.
The final chosen policy can be inventory order even in `LEARNED_RANKED`.

Each profile owns a separate worker process. Java initialization accepts exactly
one profile and emits its model bytes, SHA-256 and complete training ledgers.
The supervisor writes those exact bytes before warmup or evaluation. Subsequent
requests cannot switch profile, introduce a target or change the model hash.
Actual TRAIN sources and their alpha-equivalent identities are excluded.
Training and evaluation are separate API phases in one process per profile;
this does not claim model restoration into an independently started TEST process.

## Search and accounting

`TypedMoveSearch.Context.sourceOnly` contains no goal expression. Its existing
tagged-AST frontier receives the empty-goal source-only contract, rather than
an artificial sentinel expression. The old targeted constructor still requires
a goal. Existing targeted execution, policy and provider contracts are unchanged.

`TypedSourceOnlySearch` selects an incumbent from the real admitted frontier.
It retains the input on equal scores, ignores rejected proposals, preserves
scoped symbols, exact rational leaves and grouping, and independently replays
the selected primitive path. It is not a second search implementation.
The surface objective counts binary arithmetic and unary minus without algebraic
simplification. Number-leaf spellings are inspected so fraction/sign operations
are not silently free. Unsupported external syntax is classified by the existing
independent rational-polynomial judge before worker execution.

Each Java query retains primitive/search/verification work, objective inspection,
event/path scan work and additional selected-path replay. Extra selection/replay
can exceed the raw frontier's budget: the candidate remains diagnostic and
`internalWorkWithinBudget` becomes false. The supervisor recomputes this partition.
No over-budget candidate counts as an eligible success. Logical work is explicitly
not total machine instructions, coefficient-bit complexity or SymPy operation cost.

The common two-second limit includes request transport, response binding and the
independent exact polynomial verifier. The original paid SymPy portfolio and
process-group timeout behavior are reused unchanged. Failed requests, unsupported
controls, unchanged/worse outputs, atomic work overruns and unavailable workers
remain in the matrix. A timeout kills the real worker process group.

`lifecycle.json` retains startup, actual training, model hashing/persistence,
warmup, all query attempts and common verification. On the hosted POSIX runner,
`wait4` records each complete worker's user/system CPU, including startup,
serialization and every worker thread; CPU receipts survive timeout termination.
The Python controller's process CPU is retained separately through the end of
all requests and common verification, excluding final report export. It is not
assigned for free to a favored profile. Per-method CPU diagnostics remain separate
from whole-worker receipts. Missing measurements are not invented as zero CPU.
Three repetitions do not establish a reliable universal speed ranking.

## Reproduction and retained evidence

Use the project's supported Java 25 environment and normal build prerequisites:

```sh
bash gradle/run-typed-external-polynomial-comparison.sh
```

The dedicated `Typed external development comparison` workflow runs this command
and retains the entire evidence directory plus search/learning JUnit reports.
The original complete CI still applies independently.
Outputs include exact protocol/model bytes, complete setup and warmup receipts,
all rows, canonical untimed rows, paired comparisons, per-profile lifecycle costs,
summary, stderr and a manifest. Existing result directories are never overwritten.

```sh
PYTHONPATH=scripts build/typed-external-polynomial-venv/bin/python \
  -m external_polynomial_comparison.run_typed --verify \
  --output build/reports/typed-external-polynomial-comparison
```

## Verification scope at implementation

The implementation was developed against a source snapshot extracted from main
`81ea9ac7`'s retained CI source artifact. Local Git snapshot history is not remote
Git history; hosted execution checks ancestry against the real protocol freeze.
Nine new source-only tests, four surface-objective tests and seven existing typed
program/replay tests passed using real JUnit 6.1.3 on an unchanged source subset.
Twenty-seven Python supervisor/independent-verifier tests passed, including a
real timeout with whole-process CPU retention. The full Python discovery also
attempted the existing classpath integration test; its Gradle distribution
resolution failed in the local network-restricted runtime.

The local JDK is 21.0.11. The learning dependency closure requires the existing
Java-25 `ScopedValue.call` API, so local subset tests are not a complete module or
product verification. No production source, version floor, dependency or old test
was altered to bypass that boundary. The five new worker tests were exercised
against an intentionally failing initial scaffold; their implemented Java-25
GREEN run and the complete external matrix require hosted qualification.
No benchmark outcome, runtime advantage or completed full-CI claim follows from
these local tests. The PR remains draft pending current-head hosted evidence.
