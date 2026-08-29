#!/usr/bin/env python3
"""Merge four validated SymPy JMH shards into the unchanged full input file."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from sympy_factorization_jmh_shards import (
    ShardValidationError,
    merge_shards,
)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--shard-directory", required=True, type=Path)
    parser.add_argument("--result-output", required=True, type=Path)
    parser.add_argument("--manifest-output", required=True, type=Path)
    args = parser.parse_args()

    try:
        merged, manifest = merge_shards(args.shard_directory)
    except ShardValidationError as error:
        raise SystemExit(f"SymPy JMH shard merge invalid: {error}") from error

    args.result_output.parent.mkdir(parents=True, exist_ok=True)
    args.manifest_output.parent.mkdir(parents=True, exist_ok=True)
    args.result_output.write_text(
        json.dumps(merged, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    args.manifest_output.write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(
        "sympy-jmh-shards-merged="
        f"{manifest['entryCount']} "
        f"sha256={manifest['mergedCanonicalSha256']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
