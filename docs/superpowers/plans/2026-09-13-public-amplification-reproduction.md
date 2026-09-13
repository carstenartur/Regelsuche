# Public amplification reproduction: proposed execution connection

Status: **APPROVED DESIGN; TRANSPORT IMPLEMENTED; COORDINATED EXECUTION NOT RUN**.

This document retains the approved design/audit below. The executable commands,
role-specific environment pins and current evidence limits are documented in
[public-amplification-reproduction.md](../../public-amplification-reproduction.md).

Source audit: `e9f3216f7122a5172a3f03cb8f454c0ef6a25e74`, tree
`d507663de899e7671bd375b3ac894fe79fd23492`. Issue #730 was read on
2026-09-13; it is open and has no comments. Root's pending arithmetic/runner
refactors are outside this proposal's file ownership. The eventual execution
must bind the final reviewed commit, not this audit revision by assumption.

## Decision requested

Add a small checkout-owned **cohort transport** around the existing Java CLI
and Python verifier, with one explicitly invoked, dedicated reproduction
workflow. The workflow provisions clean machines and transfers artifacts; the
checkout owns the plan, execution commands, input checks, receipt generation
and acceptance. Actual named SymPy operations run only in dedicated
reproduction processes, never in ordinary experiment/app tests or alongside
another workload on the same reproduction machine.

No new mathematical runner, profile, corpus, selection procedure or reference
oracle is needed. The historical pilot, current native algorithms, 60-second
named-operation timeout, source/artifact bounds, exact/logical budgets and
comparative-work gates remain unchanged. This is public implementation
qualification, not #533 FINAL or #981 precision material.

## Audited execution already available

| Existing source | Authority to reuse |
| --- | --- |
| `AblatableRuleAmplificationCorpus.java:16-39` | Fixed public 16-source, three-family corpus and separately encoded qualification labels |
| `AblatableRuleAmplificationExperiment.java:43-59,81-110` | Canonical plan, four native profiles and six named operations; 64 native plus 96 external observations, complete freeze |
| `AblatableRuleAmplificationExperiment.java:113-210,261-301` | Fresh native replay; persisted/reread freeze before qualification-file access; complete report, null result and explicit unavailable-work gates |
| `SymPyNamedOperationEngine.java:33-85` | GraalPy 25.1.3, SymPy 1.14.0, mpmath 1.3.0; one fixed operation per call, 60-second timeout, bound unsupported/failure outcomes |
| `regelsuche-experiments/build.gradle:31-45` | Explicit compile/resource/classpath preparation, disconnected from `check`, `ciCheck` and historical study execution |
| `scripts/run-rule-amplification.py:33-114` | Clean HEAD checks, actual compiled-class hash, machine-ID observation, immutable image inspection and the Java invocation |
| `scripts/verify-rule-amplification-reproduction.py` | Complete row/binding checks, two host roles with unequal IDs and one container; the initial nonempty check covered only the first host, identical compiled hash and five canonical files; inconclusive remains inconclusive |
| `regelsuche-math-sympy/build.gradle:157-195` and `gradle/run-isolated-sympy-runtime-authority.sh` | Existing test-runtime isolation; no change to its authorization markers, enabled tests or JaCoCo graph |

The existing experiment tests use a closed external engine, yielding real
bound `UNAVAILABLE` outcomes without launching GraalPy. Separate SymPy module
tests call the real named operations. Neither those component tests nor a
successful `plan` command are a complete public three-environment run.

## Concrete connection gaps

1. There is no checkout-owned cohort manifest or CI transport for the same
   preregistered inputs across the three environments. Manual commands exist.
2. The documented Docker build cannot receive `build/amplification-source.tar`
   through its stated repository context: `.dockerignore` excludes `**/build`,
   while `Dockerfile.amplification:7` copies that file. There is no per-Dockerfile
   ignore override. This is a source-established context conflict, not a claim
   that a Docker build was attempted during this audit.
3. `command()` raises on nonzero exit or outer timeout before `execute_local()`
   writes stdout/stderr. A crash can leave no transport failure receipt. The
   actual partial outputs must survive; absent rows cannot be synthesized.
4. The compiled hash is currently observed before execution only. Machine ID
   is observed afterward only. The new cohort envelope should bind the actual
   before/after observations and exact source tree, not just role names.
5. Hosts currently choose `java` from PATH and record its version afterward;
   the container has a specific Temurin base. A reproducible cohort needs the
   same explicitly selected JDK build and architecture, with observed values
   retained before execution. The compiled-byte comparison must still decide
   whether the independently compiled implementations agree.
