"""Retrospective coverage of fresh patterns on inputs and all retained BASE proof prefixes."""

import json
from pathlib import Path

from verify import FAMILIES, match, read, schema_inventory, tree


def subtrees(node):
    yield node
    if len(node) == 3:
        yield from subtrees(node[1])
        yield from subtrees(node[2])


def hits(source, schemata):
    return {schema for node in subtrees(tree(source)) for schema, (pattern, _) in schemata.items()
            if match(pattern, node, {})}


def inspect(directory, schemata):
    rows = [json.loads(line) for line in read(directory, "BASE.jsonl").splitlines()]
    paths = json.loads(read(directory, "paths.json"))
    inputs_hit = targets = targets_hit = partial_targets = cases_hit = partial_hits = 0
    matched = set()
    for row in rows:
        case_hits = hits(row["source"], schemata)
        inputs_hit += bool(case_hits)
        steps = paths[row["pathHash"]]
        targets += len(steps)
        if not row["solved"]:
            partial_targets += len(steps)
        for step in steps:
            step_hits = hits(step["target"], schemata)
            targets_hit += bool(step_hits)
            case_hits.update(step_hits)
        cases_hit += bool(case_hits)
        partial_hits += bool(case_hits) and not row["solved"]
        matched.update(case_hits)
    return {"cases": len(rows), "unsolvedCases": sum(not r["solved"] for r in rows),
            "inputsWithMatch": inputs_hit, "stepTargets": targets, "stepTargetsWithMatch": targets_hit,
            "stepTargetsOnUnsolvedCases": partial_targets, "casesWithAnyMatch": cases_hit,
            "unsolvedCasesWithAnyMatch": partial_hits, "matchedSchemaIds": sorted(matched)}


if __name__ == "__main__":
    study = Path(__file__).resolve().parent
    _, schemata = schema_inventory((study / "training/snapshot.json").read_bytes())
    print(json.dumps({"freshSchemata": len(schemata),
                      "families": {f: inspect(study / "results" / f, schemata) for f in FAMILIES}}, indent=2))
