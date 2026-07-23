#!/usr/bin/env python3
"""Rewrite one JSON document with deterministic object-key ordering."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


class CanonicalizationError(RuntimeError):
    pass


def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise CanonicalizationError(f"duplicate JSON field: {key}")
        result[key] = value
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    arguments = parser.parse_args()

    source = arguments.input
    target = arguments.output
    if not source.is_file() or source.is_symlink():
        raise CanonicalizationError(
            f"expected regular non-symbolic input file: {source}"
        )
    value = json.loads(
        source.read_text(encoding="utf-8"),
        object_pairs_hook=unique_object,
    )
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(
        json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"canonicalJson={target.resolve()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
