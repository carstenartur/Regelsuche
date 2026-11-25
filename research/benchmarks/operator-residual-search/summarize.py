#!/usr/bin/env python3
"""Summarize fork medians without treating measurement blocks as independent forks."""
import csv
import statistics
import sys
from pathlib import Path

source = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).with_name("observations.tsv")
with source.open(encoding="utf-8") as stream:
    rows = list(csv.DictReader(stream, delimiter="\t"))
writer = csv.writer(sys.stdout, delimiter="\t", lineterminator="\n")
writer.writerow(["family", "arm", "median_us", "min_fork_median_us", "max_fork_median_us", "median_bytes"])
for family in ["center8", "center16", "diagonal8", "dense8"]:
    for arm in ["acquire", "selected", "directChecked", "directArithmetic", "specializedArithmetic"]:
        times, allocations = [], []
        for fork in ["1", "2", "3"]:
            selected = [r for r in rows if (r["family"], r["arm"], r["fork"]) == (family, arm, fork)]
            if len(selected) != 7:
                raise ValueError(f"expected seven blocks: {family}/{arm}/{fork}")
            times.append(statistics.median(float(r["ns_per_call"]) for r in selected) / 1000)
            allocations.append(statistics.median(float(r["allocated_bytes_per_call"]) for r in selected))
        writer.writerow([family, arm, f"{statistics.median(times):.3f}", f"{min(times):.3f}",
                         f"{max(times):.3f}", f"{statistics.median(allocations):.1f}"])
