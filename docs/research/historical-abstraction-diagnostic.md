# Historical abstraction and repeated-learning diagnostic

Source baseline: `459293be659a4adf3fd30f5e9576c934ea00ca21` (30 October 2026).
This diagnostic adds tests and retained reports, not a new production solver.
It asks whether the weak learning signal comes from easy tasks, missing primitive
moves, inability to construct auxiliary expressions, or missing strategy composition.

## Reproduce

```bash
./gradlew --no-daemon :app:test --tests '*HistoricalAbstractionDiagnosticTest'
```

Four JUnit methods invoke directly executable contracts and write JSON under
`app/build/reports/historical-abstraction-diagnostic/`. Existing CI retains this
report directory. No workflow, mathematical check, quality gate or historical
reference was changed. The helper mains also write complete JSON receipts.

## Protocol and information boundaries

The diagnostic fixes ten rounds, two familiar development identities and their
budgets before execution. These identities are not sealed holdouts. Search goals
are structural: a nonconstant product for Sophie-Germain, and a sum of two squares
of two-term degree-two forms for Brahmagupta–Fibonacci. The original input,
`1 * input`, and mere equivalence to a known answer are not successful solutions.
No historical target expression is supplied to candidate generation or ranking.
The shape recognizers are bounded diagnostic criteria, not general factorization
or sum-of-squares algorithms. Their typed-run inspections are conservatively
charged by syntax size; this is not complete JVM/heap accounting.

There are two deliberately separate experiments:

1. The current cumulative task API with its eight-rule development inventory:
   ten baseline restarts, a first-batch frozen state, ten cost-bearing cumulative
   updates, and a single baseline invocation with tenfold ceilings. The learning
   batch sees the two historical development problems; structurally distinct
   substituted inputs are used for validation. Because no lesson solves, none
   supplies a learned theorem. Each failed batch remains paid.
2. The older historical runtime with independently learned generic precursors:
   ten repeated source-only searches using native versus learned versions of the
   same completion and difference-of-squares capabilities, plus an exposure-only
   ablation. Brahmagupta adds generic distribution, square exposure, sign symmetry,
   pair application and normalization. Scalar and structural-diversity strategies
   receive the same declared depth/state/candidate ceilings.

The repeated searches are deterministic reproducibility controls, not independent
random trials. Counts from the two search APIs use different contracts and must
not be turned into a cross-engine speedup. The native-precursor control prevents
claiming that merely relearning an already supplied formula beats equivalent
handwritten knowledge. Precursor lessons are teacher-assisted: their local
endpoints come from existing generic teaching operators. Historical answers do
not enter those lessons. No external CAS or finished historical identity is
inserted as a production rule.

## Results actually executed

### The cumulative entry point cannot make its first move

| Historical task | Initial primitive candidates | Result in each of ten rounds | Query work per invocation |
| --- | ---: | --- | ---: |
| Sophie-Germain | 0 | Unsolved in baseline, frozen and cumulative arms | 72 |
| Brahmagupta–Fibonacci | 0 | Unsolved in baseline, frozen and cumulative arms | 102 |

The tenfold-ceiling invocations have the same outcomes and work. All ten
cumulative batches return `NO_NEW_SCHEMA`, each charges 802 units, and each
retains `NO_UNCONDITIONAL_COMPLETED_COMPOSITION` formation attempts. The frozen
first-batch control separately pays 802 units. These outcomes describe this
restricted inventory, not global unreachability or the capabilities of the whole
product. In particular, increasing the work allowance cannot select a missing
initial successor.

### Broader existing capabilities solve Sophie-Germain

| Control | Repeated successes | Explored states per run | Engine calls | Generated transformations |
| --- | ---: | ---: | ---: | ---: |
| Expose squares only | 0/10 | 8 | 8 | 7 |
| Native generic completion and square difference | 10/10 | 16 | 14 | 15 |
| Independently frozen learned versions of those precursors | 10/10 | 16 | 14 | 15 |

The learned and native controls have equal search counters here. This is a
positive transfer/expressiveness control, not a new learning-speedup claim.
Their five-step result includes the missing completion structure. For example,
exposure makes `(x^2)^2 + (2*y^2)^2` visible, enabling the generic completion
`(x^2 + 2*y^2)^2 - 2*x^2*2*y^2` and subsequent square-difference factoring.
Every retained structurally successful candidate is independently checked with
the existing exact symbolic polynomial verifier.

