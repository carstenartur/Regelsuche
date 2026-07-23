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
