"""Check the public frozen matrix and bind all evidence from the three v2 runs."""
import argparse
import hashlib
import json
import re
from pathlib import Path

PRODUCER = "6dce1fae5bf84cbb588129993830c41be45a8bda"
TREE = "3098d9e6b330aa4ef700fa47a7562dc9ce9fa31c"
PLAN = Path("docs/research/finite-search-knowledge-v1/plan.json")
PLAN_SHA = "b7b96c04ff94278046f076c01fd03b9ecadea8d25d3592e05992dbc39c0c2589"
CORPUS = Path("regelsuche-experiments/src/main/resources/de/regelsuche/benchmark/historical-rediscovery-corpus.json")
MANIFEST_DIGEST = "sha256:3c35c595580ae3342eb34e5832e8e516ded8e767efa4a5fe61b8644d44847b8e"
SCHEMA = "regelsuche.finite-search-knowledge-comparison/v2"
WORK = "NATIVE_CALLS_ONLY_FULL_MECHANICAL_WORK_UNMATCHED"
COSTS = ("acquisitionCalls", "restorationCalls", "searchCalls", "replayCalls", "totalNativeCalls",
         "generated", "lookupOperations", "cacheHits", "replacements")
ENVIRONMENTS = ("host-1", "host-2", "container")
SEARCH_TERMINALS = {"REACHED", "NO_PATH_WITHIN_DEPTH", "WORK_EXHAUSTED"}
GUIDED_TERMINALS = {"REACHED:COMPLETE", "NO_PATH_WITHIN_DEPTH:COMPLETE",
                    "WORK_EXHAUSTED:LOOKUP_LIMIT_REACHED", "WORK_EXHAUSTED:PRIMITIVE_ENGINE_CALL_LIMIT_REACHED"}
# Every query uses the originating seed and the same empty assumption context.
TABLE_TERMINALS = {"REACHED", "NOT_IN_TABLE", "NO_PATH_IN_TABLE_WITHIN_QUERY_DEPTH", "WORK_EXHAUSTED"}



def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def binding(path):
    data = path.read_bytes()
    return {"sha256": digest(data), "bytes": len(data)}


def expected_matrix(root):
    plan_bytes = (root / PLAN).read_bytes()
    require(digest(plan_bytes) == PLAN_SHA, "published plan checksum")
    plan = json.loads(plan_bytes)
    corpus_bytes = (root / CORPUS).read_bytes()
    require(digest(corpus_bytes) == plan["historicalCorpusSha256"], "historical corpus checksum")
    depths = {c["id"]: c["oracleMaxDepth"] for c in json.loads(corpus_bytes)["cases"] if c["targetRelation"] == "SYNTAX_EXACT"}
    require(len(depths) == 13, "original case identities")
    depths.update({f"independent-factor-sites-{n}": n for n in plan["additionalCases"]["factorSites"]})
    require(len(depths) == 16 and len(set(plan["arms"])) == 10, "frozen dimensions")
    return {(case, arm): depth for case, depth in depths.items() for arm in plan["arms"]}


def by_identity(items, expected):
    indexed = {(item["caseId"], item["arm"]): item for item in items}
    require(len(items) == len(indexed) and set(indexed) == set(expected), "missing, duplicate or foreign case/arm")
    return indexed


