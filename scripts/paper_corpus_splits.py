"""Summarize the frozen public case assignments and their declared formation boundary."""
import hashlib
import json
from collections import Counter, defaultdict


CORPUS = "research/benchmarks/candidate-independent/case-corpus.json"
RECEIPT = "research/benchmarks/candidate-independent/corpus-freeze-receipt.json"
EXPECTED_RECEIPT = "sha256:e8098edb6339d6debff04a4d97ad6e4f416202a94be7c6921fa239b066726f58"
REVISION = "597f2e94cc49683f793ea002b65630e940089051"


def semantic_root(document):
    payload = {key: value for key, value in document.items() if key != "contentHash"}
    canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return "sha256:" + hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def write_split_report(corpus_path, receipt_path, output):
    corpus = json.loads(corpus_path.read_bytes())
    receipt = json.loads(receipt_path.read_bytes())
    if (semantic_root(corpus) != corpus["contentHash"] or receipt["caseCorpusContentHash"] != corpus["contentHash"]
            or semantic_root(receipt) != receipt["contentHash"]
            or receipt["contentHash"] != EXPECTED_RECEIPT):
        raise SystemExit("paper split inputs differ from the registered corpus")
    splits = ("TRAIN", "VALIDATION", "TEST")
    counts = Counter()
    clusters = defaultdict(set)
    formed = Counter()
    for case in corpus["cases"]:
        split = case["split"]
        permitted = case["exposurePolicy"]["candidateFormationMayRead"]
        expected = ["formationInput"] if split == "TRAIN" else []
        if split not in splits or permitted != expected or (case["formationInput"] is not None) != bool(expected):
            raise SystemExit("wrong formation boundary for " + case["caseId"])
        if "evaluationInput" not in case["exposurePolicy"]["candidateFormationMustNotRead"]:
            raise SystemExit("evaluator material is visible to formation")
        if semantic_root(case) != case["contentHash"]:
            raise SystemExit("frozen case identity differs")
        counts[case["challengeId"], split] += 1
        clusters[case["structuralCluster"]].add(split)
        formed[case["challengeId"]] += int(bool(permitted))
    overlaps = sorted(label for label, values in clusters.items() if len(values) > 1)
    if overlaps:
        raise SystemExit("structural cluster spans multiple splits: " + ", ".join(overlaps))
    rows = ["| Challenge | TRAIN | VALIDATION | TEST | With formation input |",
            "| --- | ---: | ---: | ---: | ---: |"]
    for challenge in sorted(formed):
        values = [counts[challenge, split] for split in splits]
        rows.append("| " + " | ".join(str(cell) for cell in [challenge, *values, formed[challenge]]) + " |")
    link = f"https://github.com/carstenartur/Regelsuche/blob/{REVISION}/"
    sections = [
        "# Frozen public corpus and information boundary",
        "\n".join(rows),
        f"The manifest contains {len(corpus['cases'])} cases and {len(clusters)} distinct registered structural "
        f"cluster labels. {len(overlaps)} labels occur in more than one split. Only TRAIN carries a formation "
        "payload. The declared policy reserves evaluation input for the evaluator on every split.",
        f"The freeze records {receipt['executedCampaignsAtFreeze']} executed campaigns and "
        f"{receipt['executedEvaluationsAtFreeze']} executed evaluations. These counts describe the registration "
        "point. Actual campaign exposure must be checked in execution receipts. This table does not measure "
        "semantic similarity between cases.",
        f"Corpus root `{corpus['contentHash']}`. Receipt root `{receipt['contentHash']}`. "
        f"[Case manifest]({link}{CORPUS}) · [Freeze receipt]({link}{RECEIPT}).",
    ]
    (output / "public-corpus-splits.md").write_text("\n\n".join(sections) + "\n", encoding="utf-8")
