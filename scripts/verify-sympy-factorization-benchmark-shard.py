#!/usr/bin/env python3
"""Validate one exact SymPy JMH shard and write a content-addressed stamp."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

from sympy_factorization_jmh_shards import (
    SCHEMA,
    SHARD_IDS,
    ShardValidationError,
    load_and_validate,
)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--shard", required=True, choices=SHARD_IDS)
    parser.add_argument("--result", required=True, type=Path)
    parser.add_argument("--stamp", required=True, type=Path)
    args = parser.parse_args()

    try:
        entries = load_and_validate(args.result, args.shard)
    except ShardValidationError as error:
        raise SystemExit(f"SymPy JMH shard invalid: {error}") from error

    raw_bytes = args.result.read_bytes()
    canonical = json.dumps(
        entries,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
    ).encode("utf-8")
    stamp = {
        "schema": SCHEMA + ".validation",
        "shardId": args.shard,
        "entryCount": len(entries),
        "sourceSha256": hashlib.sha256(raw_bytes).hexdigest(),
        "canonicalSha256": hashlib.sha256(canonical).hexdigest(),
    }
    args.stamp.parent.mkdir(parents=True, exist_ok=True)
    args.stamp.write_text(
        json.dumps(stamp, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(
        f"sympy-jmh-shard={args.shard} entries={len(entries)} "
        f"sha256={stamp['sourceSha256']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