### Brahmagupta still needs coordinated preparation and residual balancing

| Free-search control | Repeated successes | States per run | Generated transformations |
| --- | ---: | ---: | ---: |
| Native precursor, scalar | 0/10 | 256 | 1,749 |
| Learned precursor, scalar | 0/10 | 256 | 1,749 |
| Learned precursor, structural diversity | 0/10 | 256 | 2,913 |
| Native precursor, tenfold state ceiling | 0/1 | 2,560 | 18,304 |
| Learned precursor, tenfold state ceiling | 0/1 | 2,560 | 18,304 |

Depth was 12, allowed expanding steps 12, and the per-state candidate ceiling 64.
These are bounded misses, not an impossibility result. A greater state ceiling
need not help a path removed by another limit or ranking decision.

The existing residual-composer control succeeds: normal-form preparation and
square exposure yield four source components; all six unordered pairs generate
12 plus/sign-reflected effects. Positive-only effects produce no composition.
With both signs, the composer retains two candidates, independently proved equal
to the original product. They are the two classical sum-of-two-squares forms
(with harmless explicit zeros from sign reflection). Pairing and signs are not
selected by matching a historical target. The overall preparation and residual
strategy is nevertheless programmer-supplied, not learned by this experiment.

## What abstraction can and cannot express today

The diagnostic separates mathematical validity, pattern construction and candidate
selection:

| Proposed transformation | Exact rational identity | Genome admission |
| --- | --- | --- |
| `A -> A/2 + A/2` | Valid | Accepted |
| `A^2+B^2 -> (A+B)^2-2*A*B` | Valid | Accepted |
| `A -> (A+T)-T`, where `T` has no source binding | Valid | `UNBOUND_TARGET_PLACEHOLDER` |

`TypedPatternGeneralizer` successfully generalizes the second transformation
from two concrete examples, including its newly constructed compound cross term.
It cannot invent a choice for the unbound auxiliary `T`. That is not a reason to
remove the unbound-placeholder check: the missing abstraction is a *bounded,
explicitly paid procedure for choosing/building the auxiliary*, followed by the
existing domain and equivalence checks, not an unconstrained wildcard.

The integer-pattern verifier explicitly reports the division example as
`UNSUPPORTED`. Its identity is separately checked with the existing rational
`ExactPolynomialAnalysis`; unsupported is never relabelled as a false equation.

## A second integration boundary: task goals

A separate control learns a useful ordinary cancellation schema. For the same
input and semantically identical node-count criterion, `AUTOMATIC` selects
`KNOWLEDGE_FIRST` when the goal uses the built-in singleton, but `BASELINE` when
it is wrapped in an equivalent custom objective. Both solve (176 versus 183
reported units in this diagnostic; acquisition is separate).

This characterizes the intentionally narrow current router. Factorization and
sum-of-squares goals cannot currently benefit from its learned selection policy
just by supplying a custom callback. This limitation is distinct from the missing
primitive moves and from the strategy-composition problem.

## Conclusions for the next implementation

Keep the simple regressions but do not infer broad historical learning capability
from them. Connect the existing generic preparation/composition capabilities to
the cumulative execution path; give structural task goals an explicit typed
identity; learn from independently verified useful subpaths even when the whole
problem is unsolved; and represent finite auxiliary-term construction with paid
candidate generation and explicit obligations. None of those production changes
is silently claimed by this test-only diagnostic.

## Verification scope

Both direct contracts were run using the real production code and mathematical
checkers. The final historical control run repeated all outcomes. Three compiled
negative controls were caught: removing learned completion, accepting a no-op as
task success, and removing negative residual effects.

The retained source archive came from CI 41072882017, artifact 12987095329;
GitHub comparison reports no content changes from its archived merge to the
baseline above. Locally 860 production sources from seven modules, plus the
needed existing application classes, were compiled against real Jackson jars.
Only Java 21 is available here. A separate, unpublished compatibility copy wraps
the three existing Java-25 `ScopedValue.call` sites; no mathematical class is
stubbed. The real Gradle/JUnit command was attempted but failed downloading the
wrapper (`UnknownHostException: services.gradle.org`). Thus local evidence is
not a Java-25/JUnit/full-product build or an independent code review. The PR must
pass the unmodified hosted CI before merge.
