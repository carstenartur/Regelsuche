#!/usr/bin/env python3
"""Run the four historical Java adapters in a pinned checkout and verify the retained inventory bytes."""
import argparse
import gzip
import hashlib
import json
import re
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PUBLIC = ROOT / "research/benchmarks/candidate-independent/track-c-project-inventory"
REVISION = "34eb0900f26f9791545e0201d2f5db2d4a2ee96f"
RELEASE = "de.regelsuche.release.CandidateIndependent"
ADAPTERS = {
    "finiteDifferences": (RELEASE + "FiniteSequenceAdapterMain", "finite-sequence-candidate-forms"),
    "recurrence": (RELEASE + "LinearRecurrenceAdapterMain", "finite-sequence-candidate-forms"),
    "macros": ("de.regelsuche.benchmark.CandidateIndependentReusableMacroBatchMain", "macro-primitives"),
    "rational": (RELEASE + "RationalAssumptionAdapterMain", "rational-assumption-primitives"),
}


def require(condition, details):
    if not condition:
        raise ValueError(details)


def replay(checkout, java, classpath):
    head = subprocess.check_output(["git", "-C", checkout, "rev-parse", "HEAD"], text=True).strip()
    require(head == REVISION, "expected the registered earlier checkout at " + REVISION)
    subprocess.run(["git", "-C", checkout, "diff", "--quiet", "HEAD", "--"], check=True)
    d = checkout / "research/benchmarks/candidate-independent"
    catalogue = json.loads((PUBLIC / "reference-catalogue.json").read_bytes())
    source = {}
    for relative, expected in catalogue["sources"].items():
        raw = (checkout / relative).read_bytes()
        require(hashlib.sha256(raw).hexdigest() == expected, relative)
        source[Path(relative).stem] = raw.decode()
    patterns = re.findall(r'new KnownRule\("[^"]+", "([^"]+)", "([^"]+)"\)', source["KnownRuleRepository"])
    require([list(pair) for pair in patterns] == catalogue["activeMacroPatterns"], "historical active pattern catalogue")
    operator = source["RationalNormalizationHypothesisOperator"]
    require('addSameDenominatorCandidate(root, candidates);' in operator and 'addCancellationCandidate(root, candidates);' in operator,
            "historical combined rational operation branches")
    combined = {}
    with tempfile.TemporaryDirectory() as tmp:
        working = Path(tmp)
        # This is the upstream Gradle adapter for the original benchmark source without an embedded semantic root.
        adapted = json.loads((d / "benchmark-source.json").read_bytes())
        require("contentHash" not in adapted, "unexpected historical benchmark format")
        adapted["contentHash"] = json.loads((d / "corpus-freeze-receipt.json").read_bytes())["benchmarkSourceContentHash"]
        adapted_path = working / "rational-source.json"
        adapted_path.write_text(json.dumps(adapted))
        for form, (main, profile) in ADAPTERS.items():
            out = working / (form + ".json")
            command = [java, "-cp", classpath, main, "--corpus", d / "case-corpus.json", "--profile", d / (profile + ".json"),
                       "--freeze-receipt", d / "corpus-freeze-receipt.json", "--repository-revision", REVISION, "--output", out]
            if form in ("macros", "rational"):
                command.extend(["--benchmark-source", adapted_path if form == "rational" else d / "benchmark-source.json"])
            subprocess.run(command, cwd=checkout, check=True)
            combined[form] = json.loads(out.read_bytes())
    raw = (json.dumps(combined, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode()
    require(raw == gzip.decompress((PUBLIC / "historical-adapter-runs.json.gz").read_bytes()), "historical report difference")
    print("Historical project inventory reproduced", len(raw), "bytes", hashlib.sha256(raw).hexdigest())


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("checkout", type=Path)
    parser.add_argument("--java", default="java")
    parser.add_argument("--classpath", help="compiled historical app/release classes and their runtime dependencies")
    args = parser.parse_args()
    checkout = args.checkout.resolve()
    cp = args.classpath or str(checkout / "app/build/install/app/lib/*") + ":" + str(
        checkout / "regelsuche-release/build/install/regelsuche-release/lib/*")
    replay(checkout, args.java, cp)
