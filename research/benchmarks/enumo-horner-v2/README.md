# Checked sparse Horner on Rosa and two transfer families

The new Java profile offers exact rational polynomial rewrites on the ordinary
typed search frontier. It merges like terms, tries bounded variable orders for
sparse Horner evaluation, and considers collecting rational coefficients under
one common denominator. The verifier rechecks the source and candidate via
exact polynomial projection independently of candidate construction. The
existing task solver exposes the capability as `TaskConstructionProfile.POLYNOMIAL_HORNER_V1`.

The external worker selects only candidates that fit the shared arithmetic
output grammar. The numeric and syntax inspections are charged. The total
query budget stays at 16,384 work units. The opt-in profile admits a maximum
of 16,384 theory work units per path within that same budget, and limits
Horner construction to 4,096 generation units at the root. The older native
profiles retain their own closed theory path. Each request includes source
transport, candidate generation and external judging within the common
two-second allowance. See the [protocol](protocol.json) and [v1 baseline](../enumo-public-v1/README.md).

## Rosa development panel

All 37 programs, four arms and three repetitions are retained. Each arm
returns six verified candidates per repetition and 31 unsupported inputs.
The expression costs are stable across the three repetitions.

| Program | Native base | Native learned | Native Horner | Original Enumo |
| --- | ---: | ---: | ---: | ---: |
| `rigidBody1` | 7 | 7 | **6** | 7 |
| `rigidBody2` | 13 | 13 | **7** | 11 |
| `sine` | 18 | 18 | **10** | 18 |
| `sqroot` | 18 | 18 | **9** | 15 |
| `sineOrder3` | 7 | 7 | **5** | 6 |
| `bspline3` | 4 | 4 | 4 | 4 |
| **Total surface operators** | 67 | 67 | **41** | 61 |

| Profile | Sum of six request-and-judge times per repetition, ms | Process startup/setup, ms |
| --- | --- | --- |
| Native base | 629 / 402 / 370 | 499 / 656 / 457 |
| Native learned | 254 / 298 / 291 | 1,043 / 1,021 / 859 |
| Native Horner | 765 / 469 / 460 | 497 / 415 / 432 |
| Original Enumo | 1,449 / 1,464 / 1,483 | 18 / 5 / 7 |

Horner spent 1,230–4,816 native work units on each admitted Rosa request.
The receipts retain generation, verification, path replay and selection.
The new shape reduces the measured total operator count by 33% versus the
original Enumo arm. It has lower observed request time than that arm across
all six Rosa median values, and higher aggregate request time than each
native control. Fresh original rule acquisition was measured separately at
15.186 s wall time and 15.024 s CPU time. It reproduced all 128 directed rules
from the previous acquisition exactly. The worker setup times use already
acquired original rules, and the original and native learning languages differ.

## Transfer on complete external families

The two files in the [transfer protocol](transfer-protocol.json) were selected
after development by their pinned catalog identities, before inspecting
their source contents or timing. The run preserves all 18 FPTaylor Extra
and 28 Hamming Chapter 3 programs. Three fit the common grammar in each
of the four arms. Horner and original Enumo verify all three in every
repetition. The two native controls verify two programs and return an
excluded power expression on the third. The initial Horner attempt did the
same on that program. The [retained excerpt](evidence/development-transfer-outside-grammar.json)
records this failure before the shared objective correction.

| Program | Native base | Native learned | Native Horner | Original Enumo |
| --- | ---: | ---: | ---: | ---: |
| `floudas` | 1 | 1 | 1 | 1 |
| `sum` | 8 | 8 | **2** | 8 |
| `himmilbeau` | Outside grammar | Outside grammar | 15 | 15 |
| **Total on all three** | Incomplete | Incomplete | **18** | 24 |

| Profile | Sum of three request-and-judge times per repetition, ms |
| --- | --- |
| Native base | 586 / 468 / 465 |
| Native learned | 351 / 401 / 344 |
| Native Horner | 903 / 898 / 667 |
| Original Enumo | 517 / 508 / 826 |

The times include all three attempted requests in every arm. At equal
output cost, median Horner time on `himmilbeau` is 723 ms versus 194 ms on
original Enumo. The other two Horner medians are 95 and 75 ms versus 130
and 194 ms on original Enumo. The transfer result has one quality win and
two ties under the common contract. It retains a latency loss. Both panels
use fixed order and three repetitions on a development host. Their exact
rational interpretation, unsupported rates, and acquisition asymmetry
limit conclusions about broader floating-point workloads, learning, or
general runtime. The transfer inputs were public and accessible throughout.

## Reproduction

