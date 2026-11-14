"""Build a table and a shared-scale plot from one published controller experiment."""
from __future__ import annotations

import gzip
import hashlib
import json
from pathlib import Path
from xml.sax.saxutils import escape


MATRIX = "docs/evidence/autopilot-controller-comparison-v1.json.gz"
REVISION = "99383b298bfbc0b857c33b20233ae166107bc7cc"
RAW_SHA256 = "04eaa8a4bb800791b62fed4d1cab4fcd6c3e2e4de46a9c561a24a03272972d19"
PROTOCOL_SHA256 = "7ddc243adfd1297f1c1c331453872cbea501fe2beddcfd7145a7c1e8be250f8e"
LABELS = {
    "PRODUCTION_FEEDBACK": "Feedback",
    "FIXED_UNIFORM": "Equal candidate slots",
    "NO_FEEDBACK": "No feedback",
}


def table(header, rows):
    def row(cells):
        return "| " + " | ".join(str(cell) for cell in cells) + " |"

    return "\n".join([row(header), row(["---"] * len(header)), *(row(item) for item in rows)])


def candidate_slots(branch):
    return next(item["amount"] for item in branch["decision"]["plannedResources"]
                if item["resource"] == "CANDIDATES")


def allocation_figure(lanes):
    """Each stack uses the same slots scale. Accessible descriptions include the split."""
    groups = []
    maximum = max(sum(candidate_slots(branch) for branch in lane["branches"]) for lane in lanes)
    scale = 318 / max(1, maximum)
    for index, lane in enumerate(lanes):
        y = 48 + 48 * index
        first, pair = (candidate_slots(branch) for branch in lane["branches"])
        label = escape(LABELS[lane["policy"]])
        groups.append(
            f'<g><title>{label}: full batch {first} slots, paired observations {pair} slots</title>'
            f'<text x="12" y="{y+16}">{label}</text>'
            f'<rect x="176" y="{y}" width="{first*scale:g}" height="22" fill="#275878"/>'
            f'<rect x="{176+first*scale:g}" y="{y}" width="{pair*scale:g}" height="22" fill="#b9d6e3"/>'
            f'<text x="510" y="{y+16}">{first} / {pair}</text></g>'
        )
    return ('<svg xmlns="http://www.w3.org/2000/svg" width="600" height="199" viewBox="0 0 600 199" '
            'role="img" aria-labelledby="title description"><title id="title">Candidate-slot allocation</title>'
            '<desc id="description">Full observation batch (dark) and paired observations (light), '
            'with exact counts shown in this order.</desc><rect width="600" height="199" fill="white"/>'
            '<g font-family="sans-serif" font-size="15" fill="#172e3b">'
            '<text x="12" y="24" font-weight="bold">Candidate slots allocated to the full batch / pair</text>'
            + "".join(groups) + '</g></svg>\n')


def write_controller_outputs(matrix_path: Path, output: Path) -> None:
    # The published study pins the decompressed report, independent of its gzip timestamp.
    with gzip.open(matrix_path, "rb") as stream:
        raw = stream.read(138181)
    if hashlib.sha256(raw).hexdigest() != RAW_SHA256:
        raise SystemExit("paper controller input differs from the retained raw matrix")
    matrix = json.loads(raw)
    if matrix["protocolSha256"] != PROTOCOL_SHA256:
        raise SystemExit("paper controller input differs from its registered protocol")
    lanes = matrix["lanes"]
    outcomes = []
    work = []
    for lane in lanes:
        receipts = [branch["receipt"] for branch in lane["branches"]]
        val = lane["validation"]
        label = LABELS[lane["policy"]]
        outcomes.append([label, len(receipts[0]["outputs"]), len(receipts[1]["outputs"]),
                         sum(len(r["rejectedClusters"]) for r in receipts),
                         val["executedPositiveHoldouts"], val["executedNegativeHoldouts"],
                         len(lane["nextPlan"]["decisions"]), lane["stopReason"]])
        for item in lane["ledger"]["entries"]:
            work.append([label, item["stage"], item["resource"], item["configured"],
                         item["executed"], item["skipped"], item["remaining"]])

    source = f"https://github.com/carstenartur/Regelsuche/blob/{REVISION}/{MATRIX}"
    report = [
        "# Controller comparison on the public one-round campaign",
        "Each row replays the same observations with a different controller. Positive and negative columns "
        "count executed holdouts. One candidate can be shared across policies. Slot counts are allocations.",
        "![Allocation of candidate slots between the full observation batch and the paired observations]"
        "(public-controller-allocation.svg)",
        table(["Policy", "Full-batch outputs", "Pair outputs", "Rejected clusters", "Positive", "Negative",
               "Next decisions", "Stop reason"], outcomes),
        "## Resource accounts by policy",
        table(["Policy", "Stage", "Unit", "Configured", "Executed", "Skipped", "Remaining"], work),
        "## Limits recorded by the experiment",
        "\n".join("- `" + gap + "`" for gap in matrix["coverageGaps"]),
        f"Uncompressed bytes: {len(raw)}. SHA-256: `{RAW_SHA256}`. "
        f"Preregistered protocol SHA-256: `{PROTOCOL_SHA256}`. [Raw matrix at the integrated revision]({source}).",
    ]
    (output / "public-controller-results.md").write_text("\n\n".join(report) + "\n", encoding="utf-8")
    (output / "public-controller-allocation.svg").write_text(allocation_figure(lanes), encoding="utf-8")
