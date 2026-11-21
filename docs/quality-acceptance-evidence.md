# Quality acceptance evidence (#514)

The checkout owns the regression decisions. The acceptance collector preserves
their reports, exact policy and baseline bytes, exception identities, and the
complete retained vulnerability scan in one directory. It does not execute or
replace a gate, infer a security decision from scanner JSON, or authenticate a
downloaded GitHub artifact ZIP.

```bash
AI_KNOWLEDGE_EXTRACTOR_ENABLED=true ./gradlew --no-configuration-cache ciCheck
mvn -pl regelsuche-release -am -Dtest=QualityAcceptancePublicationTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dregelsuche.qualityAcceptance.collect=true verify
```

The publication test collects the existing reports into a fresh
`build/reports/quality/acceptance-evidence` directory. Set
`-Dregelsuche.qualityAcceptance.output=build/reports/quality/another-collection`
for another capture. A Git-free checkout can use a registration of the original
commit and its complete tree. Generate it from the source checkout with
`-Dregelsuche.qualityAcceptance.registerArchive=true` and
`-Dregelsuche.qualityAcceptance.registration=ABSOLUTE_NEW_FILE` on the same publication test in place of
the collection switch. Include that file as
`build/authority-evidence/quality-source-tree.json` in the expanded archive.
The reader reconstructs the commit tree and checks every tracked source member
and executable flag. The archive verification and report collection need no Git
executable. CI validates the archive before the full Maven reactor and retains
the registration and the verification test XML with the Git-free evidence.

`index.json` records the collection checkout revision/tree, whether tracked
changes are present, each file's SHA-256 and byte length, the native outcome
field, and current value fields with their original report hash. `index.md`
links the native outcomes. Original bytes are copied under `files/`, preserving
checkout-relative paths. Historical receipts are supporting material, separate
from `currentValueBindings`.

`COMPLETE` means every member of this finite retention map is available with
the checked file bindings. It is **not** a combined PASS verdict: a fully
retained FAILED or INCONCLUSIVE report keeps that outcome. Missing files,
malformed reports, symbolic inputs and broken retained bindings produce
`INCOMPLETE`, with the affected section marked `UNAVAILABLE` and each problem
listed. The Maven publication test retains the partial diagnostic and fails. Source revision identifies collection,
not the execution history of arbitrary pre-existing local outputs; use a clean
checkout and the unchanged gate lifecycle for acceptance.

The collector requires the policies' threshold, baseline and exception fields
and the reference inventories needed to retain their files. Empty or scalar
selectors and manifests cannot skip a section. These are structural availability
checks: they do not recalculate a numeric gate decision or reevaluate an
exception's expiry. Complete historical FAILED evidence keeps its native result.

## Policy, baseline, exception and measurement map

Paths below are also retained in the bundle. The JSON policies remain the
authority for the full exact inventories; this table introduces no new limits.

