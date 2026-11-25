#!/usr/bin/env python3
"""Check deterministic Java work against the archived study and all successful counts against d4."""
import argparse
import csv
import gzip
import hashlib
from pathlib import Path
import subprocess
import tempfile
import time

HERE = Path(__file__).resolve().parent
D4_SHA256 = "4e5f5591e00af08f604dd7fe3c87446683bdf9353d3ede0b869d4f20c9ff5dc1"

def read_java(file):
    rows = list(csv.reader(file.read_text().splitlines(), delimiter="\t"))
    deterministic = [r[:-2] if r[0] in {"RUN", "ACQUIRE", "PARSE"} else r for r in rows]
    return rows, deterministic

def external(executable):
    binary = Path(executable).resolve()
    if hashlib.sha256(binary.read_bytes()).hexdigest() != D4_SHA256:
        raise ValueError("Unexpected d4 executable (see README)")
    rows = ["model\tstatus\tcount\twallNs"]
    with (HERE / "inputs.tsv").open() as index:
        for item in csv.DictReader(index, delimiter="\t"):
            name = item["model"]
            data = gzip.decompress((HERE / "inputs" / (name + ".dimacs.gz")).read_bytes())
            if hashlib.sha256(data).hexdigest() != item["sha256"]:
                raise ValueError("Input mismatch: " + name)
            with tempfile.NamedTemporaryFile(suffix=".cnf") as dimacs:
                dimacs.write(data)
                dimacs.flush()
                start = time.monotonic_ns()
                try:
                    result = subprocess.run([binary, "-i", dimacs.name, "-m", "counting"],
                                            capture_output=True, text=True, timeout=120, check=True)
                    solutions = [line[2:].strip() for line in result.stdout.splitlines() if line.startswith("s ")]
                    if len(solutions) != 1 or not solutions[0].isdigit():
                        raise ValueError("No exact integer from d4 on " + name)
                    status, value = "EXACT", solutions[0]
                except subprocess.TimeoutExpired:
                    status, value = "TIMEOUT", "-"
                rows.append(f"{name}\t{status}\t{value}\t{time.monotonic_ns()-start}")
    return list(csv.DictReader(rows, delimiter="\t"))

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("java_rows", type=Path)
    parser.add_argument("--d4", type=Path, help="rerun independent solver on all seven models")
    args = parser.parse_args()
    rows, deterministic = read_java(args.java_rows)
    if read_java(HERE / "java-results.tsv")[1] != deterministic:
        raise ValueError("Java arm/results/work or original-source audit differs")
    with (HERE / "d4-results.tsv").open() as stream:
        recorded = list(csv.DictReader(stream, delimiter="\t"))
    if args.d4:
        rerun = external(args.d4)
        keys = ["model", "status", "count"]
        if [[row[k] for k in keys] for row in recorded] != [[row[k] for k in keys] for row in rerun]:
            raise ValueError("Independent d4 result differs")
    counts = {r["model"]: r["count"] for r in recorded if r["status"] == "EXACT"}
    for row in rows:
        if row[0] == "RUN" and row[6] == "EXACT":
            if row[8] != counts.get(row[2]) or int(row[10]) == 0:
                raise ValueError("External count or audit missing for " + row[2])
    print("Verified study rows and all successful counts against the pinned d4 control.")

if __name__ == "__main__":
    main()
