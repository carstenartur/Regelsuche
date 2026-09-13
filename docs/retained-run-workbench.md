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

Every artifact role shows its retained status and target identity. An AVAILABLE
reference does not mean that this browser view loaded, replayed or verified its
content. Missing/unsupported/failed artifacts keep their actual statuses; the
controller never replaces them with the current global graph, replay or proof
state. Artifact content resolution and the complete candidate dossier remain
subsequent #669 work.

Click an artifact role to retain that selection in a link such as
`/static/index.html#run=<64 lowercase hexadecimal digits>&artifact=PATH_REPLAY`.
Reloading restores the exact run and role. The original manifest download
preserves the bytes returned by the canonical repository. Comparison loads a
second independent immutable workspace and displays all changed fields;
large Java long seeds and work counters are never rounded through JavaScript
Number arithmetic.

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

This is the next product slice of #669. Candidate/edge/replay/radar correlation,
product duplication, live operation controls, navigation consolidation and an
optional mathematical editor remain open. No new discovery evaluation,
mathematical-proof, novelty or search-quality claim follows from this view.
