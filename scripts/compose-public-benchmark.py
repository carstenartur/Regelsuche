#!/usr/bin/env python3
"""Recompute the v2 publication from frozen public protocols and unabridged result bytes."""
import argparse
from collections import Counter
import gzip
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PUBLIC = ROOT / "research/benchmarks/comparative-v2"
POLICY = "NO_UNIVERSAL_SCORE_TRACK_SCOPED_CLAIMS_ONLY"


def require(value, message):
    if not value:
        raise ValueError(message)


def canonical(value):
    return (json.dumps(value, indent=2, sort_keys=True, ensure_ascii=False) + "\n").encode()


def counts(values):
    return dict(sorted(Counter(values).items()))


def select(record, *keys):
    return {key: record[key] for key in keys}


def legacy_track(data, track):
    return {
        "track": track, "source": "legacy",
        "configurations": [row["id"] for row in data["configurations"] if row["track"] == track],
        "cases": sum(row["track"] == track for row in data["cases"]),
        "correctness": counts(str(row["correct"]) for row in data["results"] if row["track"] == track),
        "informationParity": [row["id"] for row in data["parityManifests"] if row["track"] == track],
        "claims": [row["id"] for row in data["claims"] if row["track"] == track],
        "coverageGaps": [row for row in data["coverageGaps"] if row["track"] == track],
    }


def hidden(data):
    return {"informationParity": data["informationParity"],
            "profiles": {k: select(v, "summary", "failureTaxonomy") for k, v in data["profiles"].items()}}


def grammar(data, inventory, form):
    if "frozenArms" in data:
        runs = [(arm["arm"], result["tasks"]) for arm, result in
                zip(data["frozenArms"], data["evaluations"])]
        require(len(runs) == len(data["frozenArms"]) == len(data["evaluations"]), "rational arm coverage")
    else:
        runs = [(campaign["arm"], campaign["evaluations"]) for campaign in data["campaigns"]]
    arms = {}
    for name, rows in runs:
        arms.setdefault(name, []).extend(rows)
    audit = inventory["forms"][form]
    return {
        "campaignArmSlots": len(runs),
        "sharedCaseIds": sorted({row["caseId"] for _, rows in runs for row in rows}),
        "supportAndWork": "Frozen formation records and per-task raw rows retain proposal, duplicate, support and evaluation work at each form’s own unit.",
        "proofAndFalsification": "Finite failures and counterexamples have their recorded scopes. Empirical agreement, internal certificates and formal proof have separate provenance.",
        "projectInventory": select(audit, "candidateOccurrences", "distinctSemanticKeys", "distinctMatchCounts",
                                   "occurrenceMatchCounts", "zeroOutputSlots"),
        "taskOutcomesByArmAndSplit": {
            name: {split: counts(row["outcome"] for row in rows if row["split"] == split)
                   for split in sorted({row["split"] for row in rows})} for name, rows in arms.items()},
    }


def transfer(data):
    summaries = []
    for bridge in data["bridges"]:
        families = []
        for row in bridge["familyRuns"]:
            tasks = {}
            for task in ("positive", "negative"):
                paired = row[task]
                tasks[task] = {arm: select(paired[arm], "status", "assumptions", "work", "bridgeApplications")
                               for arm in ("without", "with")}
                expansions = paired["with"]["expansions"]
                tasks[task]["bridgeInvocations"] = sum(e["bridgeInvoked"] for e in expansions)
                tasks[task]["bridgeCandidates"] = sum(e["bridgeCandidates"] for e in expansions)
            families.append({**select(row, "family", "role"), "tasks": tasks,
                             "validation": row["validation"]["status"], "formalProof": row["proof"]["formalProofStatus"]})
        summaries.append({"formationHash": bridge["formationHash"], "families": families,
                          "positiveTaskAblation": bridge["positiveTaskAblation"]["status"],
                          "invalidTargetReached": bridge["invalidTargetReached"]})
    return summaries


def rewriting(data):
    arms = {}
    for case in data["cases"]:
        for row in case["outcomes"]:
            arms.setdefault(row["system"], []).append(row)
    return {"fragment": data["fragment"], "ruleIds": data["ruleIds"],
            "arms": {arm: {key: counts(r[key] for r in rows) for key in ("translation", "verdict", "proofStatus")}
                     for arm, rows in arms.items()}}


def stages(data):
    return [{**select(lane, "panel", "arm", "configuredTickets", "remainingTickets"),
             "lineages": len(lane["candidates"]), "distinctCandidateIds": len({r["id"] for r in lane["candidates"]}),
             "outcomes": counts(r["status"] for r in lane["candidates"]),
             "resourceLedger": lane["resourceLedger"]} for lane in data["lanes"]]


def components(data):
    arms = {}
    for name, arm in data["arms"].items():
        challenges = arm["challengesInFrozenOrder"]
        arms[name] = {**select(arm, "inputSha256", "unusedCandidateSlots", "selectionAtSlotCeiling"),
                      "scheduled": len(arm["frozenSchedule"]),
                      "terminalChecks": counts(r["terminalRecheck"]["status"] for r in challenges),
                      "challengeOutcomes": counts(c["result"]["status"] for r in challenges for c in r["challenges"]),
                      "historicalControl": arm["separateHistoricalChallenge"]["terminalRecheck"]["status"]}
    return arms