6. Root independently observed a budget-sensitive identity gap while preparing
   its refactor byte comparison: different principal-list orders can yield
   different outcomes under one configuration hash. Source inspection confirms
   that the runner retains/executes list order, but configuration currently
   binds only order-independent `RuleInventoryFingerprint.contentHash` values
   for principal and preparation inventories. The public experiment already
   constructs principals through its explicit `PRINCIPALS` list; it does not
   depend on registry-stream order. The general runner's identity still needs
   the actual execution order. Root's initial unordered cross-JVM comparison
   is a diagnostic, not valid before/after parity evidence for its refactor.

Before freezing a real cohort, after root hands off its runner refactor, add a
small separate configuration fix and regression control: keep both existing
content fingerprints and additionally bind the exact principal/preparation
execution-order ID lists. The constructor already rejects duplicate IDs across
both inventories, so IDs plus the existing content fingerprints identify the
ordered definitions without duplicating rule hashing. Do not sort, normalize
or otherwise change the real execution lists. Reversed input order must change
configuration identity; the same explicitly ordered input must preserve real
outcomes and work across JVMs. Retain the reported tight-budget case as a real
RED/GREEN control. This intentionally corrects new amplification configuration
hashes; never relabel old receipts under the corrected configuration. Generate
the cohort's new plan only from that reviewed final code. No historical v2
pilot or V3/V4 producer needs a change.

## Proposed sequence

1. **Prepare and preregister once.** From a clean, final reviewed commit,
   independently compile the existing classpath task and run the existing Java
   `plan` CLI. Write a new canonical transport-only `cohort-plan.json` binding
   commit/tree, the exact three input-file hashes, public corpus identity,
   expected 16/64/96 cardinalities, compiled-class manifest/hash, execution-role
   set `{host-a, host-b, container}`, toolchain/build pins and watchdog policy.
   Bind the Dockerfile, committed source archive, wrapper checksum and locked
   dependency/configuration bytes. Labels remain opaque bytes to transport.
   Publish/transfer this immutable input bundle before any formation starts.

2. **Execute on two real clean Linux machines.** Each role checks out exactly
   the declared commit, compiles independently using the same pinned JDK,
   verifies the cohort/plan/hash bindings, and writes an exclusive start receipt
   before invoking the existing `host` command. That receipt observes actual
   machine-ID hash, HEAD/tree, clean status, implementation hash, Java version,
   architecture and input hashes. Role labels are scheduling slots, never host
   identity. After execution, retain the same observations again and reject
   changes. No same-host second process may fill the second-host slot.

3. **Build and execute one pinned container.** Create a fresh minimal Docker
   context containing only the bound Dockerfile and `source.tar` from the exact
   committed archive; change its COPY input to `source.tar`. This avoids any
   broad `.dockerignore` change. Keep the existing digest-pinned Temurin base.
   Build preparation may resolve declared dependencies; record its logs and
   actual resolved image ID/inspection. Check the source-archive hash inside
   the build, as today, and retain its binding outside the image too. Run the
   built image by observed immutable ID with networking disabled, using the
   same three input bytes and the current `inside` Java authority. Retain exact
   compiled-class agreement with both independently compiled hosts.

4. **Collect and verify once.** Transfer complete bundles and their byte
   manifests to a clean checkout of the same commit. The new cohort verifier
   checks preregistered inputs and execution-envelope bindings, then calls the
   existing reproduction verifier for canonical agreement and its existing
   status. No shell/YAML/Python code evaluates a mathematical expression,
   chooses a principal, changes budgets, or reimplements reference comparison.
   Keep the immutable Java report's `REPRODUCTION_NOT_EVALUATED` unchanged;
   reproduction is a separately bound result. Retain the first outcome, even
   if it is null, inconclusive, disagreeing, or incomplete. A later rerun needs
   a new cohort receipt linked to the previous attempt, with no best-of choice.

The coordinator need not know machine IDs before provisioning: the plan fixes
the required roles, while each issued pre-execution receipt records the actual
machine it obtained. Acceptance then requires two different observed IDs.
This establishes different observed machine instances, not remote attestation,
different physical hypervisors or independently administered infrastructure.

## Small implementation surface

- One new Python cohort module/CLI under `scripts/`, sharing the existing
  transport/verifier helpers instead of creating another mathematical API.
