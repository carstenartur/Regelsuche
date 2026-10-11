# Native Coverage Reporting Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development to execute the independently testable tasks below. Existing authorization to continue the repository integration supplies the execution method.

**Goal:** Submit the existing complete Java coverage measurements to GitHub's native coverage service without changing any measured code, threshold, or SDK runtime.

**Architecture:** Convert the policy-declared canonical JaCoCo source-file records to Cobertura in the existing Java quality module. Bind both coverage producers and the converged checkout to the measured commit/tree, and submit only the matching same-repository PR head or default-main push through the real service. Preserve the XML, source/input manifest, upload ID and processing outcome.

**Tech Stack:** Java 25, existing Jackson, hardened DOM input and StAX output, JDK HTTP/GZIP/Base64, Maven/JUnit, existing Gradle authority and GitHub Actions.

**Spec:** The accepted design in this document implements the protocol in `actions/upload-code-coverage` revision `2b21a77928be8d5168c2b9581a67f2adbebacc52` (`upload_coverage.py`, `resolve_upload_context.py`, `action.yml`). The existing `config/quality` coverage policy and converged JSON remain the numerical authority.

## Global Constraints

- No new Python product, test, or workflow automation; no new dependency.
- No proof, arithmetic cost, Java guard, SDK runtime or qualified SDK-pin change.
- Preserve all 23 canonical module reports, uncovered lines and existing coverage floors.
- No simulated check runs, status writes, threshold reductions, bypasses or official-action identity claims.
- A PR upload uses its actual head SHA only when both producer commits/trees match the convergence checkout and that tree equals the head tree. Do not relabel a different merge tree.
- Native Code Quality setup, actual report processing and each PR's review requirements remain separate gates; a completed merge alone does not establish how native rules were evaluated.
- Review target 1,500 changed text lines, hard cap 2,000 across the eventual PR.

## Review Focus

- Nested classes and equal basenames across modules must not duplicate or conflate source lines (Task 1).
- XML DTDs, entity references, path traversal and symlink escape must not access external data (Task 1).
- A stale producer, changed source file, or test-merge/head-tree mismatch must prevent upload (Task 3).
- HTTP 200 without a report ID must not become a claim of stored coverage (Task 2).
- A transient query failure must not hide an upload rejection or exceed the processing deadline (Task 2).

## Task 1: Exact JaCoCo-to-Cobertura export

**Files:** Create `regelsuche-quality/src/{main,test}/java/de/regelsuche/quality/coverage/JacocoCoberturaExporter{,Test}.java`.

**Interface:** `JacocoCoberturaExporter.export(Path root, Path policy, Path aggregate, Path outputDirectory)` returns `Result(Path report, Path manifest, long coveredLines, long validLines, String reportSha256)`.

- [ ] Write hand-derived two-module tests with nested classes, identical basenames, covered, partial and uncovered lines. Assert repository-relative filenames, every line once, binary `hits = ci > 0 ? 1 : 0`, and exact totals.
- [ ] Add missing/duplicate module, duplicate line, malformed/disagreeing counters, nonexistent source, out-of-range line, path escape, symlink and external-entity cases; accept ordinary JaCoCo DOCTYPE without fetching its DTD.
- [ ] Run `mvn -B -pl regelsuche-quality -Dtest=JacocoCoberturaExporterTest test` and retain the missing-implementation failure.
- [ ] Implement secure deterministic export from policy-declared canonical XML only. Check line sums against source-file, module and existing aggregate counters; preserve branch denominators faithfully when emitted. Retain XML/source hashes and totals in the manifest.
- [ ] Run the same test successfully and retain its XML/logs. Run a source-bound real-report export before upload.

## Task 2: Native upload and processing client

**Files:** Create `regelsuche-quality/src/{main,test}/java/de/regelsuche/quality/coverage/NativeCodeCoverageClient{,Test}.java`.

**Interface:** `upload(URI api, String repository, String token, String commit, Long pullRequest, String ref, byte[] cobertura)` returns a receipt describing HTTP status, report ID and completed processing. Exactly one of PR number/ref is present. Use package-local clock/sleeper injection for bounded tests.

