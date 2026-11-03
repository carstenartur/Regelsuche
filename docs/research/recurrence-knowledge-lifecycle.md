# Recurrence knowledge: complete JVM lifecycle measurement

The opt-in `recurrence-knowledge-lifecycle/v1` development experiment measures
the existing cumulative learner through acquisition, persistence, process exit,
a fresh consumer JVM, reload/reproof, actual queries and final shutdown. It
follows the logical-work comparison in [recurrence-family-transfer.md](recurrence-family-transfer.md).
It does not change the solver or reinterpret historical learned-schema-v2 data.

## Registered comparison

The committed [protocol](../../config/benchmarks/recurrence-knowledge-lifecycle-v1.json)
contains all lesson, validation and query inputs. Its SHA256 is
`14d05fdfd24541ffcba67474d4e948d344e54c228793252db4fb684e62ca8ba5`.
The driver requires those exact bytes before execution.

| Profile | Preparation actually executed | Query process |
| --- | --- | --- |
| BASELINE | No unused training process | Fresh JVM, empty knowledge, existing synthesis |
| EXACT_FROZEN | Two learning blocks, save, producer exit | New JVM, load/reproof, automatic strategy |
| FAMILY | Same blocks plus family formation/validation, save, producer exit | New JVM, load/reproof, automatic strategy |

Lengths 1, 8 and 64 are actual prefixes of the existing public family stream.
Three repetitions rotate profile order: 27 lifecycles, 657 retained requests.
No warmups or cached query results. The expected success counts are 1/1, 7/8 and
56/64; eight negative controls remain in the 64-query sequence. Only the FAMILY
profile reuses laws on these previously unseen coefficients.

The Java JSONL adapter invokes public `CumulativeTaskKnowledge` APIs. It does not
contain a private task generator, mathematical answer template or learning
implementation. The Python auditor binds every response to its request and
unchanged generation, then checks the nonconstant exact polynomial's degree,
variables, affine step identity and initial value independently. Different valid
laws satisfy the same task. Negative outcomes are checked for consistency; the
auditor does not claim a general proof that no law exists.

## Measurement boundaries

| Metric | Included | Interpretation |
| --- | --- | --- |
| Sequence wall time | First owned JVM start through final exit, all operations, transport and law audits | Actual sequential elapsed time |
| Worker CPU | wait4 user + system for every owned JVM, all threads, including startup/shutdown | Sum across producer and consumer where applicable |
| Controller CPU | Python process CPU during the same sequence | Includes independent audit and transport/controller work |
| Full lifecycle CPU | Worker CPU plus controller CPU | Distinct from elapsed time; parallel JVM threads can exceed wall time |
| Peak worker RSS | Maximum wait4 process peak across the sequence's workers | Not summed peaks, heap allocation or intrinsic algorithm memory |
| Command wall time | Outer supervisor through child completion including runtime snapshot, reports and manifest | Final supervisor receipt write lies outside its own finite boundary |
| Logical work | Actual acquisition once, actual restore/reproof, every query | Not elapsed time or CPU; historical acquisition is not charged twice on reload |

Failures, budget cutoffs, failed READY and timeouts retain known process/operation
receipts and all planned query rows. Missing receipts, incomplete operations,
invalid proofs, unequal full success vectors or failure to meet the registered
quality contract withhold complete performance ratios. Evidence verification
can validate a faithfully recorded unsuccessful experiment without classifying
it as a successful comparison.

## Reproduction

Requires Java 25, Maven, Python standard library and a POSIX host with `wait4`.
The new recurrence experiment itself does not need SymPy. The repository's
broader historical comparison test suite retains its existing pinned SymPy and
Gradle prerequisites.

Run the native adapter contracts, then build a production-only runtime:

```bash
mvn -pl regelsuche-learning -am \
  -Dtest=RecurrenceKnowledgeComparisonWorkerTest,RecurrenceFamilyKnowledgeTest,RecurrencePayloadLimitTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl regelsuche-learning -am -DskipTests package dependency:build-classpath \
  -DincludeScope=runtime -Dmdep.outputFile=target/runtime-classpath.txt
python3 - <<'PY'
from pathlib import Path
import os
output = Path('build/recurrence-lifecycle-classpath.txt')
output.parent.mkdir(parents=True, exist_ok=True)
entries = [str(Path('regelsuche-learning/target/classes').resolve())]
entries += Path('regelsuche-learning/target/runtime-classpath.txt').read_text().strip().split(os.pathsep)
assert all(Path(entry).exists() for entry in entries)
assert not any('test-classes' in entry or 'junit' in entry for entry in entries)
output.write_text(os.pathsep.join(entries) + '\n')
PY
python3 -m unittest scripts.external_polynomial_comparison.test_run_recurrence_knowledge -v
```

Commit implementation and protocol before measurement; the driver requires clean
tracked source and rejects untracked implementation/configuration. Choose a new
output directory on every execution. Run from the repository root with Java 25
on `PATH`:

```bash
python3 -m scripts.external_polynomial_comparison.run_recurrence_knowledge \
  --protocol config/benchmarks/recurrence-knowledge-lifecycle-v1.json \
  --classpath build/recurrence-lifecycle-classpath.txt \
  --output build/reports/recurrence-lifecycle-v1-run-1
python3 -m scripts.external_polynomial_comparison.run_recurrence_knowledge \
  --verify build/reports/recurrence-lifecycle-v1-run-1
```

Evidence includes exact runtime class/dependency/resource bytes, source commit
and tree, Java identity, protocol bytes, operation/request responses, snapshots,
independent audits, stderr, process receipts, summary/comparison reports and a
recursive manifest. The separate verify command checks retained bytes and
recomputes audits and accounting rather than trusting the saved summary.

## Scope of conclusions

This is one known public development family and its negative controls, not a
sealed holdout or evidence of general superiority across recurrence families.
Three repetitions are descriptive, not a broad precision study. The existing
baseline synthesizes its full bounded invariant basis before choosing one law,
although the requested task needs only one. Machine scheduling, JVM startup,
JIT and runtime footprint remain part of the measured execution. Neither a
logical-work improvement nor a smaller process peak alone establishes a wall-
clock, CPU or general memory improvement.
