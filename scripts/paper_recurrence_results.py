"""Summarize the public, fixed-rule recurrence comparison for the methods paper."""
import hashlib
import json
from collections import Counter
from pathlib import Path

from paper_controller_figure import table


RESULT = "research/benchmarks/candidate-independent/track-c-recurrence/comparison-result.json"
REVISION = "7c49d63e5df5ccc7f323eec850b4fc7f7fc38ff4"
SHA256 = "af8c65da2906b0b249bac4d895999b38b633adcad3579fe5b84bf1fb79b8c27c"
ARMS = {
    "PRODUCTION_EXACT_FIT": "Exact fitter",
    "ENUMERATE_VALID_GRAMMAR": "Enumeration",
    "SEEDED_RANDOM_VALID_GRAMMAR": "Seeded random",
}


def write_recurrence_report(raw: Path, output: Path) -> None:
    data = raw.read_bytes()
    if hashlib.sha256(data).hexdigest() != SHA256:
        raise ValueError("public recurrence evidence has changed")
    report = json.loads(data)
    formation_rows, evaluation_rows = [], []
    for arm, label in ARMS.items():
        campaigns = [c for c in report["campaigns"] if c["arm"] == arm]
        attempts = [attempt for c in campaigns for prefix in c["formation"]["inputs"]
                    for attempt in prefix["attempts"]]
        formation_rows.append([
            label, len(campaigns), len(attempts), sum(a["duplicate"] for a in attempts),
            sum(a["disposition"] == "SUPPORTED" for a in attempts),
            sum(not c["formation"]["models"] for c in campaigns),
        ])
        for split in ("TRAIN", "VALIDATION", "TEST"):
            outcomes = Counter(row["outcome"] for c in campaigns for row in c["evaluations"]
                               if row["split"] == split)
            evaluation_rows.append([label, split, outcomes.total(), outcomes["CONFIRMED"],
                                    outcomes["PREFIX_MISMATCH"], outcomes["REFUTED"], outcomes["NO_OUTPUT"]])

    report_url = f"https://github.com/carstenartur/Regelsuche/blob/{REVISION}/{RESULT}"
    plan_url = report_url.replace(REVISION, report["planCommit"]).replace("comparison-result", "comparison-plan")
    sections = [
        "# Formation and fixed-rule reuse on the public recurrence cases",
        "The arms receive the same two TRAIN prefixes and the same cap on proposals. Each campaign freezes "
        "its admitted coefficient tuples before testing them across all splits. Supported attempts and "
        "duplicate attempts can overlap. Empty counts campaigns with no admitted model.",
        table(["Arm", "Campaigns", "Proposals", "Duplicates", "Supported", "Empty"], formation_rows),
        "A slot is one campaign evaluated on one case. Confirmation requires agreement with the prefix "
        "and the three recorded continuation values. Each slot receives one terminal outcome. "
        "A refuted continuation means a rule matched the prefix but failed on continuation.",
        table(["Arm", "Split", "Slots", "Confirmed", "Prefix mismatch", "Refuted continuation", "No model"],
              evaluation_rows),
        "The cases and their historical outcomes were public before this comparison. All confirmations "
        "refer to the same geometric TRAIN case. Candidate proposals measure submissions to validation. "
        "The three algorithms incur different internal costs per submission.",
        f"Result bytes SHA-256 `{SHA256}`. Plan bytes `{report['planByteHash']}`. "
        f"[Retained case rows and proposals]({report_url}) · [Registered comparison plan]({plan_url}).",
    ]
    (output / "public-recurrence-results.md").write_text("\n\n".join(sections) + "\n", encoding="utf-8")
