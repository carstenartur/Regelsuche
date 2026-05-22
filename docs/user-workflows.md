# User Workflows

This page describes the concrete workflows Regelsuche is built to support.
Every workflow links to the backing UI, REST endpoints and tests.

---

## 1. Teacher / Student — "Show me the textbook path"

**Goal:** Enter an expression, inspect several derivations, pick the one
that best matches how the topic is taught, and export the result.

1. Open <http://localhost:8080/> and stay on the **Workbench** tab.
2. Type the expression (e.g. `(x+3)^2`) into the input field.
3. Pick the **Profil** `TEACHING` (or pick any profile and set
   **Ziel** to `TEACHING_FRIENDLY` — see
   [Goal dropdown](#goal-dropdown)).
4. Click **Suche starten**.
5. Switch to **Pfade** to compare the derivations, then to **Replay**
   to walk through the chosen one step by step.
6. Use the **Exporte** tab → *Markdown / LaTeX* to download the
   formatted derivation.

REST equivalent:

```http
POST /api/search
Content-Type: application/json

{ "expression": "(x+3)^2", "profile": "TEACHING", "goal": "TEACHING_FRIENDLY" }
```

E2E coverage: `BrowserDemoFlowTest#binomialDemoBrowserFlow` and
`mathEquationDemoBrowserFlow`.

---

## 2. Researcher / Developer — "Discover & promote macro rules"

**Goal:** Run a wide search, inspect the resulting rule candidates, then
promote the most universal ones into the rule inventory.

1. Pick **Profil** `DISCOVERY_PLUS` to enable the transposition table.
2. Run several searches on related expressions.
3. Open the **Regelkandidaten** tab — sort by `proofStatus` and
   `occurrenceCount`.
4. Switch to **Suchgedächtnis** → *Universelle Muster* to see which
   canonical states keep coming back across different searches
   (universality score) and which rules contribute to them
   (Rule-Coverage).
5. Use **Inventar** to enable (`/api/inventory/{id}/enable`) the
   patterns you want active in future searches.

REST surfaces involved: `/api/search`, `/api/candidates`,
`/api/memory/universal`, `/api/inventory`.

---

## 3. CAS comparison — "Don't just give me the answer, give me the alternatives"

**Goal:** Use Equality-Saturation to share the entire rewrite space in a
single e-graph, then compare cost models.

1. Pick **Profil** `EQUALITY_SATURATION`.
2. Switch between **Ziel** values (`SIMPLIFY`, `FACTORIZE`,
   `NUMERICALLY_STABLE`) to extract a different canonical form from the
   same saturated graph without re-running the search.
3. Open the **Vergleich** tab to put two results side by side.

See [`docs/equality-saturation.md`](equality-saturation.md) for the
underlying e-graph mechanics.

---

## 4. Proof workflow — "Submit, watch, download artefacts"

**Goal:** Take a rule candidate, prove it asynchronously with Lean/SMT,
and download the artefact bundle.

1. Open **Proof-Jobs** tab.
2. Enter `leftPattern`, `rightPattern`, optional assumptions and
   priority. Click **Job einreichen**.
3. The job appears in the list with its current status. Use
   **Aktualisieren** to refresh, **Cancel** to interrupt.
4. Click **Artefakte** on any finished job to inspect / download the
   bundle (`proof.lean` / `proof.smt2`, `stdout.txt`, `stderr.txt`,
   `metadata.json`).

REST equivalent:

```http
POST   /api/proof/jobs          # submit
GET    /api/proof/jobs          # list
GET    /api/proof/jobs/{id}     # detail
POST   /api/proof/jobs/{id}/cancel
GET    /api/proof/jobs/{id}/artifacts          # bundle file list
GET    /api/proof/jobs/{id}/artifacts/{name}   # raw file
```

E2E coverage: `ProofJobsApiTest`. See
[`docs/proof-workbench.md`](proof-workbench.md) for the configuration
knobs (`REGELSUCHE_PROOF_*` env vars) and the Docker image
(`Dockerfile.proof`) that ships Z3 + cvc5 preinstalled.

---

## Goal dropdown

Every workflow can be biased toward a particular `TransformationGoal`
without changing the search profile:

| Goal | When to pick it |
| --- | --- |
| `SIMPLIFY` | Default — shortest / smallest result. |
| `FACTORIZE` | Prefer factored form over expanded polynomials. |
| `TEACHING_FRIENDLY` | School-book style: small coefficients, shallow nesting. |
| `PROOF_FRIENDLY` | Prefer shapes that simplify case analysis. |
| `NUMERICALLY_STABLE` | Prefer Horner-style / well-conditioned forms. |

Surfaces:

- UI: the **Ziel** dropdown next to **Profil** on the Workbench tab.
- API: `POST /api/search` accepts `"goal": "..."`. Omitting it falls
  back to the profile's default goal.

---

## Landing page entry flow

Newcomers see a deliberately reduced landing page: only the workbench
entry tab is visible, with one primary form
(_Ausdruck eingeben → Ziel wählen → Suche starten_) and the demo tile
grid below it. Downstream tabs (`Graph`, `Replay`, `Proof-Jobs`,
`Export`, `Benchmark`, …) are hidden by CSS (`body.pre-search`) until
the user starts their first search or clicks a demo tile — see
[`LandingPageBrowserFlowTest`](../app/src/e2eTest/java/de/regelsuche/e2e/LandingPageBrowserFlowTest.java)
for the acceptance tests.

## Proof workbench workflow

For a step-by-step proof workflow including the browser flow that
captures the screenshot below, see
[Proof Workbench](proof-workbench.md). A typical run from the UI:

1. Open the **Proof-Jobs** tab (revealed automatically after the first
   search).
2. Enter `Left = a + 0`, `Right = a`, click **Job einreichen**.
3. The job appears in the list with a polled status; opening _Artefakte_
   shows the persisted `proof.smt2` / `proof.lean` / `metadata.json`.

![Proof-Job-Panel](assets/screenshots/proof-job-panel.png)

## Benchmark quality dashboard

The **Benchmark** tab and
[`docs/benchmark-report.md`](benchmark-report.md) surface, per scenario:

- ✅ / ⚠️ / ❌ Ampelstatus
- `found`, `expectedResultMatched`, `proofStatus`
- `visitedStates`, `prunedStates`, `eGraphClasses`, `eGraphNodes`
- `saturationSavings`, `learnedRuleUsed`, `exportBundleValid`

`./gradlew benchmarkReport` regenerates the Markdown report and the
machine-readable
[`docs/assets/benchmark-summary.json`](assets/benchmark-summary.json);
CI uploads both as `benchmark-report` / `benchmark-summary` artefacts.