- Small changes to `run-rule-amplification.py` for early log capture, explicit
  observed preparation/finish receipts and before/after implementation checks.
  Keep the five Java artifact schemas and the existing execution receipt
  fields intact; cohort evidence is an additive, separately versioned envelope.
- The minimal-context COPY adjustment in `reproduction/Dockerfile.amplification`
  plus a checked-in transport environment contract. The contract fixes Linux
  amd64, the existing `25.0.3_9-jdk-noble` Temurin base/digest, wrapper checksum,
  package/runtime pins and `--enable-native-access=ALL-UNNAMED -Xms512m -Xmx2g`
  from the existing isolated SymPy heap contract. Preparation verifies the
  actual JDK build; this audit did not pull or inspect the image. The envelope
  records observed OS/package metadata without
  claiming that mutable host package repositories are hermetic.
- One thin manual workflow, e.g. `.github/workflows/amplification-reproduction.yml`,
  with plan, two host roles, container role and collection jobs. Actions reuse
  the repository's exact approved commit pins and signature-verification
  behavior. Host/container jobs use dedicated fresh runners and invoke the
  same checkout CLI available locally. All artifact transfers use exact run/
  role names; aggregation validates contents against bound hashes.
- Focused Python controls and updated execution documentation. No edits to
  `PreparationArithmetic`, execution algorithms, budgets, ordinary CI gates or
  the isolated SymPy test authority. The separately reviewed execution-order
  configuration fix above is the sole planned runner edit and waits for root's
  ownership handoff.

A dedicated workflow is preferable to appending runs to ordinary CI: the
public evaluation stays explicit, and GraalPy execution has its own machines.
Manual-only shell instructions would leave the presently missing reusable
cohort and failure-retention connection. A new Java/distributed scheduler is
larger and would duplicate an already executable formation authority.

## Failure and claim boundary

Completed Java runs retain all 64 native and 96 external rows, including null,
unsupported, timeout, technical and budget outcomes. Preserve the existing
`REPRODUCED` versus `REPRODUCED_INCONCLUSIVE` classification and, when requested,
the existing `--require-conclusive` failure after writing its diagnostic.

Do not increase the current per-operation, native-work or artifact limits.
The transport currently allows 1,800 seconds for a Java run; 96 individual
60-second timeouts cannot all fit that outer window. Preserve that outer
policy in the first connection and report a killed/crashed process as
`INCOMPLETE_EXECUTION`, with available bytes, exit/timeout cause and logs.
An incomplete process is neither a null result nor a complete inconclusive
freeze. This limit must be visible rather than silently relaxed or patched by
inventing missing rows. A future changed watchdog contract would require a new
explicit plan, not a successful rerun under the old one.

Persist stdout/stderr from process start; retain a transport receipt in a
finally path after nonzero exit or timeout. Bind logs separately from the
five canonical files so timing/platform diagnostics do not affect semantic
byte identity. CI uploads available evidence after failure; cancellation or
machine loss cannot be represented as a successfully retained complete run.

All outcomes keep `comparativeGainClaim=NOT_AUTHORIZED` and
`BLOCKED_UNAVAILABLE_INTERNAL_WORK`. Exact reproduction does not supply the
missing matched internal work or authorize an efficiency ranking, #730 closure,
held-out claim or precision-study claim. Downloaded archive integrity is claimed
only for bytes actually obtained and hashed, not from an API metadata digest.

## Verification before an authorized real cohort

Use ordinary Python process controls for fresh-output refusal, input mutation,
duplicate role/host rejection, wrong source/classpath/image binding, changed
post-execution classes, missing artifact, nonzero child exit and timeout log
retention. Keep synthetic receipts explicitly synthetic. Exercise the actual
minimal-context assembly and committed-archive manifest without running Java
formation. Existing experiment Maven controls verify freeze-before-label order,
64-row native freeze and null/unsupported retention without starting GraalPy.

Public execution is already authorized. After implementation review and publication, use a
dedicated runtime area for one full real public Java run, then the same frozen
inputs on the second host and pinned container. Do not replace a missing real
environment with fixtures or a local process. Reproduction is reported only
after all three actual bundles pass the unchanged Java-evidence checks and the
new cohort bindings. No Gradle, Docker build, GraalPy run, workflow dispatch,
GitHub write, browser or protected study was performed for this design.

Audit validation performed: `python3 -B scripts/test-rule-amplification-reproduction.py`;
13 existing synthetic protocol controls passed. This is verifier-component
evidence only, not any completed public formation or reproduction.