| Area | Exact threshold / baseline / exception authority | Current value and outcome binding |
| --- | --- | --- |
| Coverage | `config/quality/coverage-policy.json`: aggregate line 79%, branch 58%; all 22 module floors, including Hibernate line 84%, branch 68%. Floors may increase after retained green CI; decreases require an issue and explicit policy revision. | `build/reports/quality/coverage-report.json`: `status`, aggregate/module covered and missed counts, percentages and minimum percentages, violations. Isolated SymPy XML is joined before the existing coverage verifier runs. |
| JMH regression | `jmh-regression-policy-v2.json`: 29 exact benchmark identities and per-benchmark baselines, units and maxima; CORE 1.5×, REWRITE_PROGRAM 1.75×, END_TO_END_SEARCH 2×. `jmh-regression-decision-policy-v3.json` content-binds that unchanged inventory and uses `max(0, score - scoreError)`. A point above the maximum with uncertainty overlapping it is INCONCLUSIVE and fails closed. No per-benchmark suppression. The existing `jmh-baseline.json` execution contract and validator remain separate and unchanged. | `build/reports/quality/jmh-regression-report.json`: `status`, every benchmark's point/error/decision score/maximum, baseline revision and artifact digest, low-precision and inconclusive identities, violations. Raw JMH, allocation and SymPy measurements and existing summaries are retained. |
| JMH history | `jmh-history-policy.json` binds the exact two retained snapshots and their SHA-256 values. Its execution contract matches the frozen benchmark inventory; rendering changes no thresholds. | `build/reports/quality/jmh-history/history.json`: PASSED rendering status, snapshot identities, all measured historical points and SVG references; every declared SVG and both source snapshots are retained. These are historical measurements, not a new benchmark execution. |
| Complexity | `config/quality/complexity-hotspots.json`: exact source/signature baselines, allowed cognitive increase +2 and cyclomatic increase +1; current exceptions `[]`. Exceptions require an exact identity, finite ceilings, rationale and ISO expiry; expiry fails closed. `ai-knowledge/complexity-baseline.json` remains a separate context-footprint baseline. | `build/reports/quality/complexity-hotspot-report.json`: `status`, current/baseline measurements, changes, active exception IDs and violations. `build/ai-knowledge/check.json` and `complexity.json` retain the separate plugin checks and raw method measurements. |
| Visual | `app/src/e2eTest/resources/screenshots/visual-regression-policy.json`: four byte/Git-blob-bound baselines; pinned Playwright 1.65.0 Chromium container/environment; per-channel tolerance 12, maximum changed-pixel ratio 0.002, inclusive boundary. Baseline updates require the explicit pinned-container refresh path. | `build/reports/visual-regression/*.comparison.json`: each comparison's status, changed/total pixels, ratio, exact tolerances, baseline/actual byte hashes; actual PNGs and every nonzero-change diff PNG are retained. A missing comparison receipt cannot be promoted from screenshot presence to success. |
| HTTP body boundary | `WebSecurityConfig.java`: default 1 MiB, configurable minimum 1024 bytes. The retained `WebWorkbenchServerRequestLimitTest.java` matrix binds the documented POST operations, exact-boundary acceptance and the first extra byte's 413 contract for fixed-length/chunked input. No size-limit exception list. | `app/build/test-results/test/TEST-de.regelsuche.web.WebWorkbenchServerRequestLimitTest.xml`: native tests/failures/errors/skipped counts and individual cases. Counts are retained without inventing a separate aggregate PASS field. |
| Dependency inventory | `config/quality/supply-chain-policy.json`: deterministic CycloneDX 1.6 inventory, complete resolved component/edge identities, timestamp exclusion from semantic identity. This original v1 contract deliberately retains NOT_EVALUATED. | `build/reports/quality/supply-chain/{bom,dependency-inventory,supply-chain-evidence}.json`: exact raw/canonical bytes, component/node/edge counts and policy/inventory hashes. A successful inventory remains distinct from a vulnerability decision. |
| Vulnerabilities | `supply-chain-vulnerability-policy.json`: CVSS ≥7.0 rejected; unknown or ambiguous individual severity and scanner failure fail closed; **no suppressions**. OSV Maven generation `1789166016904280`, created `2026-09-11T22:33:37.036Z`, archive SHA-256 `e4a141009164335369bf5daf6fc14de2f6a4fe58993f7e386a81d85cc974e786`; OSV-Scanner 2.5.1, revision `c84fa4568f2526d0333e9a914ea8a0a5f74ad68b`, binary SHA-256 `f9f25499a2c8cc367b3af45df2ea7eeca7fbccceab9c35079968f4b3652194be`. | `vulnerability/latest.json` selects one retained run and binds its exact evidence bytes. The collector requires its policy/database/scanner manifests, authority metadata/licenses/provenance, complete archive and staged archive, exact original SBOM/inventory/v1 evidence, all bound per-PURL inputs, configuration, execution receipt, stdout/stderr and native PASS/FAIL/ERROR decision. Current aggregate inventory bytes must match the scan's bytes. It preserves the scanner authority's outcome; exported JSON cannot create a new authenticated execution. |
| Test and generation cost | Slow-test reporting's default diagnostic threshold is 5 seconds; it is not a test timeout. `docs/evidence/release-evidence-task-reuse-v1.json` binds the local twelve-control measurement, its source/input/output hashes and 48 output files. The separate integrity receipt records the subsequent verifier controls and their scope. | `build/reports/quality/slow-tests.json` contains current suite/test counts, durations and slow-test identities. The original generator-only review measured 1.991 s generation versus 0.047 s unchanged reuse (later unchanged 0.031 s), while the verifier always ran. These local historic timings are not current CI latency or fresh experiment results. |

