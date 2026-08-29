#!/usr/bin/env python3
"""Exact inventory and validation helpers for sharded SymPy JMH evidence."""

from __future__ import annotations

import hashlib
import json
import math
from pathlib import Path
from typing import Any

SPECIALIST_PREFIX = (
    "de.regelsuche.math.sympy.SymPyFactorizationBenchmarks."
)
SPECIALIST_METHODS = (
    "cpythonOneShotEndToEnd",
    "graalPyBackendWarm",
    "graalPyEndToEndCold",
    "graalPyEndToEndWarm",
    "nativeBackendWarm",
    "nativeEndToEndWarm",
)
GENERAL_PREFIX = (
    "de.regelsuche.math.sympy.GeneralUnivariateFactorizationBenchmarks."
)
GENERAL_METHODS = (
    "graalPyGeneralBackendWarm",
    "graalPyGeneralEndToEndWarm",
    "nativeGeneralBackendWarm",
    "nativeGeneralEndToEndWarm",
)
GENERAL_SHARDS: dict[str, tuple[str, ...]] = {
    "general-z-small": (
        "z-linear-pair-degree2",
        "z-content-mixed-degree4",
        "z-large-coefficient-degree4",
    ),
    "general-z-structural": (
        "z-eisenstein-irreducible-degree5",
        "z-repeated-degree6",
        "z-sparse-cyclotomic-degree6",
    ),
    "general-q": (
        "q-linear-pair-degree2",
        "q-eisenstein-irreducible-degree4",
        "q-repeated-degree5",
    ),
}
SHARD_IDS = tuple(GENERAL_SHARDS) + ("specialist",)
SCHEMA = "regelsuche.sympy-factorization-jmh-shards/v1"


