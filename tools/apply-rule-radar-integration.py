#!/usr/bin/env python3
"""Apply the direct, strict and idempotent AST rule-radar integration edits."""
from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one anchor, found {count}: {old[:100]!r}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


SERVER = "app/src/main/java/de/regelsuche/web/WebWorkbenchServer.java"
replace_once(
    SERVER,
    "    private final PluginRuntimeConfig pluginRuntimeConfig;\n",
    "    private final PluginRuntimeConfig pluginRuntimeConfig;\n"
    "    private final de.regelsuche.radar.RuleRadarHttpHandler ruleRadarHandler;\n",
)
replace_once(
    SERVER,
    "        this.didacticStepValidator = new de.regelsuche.didactic.StudentStepValidator(new SymPyEquivalenceService());\n",
    "        this.didacticStepValidator = new de.regelsuche.didactic.StudentStepValidator(new SymPyEquivalenceService());\n"
    "        this.ruleRadarHandler = new de.regelsuche.radar.RuleRadarHttpHandler(\n"
    "            inventoryRepository, graphStore, this.pluginRuntimeConfig);\n",
)
replace_once(
    SERVER,
    "        secure(server.createContext(\"/api/inspect\", this::handleInspect));\n"
    "        secure(server.createContext(\"/\", this::handleStatic));\n",
    "        secure(server.createContext(\"/api/inspect\", this::handleInspect));\n"
    "        secure(server.createContext(\"/api/rule-radar\", ruleRadarHandler));\n"
    "        secure(server.createContext(\"/\", this::handleStatic));\n",
)
replace_once(
    SERVER,
    "    public void stop() {\n"
    "        if (server != null) {\n"
    "            server.stop(0);\n"
    "        }\n"
    "    }\n",
    "    public void stop() {\n"
    "        if (server != null) {\n"
    "            server.stop(0);\n"
    "        }\n"
    "        ruleRadarHandler.close();\n"
    "    }\n",
)

INDEX = "app/src/main/resources/web/index.html"
replace_once(
    INDEX,
    '    <link rel="stylesheet" href="style.css">\n',
    '    <link rel="stylesheet" href="style.css">\n'
    '    <link rel="stylesheet" href="rule-radar.css">\n',
)
replace_once(
    INDEX,
    '<button class="tab" data-tab="ruleIde">Rule-IDE</button>',
    '<button class="tab" data-tab="ruleIde">AST-Regelradar</button>',
)
replace_once(
    INDEX,
    "</body>",
    '    <script src="rule-radar.js"></script>\n</body>',
)

