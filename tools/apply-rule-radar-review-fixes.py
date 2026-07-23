#!/usr/bin/env python3
"""Apply review fixes for rejected AST rule-radar candidates.

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

print("AST rule-radar review fixes applied successfully.")
