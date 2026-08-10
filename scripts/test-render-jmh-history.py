#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
import subprocess
import sys
import tempfile
from pathlib import Path


def write_json(path: Path, payload: object) -> str:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    return "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest()


def snapshot(label: str, recorded: str, revision: str, score_a: float, score_b: float) -> dict:
    return {
        "schema": "regelsuche.quality.jmh-history-snapshot/v1",
        "label": label,
        "recordedAt": recorded,
        "sourceRevision": revision,
        "sourceArtifactDigest": "sha256:" + "1" * 64,
        "execution": {
            "mode": "avgt",
            "forks": 1,
            "threads": 1,
            "warmupIterations": 2,
            "measurementIterations": 3,
            "jmhVersion": "1.36",
            "jdkMajor": 21,
        },
        "benchmarks": [
            {
                "benchmark": "example.Fast.us",
                "family": "CORE",
                "unit": "us/op",
                "score": score_a,
                "scoreError": 0.2,
            },
            {
                "benchmark": "example.Search.ms",
                "family": "SEARCH",
                "unit": "ms/op",
                "score": score_b,
                "scoreError": 0.1,
            },
        ],
    }


def run(script: Path, root: Path, output: str = "out") -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [
            sys.executable,
            str(script),
            "--history-policy",
            str(root / "config/quality/jmh-history-policy.json"),
            "--regression-policy",
            str(root / "config/quality/jmh-regression-policy-v2.json"),
            "--output-dir",
            str(root / output),
        ],
        text=True,
        capture_output=True,
        check=False,
    )


def build_fixture(root: Path) -> tuple[Path, Path]:
    first = root / "config/quality/jmh-history/first.json"
    second = root / "config/quality/jmh-history/second.json"
    first_digest = write_json(first, snapshot("first", "2026-01-01T00:00:00Z", "a" * 40, 1000, 4.0))
    second_digest = write_json(second, snapshot("second", "2026-02-01T00:00:00Z", "b" * 40, 800, 3.0))
    write_json(
        root / "config/quality/jmh-regression-policy-v2.json",
        {
            "benchmarks": [
                {"benchmark": "example.Fast.us", "family": "CORE", "unit": "us/op"},
                {"benchmark": "example.Search.ms", "family": "SEARCH", "unit": "ms/op"},
            ]
        },
    )
    write_json(
        root / "config/quality/jmh-history-policy.json",
        {
            "schema": "regelsuche.quality.jmh-history-policy/v1",
            "normalizedUnit": "ms/op",
            "lowerIsBetter": True,
            "claimBoundary": "synthetic fixture",
            "snapshots": [
                {"path": "config/quality/jmh-history/first.json", "sha256": first_digest},
                {"path": "config/quality/jmh-history/second.json", "sha256": second_digest},
            ],
        },
    )
    return first, second


def digest_tree(path: Path) -> dict[str, str]:
    return {
        str(file.relative_to(path)): hashlib.sha256(file.read_bytes()).hexdigest()
        for file in sorted(path.rglob("*"))
        if file.is_file()
    }


def expect_failure(script: Path, root: Path, message_fragment: str) -> None:
    result = run(script, root)
    assert result.returncode != 0, result.stdout
    assert message_fragment in result.stderr, result.stderr


def main() -> int:
    script = Path(sys.argv[1]).resolve()
    with tempfile.TemporaryDirectory(prefix="regelsuche-jmh-history-") as directory:
        root = Path(directory)
        first, second = build_fixture(root)

        success = run(script, root, "out-a")
        assert success.returncode == 0, success.stderr
        payload = json.loads((root / "out-a/history.json").read_text())
        assert payload["status"] == "PASSED"
        assert payload["snapshotCount"] == 2
        assert payload["benchmarkCount"] == 2
        fast = next(item for item in payload["benchmarks"] if item["benchmark"] == "example.Fast.us")
        assert fast["points"][0]["scoreMsPerOp"] == 1.0
        assert fast["points"][1]["scoreMsPerOp"] == 0.8
        assert len(list((root / "out-a/charts").glob("*.svg"))) == 2
        assert "unten ist schneller/besser" in next((root / "out-a/charts").glob("*.svg")).read_text()

        repeat = run(script, root, "out-b")
        assert repeat.returncode == 0, repeat.stderr
        assert digest_tree(root / "out-a") == digest_tree(root / "out-b")

        # Digest mismatch must fail before rendering.
        original_policy = json.loads((root / "config/quality/jmh-history-policy.json").read_text())
        first.write_text(first.read_text() + " ", encoding="utf-8")
        expect_failure(script, root, "snapshot digest mismatch")
        write_json(first, snapshot("first", "2026-01-01T00:00:00Z", "a" * 40, 1000, 4.0))
        write_json(root / "config/quality/jmh-history-policy.json", original_policy)

        # Missing benchmark must fail closed.
        second_payload = json.loads(second.read_text())
        second_payload["benchmarks"].pop()
        second_digest = write_json(second, second_payload)
        policy = json.loads((root / "config/quality/jmh-history-policy.json").read_text())
        policy["snapshots"][1]["sha256"] = second_digest
        write_json(root / "config/quality/jmh-history-policy.json", policy)
        expect_failure(script, root, "benchmark inventory differs")

        # Non-chronological snapshots must fail.
        _, second = build_fixture(root)
        second_payload = json.loads(second.read_text())
        second_payload["recordedAt"] = "2025-12-01T00:00:00Z"
        second_digest = write_json(second, second_payload)
        policy = json.loads((root / "config/quality/jmh-history-policy.json").read_text())
        policy["snapshots"][1]["sha256"] = second_digest
        write_json(root / "config/quality/jmh-history-policy.json", policy)
        expect_failure(script, root, "strictly chronological")

        # Unit drift against the active policy must fail.
        _, second = build_fixture(root)
        second_payload = json.loads(second.read_text())
        second_payload["benchmarks"][0]["unit"] = "ms/op"
        second_digest = write_json(second, second_payload)
        policy = json.loads((root / "config/quality/jmh-history-policy.json").read_text())
        policy["snapshots"][1]["sha256"] = second_digest
        write_json(root / "config/quality/jmh-history-policy.json", policy)
        expect_failure(script, root, "unit differs")

    print("JMH history renderer characterization passed: deterministic positive case and 4 fail-closed cases")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