UI = "app/src/main/resources/web/rule-radar.js"
replace_once(
    UI,
    "            <td><code>${esc(candidate.ruleId)}</code></td>",
    "            <td><code>${esc(candidate.ruleId)}</code><br><span>${esc(candidate.displayName)}</span></td>",
)
replace_once(
    UI,
    "            state.undo.push(state.snapshot.expression);",
    "            state.undo.push({\n"
    "                expression: state.snapshot.expression,\n"
    "                candidateId: candidate.candidateId,\n"
    "                pathKey: candidate.pathKey,\n"
    "                ruleId: candidate.ruleId\n"
    "            });",
)
replace_once(
    UI,
    "        const expression = state.undo.pop();\n"
    "        if (expression == null) { return; }\n"
    "        state.selectedId = '';\n"
    "        inspect({expression});",
    "        const entry = state.undo.pop();\n"
    "        if (entry == null) { return; }\n"
    "        state.selectedId = '';\n"
    "        $('radarApplyStatus').textContent = `Rückgängig: ${entry.ruleId} an ${entry.pathKey}.`;\n"
    "        inspect({expression: entry.expression});",
)
replace_once(
    UI,
    "                <div id=\"radarCandidateDetail\" class=\"radar-candidate-detail\"><p class=\"hint\">Einen Punkt im AST oder eine Tabellenzeile auswählen.</p></div>\n"
    "                <div class=\"actions\">",
    "                <div id=\"radarCandidateDetail\" class=\"radar-candidate-detail\"><p class=\"hint\">Einen Punkt im AST oder eine Tabellenzeile auswählen.</p></div>\n"
    "                <div id=\"radarProjectedEdge\" class=\"radar-projected-edge\" aria-live=\"polite\"></div>\n"
    "                <div class=\"actions\">",
)
replace_once(
    UI,
    "        renderTree();\n"
    "        renderTable();\n"
    "        renderCandidateDetail();\n"
    "        $('radarUndo').disabled = state.undo.length === 0;",
    "        renderTree();\n"
    "        renderTable();\n"
    "        renderCandidateDetail();\n"
    "        renderProjectedEdge();\n"
    "        $('radarUndo').disabled = state.undo.length === 0;",
)
replace_once(
    UI,
    "    function bindCandidatePoints(root) {\n"
    "        root.querySelectorAll('[data-candidate-id]').forEach(element => {\n"
    "            const activate = () => selectCandidate(element.dataset.candidateId, true);\n"
    "            element.addEventListener('click', activate);\n"
    "            element.addEventListener('keydown', event => {\n"
    "                if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); activate(); }\n"
    "            });\n"
    "        });\n"
    "    }",
    "    function bindCandidatePoints(root) {\n"
    "        root.querySelectorAll('[data-candidate-id]').forEach(element => {\n"
    "            const preview = () => previewCandidate(element.dataset.candidateId);\n"
    "            const activate = () => selectCandidate(element.dataset.candidateId, true);\n"
    "            element.addEventListener('mouseenter', preview);\n"
    "            element.addEventListener('focus', preview);\n"
    "            element.addEventListener('click', activate);\n"
    "            element.addEventListener('keydown', event => {\n"
    "                if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); activate(); }\n"
    "            });\n"
    "        });\n"
    "    }",
)
replace_once(
    UI,
    "    function selectCandidate(candidateId, focusDetails = false) {\n"
    "        state.selectedId = candidateId;\n"
    "        $('radarAssumptionAck').checked = false;\n"
    "        renderTree(); renderTable(); renderCandidateDetail();\n"
    "        if (focusDetails) { $('radarCandidatePanel').scrollIntoView({behavior: 'smooth', block: 'nearest'}); }\n"
    "    }\n\n"
    "    function selectedCandidate() {",
    "    function previewCandidate(candidateId) {\n"
    "        state.selectedId = candidateId;\n"
    "        $('radarAssumptionAck').checked = false;\n"
    "        renderTable();\n"
    "        renderCandidateDetail();\n"
    "        renderProjectedEdge();\n"
    "    }\n\n"
    "    function selectCandidate(candidateId, focusDetails = false) {\n"
    "        state.selectedId = candidateId;\n"
    "        $('radarAssumptionAck').checked = false;\n"
    "        renderTree(); renderTable(); renderCandidateDetail(); renderProjectedEdge();\n"
    "        if (focusDetails) { $('radarCandidatePanel').scrollIntoView({behavior: 'smooth', block: 'nearest'}); }\n"
    "    }\n\n"
    "    function renderProjectedEdge() {\n"
    "        const host = $('radarProjectedEdge');\n"
    "        const candidate = selectedCandidate();\n"
    "        if (!candidate) { host.innerHTML = ''; return; }\n"
    "        host.innerHTML = `<strong>Projizierte Suchkante</strong><br>\n"
    "            <code>${esc(candidate.expressionBefore)}</code>\n"
    "            <span aria-hidden=\"true\">→</span>\n"
    "            <code>${esc(candidate.expressionAfter)}</code><br>\n"
    "            <span>${esc(candidate.ruleId)} @ ${esc(candidate.pathKey)} · ${esc(candidate.outcome)}</span>`;\n"
    "    }\n\n"
    "    function selectedCandidate() {",
)
replace_once(
    UI,
    "        const stateById = new Map((result.states || []).map(item => [item.stateId, item]));\n"
    "        const edges = result.edges || [];\n"
    "        const eventsByCandidate = new Map();\n"
    "        (result.events || []).forEach(event => eventsByCandidate.set(event.candidateId, event));\n"
    "        host.innerHTML = `<div class=\"radar-state-list\">${(result.states || []).map(item => `<div class=\"radar-search-state ${item.target ? 'target' : ''}\"><strong>${esc(item.stateId)}</strong><span>Tiefe ${item.depth}</span><code>${esc(item.expression)}</code></div>`).join('')}</div>\n"
    "            <div class=\"radar-edge-list\"><h4>Angewandte Suchkanten</h4>${edges.length ? edges.map(edge => `<button type=\"button\" class=\"radar-search-edge\" data-search-candidate=\"${esc(edge.candidateId)}\" data-from-expression=\"${esc(edge.fromExpression)}\">\n"
    "                <code>${esc(edge.fromStateId)}</code> → <code>${esc(edge.toStateId)}</code><br>\n"
    "                ${esc(edge.ruleId)} @ ${esc(edge.pathKey)} · ${esc(edge.origin)}\n"
    "            </button>`).join('') : '<p class=\"hint\">Keine neue Suchkante innerhalb der Budgets.</p>'}</div>\n"
    "            <details><summary>Alle Suchentscheidungen (${(result.events || []).length})</summary><ol class=\"radar-event-list\">${(result.events || []).map(event => `<li><code>${esc(event.outcome)}</code> · ${esc(event.ruleId)} @ ${esc(event.pathKey)} — ${esc(event.detail)}</li>`).join('')}</ol></details>`;\n"
    "        host.querySelectorAll('[data-search-candidate]').forEach(button => {\n"
    "            button.addEventListener('click', () => {\n"
    "                const outcomes = result.finalOutcomeByCandidateId || {};\n"
    "                state.selectedId = button.dataset.searchCandidate;\n"
    "                $('radarExpression').value = button.dataset.fromExpression;\n"
    "                inspect({\n"
    "                    expression: button.dataset.fromExpression,\n"
    "                    selectedCandidateId: button.dataset.searchCandidate,\n"
    "                    outcomeByCandidateId: outcomes\n"
    "                });\n"
    "            });\n"
    "        });",
    "        const edges = result.edges || [];\n"
    "        const outcomes = result.finalOutcomeByCandidateId || {};\n"
    "        host.innerHTML = `<div class=\"radar-state-list\">${(result.states || []).map(item => `<button type=\"button\" class=\"radar-search-state ${item.target ? 'target' : ''}\" data-search-state-expression=\"${esc(item.expression)}\"><strong>${esc(item.stateId)}</strong><span>Tiefe ${item.depth}</span><code>${esc(item.expression)}</code></button>`).join('')}</div>\n"
    "            <div class=\"radar-edge-list\"><h4>Suchkanten und kanonische Zusammenführungen</h4>${edges.length ? edges.map(edge => `<button type=\"button\" class=\"radar-search-edge outcome-${esc(edge.outcome.toLowerCase())}\" data-search-candidate=\"${esc(edge.candidateId)}\" data-from-expression=\"${esc(edge.fromExpression)}\">\n"
    "                <code>${esc(edge.fromStateId)}</code> → <code>${esc(edge.toStateId)}</code><br>\n"
    "                ${esc(edge.ruleId)} @ ${esc(edge.pathKey)} · ${esc(edge.origin)} · <code>${esc(edge.outcome)}</code>\n"
    "            </button>`).join('') : '<p class=\"hint\">Keine Suchkante innerhalb der Budgets.</p>'}</div>\n"
    "            <details><summary>Alle Suchentscheidungen (${(result.events || []).length})</summary><div class=\"radar-event-list\">${(result.events || []).map(event => `<button type=\"button\" data-search-event-candidate=\"${esc(event.candidateId)}\" data-search-event-expression=\"${esc(event.expression)}\"><code>${esc(event.outcome)}</code> · ${esc(event.ruleId)} @ ${esc(event.pathKey)} — ${esc(event.detail)}</button>`).join('')}</div></details>`;\n"
    "        host.querySelectorAll('[data-search-state-expression]').forEach(button => {\n"
    "            button.addEventListener('click', () => inspect({expression: button.dataset.searchStateExpression}));\n"
    "        });\n"
    "        host.querySelectorAll('[data-search-candidate]').forEach(button => {\n"
    "            button.addEventListener('click', () => inspect({\n"
    "                expression: button.dataset.fromExpression,\n"
    "                selectedCandidateId: button.dataset.searchCandidate,\n"
    "                outcomeByCandidateId: outcomes\n"
    "            }));\n"
    "        });\n"
    "        host.querySelectorAll('[data-search-event-candidate]').forEach(button => {\n"
    "            button.addEventListener('click', () => inspect({\n"
    "                expression: button.dataset.searchEventExpression,\n"
    "                selectedCandidateId: button.dataset.searchEventCandidate,\n"
    "                outcomeByCandidateId: outcomes\n"
    "            }));\n"
    "        });",
)