The index covers #514's finite report map. Existing container-image/immutable
workflow contracts, real Docker/browser/tool checks, release reproduction and
other repository authorities remain required by their original lifecycles.
Their success is not inferred from this directory's completeness.

The current slow-test report describes its producer's Gradle test corpus.
Isolated SymPy and Maven timings remain in their own authority artifacts; the
collector does not invent a combined duration or test count across those jobs.

## CI retention and remaining acceptance

The existing `gradle.yml` required `verification` job downloads the Gradle,
isolated JMH and SymPy artifacts from the same workflow run. The Maven
`verify-cross-authority-coverage` profile runs the Java verifier through
Failsafe. The verifier joins the isolated SymPy XML, rejects an occupied canonical
path and prints the SHA-256 of the byte-identical installed report before
evaluating the existing coverage policy. With `-Dregelsuche.coverage.publish=true`,
the same Failsafe execution also renders the standard JaCoCo pages from every
module's retained execution data, classes and sources and verifies their counters
against the canonical reports and gate. The job then runs the Maven publication
test, which requires a `COMPLETE` result. The `quality-acceptance-evidence` artifact contains
only the collector's output. The existing `repository-verification`,
`jmh-verification`, `sympy-runtime-verification`, `coverage-verification` and
other authority artifacts remain retained. The collector preserves the existing
gate selection, thresholds and suppressions. GitHub transfers files. The checkout owns
the completeness check and every existing quality decision.

To verify downloaded coverage inputs with Maven, use a fresh evidence tree with
the checkout policy, the project-parallel module XML files and the isolated SymPy
XML. The Java verifier writes the canonical SymPy input and coverage reports
into that tree. An existing canonical SymPy XML is ambiguous and fails the join.

```bash
mvn -pl regelsuche-quality -Pverify-cross-authority-coverage -DskipTests verify \
  -Dregelsuche.coverage.evidenceRoot=/absolute/path/to/evidence-tree \
  -Dregelsuche.coverage.isolatedReport=build/authority-evidence/sympy/site/jacoco/jacoco.xml
```

The profile defaults to the isolated report path shown above and the current
checkout as the evidence tree. `-DskipTests` skips the ordinary Surefire suite
while the profile's Failsafe verification runs on the retained inputs.
To reproduce the coverage site as well, add `-Dregelsuche.coverage.publish=true`
and include the `inputs` directories alongside the XML reports in the evidence
tree, including the isolated SymPy report's sibling `inputs` directory.

Historical charts and safe generation reuse have already been implemented and
measured with the limits described in [performance history](performance-history.md)
and [release task reuse](release-evidence-task-reuse.md). The required
[snapshot-bound scanner](supply-chain-vulnerability-evidence.md) already retains
its SBOM and complete authority as one coherent run. Its older documented
Netty 4.2.15 FAIL remains historical evidence, not a current-tree verdict.

This consolidation does not itself provide a fresh complete CI result. The
remaining final acceptance is a clean full required run, successful original
gates and a COMPLETE converged collection from that run. Deliberate-regression
controls continue in their existing checkout-owned tests/tasks; new collector
controls exercise missing files, malformed/broken bindings, exact byte copying,
path isolation and preservation of negative outcomes. No protected precision
study, campaign, held-out run or release publication is needed merely to
collect existing outputs. The separate precision-study protocol/adoption and
normal hosted-release observation retain their own outstanding boundaries.
