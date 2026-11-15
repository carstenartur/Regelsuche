"""Derive manuscript baseline and failure tables from public case records."""
from collections import Counter

from paper_controller_figure import table


LEGACY_TRACKS = {
    "TARGET_DIRECTED_SEARCH": ("search-", "Target-directed search (A1)", 3, 3),
    "SIMPLIFICATION_COMPETITION": ("simplify-", "Target-free simplification (A2)", 7, 8),
    "EQUALITY_VALIDATION": ("validation-", "Equality validation (E1)", 2, 3),
}


def counts_text(counts):
    return ", ".join(f"{status.replace('_', ' ').lower()}: {count}" for status, count in sorted(counts.items())) or "None"


def legacy_sections(report):
    sections = []
    for track, (prefix, title, case_count, configuration_count) in LEGACY_TRACKS.items():
        configurations = [entry for entry in report["configurations"] if entry["track"] == track]
        cases = {entry["contentHash"]: entry["id"] for entry in report["cases"] if entry["track"] == track}
        if len(cases) != case_count or len(configurations) != configuration_count:
            raise ValueError(f"legacy comparison cardinality changed for {track}")
        rows = []
        for configuration in configurations:
            label = configuration["id"].removeprefix(prefix)
            results = [row for row in report["results"] if row["configurationHash"] == configuration["contentHash"]]
            if Counter(row["caseHash"] for row in results) != Counter(cases.keys()):
                raise ValueError(f"incomplete result matrix for {label}")
            verdicts = Counter(row["observedVerdict"] for row in results)
            if track == "TARGET_DIRECTED_SEARCH":
                work = [sum(row["resources"][key] for row in results)
                        for key in ("exploredStates", "generatedSuccessors", "pathLength")]
                calls = sum(row["trackMetrics"]["engineInvocations"] for row in results)
                rows.append([label, f"{verdicts['TARGET_REACHED']}/{case_count}", *work, calls])
            elif track == "SIMPLIFICATION_COMPETITION":
                misses = ", ".join(cases[row["caseHash"]].removeprefix("simplify-") for row in results if not row["correct"])
                rows.append([label, f"{verdicts['TARGET_REACHED']}/{case_count}", misses or "None"])
            else:
                proofs = Counter(row["evidence"]["proofStatus"] for row in results)
                lossless = sum(row["trackMetrics"]["translationLossless"] for row in results)
                rows.append([label, verdicts["CONFIRMED"], verdicts["REFUTED"], f"{lossless}/{case_count}", counts_text(proofs)])
        sections.extend([f"## {title}", table(LEGACY_HEADERS[track], rows), LEGACY_SCOPE[track]])
    return sections


LEGACY_HEADERS = {
    "TARGET_DIRECTED_SEARCH": ["Search", "Reached", "States explored", "Successors", "Path steps", "Engine calls"],
    "SIMPLIFICATION_COMPETITION": ["Configuration", "Reference forms", "Cases where the reference form was missed"],
    "EQUALITY_VALIDATION": ["Validator", "Confirmed", "Refuted", "Lossless translation", "Proof or certificate status"],
}
LEGACY_SCOPE = {
    "TARGET_DIRECTED_SEARCH": "All arms saw the target and shared the same budget. Work totals span the three fixed cases.",
    "SIMPLIFICATION_COMPETITION": "Formation had no pinned reference. Each output was checked against the same reference-form surface. "
    "A missed form can be another expression, so this comparison measures reference-form recovery. "
    "The small corpus covers one domain and has no independent assumption-aware output validation.",
    "EQUALITY_VALIDATION": "Each validator received the same positive identity and false equality. Confirmation and refutation are "
    "both correct outcomes for their respective cases. Retained validation certificates and formal certificates carry different scope.",
}


def rewriting_sections(report):
    outcomes = {arm: [] for arm in ("best-first", "equality-saturation")}
    for case in report["cases"]:
        if Counter(result["system"] for result in case["outcomes"]) != Counter(outcomes.keys()):
            raise ValueError(f"incomplete rewriting comparison for {case['id']}")
        for result in case["outcomes"]:
            outcomes[result["system"]].append(result)
    rows = []
    for arm, results in outcomes.items():
        verdicts = Counter(result["verdict"] for result in results)
        proofs = Counter(result["proofStatus"] for result in results)
        supported = verdicts["REFERENCE_COST_REACHED"] + verdicts["REFERENCE_COST_MISSED"]
        if supported + verdicts["UNSUPPORTED"] != len(results):
            raise ValueError(f"unaccounted rewriting outcome for {arm}")
        rows.append([arm, f"{verdicts['REFERENCE_COST_REACHED']}/{supported}", verdicts["REFERENCE_COST_MISSED"],
                     verdicts["UNSUPPORTED"], counts_text(proofs)])
    failures = [[case["id"], counts_text(Counter(result["translation"] for result in case["outcomes"]))]
                for case in report["cases"] if any(result["verdict"] == "UNSUPPORTED" for result in case["outcomes"])]
    return ["## Shared-fragment rewriting (E2)",
            table(["Search", "Reference cost", "Cost missed", "Unsupported", "Proof or replay status"], rows),
            f"Both arms used the same {len(report['ruleIds'])} unconditional rules. The reference cost counts operators. "
            "Search uses path work and equality saturation uses graph work, each with its own budget. "
            "The evidence retains primitive replay, detached certificate checks and native work per case.",
            table(["Unsupported case", "Translation boundaries across the two arms"], failures)]


def formation_outcome_table(forms, labels):
    rows = []
    for key, label, _ in labels:
        for split in ("TRAIN", "VALIDATION", "TEST"):
            outcomes = Counter()
            for lane in forms[key]["lanes"]:
                outcomes.update(lane["evaluationOutcomes"][split])
            rows.append([label, split, outcomes.total(), counts_text(outcomes)])
    return table(["Form", "Split", "Evaluations", "Terminal outcomes across all arms"], rows)