def validate_terminal(row, case_depth):
    arm = row["arm"]
    if arm.startswith("GUIDED_CACHE_"):
        allowed = GUIDED_TERMINALS
    elif arm == "TABLE_COLD":
        allowed = TABLE_TERMINALS | {"ACQUISITION_INCONCLUSIVE"}
    elif arm in ("TABLE_RESTORED", "TABLE_WARM"):
        allowed = TABLE_TERMINALS | {"NO_COMPLETE_SOURCE_TABLE"}
    else:
        allowed = SEARCH_TERMINALS
    require(row["status"] in allowed, "invalid terminal for " + arm)
    # The oracle searches for an unreachable fresh symbol. Its retained states supply the real reference.
    require(row["referenceStatus"] in {"COMPLETE_BOUNDED_CLOSURE", "BUDGET_INCONCLUSIVE", "TECHNICAL_FAILURE"},
            "invalid closure status")
    depth, reference = row["pathLength"], row["referenceMinimumDepth"]
    require(type(depth) is int and type(reference) is int and -1 <= depth <= case_depth
            and -1 <= reference <= case_depth, "witness/reference outside frozen case depth")
    reached = row["status"] in {"REACHED", "REACHED:COMPLETE"}
    require(reached == (depth >= 0), "witness depth/status inconsistency")
    if reached and row["referenceStatus"] == "COMPLETE_BOUNDED_CLOSURE":
        require(0 <= reference <= depth, "witness/reference disagreement")
    if row["status"] in {"NO_PATH_WITHIN_DEPTH", "NO_PATH_WITHIN_DEPTH:COMPLETE", "NOT_IN_TABLE",
                         "NO_PATH_IN_TABLE_WITHIN_QUERY_DEPTH"}:
        require(reference == -1, "negative path claim contradicts a reached reference")


def validate_work(row, limit):
    require(all(type(row[k]) is int and row[k] >= 0 for k in COSTS), "mechanical work fields")
    require(sum(row[k] for k in COSTS[:4]) == row["totalNativeCalls"] <= limit, "native work reconciliation")
    arm, status = row["arm"], row["status"]
    if status == "NO_COMPLETE_SOURCE_TABLE":
        allowed = set()
    elif status == "ACQUISITION_INCONCLUSIVE":
        allowed = {"acquisitionCalls", "generated"}
    elif arm.startswith("TABLE_"):
        allowed = {"replayCalls", "generated", "lookupOperations"}
        if arm == "TABLE_COLD":
            allowed.add("acquisitionCalls")
        if arm == "TABLE_RESTORED":
            allowed.add("restorationCalls")
    else:
        allowed = {"searchCalls", "replayCalls", "generated"}
        if arm.startswith("GUIDED_CACHE_"):
            allowed.update(("lookupOperations", "cacheHits", "replacements"))
    # The frozen producer inserts literal zeroes for all other work components.
    require(all(row[k] == 0 for k in COSTS if k != "totalNativeCalls" and k not in allowed),
            "unexpected work for " + arm + " " + status)


def validate(report, runtime, matrix):
    require(report["schema"] == SCHEMA and runtime["schema"] == SCHEMA + "/runtime", "producer schemas")
    require(report["guidedController"] == "regelsuche.guided-factor-deepening/v2" and
            report["portfolio"] == "regelsuche.factor-search-portfolio/v1", "controller versions")
    require(report["regime"] == "PUBLIC_TARGET_DIRECTED_EXACT_SYNTAX" and report["workComparability"] == WORK
            and report["stretch1000x"] == "UNMET", "claim boundary")
    require(report["maximumNativeCallsPerArm"] == 4096 and report["guidedLookupLimit"] == 262144, "budget changes")
    rows = by_identity(report["rows"], matrix)
    samples = by_identity(runtime["samples"], matrix)
    references = {}
    tables = set()
    for (case, arm), row in rows.items():
        validate_terminal(row, matrix[case, arm])
        validate_work(row, report["maximumNativeCallsPerArm"])
        reference = row["referenceMinimumDepth"], row["referenceStatus"]
        require(references.setdefault(case, reference) == reference, "reference differs across arms")
        if arm == "TABLE_COLD" and row["status"] != "ACQUISITION_INCONCLUSIVE":
            tables.add(case)
        observation = samples[case, arm]
        wall, allocated = observation["wallNanos"], observation["currentThreadAllocatedBytes"]
        require(type(wall) is int and wall >= 0 and type(allocated) is int and allocated >= -1, "runtime fields")
        if arm.startswith("PORTFOLIO_"):
            require(allocated == -1, "portfolio allocation is unobserved")
    require(set(report["sourceTables"]) == tables, "source tables differ from acquired tables")
    for case in references:
        for arm in ("TABLE_WARM", "TABLE_RESTORED"):
            require((rows[case, arm]["status"] == "NO_COMPLETE_SOURCE_TABLE") == (case not in tables),
                    "table terminal contradicts cold acquisition")
    for case, table in report["sourceTables"].items():
        require(isinstance(table, str) and 0 < len(table.encode("utf-8")) <= 4194304, "v2 canonical table envelope")
        envelope = json.loads(table)
        content = envelope["content"]
        payload = json.dumps(content, ensure_ascii=False, separators=(",", ":")).encode()
        require(content["schema"] == "regelsuche.finite-factor-knowledge/v1" and
                envelope["contentHash"] == "sha256:" + digest(payload), "table content binding")
        calls = content["acquisition"]["calls"]
        require(rows[case, "TABLE_COLD"]["acquisitionCalls"] == calls and
                rows[case, "TABLE_RESTORED"]["restorationCalls"] == calls, "table acquisition/restoration cost")


