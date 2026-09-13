# Public amplification reproduction

`scripts/run-amplification-cohort.py` connects the existing Java amplification
authority to a preregistered public cohort. It prepares one plan, executes two
clean host copies and one separately compiled container, then calls the existing
reproduction verifier. The 16 public sources, four native profiles and six
separate SymPy operations remain owned by Java: 64 native and 96 external rows
per environment. No workflow or Python command selects mathematical outcomes.

The implementation has ordinary process and synthetic artifact controls. A real
two-host/container cohort has **not** been executed by this implementation slice.
It is public implementation qualification, not #533 FINAL or #981 precision
material. Existing thresholds, work contracts and historical artifacts are
unchanged. `comparativeGainClaim=NOT_AUTHORIZED` remains mandatory.

## Preregister and execute

Use the same reviewed, clean commit in every checkout. The environment contract
in `reproduction/amplification-environment.json` requires Linux amd64 and the
exact Eclipse Adoptium Temurin `25.0.3+9` build, with
`--enable-native-access=ALL-UNNAMED -Xms512m -Xmx2g`. A mutable Java major-version
selector does not meet the contract. GraalPy 25.1.3, SymPy 1.14.0, mpmath 1.3.0,
the wrapper checksum and the existing Temurin base-image digest are bound before
formation. POSIX process-group termination is required; unsupported systems fail.

