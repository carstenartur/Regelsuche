#!/usr/bin/env python3
"""Validate and summarize the claim-bounded paper foundation."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any


def canonical_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")


def sha256_bytes(value: bytes) -> str:
    return "sha256:" + hashlib.sha256(value).hexdigest()


def load_unique(path: Path) -> Any:
    def pairs(values: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in values:
            if key in result:
                raise ValueError(f"duplicate JSON field {key!r} in {path}")
            result[key] = value
        return result

    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle, object_pairs_hook=pairs)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--schema", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    root = Path.cwd()
    manifest = load_unique(args.manifest)
    schema = load_unique(args.schema)

    try:
        from jsonschema import Draft202012Validator
    except ImportError as exc:  # pragma: no cover - operational failure
        raise SystemExit(
            "jsonschema is required to verify the paper foundation"
        ) from exc

    Draft202012Validator.check_schema(schema)
    Draft202012Validator(schema).validate(manifest)

    for item in manifest["files"]:
        path = root / item["path"]
        if not path.is_file():
            raise SystemExit(f"missing paper source: {item['path']}")
        actual = sha256_bytes(path.read_bytes())
        if actual != item["sha256"]:
            raise SystemExit(f"paper source hash mismatch: {item['path']}")

    without_hash = dict(manifest)
    without_hash.pop("contentHash")
    expected = sha256_bytes(canonical_bytes(without_hash))
    if expected != manifest["contentHash"]:
        raise SystemExit("paper artifact manifest contentHash mismatch")

    claims = (root / manifest["claimRegistry"]).read_text(encoding="utf-8")
    limitations = (root / manifest["limitations"]).read_text(encoding="utf-8")
    manuscript = (root / manifest["manuscript"]).read_text(encoding="utf-8")

    required_tokens = [
        "PENDING_383",
        "PENDING_235",
        "PENDING_384",
        "PENDING_387",
        "NOT_AUTHORIZED",
    ]
    for token in required_tokens:
        if token not in claims:
            raise SystemExit(f"claim registry is missing {token}")
    for phrase in [
        "Project-internal novelty",
        "Automated tools",
        "Byte-identical local and container runs",
    ]:
        if phrase not in limitations:
            raise SystemExit(f"limitations are missing required section: {phrase}")
    if "Primary tables and figures are intentionally absent" not in manuscript:
        raise SystemExit("foundation manuscript must not imply final results")

    args.output.mkdir(parents=True, exist_ok=True)
    summary = {
        "schema": "regelsuche.paper-foundation-verification/v1",
        "manifestHash": manifest["contentHash"],
        "status": manifest["status"],
        "requiredEvidenceIssues": sorted(
            item["issue"] for item in manifest["requiredEvidence"]
        ),
        "centralClaimsPending": True,
        "externalNoveltyAuthorized": False,
    }
    (args.output / "foundation-verification.json").write_text(
        json.dumps(
            summary,
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        ) + "\n",
        encoding="utf-8",
    )
    (args.output / "foundation-summary.md").write_text(
        "# Paper foundation verification\n\n"
        f"- Status: `{manifest['status']}`\n"
        f"- Manifest: `{manifest['contentHash']}`\n"
        "- Central empirical claims: pending preregistered evidence\n"
        "- External mathematical novelty: not authorized\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