- [ ] Write real loopback HTTP tests that independently decode the transmitted base64/gzip and assert exact XML, JSON types, Java language, stable `code-coverage/jacoco` label, headers, endpoint and source SHA.
- [ ] Cover 200/no-ID skip, other 2xx/no-ID failure, upload 403/500, pending/processing/succeeded, failed/errors, query 404, transient 503, malformed/unknown status timeout and interruption.
- [ ] Run the focused Maven/JUnit class and retain the initial failure.
- [ ] Implement one PUT to `/repos/{repository}/code-coverage/report`, then GET `/repos/{repository}/code-coverage/reports/{id}`. Use 5-second initial wait, exponential delay capped at 60 seconds, a 160-second monotonic deadline, bounded requests and interruption propagation. Never retry PUT blindly or log credentials. Only `succeeded` proves processing; skipped storage cannot satisfy the current-head gate.
- [ ] Run the focused tests successfully and retain their XML/logs. No live endpoint call outside normal authorized CI.

## Task 3: Source binding and existing-workflow integration

**Files:** Create `NativeCodeCoverageMain.java` and `NativeCodeCoverageMainTest.java` in the same main/test package; modify `.github/workflows/gradle.yml` and `regelsuche-quality/build.gradle`.

**Interface:** Main consumes `--root <checkout> --gradle-source <two-line commit/tree receipt> --sympy-source <receipt> --output <directory>`. It reads only the needed GitHub event/context fields, obtains current/head trees with fixed-argument Git subprocesses, invokes Task 1 then Task 2, and retains outcomes. Root owns workflow/build changes; uploader agent owns Main and its tests.

- [ ] Test same-repository PR head binding versus synthetic merge SHA, default-main push, mismatched producer/source/head tree, missing metadata, unsupported contexts and fork PRs before any request. Check that diagnostics and receipts omit tokens.
- [ ] Add commit/tree producer receipts immediately after clean checkout, before each Gradle/SymPy measurement, and retain them with their existing artifacts.
- [ ] Provision the quality module's small runtime distribution from the existing Gradle build; add Java 25 at convergence and use checkout depth 2 to resolve the merge parents.
- [ ] Keep the existing convergence/acceptance checks unchanged. After they pass, run the Java reporter with `code-quality: write` only on the uploading job and token only on its step. Preserve explicit unsupported-context skips, with no `pull_request_target` or success claim for absent storage.
- [ ] Retain native report/manifest/outcome in the coverage artifact and extend the five-minute aggregate timeout only enough for bounded native processing.
- [ ] Run the full normal quality-module Maven suite, review the whole diff independently, and publish a substantive commit only after local validation. Normal CI must prove actual ingestion; native minimum/drop evaluation and Code Quality setup remain separately visible.

## Integration sequencing

- SDK PR #1087 was merged as `e4ea0d9eedda28868a181df61fc3fd6cb1c4ee3a`; its entire tree equals the qualified source `6a29154e217ea8748257cfda0340eb463ff157b2`. Keep the earlier execution evidence bound to its actual source revision.
- Publish this reporting infrastructure separately against that main revision. Its code remains outside the SDK runtime closure and does not require a new Sandbox SDK pin.
- The final exporter includes real JaCoCo complexity totals and validates the strict Cobertura 04 DTD. Its 30 tests and the final 35 client/Main tests were independently green; verify all 65 together after assembly on current main.
- Actual Gradle runtime staging, the complete 23-module export and native processing require the normal CI run. The local full quality-suite attempt was blocked by the existing scanner download dependency; do not relabel it as passing.
- Sandbox PR #1695 was merged as `7e11fd618733a390268d38fbd7e12014b0948f23`. Preserve its qualified product and actual fresh Help capture evidence while completing the separate 3,109-source installed-product comparison.
- Merge only when GitHub's actual review and rule requirements are satisfied. Never use the caller's administrative bypass.