The dedicated workflow uses Ubuntu 22.04 hosts with `patchelf=0.14.3-1`.
The unchanged Noble container base uses `patchelf=0.18.0-1.1build1`. These are
separate declared relocation-tool pins, verified against the actual tool output.
The published versions come from Ubuntu's [Jammy package](https://packages.ubuntu.com/jammy/patchelf)
and [Noble package](https://packages.ubuntu.com/noble/patchelf). This does not claim
that host and container OS/native-package bytes are identical.

Prepare once on a clean checkout with the required JDK and host prerequisite:

```bash
python3 -B scripts/run-amplification-cohort.py prepare \
  --output build/amplification-plan
```

Preparation compiles the actual authority and invokes only Java's `plan` command.
It does not perform candidate formation or a SymPy operation. It archives the
exact commit with `git archive`, retains every compiled Regelsuche class hash,
and creates a fresh `cohort-plan.json` binding the commit/tree, three Java inputs,
source archive, Dockerfile, environment contract, class manifest and roles.
Retain its printed SHA-256 separately before transferring the inputs. The
workflow carries that value directly from the preparation job's output.

Transfer the entire `build/amplification-plan/inputs` directory to each execution
checkout. On host A, set `AMPLIFICATION_COHORT_HASH` to the **previously retained**
value and run:

```bash
python3 -B scripts/run-amplification-cohort.py host-a \
  --inputs build/amplification-plan/inputs \
  --expected-cohort-hash "$AMPLIFICATION_COHORT_HASH" \
  --output build/amplification-execution
```

On a separate clean host, use `host-b` with the same inputs/hash and a fresh output
directory. Each host recompiles independently with build-cache reuse disabled
and tasks forced, and must reproduce the preregistered class manifest before
formation. Two processes on one machine cannot satisfy the two-host requirement.
Actual nonempty machine-ID hashes must differ; caller-provided role names do not
establish machine identity.

For the third role, on a clean checkout with Docker available:

```bash
python3 -B scripts/run-amplification-cohort.py container \
  --inputs build/amplification-plan/inputs \
  --expected-cohort-hash "$AMPLIFICATION_COHORT_HASH" \
  --output build/amplification-execution
```

The owned command creates a fresh Docker context containing only the bound
`source.tar` and `Dockerfile`. Repository `.dockerignore` rules cannot remove the
archive. The digest-pinned image verifies the archive SHA-256 and compiles it
separately; the actual image ID, full inspection, source labels and build logs
are retained. The build resolves existing declared dependencies and is not a
fully hermetic build. Availability of the pinned image/packages still requires
a real build. Execution uses `--network=none` and read-only transferred inputs.
The image's declared commit-archive source authority is distinct from a host's
observed clean Git checkout; there is no invented Git status inside the image.

Every Java run retains its actual class/source observation and cohort hash
before formation, then rechecks them after execution. The existing Java command
persists and rereads the complete candidate freeze, independently replays native
formation, and only then opens qualification. Transport copies qualification as
bounded opaque bytes. No new label-reading or selection path is introduced.

Transfer the **whole** execution directories, including raw logs and nested
container output, to `retained/host-a`, `retained/host-b`, and `retained/container`.
On the same clean verifier checkout, run:

```bash
python3 -B scripts/run-amplification-cohort.py compare \
  --inputs build/amplification-plan/inputs \
  --expected-cohort-hash "$AMPLIFICATION_COHORT_HASH" \
  --roots retained/host-a retained/host-b retained/container \
  --output build/amplification-result/cohort-result.json --require-conclusive
```

Acceptance checks all role/input/class/environment/process bindings before
calling `verify-rule-amplification-reproduction.py`. That unchanged verifier
checks complete row sets, canonical bindings, availability decisions and exact
agreement of the five Java authority files. The outer container projection must
match the retained inner Java bytes. The cohort result binds all three execution
receipts. These are checkout-owned execution observations and retained-file
hashes, not remote attestation, physical-machine independence, or an independent
verification of a transport service's ZIP bytes. Static symlink paths are
rejected; this is not a hostile concurrent-filesystem snapshot protocol.

Comparison requires complete retained-file maps and checks each Java and outer
Docker process's exit status, declared watchdog, actual argv and both raw log
hashes. The Java flags/main/revision and Docker image/cohort/name/network/read-only
input mount must match their existing authorities. A contradictory process or
an emptied file map cannot qualify through a `COMPLETE_BUNDLE` label. Host source
observations require `CLEAN_CHECKOUT`; the container's archive authority cannot
substitute for that role. Synthetic wire fixtures exercise these checks without
constituting reproduction evidence.

## Failure and attempt retention

Fresh output paths and exclusive file creation prevent replacement within one
local attempt. Stdout and stderr are opened before the child starts. Each process
retains its argv, timestamps, exit code or failure-to-start status, timeout and
raw log hashes. The unchanged outer Java watchdog is 1,800 seconds; container
execution has a 3,600-second watchdog. The per-operation 60-second Java timeout
and mathematical budgets are unchanged. A worst-case sequence may exceed the
outer watchdog; it remains an incomplete run. On a timed-out local process the
whole process group is killed. A failed Docker client also requests force-removal
of only its uniquely named container and retains that cleanup result.

| Retained outcome | Meaning |
| --- | --- |
| `PREPARATION_FAILED` | No cohort was qualified for formation; available preparation diagnostics remain |
| `INCOMPLETE_EXECUTION` | Failed start, nonzero exit, timeout, changed authority or another transport failure; only actually available files remain |
| `REPRODUCED_INCONCLUSIVE` | All three complete artifacts agree but retain a technical or budget failure; conclusive qualification fails |
| `REPRODUCED` | Complete canonical agreement under the existing verifier, including a legitimate complete null-result report; no comparative-gain authorization |

Transport never fills missing freeze/report rows with fabricated null or
inconclusive outcomes. Comparison retains `NOT_REPRODUCED` plus the observed
failure when a role or binding is missing. `--require-conclusive` also retains
the complete inconclusive diagnostic before returning failure.

The dedicated `amplification-reproduction.yml` workflow has read-only repository
permissions and no mathematical decisions. It provisions the pinned environment,
invokes these same commands and always attempts to upload available preparation,
execution and comparison directories. It is separate from ordinary `check`,
`ciCheck`, JMH and the existing isolated SymPy test lane. Repository integration
CI does not itself execute this cohort.

GitHub job reruns (`GITHUB_RUN_ATTEMPT > 1`) are refused. A new explicit cohort
gets a fresh nonce and may bind the previous retained `cohort-result.json` hash
through `prepare --previous-result-hash` or the workflow input. Failed first
artifacts must be retained alongside any later attempt. This prevents silently
mixing replacement roles into one accepted cohort; it does not invent a global
custody or attempt-history service. The actual coordinated workflow is launched
only after this concrete source has been reviewed and published.
