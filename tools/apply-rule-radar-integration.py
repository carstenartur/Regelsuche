#!/usr/bin/env python3
"""Apply strict, idempotent AST rule-radar integration anchors."""
from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one anchor, found {count}: {old[:80]!r}")
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
    "| AST-Knoten mit einem einheitlichen Regelkreis visualisieren | noch keine gemeinsame produktive Ansicht | fehlt |",
    "| AST-Knoten mit einem einheitlichen Regelkreis visualisieren | `AstRuleRadarService`, `/api/rule-radar/inspect`, `rule-radar.js` | implementiert |",
)
replace_once(
    DOC,
    "| Grund-, Plugin- und Makroregeln in einem positionsbezogenen DTO vereinigen | noch kein gemeinsamer Vertrag | fehlt |",
    "| Grund-, Knowledge-Pack-, Regeldatei-, Plugin- und Makroregeln in einem positionsbezogenen DTO vereinigen | `AstRuleRadar.ApplicableMove` mit stabiler Candidate-ID | implementiert |",
)
replace_once(
    DOC,
    "| angewandte Suchkante zurück auf AST-Position und Punkt beziehen | Teilinformationen existieren, aber kein einheitlicher Korrelationsschlüssel | fehlt |",
    "| angewandte Suchkante zurück auf AST-Position und Punkt beziehen | `/api/rule-radar/search` referenziert `candidateId` und `pathKey`; UI navigiert bidirektional | implementiert |",
)

print("Rule-radar integration anchors applied successfully.")