def compare(paths):
    if len(paths) != 3:
        raise ValueError("three comparison files required")
    first = paths[0].read_bytes()
    require(all(path.read_bytes() == first for path in paths[1:]), "canonical v2 comparison bytes differ")
    return digest(first)


def image_receipt(output):
    image = (output / "image.id").read_text().strip()
    require(len(image) == 71 and image.startswith("sha256:") and
            all(c in "0123456789abcdef" for c in image[7:]), "invalid immutable image identity")
    manifests = json.loads((output / "image-digests.json").read_bytes())
    require(isinstance(manifests, list) and any(value.rpartition("@")[2] == MANIFEST_DIGEST for value in manifests),
            "inspected OCI image lacks the pinned distribution manifest")
    return {"imageId": image, "distributionManifest": MANIFEST_DIGEST, "runtimeNetwork": "none"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("evidence", type=Path)
    args = parser.parse_args()
    root, output = args.source, args.evidence
    workflow_source = (output / "workflow-source.txt").read_text().strip()
    require(re.fullmatch(r"[0-9a-f]{40}", workflow_source), "missing or invalid workflow revision")
    matrix = expected_matrix(root)
    runs = {}
    comparisons = []
    plan = json.loads((root / PLAN).read_bytes())
    for name in ENVIRONMENTS:
        directory = output / name
        require((directory / "source.txt").read_text().splitlines() == [PRODUCER, TREE], "producer commit/tree")
        comparison, runtime = directory / "comparison.json", directory / "runtime.json"
        report = json.loads(comparison.read_bytes())
        require(report["corpusSha256"] == plan["historicalCorpusSha256"], "reported corpus substitution")
        validate(report, json.loads(runtime.read_bytes()), matrix)
        for log in ("build.log", "run.log"):
            require((directory / log).read_bytes().strip(), "empty evidence log: " + name + "/" + log)
        comparisons.append(comparison)
        runs[name] = {file: binding(directory / file) for file in
                      ("source.txt", "comparison.json", "runtime.json", "build.log", "run.log")}
    receipt = {"schema": "regelsuche.finite-search-knowledge-reproduction/v2", "producer": PRODUCER, "tree": TREE,
               "planSha256": PLAN_SHA, "corpusSha256": plan["historicalCorpusSha256"], "cases": 16, "arms": 10,
               "workflowSource": workflow_source,
               "rowsPerRun": len(matrix), "canonicalComparisonSha256": compare(comparisons), "container": image_receipt(output),
               "runs": runs, "workComparability": WORK, "stretch1000x": "UNMET"}
    (output / "reproduction.json").write_text(json.dumps(receipt, sort_keys=True, indent=2) + "\n")


if __name__ == "__main__":
    main()