def compose(raw, sources):
    data = {key: json.loads(b) for key, b in raw.items() if not sources[key]["path"].endswith(".md")}
    a = data["legacy"]
    require(a["scorePolicy"] == POLICY, "legacy score policy")
    tracks = {key: legacy_track(a, track) for key, track in
              [("A1", "TARGET_DIRECTED_SEARCH"), ("A2", "SIMPLIFICATION_COMPETITION"), ("E1", "EQUALITY_VALIDATION")]}
    tracks["B"] = {"regimeRevisions": {"hidden-rule-current": "9ddd37acd174edcfdfe440e72f726bf7392ed34c",
                                      "hidden-rule-historical": "03b58d1dfdd9c5076622f1f9bf35dd9c618bf897"},
                   "regimes": {k: hidden(data[k]) for k in ("hidden-rule-current", "hidden-rule-historical")},
                   "scope": "The same twenty opaque tasks under exact and historical validation; report each separately."}
    tracks["C"] = {
        "formation": "Only public TRAIN prefixes or start expressions and authorized assumptions. Each form uses its frozen grammar, resource accounts, independent evaluation and project-inventory audit.",
        "coverage": "Four independent campaigns per form with enumerative and seeded random controls. Shared cases across arms and forms are repeated observations. The finite retrospective project inventory tests no external novelty.",
        "forms": {key: grammar(data[key], data["audit-result"], form) for key, form in
                  [("finite", "finiteDifferences"), ("recurrence", "recurrence"), ("macro", "macros"), ("rational", "rational")]}
    }
    tracks["D"] = {"informationParity": data["transfer-plan"]["formation"], "coverage": data["transfer-plan"]["scope"],
                   "bridges": transfer(data["transfer-transfer-result"])}
    tracks["E2"] = {**rewriting(data["rewriting"]), "informationParity": select(data["rewriting"], "targetVisible", "familyLabelsVisible", "sideConditionPolicy", "pathBudget", "graphBudget")}
    tracks["F"] = {"terminalAllocation": {"coverageGaps": data["terminal"]["coverageGaps"], "arms": {
        r["policy"]: {**select(r, "stopReason", "status"), "nextPlanDecisions": len(r["nextPlan"]["decisions"])}
        for r in data["terminal"]["lanes"]}}, "sevenStageAllocation": stages(data["stages-measured"]),
        "sharedBudget": data["stages-plan"]["unit"], "scope": data["stages-plan"]["stopping"]}
    component = {key: components(data[key]) for key in ("components", "components-exact")}
    component["independentFanInControl"] = {arm: [{"accepted": len(r["mining"]["conjectures"]), "rejected": len(r["mining"]["rejectedClusters"])} for r in data["fan-in"][arm]] for arm in ("production", "noFanIn")}
    return {"schema": "regelsuche.public-comparative-benchmark/v2", "scorePolicy": POLICY, "tracks": tracks,
            "discoveryComponents": component, "sources": sources}


def read_source(path):
    b = path.read_bytes()
    return gzip.decompress(b) if path.suffix == ".gz" else b


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true", help="regenerate the checked-in canonical publication")
    parser.add_argument("--bundle", type=Path, help="check the detached published evidence and legacy aggregate")
    args = parser.parse_args()
    registry = json.loads((PUBLIC / "sources.json").read_bytes())
    sources = {}
    raw = {}
    for key, item in registry.items():
        extension = ".md" if item["path"].endswith(".md") else ".json"
        source = {**item, "attachment": "evidence/" + item["sha256"] + extension}
        data = ((args.bundle / "v2" / source["attachment"]).read_bytes() if args.bundle
                else read_source(ROOT / source["path"]))
        require(hashlib.sha256(data).hexdigest() == source["sha256"] and len(data) == source["bytes"], key)
        sources[key], raw[key] = source, data
    rendered = canonical(compose(raw, sources))
    if args.write:
        require(not args.bundle, "cannot update a detached publication")
        (PUBLIC / "report.json").write_bytes(rendered)
    require((PUBLIC / "report.json").read_bytes() == rendered, "checked-in public composition drift")
    if args.bundle:
        require((args.bundle / "v2/report.json").read_bytes() == rendered, "detached composition drift")
        require((args.bundle / "report.json").read_bytes() == raw["legacy"], "legacy run differs")
        files = {p.relative_to(args.bundle / "v2").as_posix() for p in (args.bundle / "v2").rglob("*") if p.is_file()}
        require(files == {"report.json", *(s["attachment"] for s in sources.values())}, "public evidence inventory differs")
    print(f"public comparative v2: {len(sources)} artifacts; SHA-256 {hashlib.sha256(rendered).hexdigest()}")


if __name__ == "__main__":
    main()
