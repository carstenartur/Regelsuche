# Retained runs in the Workbench

Open **Gespeicherte Runs** in the existing local Workbench. The bounded history
lists the immutable workspaces already retained by `/api/discovery-runs`.
You can also open a SHA-256 Run ID or import an exact canonical workspace JSON
file (up to 1,048,576 bytes, matching the default HTTP request limit). The Java repository validates canonical bytes,
schema and all nested content hashes before accepting an import.

The view shows input, assumptions, information boundary, strategy/profile,
objective, exact seed, backend identities, recorded state/reason and work
accounting. CREATED means work has not yet been declared; RUNNING is explicitly
a retained snapshot, with no live updates or fabricated progress percentage.
Canonical mathematical work and runtime-diagnostic identities stay separate.

Every artifact role shows its retained status and target identity. Missing,
unsupported and failed artifacts keep their actual statuses; the controller
never replaces them with the current global graph, replay or proof state.

## Candidate dossier

The next bounded #669 slice opens the existing #663
`regelsuche.target-free-sympy-bridge-discovery/v1` artifact, already bound by
`CANDIDATE_DOSSIERS`. Import the canonical `target-free-sympy-bridge.json`
produced alongside `run-workspace.json` by
`TargetFreeRepresentationDiscoveryRun.write`. An optional final LF from that
writer is accepted. The dossier endpoint accepts at most 1 MiB and checks
canonical UTF-8, the scenario/search hashes, the immutable RunBundle bindings,
information boundary, source assumptions and bridge-to-state lineage. Every
subsequent read repeats those checks. If the manifest binds a supported dossier
and the local copy is missing, the Workbench shows the expected artifact hash
and offers the import control. Opening the dossier does not run search, replay
or mathematical validation.

The retained budget commitment and configured work must match the scenario's
recorded search budget. Required nested identities, provenance text, evidence
statuses and recorded counts are checked before persistence; missing or malformed
fields return `400 INVALID_DOSSIER`. This contract check does not supply any of
the proof or guard evidence that the source artifact does not retain.

The dossier shows the selected retained state hash and expression together with:

- source expression and whole-expression context;
- all raw description dimensions before/after, semantic occurrence/sharing
  counters and symbol inventories, without a scalar simplification score;
- the recorded bridge classification, evidence threshold and warnings;
- the separate executed follow-on capability evidence, including enabled and
  disabled inventory identities and retained successors;
- declared run and path assumptions, exact recorded validation status when
  present, primitive rule IDs, path lineage and provenance;
- Run ID, candidate/state identity, artifact/search hashes, freeze receipts,
  inventory/catalog identities and application/backend revisions.

Classification and follow-on evidence are attached only to the scenario's
`discoveredBridge.stateHash`. Selecting any other retained candidate does not
inherit that bridge's validation status, catalog label or capability result.
`equivalencePreserving` remains a construction metadata flag, not a proof.

This source does **not** retain an exact local AST occurrence, separate syntax
and semantic IDs, an executable DAG, the complete capability frontier or lost
capabilities, guard-evaluator transcripts, counterexample reports, exact proof
certificates, replay steps, rule-radar candidate IDs or proof-obligation IDs.
The UI names these gaps. It preserves the opaque recorded `applicationKey`
without interpreting it as an AST path. `TRUE`, `FALSE`, `UNKNOWN` and conflict
have a textual legend; absent guard evidence is explicitly `UNKNOWN`, and the
legend itself is not presented as an evaluation.

Graph links use the retained search artifact hash plus its exact transition
sequence. They open the full recorded transition within the same run, including
source/target state hashes, rule ID, application key and primitive rule IDs.
The return action restores candidate-button focus. Replay, radar and proof
references remain unavailable when the artifact has no exact correlated IDs.

`GET/POST /api/discovery-runs/{runDigest}/dossier` is documented in the canonical
OpenAPI surface. The response binds `X-Regelsuche-Run-Id` and an artifact ETag.
The supported artifacts are stored beside the configured run directory in
`<run-directory>-dossiers`, one immutable file per Run ID, with the same finite
run-count cap. Symbolic repository paths and non-regular files are rejected.
Keep both directories when moving an existing retained collection.

