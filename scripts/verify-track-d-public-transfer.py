"""Check provenance, registered task coverage, path accounting and the bounded Track D result."""
import hashlib
import json
from pathlib import Path

DATA = Path(__file__).resolve().parents[1] / "research/benchmarks/track-d-public-transfer"


def load(name):
    return json.loads((DATA / name).read_bytes())


def require(condition, message):
    if not condition:
        raise ValueError(message)


def verify():
    plan = load("plan.json")
    for name, expected in plan["inputs"].items():
        require(hashlib.sha256((DATA / name).read_bytes()).hexdigest() == expected, name)
    source = load("formation.json")
    formation = load("formation-result.json")
    evaluation = load("transfer-result.json")
    require(formation["registration"] == evaluation["registration"] == "bc52b33d5dc44d1c7661a6335cb49b33733bb616",
            "registration commit")
    require(evaluation["formationBytesSha256"] == hashlib.sha256((DATA / "formation-result.json").read_bytes()).hexdigest(),
            "formation input to evaluation")
    require(len(source) == len(formation["blindBatches"]), "blind batches")
    ids = set()
    for batch, result in zip(source, formation["blindBatches"], strict=True):
        require(batch["batch"] == result["batch"], "batch identity")
        require(not result["mining"]["targetProvided"], "target in formation")
        for observation, search in zip(batch["observations"], result["searches"], strict=True):
            require(observation == {"id": search["id"], "expression": search["source"]}, "search identity")
            require(search["outcome"] == "UNTARGETED" and search["id"] not in ids, "blind search")
            ids.add(search["id"])
        for conjecture in result["mining"]["conjectures"]:
            require(not conjecture["postHocFamilies"], "family label in mined candidate")
            for item in conjecture["evidence"]:
                require(item["family"] == "" and item["observationId"] in ids, "family-blind observation")
                require(len({tuple(p["ruleIds"]) for p in item["paths"]}) >= 2, "independent native paths")
                for path in item["paths"]:
                    require(path["expressions"][0] == item["inputExpression"] and
                            path["expressions"][-1] == item["outputExpression"], "convergence endpoints")
                    require(path["depth"] == len(path["ruleIds"]) == len(path["expressions"]) - 1, "formation path length")
    cases = {c["family"]: c for c in load("evaluation.json")}
    require(len(evaluation["bridges"]) == len(formation["bridges"]) == 1, "formed and evaluated bridge")
    result = evaluation["bridges"][0]
    bridge = formation["bridges"][0]
    require(set(bridge["conjecture"]["supportingObservationIds"]) == ids, "bridge provenance")
    require(bridge["formationHash"] == result["formationHash"] == result["transfer"]["formationHash"], "bridge binding")
    require({f["family"] for f in result["familyRuns"]} == set(cases), "suites")
    invocations = 0
    for family in result["familyRuns"]:
        case = cases[family["family"]]
        require(family["role"] == case["role"], "registered role")
        checked = family["validation"]
        require(checked["status"] == "REJECTED" and checked["executedPositiveHoldouts"] == 1 and
                checked["executedNegativeHoldouts"] == 1 and checked["positiveResults"][0]["candidateCount"] == 0 and
                checked["negativeResults"][0]["noApplication"], "measured candidate applicability")
        require(family["proof"]["proofStatus"] == "NOT_RUN", "proof eligibility")
        for control in ("positive", "negative"):
            pair = family[control]
            require(pair["source"] == case[control + "Source"] and pair["target"] == case[control + "Target"], "task identity")
            without, with_bridge = pair["without"], pair["with"]
            for arm, enabled in ((without, False), (with_bridge, True)):
                require(len(arm["path"]) == len(arm["rules"]) + 1, "utility path length")
                require(len(arm["expansions"]) == arm["work"]["expandedStates"], "engine invocations")
                require(sum(c["nativeCandidates"] + c.get("bridgeCandidates", 0) for c in arm["expansions"])
                        == arm["work"]["generatedTransformations"], "generation work")
                require(all(c["bridgeInvoked"] == enabled for c in arm["expansions"]), "bridge availability")
            invocations += len(with_bridge["expansions"])
            require(not with_bridge["bridgeApplications"], "unreported bridge application")
            require(with_bridge["work"] == without["work"] and with_bridge["rules"] == without["rules"] and
                    with_bridge["status"] == without["status"], "paired outcome")
            require((with_bridge["status"] == "REACHED") == (control == "positive"), "positive or false target reached")
    require(not result["invalidTargetReached"] and result["positiveTaskAblation"]["status"] == "NOT_ELIGIBLE_TRANSFER",
            "claim boundary")
    print(f"Verified {len(ids)} blind formation searches, {2*2*len(cases)} paired-arm runs and {invocations} bridge invocations.")


if __name__ == "__main__":
    verify()
