# Corrected v2 supplemental execution, 2026-11-10

**The corrected mixed-workload gate rejected the depth-3 candidate for insufficient
measured payback in all three repetitions.** It first obtained and independently
validated a real candidate, then measured all eight promotion roles three times
(24 paired trials per repetition). Both seed batches were promoted. No new model
was adopted by the performance gate; no speedup is established.

This corrects the initial supplemental runtime, which omitted current learning
service metadata and consequently reported no mathematically promoted candidates.
The original raw files and report remain unchanged under
`initial-incomplete-provider-attempt/`, explicitly classified as a non-qualifying
setup attempt. Its earlier “no candidate” observation is not evidence about the
intended learning runtime.

## Corrected workload and costs

The default prospective protocol executed 16 blocks, three repetitions and two
arms under Java 25.0.5. All 768 evaluation outputs passed source/witness binding,
exact polynomial and occurrence-node-score audits. Each arm solved 112/128 tasks;
the 16 intended negative controls remained unsolved. Every paired repetition
preserved quality.

| Repetition | Seed decisions, both arms | Improvement candidate | Paired trials | Second attempt |
| ---: | --- | --- | ---: | --- |
| 0 | PROMOTED, PROMOTED | PROMOTED → NO_GAIN | 24 | NO_NEW_SCHEMA → NO_CANDIDATE |
| 1 | PROMOTED, PROMOTED | PROMOTED → NO_GAIN | 24 | NO_NEW_SCHEMA → NO_CANDIDATE |
| 2 | PROMOTED, PROMOTED | PROMOTED → NO_GAIN | 24 | NO_NEW_SCHEMA → NO_CANDIDATE |

All acquisition, rejected attempts, paired promotion trials, snapshot operations,
query work and audits remained paid. Each baseline arm used 185,794 mechanical
units; each improvement arm used 225,883: **40,089 additional units**. Common
exact split validation consumed another 12,084 units. Mechanical units are not
estimates of bit complexity or time.

| Repetition | Baseline wall s | Improvement wall s | Baseline CPU s | Improvement CPU s | Both observed totals lower? |
| ---: | ---: | ---: | ---: | ---: | --- |
| 0 | 1.814482988 | 0.954550339 | 6.67 | 3.95 | yes |
| 1 | 0.435369648 | 0.724765260 | 1.47 | 2.95 | no |
| 2 | 0.290863511 | 0.373208365 | 0.61 | 0.75 | no |

These observed whole-arm totals share one JVM, with order alternating by
repetition. Strong warmup/order effects remain visible. No improvement candidate
was adopted, so the first pair's lower totals cannot be attributed to an adopted
learning change. `observedBothResourcePayback` is only an arithmetic comparison,
not a causal or statistical claim. Process CPU can exceed wall time because it
sums JVM threads.

The enclosing Java process took 5.425639476 wall seconds, 17.848533 user CPU seconds
and 0.431022 system CPU seconds. The separate process receipt includes startup,
shutdown and final JSON serialization. It reports peak resident memory 241,844 KiB
from Linux `getrusage(RUSAGE_CHILDREN)` for this single Java child. Internal timing
and enclosing-process timing have different scopes and must not be added together.

## Runtime diagnosis and correction

The initial command compiled referenced sources using `-Xprefer:source`, but its
classpath had no current `regelsuche-learning/src/main/resources`. Learning's
`CheckedSchemaTheoryEvidenceProvider` and other services are discovered through
`ServiceLoader`; the compiler cannot infer these runtime registrations. The
bundled SDK exposed a different service set. Thus valid schema evidence had no
installed recognition provider, and source-bound search fell back to primitives.

A controlled probe used exactly the same freshly compiled project classes and
external-only dependency jar, changing only the presence of current learning
resources. Without them the existing depth-1 useful fixture returned
`NO_VALIDATED_BENEFIT`; with them it returned `PROMOTED`. Depth-2 and depth-3 fixtures
also promoted with the corrected resources. No training, quality, proof or
promotion threshold was changed.

The new `regelsuche-learning/scripts/run-mixed-v2-supplemental.sh` prevents this
setup error by excluding all bundled `de/regelsuche/` classes and
`META-INF/services/de.regelsuche.*` descriptors, compiling the current source
closure and every provider named in current search/learning descriptors, and
including both current resource directories at runtime. Provenance records exact
service descriptor contents and hashes, external dependency jar hashes, compiled
project class inventory hashes, Java source-tree hash and command. The built jar
is checked to contain neither old project classes nor old project service entries.
All project bytecode in this corrected run therefore comes from the checkout;
external libraries still come from the bundled jar, which is not Maven qualification.

## Regression and verification status

The revised deterministic-clock contract executes the actual public `improve`
method with a costly real rejected first attempt and a genuinely promoted second
candidate. Its short horizon repays the second attempt alone but not the full run.
It verifies all three state transitions:

- Default cumulative scope: `NO_GAIN`; active generation remains the initial one.
- Explicit legacy scope at the same horizon: `ADOPT`; active generation becomes
  the actual candidate.
- Default cumulative scope at a sufficiently long horizon: `ADOPT`; active
  generation becomes the candidate.

The test requires the learner's `PROMOTED` decision, a nonempty candidate identity,
real paired solved executions and retained paid work. No candidate receipt is
fabricated and no mathematical gate is mocked. Reverting **only the production
final adoption checkpoint** to `KnowledgeCostGate.evaluate(paid, ...)`, leaving
the helper and receipt code unchanged, fails with `expected NO_GAIN got ADOPT`.
The restored implementation passes. These clock inputs test decisions; they are
not performance evidence.

The existing `CumulativeTaskKnowledgeContract` also executes with current services,
including three accepted generations, persistence and paid-cost invariants; its
complete result is retained alongside the other supplemental logs.

Official Maven tests remain an outstanding qualification gate because the recorded
Maven Central DNS failure prevented dependency resolution. The existing
`maven-attempt.txt` is preserved; no dependency version, network restriction or
mathematical assertion was changed to obtain supplemental results. Root CI must
run the affected JUnit suite under the canonical dependency closure.

The complete alternate representation audit is still enabled. No residual-only
shortcut replaces its rank/solution-set checks.
