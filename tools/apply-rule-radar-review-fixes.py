#!/usr/bin/env python3
"""Apply review and integration fixes for the AST rule radar.

The script is strict and idempotent because it is executed once by the temporary
fixed-head verifier before the final source-only PR is merged.
"""
from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one anchor, found {count}: {old[:120]!r}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


SERVICE = "app/src/main/java/de/regelsuche/radar/AstRuleRadarService.java"

replace_once(
    SERVICE,
    "        boolean assumptionsSatisfied = context.assumptions().containsAll(assumptions);\n"
    "        CandidateOutcome outcome = assumptionsSatisfied\n"
    "            ? context.outcomeByCandidateId().getOrDefault(\"\", CandidateOutcome.AVAILABLE)\n"
    "            : CandidateOutcome.REJECTED_ASSUMPTION;\n"
    "        List<Binding> bindings = bindings(rule, subtree);",
    "        boolean assumptionsSatisfied = context.assumptions().containsAll(assumptions);\n"
    "        if (!assumptionsSatisfied && !context.includeRejectedCandidates()) {\n"
    "            return Optional.empty();\n"
    "        }\n"
    "        CandidateOutcome outcome = assumptionsSatisfied\n"
    "            ? CandidateOutcome.AVAILABLE\n"
    "            : CandidateOutcome.REJECTED_ASSUMPTION;\n"
    "        List<Binding> bindings = bindings(rule, subtree);",
)

replace_once(
    SERVICE,
    "                if (match.expressionAfter() == null || match.expressionAfter().isBlank()\n"
    "                    || match.subtreeAfter() == null || match.subtreeAfter().isBlank()) {\n"
    "                    continue;\n"
    "                }\n"
    "                List<Binding> bindings = match.bindings().stream()",
    "                if (match.expressionAfter() == null || match.expressionAfter().isBlank()\n"
    "                    || match.subtreeAfter() == null || match.subtreeAfter().isBlank()) {\n"
    "                    continue;\n"
    "                }\n"
    "                if (!match.applicable() && !context.includeRejectedCandidates()) {\n"
    "                    continue;\n"
    "                }\n"
    "                List<Binding> bindings = match.bindings().stream()",
)

SERVER = "app/src/main/java/de/regelsuche/web/WebWorkbenchServer.java"
replace_once(
    SERVER,
    "        } else if (path.startsWith(\"/vendor/\") || path.equals(\"/app.js\") || path.equals(\"/style.css\")) {\n"
    "            sendStaticResource(exchange, \"/web\" + path, mimeFor(path));",
    "        } else if (path.startsWith(\"/vendor/\")\n"
    "            || path.equals(\"/app.js\")\n"
    "            || path.equals(\"/style.css\")\n"
    "            || path.equals(\"/rule-radar.js\")\n"
    "            || path.equals(\"/rule-radar.css\")) {\n"
    "            sendStaticResource(exchange, \"/web\" + path, mimeFor(path));",
)

UI = "app/src/main/resources/web/rule-radar.js"
replace_once(
    UI,
    "        undo: [],\n"
    "        zoom: 1,\n"
    "        search: null\n"
    "    };",
    "        undo: [],\n"
    "        zoom: 1,\n"
    "        search: null,\n"
    "        inspectionSequence: 0\n"
    "    };",
)
replace_once(
    UI,
    "    function inspect(options = {}) {\n"
    "        const expression = (options.expression != null ? options.expression : $('radarExpression').value).trim();\n"
    "        if (!expression) { setStatus('Bitte einen Ausdruck eingeben.', true); return Promise.resolve(); }\n"
    "        setStatus('AST und lokale Regelanwendungen werden im Backend berechnet …');\n"
    "        return post('/api/rule-radar/inspect', {\n"
    "            expression,\n"
    "            context: context(options)\n"
    "        }).then(snapshot => {\n"
    "            state.snapshot = snapshot;",
    "    function inspect(options = {}) {\n"
    "        const expression = (options.expression != null ? options.expression : $('radarExpression').value).trim();\n"
    "        if (!expression) { setStatus('Bitte einen Ausdruck eingeben.', true); return Promise.resolve(); }\n"
    "        const requestSequence = ++state.inspectionSequence;\n"
    "        setStatus('AST und lokale Regelanwendungen werden im Backend berechnet …');\n"
    "        return post('/api/rule-radar/inspect', {\n"
    "            expression,\n"
    "            context: context(options)\n"
    "        }).then(snapshot => {\n"
    "            if (requestSequence !== state.inspectionSequence) { return; }\n"
    "            state.snapshot = snapshot;",
)
replace_once(
    UI,
    "        }).catch(error => setStatus('Fehler: ' + error.message, true));\n"
    "    }",
    "        }).catch(error => {\n"
    "            if (requestSequence === state.inspectionSequence) {\n"
    "                setStatus('Fehler: ' + error.message, true);\n"
    "            }\n"
    "        });\n"
    "    }",
)

print("AST rule-radar review, asset-serving and stale-response fixes applied successfully.")
