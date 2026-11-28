"""Check the eight rotated selection replays and write their compact evidence to stdout."""
import hashlib
import json
import sys
from pathlib import Path

ARMS = ["BASE", "DROP", "LOCAL", "INDEXED", "SELECTED", "ROUTED", "SELECTED_DROP", "ROUTED_DROP"]
HERE = Path(__file__).resolve().parent
HISTORY = HERE.parent / "evidence"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def source_manifest():
    root = HERE.parents[3]
    modules = ["core", "egraph", "learning", "math-algorithms", "search", "solver-ir", "validation"]
    files = [p for module in modules for p in (root / f"regelsuche-{module}/src/main/java").rglob("*.java")]
    study = root / "regelsuche-learning/src/test/java/de/regelsuche/evolution"
    files.extend(study / f"{name}.java" for name in ["LocalTraceFamilyCases", "LocalTraceFamilyStudy", "LocalTraceFamilyStudyTest"])
    files.extend(root / f"regelsuche-{module}/pom.xml" for module in modules)
    files.append(root / "pom.xml")
    entries = [p.relative_to(root).as_posix().encode() + b"\0" + digest(p.read_bytes()).encode() + b"\n"
               for p in sorted(files)]
    return {"modules": modules, "files": len(files), "sha256": digest(b"".join(entries))}


def row_digest(rows):
    return digest(json.dumps(rows, sort_keys=True, separators=(",", ":")).encode())


def learned_steps(row):
    return [step for step in row["steps"] if step["kind"] == "LEARNED"]


def semantic_path(row):
    # Plan versions may change the provider descriptor. Keep its exact checked schema identity.
    return [(step["schemaId"] or step["rule"], step["source"], step["target"]) for step in row["steps"]]


def compare_history(arm, rows):
    previous = [row for row in map(json.loads, (HISTORY / "final.jsonl").read_text().splitlines())
                if row["arm"] == arm]
    require(len(rows) == len(previous), f"historical case count differs: {arm}")
    for row, old in zip(rows, previous):
        for key in row.keys() - {"steps"}:
            require(row[key] == old[key], f"historical {key} differs on {arm}/{row['family']}/{row['n']}")
        require(len(row["steps"]) == len(old["steps"]), f"historical path length differs on {arm}")
        for new_step, old_step in zip(row["steps"], old["steps"]):
            keys = ["kind", "source", "target", "verification"]
            if old_step["kind"] == "PRIMITIVE":
                keys.append("rule")
            require(all(new_step[k] == old_step[k] for k in keys), f"historical step differs on {arm}")


def totals(observation):
    rows = observation["rows"]
    query = sum(r["queryWork"] for r in rows)
    return {
        "acquisition": observation["acquisition"], "loadAndPrepare": observation["restoration"],
        "query": query, "lifecycle": observation["acquisition"] + observation["restoration"] + query,
        "separateCoefficientAudit": sum(r["externalAudit"] for r in rows),
        "solved": sum(r["solved"] for r in rows),
        "casesWithLearnedStep": sum(bool(learned_steps(r)) for r in rows),
        "learnedSteps": sum(len(learned_steps(r)) for r in rows), "rowsSha256": row_digest(rows),
    }


def summarize(directory, rows_output=None):
    history = json.loads((HISTORY / "follow-up.json").read_text())
    freeze = "sha256:" + history["freezeSha256"]
    runs, observations = [], {}
    for index in range(len(ARMS)):
        raw = (directory / f"selection-{index}.json").read_bytes()
        data = json.loads(raw)
        order = ARMS[index:] + ARMS[:index]
        require(data["rotation"] == index and data["freezeHash"] == freeze, "wrong rotation or freeze")
        require([a["arm"] for a in data["arms"]] == order, "wrong arm order")
        times = {}
        for arm in data["arms"]:
            name = arm["arm"]
            times[name] = [arm.pop("wallNanos"), arm.pop("threadCpuNanos")]
            require(arm == observations.setdefault(name, arm), f"rotations disagree on {name}")
        runs.append({"rotation": index, "rawSha256": digest(raw), "elapsedAndCpuNanos": times})

    all_cases = [(r["family"], r["n"], r["source"], r["maximumNodes"]) for r in observations["LOCAL"]["rows"]]
    require(len(set(all_cases)) == len(all_cases) == 24, "incomplete frozen panel")
    for name, arm in observations.items():
        require([(r["family"], r["n"], r["source"], r["maximumNodes"]) for r in arm["rows"]] == all_cases,
                f"cases differ: {name}")
        require(all(r["accountingComplete"] for r in arm["rows"]), f"incomplete work: {name}")
        if name in ["BASE", "DROP", "LOCAL"]:
            compare_history(name, arm["rows"])
            record = history["outcomes"][name]
            require(arm["acquisition"] == record["acquisition"] and arm["restoration"] == record["restoration"],
                    f"historical lifecycle differs: {name}")

    cases = []
    for index, (family, n, _, _) in enumerate(all_cases):
        rows = [observations[name]["rows"][index] for name in ARMS]
        learned_arms = {name: rows[ARMS.index(name)] for name in ("SELECTED", "ROUTED")}
        cases.append({
            "family": family, "n": n, "queryInArmOrder": [r["queryWork"] for r in rows],
            "solvedInArmOrder": [r["solved"] for r in rows],
            "schemaIds": {name: [s["schemaId"] for s in learned_steps(row)] for name, row in learned_arms.items()},
            "samePathAsLocal": {name: semantic_path(row) == semantic_path(rows[2]) for name, row in learned_arms.items()},
            "sameOutcomeAsLocal": {name: all(row[key] == rows[2][key] for key in ["solved", "outcome", "output", "outputScore"])
                                   for name, row in learned_arms.items()},
        })
    lines = [json.dumps({"arm": name, **row}, ensure_ascii=False, separators=(",", ":"))
             for name in ARMS for row in observations[name]["rows"]]
    normalized = ("\n".join(lines) + "\n").encode()
    if rows_output is not None:
        rows_output.write_bytes(normalized)

    return {"classification": "RETROSPECTIVE_FIXED_PANEL", "rowsJsonlSha256": digest(normalized), "freezeHash": freeze, "sourceManifest": source_manifest(),
            "armOrder": ARMS,
            "totals": {name: totals(observations[name]) for name in ARMS}, "cases": cases, "runs": runs}


if __name__ == "__main__":
    if len(sys.argv) not in (2, 3):
        sys.exit("usage: python3 summarize.py DIRECTORY_WITH_EIGHT_ROTATIONS [ROWS.jsonl]")
    rows_output = Path(sys.argv[2]) if len(sys.argv) == 3 else None
    print(json.dumps(summarize(Path(sys.argv[1]), rows_output), indent=2))
