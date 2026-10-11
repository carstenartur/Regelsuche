# Native Java coverage reporting

The existing Gradle and isolated SymPy authorities produce the canonical JaCoCo
XML files. The unchanged cross-authority check converges all 23 policy-declared
modules and writes `build/reports/quality/coverage-report.json`. The Java reporter
submits those same measurements to GitHub after that check succeeds.

`JacocoCoberturaExporter` emits one Cobertura class per repository source file.
Nested Java classes do not duplicate lines, and equal basenames in different
modules retain different repository-relative paths. JaCoCo instruction coverage
determines binary line hits; the export does not invent execution counts.
Source, module and aggregate line/branch totals must agree exactly. The exporter
retains SHA-256 bindings for the policy, aggregate, every XML input and every
reported source file. It rejects missing or extra canonical modules, invalid
counters, unsafe paths and external XML entities before writing the report.
Cobertura complexity uses the actual JaCoCo covered-plus-missed counters at
source, package and report level. A missing counter is allowed only for a source
with no executable lines and is recorded as an unavailable zero. Tests validate
the output against the retained strict Cobertura 04 DTD without network access.

Each producing job records its actual Git commit and tree before measurement.
Tracked input changes and untracked Java files in top-level module source roots
fail the source checks; generated consumer copies beneath build output remain
outside those canonical roots. At convergence, both producer receipts must match
the checkout, and its tree must equal the intended upload commit's tree. A PR
upload identifies the actual same-repository PR head, even when GitHub tests a
separate merge commit with the same tree. A different tree is rejected.

The existing quality module stages its JAR and existing dependencies through
`:regelsuche-quality:nativeCoverageRuntime`. The convergence job invokes
`NativeCodeCoverageMain` with `--root`, `--gradle-source`, `--sympy-source` and
`--output`. Only that step receives `GITHUB_TOKEN`; only its job adds the
`code-quality: write` permission. Supported uploads are same-repository PRs and
default-branch pushes. Fork PRs and other events retain an explicit skipped
outcome without uploading.

The client implements the report protocol in the pinned first-party
[upload-code-coverage action](https://github.com/actions/upload-code-coverage/tree/2b21a77928be8d5168c2b9581a67f2adbebacc52):
one compressed report upload followed by bounded processing queries. Only a
report ID whose processing status becomes `succeeded` passes the upload step.
HTTP 200 without an ID records skipped storage and fails a supported upload;
it cannot certify coverage for the current head. Uploads are not blindly retried,
credentials are redacted, and interrupted or timed-out processing fails closed.

The `coverage-verification` artifact retains `coverage.cobertura.xml`,
`coverage-manifest.json` and `native-coverage-outcome.json` beneath
`build/reports/quality/native-coverage`. The outcome records both producer
identities; their original receipts also remain in the producer artifacts.
GitHub's native coverage
thresholds, base-branch comparison, Code Quality setup and review requirements
remain separate repository rules. This reporter neither changes nor bypasses
them, and its own runtime is outside the standalone optimization SDK closure.