Click an artifact role to retain that selection in a link such as
`/static/index.html#run=<64 lowercase hexadecimal digits>&artifact=PATH_REPLAY`.
Reloading restores the exact run and role. The original manifest download
preserves the bytes returned by the canonical repository. Comparison loads a
second independent immutable workspace and displays all changed fields;
large Java long seeds and work counters are never rounded through JavaScript
Number arithmetic.

Candidate and graph selections extend the same fragment with
`candidate=sha256%3A<digest>` and `edge=<retained transition sequence>`.
Unknown candidate IDs and edges from other candidates fail visibly instead of
selecting a substitute. Run, role and candidate selections are immutable
snapshots. Late dossier loads, errors and duplication responses cannot replace
a newer run or selection.
Role-only links also keep their requested artifact role when the default
candidate is first loaded; explicit candidate and graph actions still select
their corresponding artifact roles.

## One-parameter duplication

The duplication form shows the parent Run ID and the old/new deterministic
seed. `POST /api/discovery-runs/{runDigest}/duplicate` accepts exactly that one
parameter, checks an exact Java long, and delegates to the existing
`duplicateWithOnePlanChange` contract. Identical seeds, extra parameters,
duplicate JSON keys and out-of-range values are rejected.

The new manifest binds `DUPLICATED_ONE_PARAMETER`, `parentRunId` and
`changedPlanParameter=deterministicSeed`; it is `CREATED / NOT_STARTED` with
zero work and every artifact reset to `NOT_PRODUCED`. The original declared
revisions are retained as configuration; the new run has not been executed.
The full manifest comparison opens against the immutable parent and keeps
large seeds exact. Original manifest bytes are unchanged.

While a retained run is active, its ID is visible and unrelated mutable
Workbench tabs are disabled. **Zur aktuellen Sitzung** explicitly restores
those existing views. Existing URLs, ordinary searches and their tab controller
remain supported. The new bounded controllers use only first-party native
JavaScript and the existing CSS; no package manager or Node host build
requirement is added.

`RetainedRunWorkbenchBrowserTest` is part of the existing Gradle `:app:e2eTest`
authority. It exercises the real HTTP repository through import, history,
deep-link/reload, role selection, exact manifest download, comparison, keyboard
operation and a narrow viewport. It also runs eleven immutable-state, late
success/error, foreign-ID/ETag, integer and malformed-role/schema controls in
the browser. Screenshots are retained below `build/reports/retained-run-ui`.

`CandidateDossierBrowserTest` adds real HTTP import and dossier review through
`/`, `/index.html` and `/static/index.html`; keyboard candidate selection,
exact graph navigation/reload, seed duplication/comparison, candidate-specific
claim isolation and delayed-response controls. Screenshots are generated under
`build/reports/candidate-dossier-ui` at desktop and 390-pixel widths. These
native controllers add no Node/npm host requirement.

