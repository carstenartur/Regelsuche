# Optional live-runner handoff

The admissible proof workspace can receive new experiment bytes from an
operator-installed same-document runner adapter. The expression Workbench
server itself does not gain a process-launch endpoint or a dependency on the
private Primachsenraum repository.

The paired Primachsenraum SDK consumer provides the optional loopback runner.
It reuses these five resources, adding its own controls and transport script.
A completed child JVM delivers an `admissible:local-result` CustomEvent whose
`detail` is a bounded JSON string. This is not a trusted result object: the
workspace clears stale state and invokes the same independent proof worker as
for a file import. Errors, oversized strings and non-string payloads fail closed.
No event can supply an accepted proof status directly.

## New inputs are not held-out benchmark evidence

The old `admissible-workbench/v1` experiment format remains unchanged. Newly
requested computations use `admissible-workbench/v2`, with exactly the old
fields plus `scope: "exploratory"`. Other scopes and additional fields are
rejected. Mathematical checking is identical for both versions.

The scope survives downloading and reimporting the JSON, so a manually chosen
new input is not subsequently labelled as a held-out test case. A direct
same-document handoff is always labelled exploratory, even when its bytes use
the old schema. This marking describes experiment provenance, not mathematical
authentication. Native work, training selection and source identity remain
imported claims rather than browser-replayed facts.

The actual SDK run, separate native policy controls, process budget and HTTP
security belong to the paired consumer. A browser proof pass does not certify
all work of that campaign or assert that a new learning run occurred.

## Checks

`AdmissibleWorkbenchBrowserTest` shares one production-server/worker fixture
for import, live handoff and v2 reimport, rejecting damaged bytes and forged
status objects. `scripts/admissible-live-scope.test.cjs` checks compatibility,
strict scope and unchanged mathematical guards against retained actual CI
certificates. The main-page no-upload test monitors the experiment popup and
deliberately injects one POST to demonstrate that its monitor is active; it
does not forbid the expression page's legitimate AST POST.
