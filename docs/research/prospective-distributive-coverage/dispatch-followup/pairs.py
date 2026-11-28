"""Bind all trial rows to the frozen witness/output dictionaries and reconcile passive dispatch counts."""
import csv
import json
import sys
from pathlib import Path


def json_rows(path):
    return [json.loads(line) for line in path.read_text().splitlines()]


def write_rows(path, fields, rows):
    with path.open("w", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def key(row):
    return (row["arm"], row["family"], int(row["n"]), int(row["budget"]))


def load_raw(directory, outputs, paths):
    converted = []
    for row in json_rows(directory / "rows.jsonl"):
        row["output"] = outputs[row["output"]]
        row["path"] = paths[row["path"]]
        converted.append({k: str(v) for k, v in row.items()})
    return converted


def main(frozen, diagnostic, log, trial, out):
    out.mkdir(exist_ok=True)
    outputs = {s: i for i, s in enumerate(json.loads((frozen / "outputs.json").read_text()))}
    paths = {r["hash"]: r["id"] for r in json_rows(frozen / "paths.jsonl")}
    with (frozen / "rows.csv").open() as stream:
        old = {key(row): row for row in csv.DictReader(stream)}
    observed = load_raw(diagnostic, outputs, paths)
    assert len(old) == len(observed) == 1224
    assert {key(row): row for row in observed} == old, "diagnostic changed a frozen row"

    rows = load_raw(trial, outputs, paths)
    assert len(rows) == 1224 and set(map(key, rows)) == set(old)
    pairs = []
    for row in sorted(rows, key=key):
        before = old[key(row)]
        if row["arm"] != "LEARNED":
            assert row == before, "control differed"
            continue
        pair = {"originalWork": before["work"], **row}
        pairs.append(pair)
    assert len(pairs) == 408
    write_rows(out / "learned-pairs.csv", pairs[0].keys(), pairs)

    # The DROP arm invokes the primitive-provider search directly, bypassing solve().
    expected = [row for row in observed if row["budget"] == "200000" and row["arm"] != "DROP"]
    counts = []
    for line in log.read_text().splitlines():
        if line.startswith("DISPATCH_CASE "):
            row = expected[len(counts)]
            counts.append(dict(arm=row["arm"], family=row["family"], n=row["n"], attempts=0,
                               failures=0, failedMatcherWork=0))
        elif line.startswith("DISPATCH_MATCH "):
            _, matched, work = line.split()
            current = counts[-1]
            current["attempts"] += 1
            if matched == "false":
                current["failures"] += 1
                current["failedMatcherWork"] += int(work)
    assert len(counts) == len(expected) == 48
    assert all(row["attempts"] == 0 for row in counts if row["arm"] == "BASE")
    learned = [r for r in counts if r["arm"] == "LEARNED"]
    write_rows(out / "reference-dispatch.csv", learned[0].keys(), learned)


if __name__ == "__main__":
    main(*(Path(arg) for arg in sys.argv[1:]))