The existing [Proof-Jobs panel](proof-workbench.md#laufenden-proof-job-anhalten)
can stop a live proof worker and retain its request time, terminal reason and
actual attempt count under that job ID. Its admission and stop records belong
to the proof job. A retained discovery run needs a source reference to
associate that job with its candidate.

In the ordinary Workbench search form, **Formel bearbeiten und Vorschau öffnen**
can wrap a selected expression, square it or insert a fraction. The same text
field remains editable and supplies the search request. **Parser-Vorschau**
renders the term or equation as MathML and exposes its exact AST, including
the zero introduced by unary minus. On syntax errors the parser returns the
offending half-open UTF-16 interval in the original input. Use **Fehlerstelle
im Ausdruck auswählen** to select it or move the caret to a missing character.
For example, `2x` selects the unexpected `x`; enter `2*x` to multiply.
The preview accepts up to 256 UTF-16 code units. Both Runtime profiles use
the search parser to preview and edit terms or whole systems. Each equation
keeps its original source offsets, so an occurrence in a later row can be
replaced and submitted from the same input field.

The retained history and the new independent occurrence reviews have distinct authority. The source-bound controls and checks below describe the supported native and frozen formats.

## Run a native search in the Workbench

Open **Gespeicherte Runs → Zieloffene Suche ausführen**, enter a term and choose the 12- or 32-state budget. The search worker uses the same R1 native runner, CORE formation inventory and immutable dossier format as the command-line producer. **Pausiert starten** creates an observable initial checkpoint. **Pausieren**, **Fortsetzen** and **Suche stoppen** operate at search-event boundaries. A successful search publishes the usual workspace and occurrence dossier. Its operation URL remains available in the execution history. If the completed observation exceeds the 1 MiB dossier limit, the operation records DOSSIER_BYTE_BUDGET and the observed counters. A smaller budget can produce a retainable dossier.

The operation record contains its exact source, source revision, six budget parameters, visited and queued states, generations and discard counts by reason. Token and AST minima come from the visited expressions. A pending search has unknown total work. This runner has one best-first frontier, with no Pareto archive or external proof queue. Event collection and the optional UI measurements are operational overhead and are separate from the canonical native work ledger.

A live process holds one writer lease for the repository. It retains progress every 32 events and on control transitions. Cancellation and graceful shutdown retain their observed counters and terminal reason. Following a process crash the next reader recovers the last durable snapshot as INTERRUPTED. An interrupted worker's in-memory frontier cannot be resumed. Opening its record preserves the terminal history. Start a new operation to rerun the same input. The Gradle and Maven application JARs embed the producing commit. The build reads the checkout HEAD, the commit recorded by Git in a source archive or the separately supplied preparation revision. A shipped JVM reads the revision from the same JAR as the runner. For independently assembled sources without embedded provenance, the operator can supply the exact producing commit as JVM property `regelsuche.repository.revision` or environment variable `REGELSUCHE_REPOSITORY_REVISION`. Any conflict with the embedded revision or a missing revision prevents admission.

## Review an occurrence and its assumptions

The candidate dossier's **Ausgewähltes Vorkommen prüfen** controls keep the end candidate, transition and native generation in the URL while switching between graph, replay, radar and proof. The five task groups in **Werkzeuge nach Aufgabe** also open these bound views when their inputs exist. Available frozen #663 observations remain in the historical section. The occurrence review is a separately produced receipt.

For a frozen #663 transition the server resolves the retained parent, rule, application identity and successor. For a native candidate it additionally checks generation membership in its recorded path and the archived position. It matches the source formation inventory hash and independently applies the exact rule to the source. The radar uses the same occurrence and its real matching bindings. Reused expressions elsewhere in the tree cannot substitute for the selected occurrence. The receipt records the local before/after subtrees, whole roots, syntax and sharing metrics, historical assumptions, formation inventory and information boundary.

**Beweisverpflichtung** builds a typed equality obligation with a source-binding hash and any required side conditions. Within the bounded polynomial fragment the installed polynomial backend executes it and retains its own translation, result and certificate references. The Workbench displays the backend's actual status. It keeps historical symbolic classification for the frozen trigonometric example and reports the scope of the current independent checker separately.

**Zusätzlichen Annahmekontext untersuchen** compares a typed guard against entered predicates, for example `x != 0` and `x = 0`. Two attributed evaluators independently look for an identical predicate and its exact logical complement on the same operands. A conflict stores both witnesses and resolves to UNKNOWN. Each result is written in text. Extra premises affect only this review and cannot certify the original rewrite or change its typed equality obligation.

The HTTP POST at `/api/discovery-runs/{runDigest}/candidate-evidence` accepts the selected candidate and edge, with optional review guard and premises. The response binds its hash in the ETag and returns a permanent GET location. **Gespeicherten Originalbeleg erneut prüfen und öffnen** reads those retained bytes and independently recomputes their content from the original source before returning them. The canonical JSON is downloadable for independent inspection.

The occurrence review compares the original input with the selected end candidate even when the user inspects an earlier replay step. It records raw measures, a source-text syntax hash, the versioned semantic-value DAG digest and all whole/subform matches against the catalog committed by the run. Its bounded one-step formation-rule scan preserves concrete successors, their assumptions, provenance and truncation. Newly observed or lost rule IDs count unconditional returned successors only. An incomplete scan describes the observed window.