CSS = "app/src/main/resources/web/rule-radar.css"
replace_once(
    CSS,
    ".radar-search-state {\n"
    "    display: grid;\n"
    "    grid-template-columns: auto auto 1fr;\n"
    "    gap: .65rem;\n"
    "    padding: .55rem;\n"
    "    border: 1px solid var(--border, #d8dde6);\n"
    "    border-radius: .45rem;\n"
    "}",
    ".radar-search-state {\n"
    "    display: grid;\n"
    "    width: 100%;\n"
    "    grid-template-columns: auto auto 1fr;\n"
    "    gap: .65rem;\n"
    "    padding: .55rem;\n"
    "    border: 1px solid var(--border, #d8dde6);\n"
    "    border-radius: .45rem;\n"
    "    background: #fff;\n"
    "    text-align: left;\n"
    "}",
)
replace_once(
    CSS,
    ".radar-rewrite-preview,\n.radar-assumptions,\n.radar-macro-evidence {",
    ".radar-projected-edge {\n"
    "    margin-top: .8rem;\n"
    "    padding: .7rem;\n"
    "    border: 2px dashed #2563eb;\n"
    "    border-radius: .5rem;\n"
    "    background: #eff6ff;\n"
    "    overflow-wrap: anywhere;\n"
    "}\n\n"
    ".radar-rewrite-preview,\n.radar-assumptions,\n.radar-macro-evidence {",
)
replace_once(
    CSS,
    ".radar-event-list {\n"
    "    max-height: 260px;\n"
    "    overflow: auto;\n"
    "}",
    ".radar-event-list {\n"
    "    display: grid;\n"
    "    gap: .35rem;\n"
    "    max-height: 260px;\n"
    "    overflow: auto;\n"
    "}\n"
    ".radar-event-list button { text-align: left; }\n"
    ".radar-search-edge.outcome-pruned_duplicate,\n"
    ".radar-search-edge.outcome-pruned_known_better { border-style: dashed; opacity: .8; }",
)

