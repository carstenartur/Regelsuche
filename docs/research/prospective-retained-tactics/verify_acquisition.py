"""Check third-generation tactic provenance against the archived primitive development inputs."""

from collections import defaultdict
import json
import re
import runpy


COST_FIELDS = {"lessons", "formation", "preparation", "validation", "selection", "restoration", "splitChecking"}


def verify(study, model):
    parent = runpy.run_path(str(study.parent / "prospective-tactic-transfer" / "verify.py"))
    require, read, sha, tree = (parent[k] for k in ("require", "read", "sha", "tree"))
    training = study / "training"
    trace = json.loads(read(training, "trace-acquisition.json"))
    receipt = json.loads(read(training, "receipt.json"))
    archive = study.parent / "prospective-tactic-transfer" / "results"
    rules = {s["id"]: s for s in model["schemas"]}
    supporting = defaultdict(set)
    sources = []
    total = trace["joinWork"]
    loads = 0

    def natural(value):
        return type(value) is int and value >= 0

    def audit_witness(task, evidence):
        steps = evidence["witness"]
        wire = json.dumps(steps, separators=(",", ":"), ensure_ascii=False).encode()
        parent["verify_paths"]({"sha256:" + sha(wire): steps}, {})
        start, end = tree(task["source"]), tree(evidence["output"])
        if steps:
            require(start == tree(steps[0]["source"]) and end == tree(steps[-1]["target"]), "acquisition endpoints")
        else:
            require(start == end, "empty acquisition proof changed its source")
        require(natural(evidence["work"]) and evidence["work"] >= 2 * sum(s["verification"] for s in steps),
                "acquisition search omitted verification or final replay charges")
        require(type(evidence["solved"]) is bool, "acquisition outcome")
        if evidence["solved"]:
            _, size, _ = parent["previous"]["analyze"](evidence["output"])
            require(size <= task["target"] and evidence["work"] <= 8192, "unearned acquisition solution")

    batches = trace["batches"]
    require(natural(total) and sorted(b["stream"] for b in batches) == ["A", "B"], "invalid acquisition batches")
    for batch in batches:
        stream = batch["stream"]
        lines = read(archive / stream, "BASE.jsonl").splitlines(keepends=True)[:6]
        require(len(lines) == 6 and all(l.endswith(b"\n") for l in lines), "incomplete archive prefix")
        rows = [json.loads(raw) for raw in lines]
        require(batch["readWork"] == sum(map(len, lines)), "unpaid archive input bytes")
        base = json.loads(read(archive / stream, "summary.json"))["arms"]["BASE"]["setup"]
        require(natural(batch["load"]) and batch["referenceSetup"] == base["preparation"], "invalid acquisition setup")
        require(re.fullmatch("sha256:[0-9a-f]{64}", batch["modelHash"]) is not None, "missing batch model ID")
        lessons, gates = batch["training"], batch["gates"]
        require([r["index"] for r in lessons] == [0, 1, 2] and [r["index"] for r in gates] == [3, 4, 5],
                "wrong acquisition split")
        for task, original in zip(lessons + gates, rows, strict=True):
            require(task["index"] == original["index"] and task["source"] == original["source"]
                    and task["target"] == original["target"], "acquisition input differs from published BASE input")
            sources.append(task["source"])
        for task in lessons:
            audit_witness(task, task)
        validation_cost = 0
        for gate in gates:
            reference = gate["reference"]
            audit_witness(gate, reference)
            depth = max(1, len(reference["witness"]) - 1) if reference["solved"] else 32
            require(gate["depth"] == depth, "gate depth differs from measured primitive solution")
            charges = [gate[k] for k in ("base", "previous", "candidate", "routed")]
            require(all(natural(v) for v in charges), "invalid gate charge")
            validation_cost += sum(charges)
        costs = batch["acquisition"]
        require(set(costs) == COST_FIELDS and all(natural(v) for v in costs.values()), "invalid acquisition ledger")
        require(costs["lessons"] == sum(r["work"] for r in lessons) and costs["validation"] == validation_cost,
                "training or gate attempt omitted")
        require(batch["referenceWork"] == sum(r["reference"]["work"] for r in gates), "unpaid gate measurement")
        total += batch["readWork"] + batch["referenceSetup"] + batch["referenceWork"] + sum(costs.values())
        loads += batch["load"]
        for rule, origins in batch["support"].items():
            require(rule in rules and batch["decision"] == "PROMOTED", "schema absent from promoted archive batch")
            schema = rules[rule]
            patterns = tuple(parent["freeze_tree"](schema[side]) for side in ("source", "target"))
            parent["check_provenance"]({"supportingObservationIds": origins}, patterns, lessons)
            supporting[rule].update(origins)
    hashes = [batch["modelHash"] for batch in batches]
    if hashes[0] != hashes[1]:
        links = [a["observationIds"] for a in model["attempts"] if a["status"] == "RETAINED_LIBRARIES"]
        require(links == [hashes], "combined model does not name both archived acquisitions")
    else:
        require("sha256:" + sha(read(training, "acquired.json")) == hashes[0], "incorrect reused archive model")
    require(set(supporting) == set(rules), "retained schema lacks archived proof provenance")
    for rule, origins in supporting.items():
        require(origins == set(rules[rule]["supportingObservationIds"]), "lost or unrecognized archive origins")
    require(trace["acquisition"] == total == receipt["acquiredAcquisition"], "incorrect total acquisition charge")
    require(trace["load"] == loads == receipt["acquiredLoad"], "incorrect total acquisition load")
    return sources