Use the pinned upstream Rust workspace, Cargo lock, dynamic Z3 patch and
bridge from the [v1 build instructions](../enumo-public-v1/README.md#reproduction).
Set `enumo_binary` to the rebuilt worker binary and `enumo_rules` to a freshly
acquired rules file with its adjacent `.receipt.json`. The [recorded
provenance](provenance.json) binds both runs to the exact worker, acquisition,
toolchain, and measured Java source.

From the Regelsuche checkout, build and test with JDK 25:

```sh
mvn -B -ntp -pl regelsuche-experiments,regelsuche-learning -am \
  -Dtest=SparsePolynomialHornerTest,SparseHornerSearchTest,TypedExternalPolynomialComparisonWorkerTest,EnumoPublicComparisonTest,EnumoRecordedAuditTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
mvn -B -ntp -pl regelsuche-experiments,regelsuche-learning -am -DskipTests install
mvn -B -ntp -f regelsuche-experiments/pom.xml dependency:build-classpath \
  -Dmdep.outputFile=target/enumo-classpath.txt
classpath="regelsuche-experiments/target/classes:regelsuche-learning/target/classes:$(cat regelsuche-experiments/target/enumo-classpath.txt)"
java -Xmx512m -cp "$classpath" de.regelsuche.benchmark.peer.EnumoPublicComparison \
  "$PWD" "$enumo_binary" "$enumo_rules" /tmp/regelsuche-horner-rosa --horner-v2
java -Xmx512m -cp "$classpath" de.regelsuche.benchmark.peer.EnumoPublicComparison \
  "$PWD" "$enumo_binary" "$enumo_rules" /tmp/regelsuche-horner-transfer --horner-transfer
```

Choose output directories that do not already exist. The original four-argument
command remains the three-armed v1 runner. Both new commands archive the full
inventory and its negative outcomes. To recheck the pinned archives without
executing Rust or the Java optimization worker:

```sh
java -cp "$classpath" de.regelsuche.benchmark.peer.EnumoRecordedAudit \
  "$PWD" research/benchmarks/enumo-horner-v2/evidence/rosa
java -cp "$classpath" de.regelsuche.benchmark.peer.EnumoRecordedAudit \
  "$PWD" research/benchmarks/enumo-horner-v2/evidence/transfer
```

The archive verifier checks all 996 rows, source and profile bindings, numeric
equivalence and operator costs. It checks the new typed witness endpoint
bindings and separately charged theory-path and replay limits. A second
coefficient computation with SymPy 1.14.0 and an independent syntax-tree
operator count matched all 102 verified rows. The complete fresh responses
and exit intervals are under [evidence](evidence). The external files carry
the [same FPBench license](../enumo-public-v1/FPBENCH-LICENSE.txt).

## Root-only Horner follow-up

The transfer trace attributed most of the `himmilbeau` search to cheaper
power expressions outside the shared grammar. The mixed Horner arm
examined 111 proposals, including 110 instances from the `square-product`
rule family. It also verified a Horner candidate that did not improve
the incumbent. The incumbent stayed at cost 15 without a selected proof
path. Each mixed-arm response exceeded 1.4 MB.

The root profile `POLYNOMIAL_HORNER_ROOT_V1` registers the existing Horner
provider alone. A whole-source candidate receives an exact certificate
only if its shared-grammar cost is strictly lower. An independent
receiver repeats the cost admission and exact projection. The same
frontier, budgets and final replay apply. Unchanged inputs retain the
original expression and the cost of the proposal attempt.

The [new protocol](root-protocol.json) reran all previous arms plus root
Horner in three fresh processes per profile. It retains 555 Rosa rows and
690 transfer rows. There are 90 verified Rosa and 39 verified transfer
rows, including repeated queries. All 31 Rosa and 43 transfer exclusions
per profile are retained.

| Panel and arm | Total operators per repetition | Sum of request-and-judge times per repetition, ms |
| --- | ---: | --- |
| Rosa, native base | 67 | 472 / 404 / 349 |
| Rosa, native learned | 67 | 300 / 270 / 212 |
| Rosa, mixed Horner | 41 | 1,254 / 893 / 599 |
| Rosa, root Horner | 41 | 370 / 354 / 390 |
| Rosa, original Enumo | 61 | 1,713 / 1,884 / 1,979 |
| Transfer, native base | Incomplete | 975 / 748 / 538 |
| Transfer, native learned | Incomplete | 564 / 401 / 386 |
| Transfer, mixed Horner | 18 | 679 / 937 / 643 |
| Transfer, root Horner | 18 | 242 / 244 / 187 |
| Transfer, original Enumo | 24 | 669 / 872 / 515 |

Root Horner preserves every verified mixed-arm operator count. On
`himmilbeau`, all three matching arms have cost 15. The median
request-and-judge time fell from 508.7 ms for mixed Horner to 26.8 ms for
root Horner. Original Enumo took 184.0 ms. On `floudas`, both root Horner
and original Enumo had cost 1, with medians of 134.3 and 122.1 ms.
The root profile still paid JVM startup and setup of 373–583 ms
across the panels. The original application worker took 5–18 ms to
start with its acquired rules. These are request and startup measurements
in fixed order on the same development host. The narrower common
arithmetic domain and the cost and learning boundaries above still apply.

Use the same built tools and classpath for the follow-up:

```sh
java -Xmx512m -cp "$classpath" de.regelsuche.benchmark.peer.EnumoPublicComparison \
  "$PWD" "$enumo_binary" "$enumo_rules" /tmp/regelsuche-root-horner-rosa --root-horner-rosa
java -Xmx512m -cp "$classpath" de.regelsuche.benchmark.peer.EnumoPublicComparison \
  "$PWD" "$enumo_binary" "$enumo_rules" /tmp/regelsuche-root-horner-transfer --root-horner-transfer
java -cp "$classpath" de.regelsuche.benchmark.peer.EnumoRecordedAudit \
  "$PWD" research/benchmarks/enumo-horner-v2/evidence/root-rosa
java -cp "$classpath" de.regelsuche.benchmark.peer.EnumoRecordedAudit \
  "$PWD" research/benchmarks/enumo-horner-v2/evidence/root-transfer
```

See [root-run provenance](root-provenance.json) for source and executable
identities. A separate exact coefficient computation and syntax-tree count
matched all 129 verified rows.