SERVICE = "app/src/main/java/de/regelsuche/radar/AstRuleRadarService.java"
replace_once(
    SERVICE,
    "        boolean applicable = proofAccepted && qualityAccepted && goalAccepted && assumptionsSatisfied;\n"
    "        if (!applicable && !context.includeRejectedCandidates()) {\n"
    "            return Optional.empty();\n"
    "        }",
    "        if (!goalAccepted) {\n"
    "            return Optional.empty();\n"
    "        }\n"
    "        boolean applicable = proofAccepted && qualityAccepted && assumptionsSatisfied;\n"
    "        if (!applicable && !context.includeRejectedCandidates()) {\n"
    "            return Optional.empty();\n"
    "        }",
)

DOC = "docs/ast-rule-radar.md"
replace_once(
    DOC,
    "- Status: Architektur- und Visualisierungsentwurf\n"
    "- Geltungsbereich: Ausdrucks-AST, lokale Regelanwendungen, gelernte Makroregeln und globaler Suchgraph\n"
    "- Abgrenzung: Diese Seite beschreibt sowohl den bereits vorhandenen Kern als auch die noch fehlende einheitliche Visualisierungsschicht. Abweichungen sind ausdrücklich als Zielbild markiert.\n",
    "- Status: Implementiert (`regelsuche.ast-rule-radar/v1`)\n"
    "- Geltungsbereich: Ausdrucks-AST, lokale Regelanwendungen, gelernte Makroregeln und korrelierter begrenzter Suchgraph\n"
    "- Abgrenzung: Die Ansicht zeigt die tatsächlich vom Backend enumerierten Kandidaten im gewählten endlichen Kontext. Sie behauptet weder mathematische Vollständigkeit noch formalen Beweis.\n",
)
replace_once(
    DOC,
    "Der vorhandene Suchgraph und das vorgeschlagene AST-Regelradar konkurrieren daher",
    "Der vorhandene Suchgraph und das implementierte AST-Regelradar konkurrieren daher",
)
replace_once(
    DOC,
    "| AST-Knoten mit einem einheitlichen Regelkreis visualisieren | noch keine gemeinsame produktive Ansicht | fehlt |",
    "| AST-Knoten mit einem einheitlichen Regelkreis visualisieren | `AstRuleRadarService`, `/api/rule-radar/inspect`, `rule-radar.js` | implementiert |",
)
replace_once(
    DOC,
    "| Grund-, Plugin- und Makroregeln in einem positionsbezogenen Visualisierungs-DTO vereinigen | derzeit mehrere vorhandene Pfade und DTOs | teilweise |",
    "| Grund-, Knowledge-Pack-, Regeldatei-, Plugin- und Makroregeln in einem positionsbezogenen DTO vereinigen | `AstRuleRadar.ApplicableMove` mit stabiler Candidate-ID | implementiert |",
)
replace_once(
    DOC,
    "| Auswahl-, Pruning- und Ausführungsstatus jedes Punkts live darstellen | Suchmetriken vorhanden, aber nicht an ein AST-Regelradar gebunden | fehlt |",
    "| Auswahl-, Pruning- und Ausführungsstatus jedes Punkts live darstellen | `/api/rule-radar/search` und bidirektionale UI-Korrelation | implementiert |",
)
replace_once(DOC, "## Zielarchitektur der Visualisierung", "## Implementierte Architektur der Visualisierung")
replace_once(
    DOC,
    "Die Visualisierung sollte keine eigene Mathematik- oder Matchinglogik enthalten. Sie",
    "Die Visualisierung enthält keine eigene Mathematik- oder Matchinglogik. Sie",
)
replace_once(
    DOC,
    "Der neue Integrationspunkt sollte bestehende Komponenten adaptieren, nicht parallel\nneu implementieren.",
    "Der Integrationspunkt adaptiert bestehende Komponenten, statt Matching und Rewrite parallel\nneu zu implementieren.",
)
replace_once(
    DOC,
    "Eine geeignete erste produktive Ansicht besitzt folgende Eigenschaften:",
    "Die produktive Ansicht besitzt folgende Eigenschaften:",
)
replace_once(DOC, "## Empfohlene Umsetzungsschritte", "## Umgesetzte Schritte")
replace_once(
    DOC,
    "## Verwandte Dokumentation",
    "## API- und Evidence-Verträge\n\n"
    "- [`regelsuche.ast-rule-radar/v1`](schemas/regelsuche-ast-rule-radar-v1.schema.json) beschreibt AST, Kandidaten und Trunkierung.\n"
    "- [`regelsuche.ast-rule-radar-search/v1`](schemas/regelsuche-ast-rule-radar-search-v1.schema.json) beschreibt Zustände, Kanten und Kandidatenereignisse.\n"
    "- `RuleRadarBrowserFlowTest` erzeugt bei `-Pregelsuche.recordDocs=true` den Screenshot `docs/assets/screenshots/ast-rule-radar.png`; der statische Entwurf oben bleibt als erklärendes Architekturdiagramm erhalten.\n\n"
    "## Verwandte Dokumentation",
)

DOC_INDEX = "docs/README.md"
replace_once(
    DOC_INDEX,
    "- [AST-Regelradar](ast-rule-radar.md) — präzises Modell von AST-Positionen, lokal anwendbaren Grund- und Makroregeln, Subtree-Rewrite und globalem Suchgraph.\n",
    "- [AST-Regelradar](ast-rule-radar.md) — implementierte positionsgebundene Grund-, Erweiterungs- und Makroregel-Kandidaten, Subtree-Rewrite und korrelierter Suchgraph.\n"
    "- [AST-Regelradar-Schema v1](schemas/regelsuche-ast-rule-radar-v1.schema.json) und [Search-Schema v1](schemas/regelsuche-ast-rule-radar-search-v1.schema.json).\n",
)

print("Rule-radar integration anchors applied successfully.")
