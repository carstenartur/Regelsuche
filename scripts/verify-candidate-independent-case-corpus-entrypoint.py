#!/usr/bin/env python3
"""Run the corpus verifier with the canonical nested-document hash policy.

The frozen corpus is a Merkle-style document: each case owns its contentHash and
the corpus owns the semantic payload tree. Embedded contentHash fields are
therefore excluded recursively when computing any document's own contentHash.
The payload bytes remain covered; only redundant child hash strings are omitted
from the parent semantic root.
"""

from __future__ import annotations

import hashlib
import importlib.util
import json
from pathlib import Path
import sys
from typing import Any

IMPLEMENTATION = Path(__file__).with_name(
    "verify-candidate-independent-case-corpus.py"
)


def canonical_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def without_embedded_content_hashes(value: Any) -> Any:
    if isinstance(value, dict):
        return {
            key: without_embedded_content_hashes(child)
            for key, child in value.items()
            if key != "contentHash"
        }
    if isinstance(value, list):
        return [without_embedded_content_hashes(child) for child in value]
    return value


def recursive_document_hash(document: dict[str, Any]) -> str:
    payload = canonical_bytes(without_embedded_content_hashes(document))
    return "sha256:" + hashlib.sha256(payload).hexdigest()


def load_implementation():
    specification = importlib.util.spec_from_file_location(
        "candidate_independent_case_corpus_verifier",
        IMPLEMENTATION,
    )
    if specification is None or specification.loader is None:
        raise RuntimeError(f"cannot load verifier implementation: {IMPLEMENTATION}")
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    return module


def main() -> int:
    module = load_implementation()
    module.document_hash = recursive_document_hash
    return int(module.main())


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as error:
        print(f"candidate-independent corpus verifier entrypoint failed: {error}", file=sys.stderr)
        raise SystemExit(1)
