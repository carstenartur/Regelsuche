# Retained run Workbench — issue #669, next product slice

The immutable Java workspace, selection and bounded HTTP repository already
exist. This slice makes their retained evidence accessible inside the existing
Workbench. It does not rerun discovery or manufacture missing candidate data.

## Design

- Add a Runs panel to the existing tab controller. Open retained history,
  import exact canonical workspace bytes, deep-link to a run and artifact role,
  compare two complete retained manifests and download the original bytes.
- Use a separate, bounded immutable state controller. Every asynchronous load
  receives a generation token; only the current generation may publish data,
  errors or a selection. Validate schema, requested Run ID, ETag and roles.
- Preserve Java long values exactly in the browser; neither display nor
  comparison may silently round a seed or work counter.
- While a retained run is active, show its ID and gate unrelated mutable
  Workbench panels. Returning to the current session is an explicit action.
  Legacy URLs and the normal search flow keep their existing behavior.
- Show every artifact's actual status, schema and hash. AVAILABLE is a retained
  reference, not evidence that the browser loaded or verified that artifact.
  No links to global graph, replay or proof endpoints are substituted.
- Distinguish the recorded current/terminal state and reason. Configured work,
  consumed work and remaining work are exact; a CREATED run has no declared
  progress total. Runtime diagnostics retain their separate hash.

## Verification and delivery

1. Add browser controls for immutable state, late response rejection, a foreign
   Run ID, long precision, role selection and comparison.
2. Implement state and DOM controllers using existing native assets only.
3. Exercise the real HTTP repository through import, reload, history, deep link,
   compare and byte-identical download in the checkout-owned Playwright suite.
4. Capture desktop/narrow screenshots from the flow; review independently;
   run existing web/HTTP contracts and the complete CI authorities.

## Still required for the whole issue

Candidate dossier content resolution and cross-view candidate/edge/replay/radar
correlation, one-parameter duplication as a product action, live operation
controls, navigation consolidation and the optional mathematical editor remain
separate steps. This slice must not close #669 by itself.
