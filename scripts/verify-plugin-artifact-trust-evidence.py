#!/usr/bin/env python3
"""Validate retained plugin artifact trust evidence from a plain checkout."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import sys
from pathlib import Path

from jsonschema.exceptions import ValidationError
from jsonschema.validators import validator_for


def fail(message: str) -> None:
    raise RuntimeError(message)


def require(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


def load(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"cannot read {path}: {error}")


def sha256(value: bytes) -> str:
    return "sha256:" + hashlib.sha256(value).hexdigest()


def validate(root: Path) -> None:
    schema_paths = [
        root / "docs/schemas/regelsuche-plugin-signature-v1.schema.json",
        root / "docs/schemas/regelsuche-plugin-trust-store-v1.schema.json",
        root / "docs/schemas/regelsuche-plugin-artifact-verification-v1.schema.json",
        root / "docs/schemas/regelsuche-plugin-artifact-gate-v1.schema.json",
    ]
    validators = {}
    for path in schema_paths:
        schema = load(path)
        validator_for(schema).check_schema(schema)
        require(
            schema.get("additionalProperties") is False,
            f"schema does not reject additional properties: {path}",
        )
        validators[schema["$id"]] = validator_for(schema)(schema)

    report_dir = root / "app/build/reports/plugin-artifact-trust"
    instances = {
        "regelsuche.plugin-signature/v1": load(report_dir / "signature-manifest.json"),
        "regelsuche.plugin-trust-store/v1": load(report_dir / "trust-store.json"),
        "regelsuche.plugin-artifact-verification/v1": load(
            report_dir / "verification.json"
        ),
        "regelsuche.plugin-artifact-gate/v1": load(report_dir / "gate-result.json"),
    }
    for schema_id, instance in instances.items():
        validators[schema_id].validate(instance)

    verification = instances["regelsuche.plugin-artifact-verification/v1"]
    require(
        verification["status"] == "VERIFIED_TRUSTED",
        "canonical verification is not VERIFIED_TRUSTED",
    )
    require(
        verification["signatureVerified"] is True,
        "canonical verification did not verify the signature",
    )
    require(
        verification["trusted"] is True,
        "canonical verification is not trusted",
    )

    gate = instances["regelsuche.plugin-artifact-gate/v1"]
    require(gate["policy"] == "REQUIRE_VERIFIED", "gate policy drift")
    require(
        gate["admittedArtifacts"] == ["reference-plugin.jar"],
        "canonical admitted artifact set drift",
    )
    require(gate["blockedArtifacts"] == [], "canonical gate unexpectedly blocked artifacts")
    accounted = sorted(gate["admittedArtifacts"] + gate["blockedArtifacts"])
    expected = sorted(item["artifactFileName"] for item in gate["verifications"])
    require(accounted == expected, "gate artifact accounting does not balance")
    require(gate["verifications"] == [verification], "gate verification embedding drift")

    trust_bytes = (report_dir / "trust-store.json").read_bytes()
    require(
        gate["trustStoreHash"] == sha256(trust_bytes),
        "gate trust-store hash does not match retained bytes",
    )

    gate_payload = {
        "schema": gate["schema"],
        "policy": gate["policy"],
        "trustStoreHash": gate["trustStoreHash"],
        "verifications": gate["verifications"],
        "admittedArtifacts": gate["admittedArtifacts"],
        "blockedArtifacts": gate["blockedArtifacts"],
    }
    gate_bytes = json.dumps(
        gate_payload,
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode("utf-8")
    require(
        gate["contentHash"] == sha256(gate_bytes),
        "gate content hash does not match canonical payload",
    )

    invalid_verification = copy.deepcopy(verification)
    invalid_verification["trusted"] = False
    for schema_id, invalid_instance in (
        ("regelsuche.plugin-artifact-verification/v1", invalid_verification),
        (
            "regelsuche.plugin-artifact-gate/v1",
            {**gate, "verifications": [invalid_verification]},
        ),
    ):
        try:
            validators[schema_id].validate(invalid_instance)
        except ValidationError:
            pass
        else:
            fail(f"{schema_id} accepted signatureVerified=true with trusted=false")

    oversized = copy.deepcopy(verification)
    oversized.update(
        {
            "artifactSha256": "",
            "manifestFileName": "",
            "status": "ARTIFACT_TOO_LARGE",
            "signaturePresent": False,
            "signatureVerified": False,
            "trusted": False,
            "publisherId": "",
            "keyId": "",
            "warnings": ["ARTIFACT_EXCEEDS_MAX_BYTES"],
        }
    )
    validators["regelsuche.plugin-artifact-verification/v1"].validate(oversized)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("."))
    args = parser.parse_args()
    try:
        validate(args.root.resolve())
    except (RuntimeError, ValidationError, OSError, KeyError, TypeError, ValueError) as error:
        print(f"plugin artifact trust evidence invalid: {error}", file=sys.stderr)
        return 1
    print("plugin-artifact-trust-evidence=valid")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
