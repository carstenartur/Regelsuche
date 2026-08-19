#!/usr/bin/env python3
"""Remove unenforced target-free v1 ceilings and rebind frozen resources."""

from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = (
    ROOT
    / "regelsuche-discovery/src/main/resources/de/regelsuche/discovery/representation"
)
FORMATION_PATH = PACKAGE / "target-free-representation-formation-v1.json"
PREREGISTRATION_PATH = (
    PACKAGE / "target-free-representation-preregistration-v1.json"
)
JAVA_PATH = ROOT / (
    "regelsuche-discovery/src/main/java/de/regelsuche/discovery/representation/"
    "TargetFreeRepresentationEvaluationPlan.java"
)
DOCS_PATH = ROOT / "docs/target-free-representation-evaluation.md"


def sha256(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def require_subn(
    pattern: str,
    replacement: str,
    text: str,
    label: str,
    *,
    flags: int = 0,
) -> str:
    updated, count = re.subn(pattern, replacement, text, flags=flags)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return updated


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one occurrence, found {count}")
    return text.replace(old, new)


def main() -> None:
    formation = json.loads(FORMATION_PATH.read_text(encoding="utf-8"))
    for case in formation["cases"]:
        budget = case["budget"]
        for key in ("maxEngineCalls", "maxAdmittedPrimitiveSteps"):
            if key not in budget:
                raise SystemExit(f"missing {key} in {case['id']}")
            budget.pop(key)
    FORMATION_PATH.write_text(
        json.dumps(formation, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    formation_bytes = FORMATION_PATH.read_bytes()
    formation_hash = sha256(formation_bytes)

    preregistration = json.loads(
        PREREGISTRATION_PATH.read_text(encoding="utf-8")
    )
    preregistration["formationByteLength"] = len(formation_bytes)
    preregistration["formationSha256"] = formation_hash
    PREREGISTRATION_PATH.write_text(
        json.dumps(preregistration, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    preregistration_bytes = PREREGISTRATION_PATH.read_bytes()
    preregistration_hash = sha256(preregistration_bytes)

    java = JAVA_PATH.read_text(encoding="utf-8")
    java = require_subn(
        r"public static final long PREREGISTRATION_BYTE_LENGTH = \d+L;",
        f"public static final long PREREGISTRATION_BYTE_LENGTH = "
        f"{len(preregistration_bytes)}L;",
        java,
        "preregistration byte-length constant",
    )
    java = require_subn(
        r"public static final String PREREGISTRATION_SHA256 =\n"
        r"\s*\"sha256:[0-9a-f]{64}\";",
        "public static final String PREREGISTRATION_SHA256 =\n"
        f"        \"{preregistration_hash}\";",
        java,
        "preregistration hash constant",
    )
    java = require_subn(
        r"(\n\s*int maxExpandingSteps,\n\s*int beamWidth),"
        r"\n\s*int maxEngineCalls,\n\s*int maxAdmittedPrimitiveSteps",
        r"\1",
        java,
        "WorkBudget signature",
    )
    java = require_subn(
        r"\n\s*\|\| maxEngineCalls < 1"
        r"\n\s*\|\| maxEngineCalls > maxExploredStates"
        r"\n\s*\|\| maxAdmittedPrimitiveSteps < 1",
        "",
        java,
        "WorkBudget validation",
    )
    JAVA_PATH.write_text(java, encoding="utf-8")

    docs = DOCS_PATH.read_text(encoding="utf-8")
    docs = replace_once(
        docs,
        "- sämtliche endlichen Arbeitsbudgets einschließlich Engine-Aufrufen und\n"
        "  zugelassenen primitiven Schritten;",
        "- sämtliche endlichen, von v1 tatsächlich durchgesetzten Suchbudgets;",
        "information-boundary budget wording",
    )
    docs = replace_once(
        docs,
        "- zusätzliche, explizite Budgets für `significantImprovementThreshold`,\n"
        "  `maxExpandingSteps`, `beamWidth`, Engine-Aufrufe und zugelassene primitive\n"
        "  Schritte;",
        "- zusätzliche, explizite Budgets für `significantImprovementThreshold`,\n"
        "  `maxExpandingSteps` und `beamWidth`;",
        "work-budget list",
    )
    docs = replace_once(
        docs,
        "Gleiche konfigurierte Obergrenzen dürfen später nicht als gleiche tatsächlich\n"
        "verbrauchte Arbeit ausgegeben werden. Der Ausführungsbericht muss die realen\n"
        "Engine-Aufrufe, zugelassenen primitiven Schritte, erzeugten Transitionen,\n"
        "explorierten Zustände und erhaltenen Kandidaten je Zeile ausweisen.",
        "Engine-Aufrufe und primitive Schritte bleiben in v1 beobachtete Arbeitsledger,\n"
        "aber keine vorab als durchgesetzt behaupteten Obergrenzen. Der spätere\n"
        "Ausführungsbericht muss sie neben erzeugten Transitionen, explorierten\n"
        "Zuständen und erhaltenen Kandidaten je Zeile ausweisen.",
        "observed-work wording",
    )
    docs = require_subn(
        r"Formation:\s+\d+ Bytes\n\s+sha256:[0-9a-f]{64}",
        f"Formation:      {len(formation_bytes)} Bytes\n"
        f"                {formation_hash}",
        docs,
        "formation documentation binding",
    )
    docs = require_subn(
        r"Preregistrierung:\n\s+\d+ Bytes\n\s+sha256:[0-9a-f]{64}",
        "Preregistrierung:\n"
        f"                {len(preregistration_bytes)} Bytes\n"
        f"                {preregistration_hash}",
        docs,
        "preregistration documentation binding",
    )
    DOCS_PATH.write_text(docs, encoding="utf-8")

    print(f"formationBytes={len(formation_bytes)}")
    print(f"formationHash={formation_hash}")
    print(f"preregistrationBytes={len(preregistration_bytes)}")
    print(f"preregistrationHash={preregistration_hash}")


if __name__ == "__main__":
    main()
