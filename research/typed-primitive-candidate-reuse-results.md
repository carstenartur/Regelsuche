# Primitive candidate reuse: integration evidence and limitations

## Executed implementation

The cache is connected to the actual compiled learning inventory through
`TypedLearnedMoveInventory.newSearchSession`, with fresh bounded caches per
query and unchanged learned programs and primitive/selected-path verification.
This is opt-in; the existing providers and frozen v1 worker remain uncached.
The [contract](typed-primitive-candidate-reuse.md) specifies key isolation,
per-provider storage bounds and the deterministic-transport caller obligation.

At `c7d1da04a5469fad6a6f6840b6cbf40f91e07cce`,
[Java-25 run 35490555068](https://github.com/carstenartur/Regelsuche/actions/runs/35490555068)
passed **all 428 search-module tests and 879 learning-module tests** (1,307 total,
zero failures/errors/skips), all 43 Python tests, the unchanged 729-row v1
comparison and its independent selected-path audit. Artifact `10598289463`
contains the full module XML reports and printed integration diagnostics.
Its hosted ZIP digest is
`4ce9219e9a6d99f0ebde824a05c44ae4841f74cf78981776123d5ab2fac8a524`.
The hosted logs were inspected; no local execution or independent artifact
reverification is claimed for this development session.

## Actual invocation reuse, not a net speedup claim

These four post-hoc integration fixtures compare primitive and learned inventories
with and without caching. Each query owns fresh caches, with 64 entries and
1,000,000 retained characters allowed **per primitive provider**. The ample shared
work budget is 200,000; other search limits remain depths 9/9, states 256 and debt
24. The tests compare complete admitted events, reached states, incumbent,
selected witnesses and fresh replay. Every such comparison passes.

| Fixture | Inventory | Generator calls without / with reuse | Original work | Cached work |
|---|---|---:|---:|---:|
| Two independent cancellation sites | Primitive | 336 / 200 | 2,849 | 3,613 |
| Two independent cancellation sites | Learned | 488 / 200 | 4,881 | 5,493 |
| Zero/one composition | Primitive | 88 / 64 | 509 | 765 |
| Zero/one composition | Learned | 88 / 64 | 605 | 861 |
| `a+23` | Primitive | 8 / 8 | 32 | 64 |
| `a+23` | Learned | 8 / 8 | 38 | 70 |
| Near miss | Primitive | 16 / 16 | 94 | 160 |
| Near miss | Learned | 16 / 16 | 109 | 175 |

Exact fixture inputs are retained in `TypedPrimitiveReuseSessionTest` and the
JUnit stdout. The two-site case is
`((a+b)*(a-b)+b*b)+((c+d)*(c-d)+d*d)`; the zero/one case is
`((a+0)*1)+(b+0)`; the near miss is `(a+b)*(a-b)+b*(b+2)`.

The two-site learned search avoids 288 repeated primitive generator invocations.
It still performs fresh selected replay (45 units, unchanged); the primitive
control's selected replay remains 20 units. Avoided generator calls do not prove
faster execution: paid cache bookkeeping increases the declared total work in
all eight pairs. No cached-path wall-time/CPU comparison was performed here.
The accounting is an event ledger, not a complete instruction/allocation/string
hashing cost model. Do not tune the old ledger or frozen v1 results to hide this
negative result. The present evidence supports retaining the feature as opt-in,
not making it the default or claiming successful learning amortization.

## Retention review regression

Author review identified missing generated premise text in the character bound.
The test-only commit `672b7b59f493bbb8314f8ab6447a9ec19008c79a` exercised a real
conditional candidate with a 1,600-character symbol and a 1,000-character cache
limit. In run `35490762541`, its retention assertion at line 35 failed while all
other 428 search-module tests and all 43 Python tests passed. Artifact
`10599231839` retains that RED evidence. The later learning-module task and
comparison did not run after the intended search-test failure.

The correction counts both generated transformation premises and normalized
move premises, with their inspection cost. Oversized candidates remain returned
unchanged but are not retained. The original regression is unchanged; the fixed
head requires its own complete affected-module GREEN run. Prior green evidence
must not be substituted for that run.

## Qualification boundary

The separate base PR #1042 full run `35488441354` failed Maven/Docker, JMH and
checkout-local Gradle authority jobs. Their causes are not resolved here. Passing
both affected module suites is not full repository qualification. No existing
gate, dependency, production default or frozen benchmark input is weakened.
Review here is author self-review, not independent code-review approval.

A separate, fairly controlled experiment must compare both primitive and learned
execution with/without reuse and actual setup/CPU/memory costs. Source-only
utility selection and selective reuse are not implemented by this change.