class ShardValidationError(ValueError):
    """Raised when one shard or the merged inventory is incomplete."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ShardValidationError(message)


def finite_nonnegative(value: Any, label: str) -> float:
    require(
        isinstance(value, (int, float)) and not isinstance(value, bool),
        f"{label} must be numeric",
    )
    numeric = float(value)
    require(
        math.isfinite(numeric) and numeric >= 0.0,
        f"{label} must be finite and nonnegative",
    )
    return numeric


def integer(value: Any, label: str) -> int:
    require(
        isinstance(value, int) and not isinstance(value, bool),
        f"{label} must be an integer",
    )
    return int(value)


def expected_identities(shard_id: str) -> set[tuple[str, str | None]]:
    require(shard_id in SHARD_IDS, f"unsupported shard: {shard_id}")
    if shard_id == "specialist":
        return {
            (SPECIALIST_PREFIX + method, None)
            for method in SPECIALIST_METHODS
        }
    return {
        (GENERAL_PREFIX + method, case_id)
        for method in GENERAL_METHODS
        for case_id in GENERAL_SHARDS[shard_id]
    }


def entry_identity(
    shard_id: str,
    entry: dict[str, Any],
) -> tuple[str, str | None]:
    benchmark = entry.get("benchmark")
    require(isinstance(benchmark, str), "benchmark must be a string")
    require(entry.get("mode") == "avgt", f"{benchmark} must use AverageTime")
    require(entry.get("threads") == 1, f"{benchmark} must use one thread")
    metric = entry.get("primaryMetric")
    require(isinstance(metric, dict), f"{benchmark} has no primaryMetric")
    require(
        metric.get("scoreUnit") == "ms/op",
        f"{benchmark} must report ms/op",
    )
    finite_nonnegative(metric.get("score"), f"{benchmark}.score")
    finite_nonnegative(metric.get("scoreError"), f"{benchmark}.scoreError")
    forks = integer(entry.get("forks"), f"{benchmark}.forks")
    warmups = integer(
        entry.get("warmupIterations"),
        f"{benchmark}.warmupIterations",
    )
    measurements = integer(
        entry.get("measurementIterations"),
        f"{benchmark}.measurementIterations",
    )

    params = entry.get("params")
    if shard_id == "specialist":
        require(
            benchmark.startswith(SPECIALIST_PREFIX),
            f"specialist shard contains undeclared benchmark: {benchmark}",
        )
        method = benchmark.removeprefix(SPECIALIST_PREFIX)
        require(
            method in SPECIALIST_METHODS,
            f"undeclared specialist method: {method}",
        )
        require(
            params in (None, {}),
            f"{benchmark} must not declare benchmark parameters",
        )
        require(
            (forks, warmups, measurements) == (1, 2, 3),
            f"{benchmark} changed the specialist sampling contract",
        )
        return benchmark, None

    require(
        benchmark.startswith(GENERAL_PREFIX),
        f"general shard contains undeclared benchmark: {benchmark}",
    )
    method = benchmark.removeprefix(GENERAL_PREFIX)
    require(
        method in GENERAL_METHODS,
        f"undeclared general method: {method}",
    )
    require(
        isinstance(params, dict) and set(params) == {"caseId"},
        f"{benchmark} must declare only caseId",
    )
    case_id = params["caseId"]
    require(isinstance(case_id, str), f"{benchmark}.caseId must be a string")
    require(
        case_id in GENERAL_SHARDS[shard_id],
        f"{benchmark}/{case_id} belongs to another shard",
    )
    require(
        forks >= 3 and warmups >= 3 and measurements >= 5,
        f"{benchmark}/{case_id} misses the general sampling floor",
    )
    return benchmark, case_id


def sort_key(entry: dict[str, Any]) -> tuple[str, str]:
    params = entry.get("params")
    case_id = params.get("caseId", "") if isinstance(params, dict) else ""
    return str(entry.get("benchmark", "")), str(case_id)


def validate_shard(
    shard_id: str,
    raw: Any,
) -> list[dict[str, Any]]:
    require(shard_id in SHARD_IDS, f"unsupported shard: {shard_id}")
    require(isinstance(raw, list) and raw, f"{shard_id} must be a nonempty array")
    seen: set[tuple[str, str | None]] = set()
    entries: list[dict[str, Any]] = []
    for raw_entry in raw:
        require(isinstance(raw_entry, dict), f"{shard_id} contains a non-object")
        identity = entry_identity(shard_id, raw_entry)
        require(identity not in seen, f"duplicate benchmark/case: {identity}")
        seen.add(identity)
        entries.append(raw_entry)
    expected = expected_identities(shard_id)
    missing = sorted(expected - seen)
    unexpected = sorted(seen - expected)
    require(not missing, f"{shard_id} misses entries: {missing}")
    require(not unexpected, f"{shard_id} has unexpected entries: {unexpected}")
    return sorted(entries, key=sort_key)


def load_and_validate(path: Path, shard_id: str) -> list[dict[str, Any]]:
    require(path.is_file(), f"missing shard file: {path}")
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ShardValidationError(f"cannot read {path}: {error}") from error
    return validate_shard(shard_id, raw)


def merge_shards(directory: Path) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    require(directory.is_dir(), f"missing shard directory: {directory}")
    expected_files = {f"{shard_id}.json" for shard_id in SHARD_IDS}
    actual_files = {path.name for path in directory.glob("*.json")}
    require(
        actual_files == expected_files,
        "shard file set mismatch: "
        f"missing={sorted(expected_files - actual_files)} "
        f"unexpected={sorted(actual_files - expected_files)}",
    )

    merged: list[dict[str, Any]] = []
    manifest_shards: list[dict[str, Any]] = []
    all_seen: set[tuple[str, str | None]] = set()
    for shard_id in SHARD_IDS:
        path = directory / f"{shard_id}.json"
        raw_bytes = path.read_bytes()
        entries = load_and_validate(path, shard_id)
        identities = {entry_identity(shard_id, entry) for entry in entries}
        require(
            not (all_seen & identities),
            f"cross-shard duplicate identities: {sorted(all_seen & identities)}",
        )
        all_seen.update(identities)
        merged.extend(entries)
        manifest_shards.append(
            {
                "shardId": shard_id,
                "entryCount": len(entries),
                "sha256": hashlib.sha256(raw_bytes).hexdigest(),
                "identities": [
                    {"benchmark": benchmark, "caseId": case_id}
                    for benchmark, case_id in sorted(
                        identities,
                        key=lambda value: (value[0], value[1] or ""),
                    )
                ],
            }
        )

    expected_all = set().union(
        *(expected_identities(shard_id) for shard_id in SHARD_IDS)
    )
    require(all_seen == expected_all, "merged benchmark inventory is incomplete")
    merged = sorted(merged, key=sort_key)
    canonical = json.dumps(
        merged,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
    ).encode("utf-8")
    manifest = {
        "schema": SCHEMA,
        "entryCount": len(merged),
        "mergedCanonicalSha256": hashlib.sha256(canonical).hexdigest(),
        "shards": manifest_shards,
    }
    return merged, manifest
