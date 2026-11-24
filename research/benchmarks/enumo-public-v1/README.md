# Original Enumo on the complete public Rosa inventory

Date: 24 November 2026. This is a public development comparison with the actual
original Enumo implementation. Its complete foreign inventory contains 37
programs. Six fit the frozen common polynomial fragment. All three profiles
return exactly checked candidates for those six programs in all three runs.
The remaining 31 programs are retained as unsupported, not omitted.

**Enumo obtains smaller expressions in three of the six shared programs.**
Regelsuche has lower observed request latency on this panel. These observations
do not establish a general runtime winner: expression quality differs, acquisition
grammars differ, and the panel is small and public. The two native profiles have
identical output costs; this experiment demonstrates no learned quality advantage.

## Original implementation and foreign source

The peer is [uwplse/ruler at
7ed456de94df26fdddc0c15417a987d101dc57b5](https://github.com/uwplse/ruler/tree/7ed456de94df26fdddc0c15417a987d101dc57b5).
That revision contains Enumo. The acquisition calls its original
`rational_best::best_enumo_recipe`, which invokes the original replication recipe
and then conditional factorization. The worker uses the original rational
`Math` domain, `Ruleset`, rule appliers, characteristic-vector analysis and egg
runner. This is not a fresh execution of the separate 2021 Ruler branch.

The [thin protocol bridge](enumo_bridge.rs) supplies source strings and extracts
the candidate according to the frozen surface cost. It does not replace the
synthesizer with a project implementation. The only upstream build change is
the [documented dynamic Z3 configuration](upstream-build.patch). Original
algorithm/domain files are unchanged. The actual generated [Cargo lock](Cargo.lock),
binary hash, Rust version, Z3 version and header identity are retained in
[provenance.json](provenance.json).

The corpus is the **complete** [FPBench Rosa file at
090d81982edc0d2a597f4c15644253d4f266a6ef](https://github.com/FPBench/FPBench/blob/090d81982edc0d2a597f4c15644253d4f266a6ef/benchmarks/rosa.fpcore).
[Original bytes](rosa.fpcore) and the [MIT license](FPBENCH-LICENSE.txt) are
retained. Corpus SHA-256:
`17caef3eb6065513600918f56f294a5c2401cf5593026d30aefe1bf4e62db09f`.

The [protocol](protocol.json) was committed locally before implementation and
execution. Its SHA-256 is
`5ed4b7a7fff5dcd023dfd31d1bddcff92cad7392b4a6eca4ce4272929a6e3137`.
The source history preserves that order. A local freeze is not an independent
preregistration, and public cases are not an unseen evaluation set.

## Mathematical contract and inventory

The comparison uses an **exact rational projection** of FPCore bodies. It does
not preserve or prove IEEE-754 rounding behavior. Range preconditions are not
used as optimization hints. The shared syntax admits scalar variables,
representable exact literals, addition, subtraction, multiplication, unary
negation, division by nonzero constants, and lexical `let`/`let*` bindings.
Parallel `let` and sequential `let*` have separate tested meanings.

Variable denominators are rejected before algebraic cancellation. Square roots,
branches and loops are outside this projection. Limits are four variables,
256 input nodes and 2,048 source characters. Literals must fit the original tool's signed 64-bit rational representation.
The native bridge already preserves exact rational leaves; no binary64 or
53-bit restriction applies. The first rejected literal in `carbonGas` exceeds
the original rational range. That program also has variable denominators, so
relaxing the literal bound alone would not make it part of the shared fragment.

| First unsupported reason | Programs |
| --- | ---: |
| Square-root operator | 13 |
| Variable denominator | 9 |
| Conditional expression | 5 |
| Loop | 2 |
| Literal outside the original signed rational range | 1 |
| More than four variables | 1 |
| Supported exact rational projection | **6** |
| Complete foreign file | **37** |

These are first encountered rejection reasons, not a claim that removing one
restriction would make every corresponding program supported. All names,
ordinals, reasons and projected sources are in the archived inventory.

## Frozen execution

Each repetition starts a fresh worker for each profile, in this order:
`REGELSUCHE_BASE`, `REGELSUCHE_LEARNED_RANKED`, `ENUMO_LEARNED`. Programs retain
corpus order. There is no extra warmup. Startup and initialization share a
60-second limit. Each request includes source conversion, transport, worker
execution, exact checking and cost calculation within a common two-second
allowance. Writing evidence is outside that query timer.

The original Enumo rules are acquired once, before the application panel, with
a separate 600-second allowance. The original recipe's own synthesis limits are
unchanged. Application uses five egg iterations, 100,000 nodes and an internal
1.8-second limit inside the common supervisor allowance. The unchanged native
worker uses its existing work/depth/state/debt limits. Internal work counters
from different systems are never treated as the same unit.

Both JVMs receive `-Xmx512m`. This is a Java heap setting, not a hard total RSS
limit or equal-memory experiment. The supervisor kills its own failed worker
and retains blocked subsequent supported queries. Unknown and failed cases
remain rows. The final matrix contains exactly **333 rows**:
37 programs × three profiles × three repetitions.

Requests contain only source and the already frozen native profile. No target
answer, source metadata or competing candidate is sent to either optimizer.
Every returned source echo is checked. Every successful candidate must have
the same exact polynomial coefficients as the input. This checker is independent
of the acquired rewrite set, but shares Regelsuche's existing polynomial kernel;
it is not an independently authored proof implementation or human adjudication.

## Results

Surface cost counts one operation for each binary arithmetic operation or unary
negation. Output costs below are identical across all three repetitions.

| Foreign program | Input | Native base | Native learned/ranked | Original Enumo |
| --- | ---: | ---: | ---: | ---: |
| `rigidBody1` | 7 | 7 | 7 | 7 |
| `rigidBody2` | 14 | 13 | 13 | **11** |
| `sine` | 18 | 18 | 18 | 18 |
| `sqroot` | 18 | 18 | 18 | **15** |
| `sineOrder3` | 7 | 7 | 7 | **6** |
| `bspline3` | 4 | 4 | 4 | 4 |
| Sum over six supported programs | 68 | 67 | 67 | **61** |

Each profile has 18 exactly checked candidates and 93 unsupported rows. There
are no invalid, timed-out or technically failed queries in the final run.
The total is 54 checked candidates and 279 unsupported rows; these are repeated
evaluations of six programs, not 54 independent application cases.

Observed median request-and-judge times, milliseconds:

| Program | Native base | Native learned/ranked | Original Enumo |
| --- | ---: | ---: | ---: |
| `rigidBody1` — first supported request | 108.196 | 32.726 | 260.938 |
| `rigidBody2` | 72.310 | 49.883 | 351.312 |
| `sine` | 141.502 | 99.637 | 248.394 |
| `sqroot` | 28.273 | 46.135 | 177.453 |
| `sineOrder3` | 26.783 | 47.954 | 168.296 |
| `bspline3` | 21.043 | 47.586 | 133.319 |

Native base query sums are 591.384 / 394.034 / 380.288 ms per repetition;
native learned/ranked sums 342.413 / 287.202 / 362.272 ms; original Enumo sums
1,338.520 / 1,362.558 / 1,386.264 ms. These figures do not include setup or rule
acquisition and must not be presented as total learning cost.

The original acquisition produced **128 directed rules**, represented by the
upstream `bidir_len` summary as 71 bidirectional entries. Recipe time was
13.132 seconds. The complete acquisition process took 13.166 seconds wall time
and 12.682 CPU seconds, with observed maximum child RSS 778,068 KiB. Downloads,
toolchain provisioning and compilation are excluded.

Native base process setup took 422.494–541.360 ms. Native learned/ranked setup
took 838.181–1,171.222 ms, including acquisition of two learned programs and
3,812 native work units. Original Enumo application-worker setup, loading the
already acquired rules, took 5.251–8.021 ms. These training languages and acquired
inventories are different: the experiment does not measure matched learner
sample efficiency or an overall learner advantage.

The final application supervisor, all nine fresh workers, native initialization
and report serialization together took 11.451 seconds wall time and 25.500 CPU
seconds. Its observed maximum child RSS was 203,744 KiB. Separate resource
receipts retain the exact boundaries. Execution occurred during concurrent
development on a machine reporting nine processors. Fixed profile order,
JVM warmup, different output quality and only three repetitions limit timing
claims; no speed ratio or scaling conclusion is drawn.

## Development failures and review controls

The first bridge failed because it added expressions before initializing the
original rational domain's variable samples. The original applier needs these
samples to reject undefined intermediate terms. The observed interval panic was
an adapter failure, not evidence of inferior original-tool quality.

The bridge now follows upstream `Workload::to_egraph` initialization order and
calls the original `Math::initialize_vars`. Two bridge regression tests failed
before the correction and pass afterward. Constant-only requests also preserve
undefined-intermediate rejection without inventing a variable in their graph.
The complete failed panel and the first corrected panel are retained separately.
The final panel uses the same frozen corpus, budgets, profiles and acquired rules.

Two additional Java regressions reject quoted string terms and prevent startup
and initialization from receiving separate replenished allowances. The retained
audit checks corpus/protocol/rule/raw-response hashes, all 333 ordered rows,
source-only requests, actual returned candidates, exact equivalence, operation
counts and success deadlines. It does not trust a summary merely because its
hash was recomputed. Its negative tests alter raw output, consistently forge
both raw and summarized output, remove an unsupported row and claim success past
the deadline. All four failed against the initial stub before implementation.

Independent review additionally found that negative status labels, setup/profile
metadata, internal work counters and implausibly short outer times could bypass
parts of the first archive checker. New regressions failed before those checks
were tightened. The auditor now derives candidate outcomes from raw responses,
binds source-only requests, acquisition, setup and profile identities, verifies
work-component totals and rejects an outer duration shorter than worker time.
Both profiles must pass the same frozen surface grammar before exact checking;
internal native powers and canceled variable denominators receive no one-sided
shortcut. The unnecessary 53-bit literal restriction is removed.

The full 333-row application panel was rerun after these corrections. The
previous final run remains under `evidence/development-pre-review-final`; it is
not silently replaced in the development history. The supported inventory and
54 verified candidates remain unchanged.

The focused final Java run passed 22 tests without failures, errors or skips.
The two Rust bridge tests passed. A successful archive audit confirms the
retained response contract; it does not authenticate historical execution from
an untrusted author or replay the original synthesizer.

## Reproduction

Prerequisites for rebuilding the original peer are Rust/Cargo, Clang and a
compatible installed Z3 shared library and headers. The recorded environment
used Rust 1.100.0, Z3 4.13.3.0 and Java 25.0.2. Use the retained Cargo lock;
the pinned upstream repository did not originally ship one.

From the Regelsuche checkout, with paths of your choice:

```sh
benchmark_root="$PWD"
git clone https://github.com/uwplse/ruler /tmp/enumo-original
git -C /tmp/enumo-original checkout 7ed456de94df26fdddc0c15417a987d101dc57b5
git -C /tmp/enumo-original apply "$benchmark_root/research/benchmarks/enumo-public-v1/upstream-build.patch"
cp research/benchmarks/enumo-public-v1/Cargo.lock /tmp/enumo-original/Cargo.lock
mkdir -p /tmp/enumo-original/src/bin
cp research/benchmarks/enumo-public-v1/enumo_bridge.rs /tmp/enumo-original/src/bin/regelsuche_peer.rs
cargo test --manifest-path /tmp/enumo-original/Cargo.toml --release --locked --bin regelsuche_peer bridge_tests
cargo build --manifest-path /tmp/enumo-original/Cargo.toml --release --locked --bin regelsuche_peer
mkdir -p /tmp/enumo-reproduction
/tmp/enumo-original/target/release/regelsuche_peer acquire /tmp/enumo-reproduction/learned.rules
```

Run acquisition under a process supervisor with the declared 600-second limit;
the recorded process resource receipt must be collected separately. On systems
with nonstandard header/library locations, set the usual `Z3_SYS_Z3_HEADER`,
`LIBCLANG_PATH`, `LIBRARY_PATH` and `LD_LIBRARY_PATH` to those installed paths.
Do not change the upstream domain or remove rules to improve the outcome.

Build the native modules and run the focused contracts:

```sh
mvn -B -ntp -pl regelsuche-experiments,regelsuche-learning -am \
  -Dtest=FpcorePolynomialCorpusTest,EnumoPublicComparisonTest,EnumoRecordedAuditTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
mvn -B -ntp -pl regelsuche-experiments,regelsuche-learning -am -DskipTests install
mvn -B -ntp -f regelsuche-experiments/pom.xml dependency:build-classpath \
  -Dmdep.outputFile=target/enumo-classpath.txt
benchmark_classpath="regelsuche-experiments/target/classes:regelsuche-learning/target/classes:$(cat regelsuche-experiments/target/enumo-classpath.txt)"
java -Xmx512m -cp "$benchmark_classpath" de.regelsuche.benchmark.peer.EnumoPublicComparison \
  "$PWD" /tmp/enumo-original/target/release/regelsuche_peer \
  /tmp/enumo-reproduction/learned.rules /tmp/enumo-reproduction/new-panel
```

The artifact-preparation `install -DskipTests` is not a substitute for the test
command or regular CI. The output directory for the panel must not already exist.
The comparison prints its complete status counts and saves raw responses,
startup evidence, full matrix and worker stderr files.

The retained archive can be checked without Rust, Z3 or any original worker:

```sh
java -cp "$benchmark_classpath" de.regelsuche.benchmark.peer.EnumoRecordedAudit \
  "$PWD" research/benchmarks/enumo-public-v1/evidence
```

The [source-history bundle](source-history.bundle) retains the local freeze and
the actual final measured comparison code relative to public base
`f183aedd1ff2ab061ed903559b96a11d28974e7f`. It avoids advertising local hashes as
public GitHub commits. Verify and fetch it in a clone containing that base:

```sh
git bundle verify research/benchmarks/enumo-public-v1/source-history.bundle
git fetch research/benchmarks/enumo-public-v1/source-history.bundle \
  refs/heads/codex/remediation-integration-20261124:refs/heads/reproduce-enumo-public
```

The bundle includes the reviewed shared-grammar check and archive auditor used
for the final recorded panel. Product optimizer code remains the recorded base.
[artifact-sha256.json](artifact-sha256.json) binds all retained files. The
acquisition and application receipts, including exact commands and resource
scope, are in [evidence/](evidence/).

## Consequence

The missing direct original-tool comparison has a concrete first execution.
It shows both a native latency niche and an original Enumo expression-quality
advantage within a narrow common fragment. Broader foreign applications,
unseen families, matched acquisition studies, independent replication and
independent user evaluation remain separate work. This result does not establish
novelty, general competitiveness or IEEE-safe source optimization.
