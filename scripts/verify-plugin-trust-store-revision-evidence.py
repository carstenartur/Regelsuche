#!/usr/bin/env python3
"""Validate retained plugin trust-store revision evidence from a plain checkout."""

from __future__ import annotations

import argparse
import base64
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile

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


def compact(value: object) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        + b"\n"
    )


def sha256(value: bytes) -> str:
    return "sha256:" + hashlib.sha256(value).hexdigest()


def field(name: str, value: object) -> bytes:
    name_bytes = name.encode("utf-8")
    value_bytes = str(value).encode("utf-8")
    return (
        struct.pack(">I", len(name_bytes))
        + name_bytes
        + struct.pack(">I", len(value_bytes))
        + value_bytes
    )


def require_rejection(validator, value: object, label: str) -> None:
    try:
        validator.validate(value)
    except ValidationError:
        return
    fail(f"{label} unexpectedly passed schema validation")


def validate_signature(authority_key: dict, revision: dict) -> None:
    signed_payload = b"".join(
        [
            field("schema", revision["schema"]),
            field("trustDomainId", revision["trustDomainId"]),
            field("sequence", revision["sequence"]),
            field("previousRevisionHash", revision["previousRevisionHash"]),
            field("trustStoreContentHash", revision["trustStoreContentHash"]),
            field("authorityId", revision["authorityId"]),
            field("keyId", revision["keyId"]),
            field("algorithm", revision["algorithm"]),
        ]
    )
    signature_bytes = base64.b64decode(revision["signatureBase64"], validate=True)
    require(len(signature_bytes) == 64, "Ed25519 revision signature is not 64 bytes")
    require(
        base64.b64encode(signature_bytes).decode("ascii")
        == revision["signatureBase64"],
        "revision signature is not canonical base64",
    )
    with tempfile.TemporaryDirectory() as temporary:
        temporary_path = Path(temporary)
        key_path = temporary_path / "authority.der"
        payload_path = temporary_path / "payload.bin"
        signature_path = temporary_path / "signature.bin"
        key_path.write_bytes(
            base64.b64decode(authority_key["publicKeyBase64"], validate=True)
        )
        payload_path.write_bytes(signed_payload)
        signature_path.write_bytes(signature_bytes)
        completed = subprocess.run(
            [
                "openssl",
                "pkeyutl",
                "-verify",
                "-pubin",
                "-inkey",
                str(key_path),
                "-keyform",
                "DER",
                "-rawin",
                "-in",
                str(payload_path),
                "-sigfile",
                str(signature_path),
            ],
            check=False,
            capture_output=True,
            text=True,
        )
        require(
            completed.returncode == 0,
            "OpenSSL rejected the retained trust-store revision signature: "
            + (completed.stderr.strip() or completed.stdout.strip()),
        )


def validate(root: Path) -> None:
    schema_paths = {
        "store": root / "docs/schemas/regelsuche-plugin-trust-store-v1.schema.json",
        "revision": root
        / "docs/schemas/regelsuche-plugin-trust-store-revision-v1.schema.json",
        "checkpoint": root
        / "docs/schemas/regelsuche-plugin-trust-store-chain-checkpoint-v1.schema.json",
        "verification": root
        / "docs/schemas/regelsuche-plugin-trust-store-revision-verification-v1.schema.json",
    }
    validators = {}
    for name, path in schema_paths.items():
        schema = load(path)
        validator_for(schema).check_schema(schema)
        require(
            schema.get("additionalProperties") is False,
            f"schema does not reject additional properties: {path}",
        )
        validators[name] = validator_for(schema)(schema)

    report_dir = root / "app/build/reports/plugin-trust-store-revision"
    root_path = report_dir / "root-trust-store.json"
    root_store = load(root_path)
    validators["store"].validate(root_store)
    root_hash = sha256(root_path.read_bytes())

    previous_revision_hash = ""
    previous_checkpoint_hash = ""
    for sequence in (1, 2):
        store_path = report_dir / f"trust-store-{sequence}.json"
        revision_path = report_dir / f"revision-{sequence}.json"
        verification_path = report_dir / f"verification-{sequence}.json"
        checkpoint_path = report_dir / f"checkpoint-{sequence}.json"
        store = load(store_path)
        revision = load(revision_path)
        verification = load(verification_path)
        checkpoint = load(checkpoint_path)
        validators["store"].validate(store)
        validators["revision"].validate(revision)
        validators["verification"].validate(verification)
        validators["checkpoint"].validate(checkpoint)

        require(revision["sequence"] == sequence, "revision sequence drift")
        require(
            revision["previousRevisionHash"] == previous_revision_hash,
            "revision predecessor hash drift",
        )
        require(
            revision["trustStoreContentHash"] == sha256(store_path.read_bytes()),
            "revision trust-store hash drift",
        )
        require(revision["algorithm"] == "Ed25519", "unexpected revision algorithm")
        matching_keys = [
            key
            for key in root_store["keys"]
            if key["publisherId"] == revision["authorityId"]
            and key["keyId"] == revision["keyId"]
        ]
        require(len(matching_keys) == 1, "revision authority key is missing or ambiguous")
        authority_key = matching_keys[0]
        require(
            authority_key["status"] in {"ACTIVE", "RETIRED"},
            "revision authority key is not trusted",
        )
        require(
            authority_key["algorithm"] == revision["algorithm"],
            "revision authority algorithm mismatch",
        )
        validate_signature(authority_key, revision)

        revision_payload = {
            key: revision[key]
            for key in (
                "schema",
                "trustDomainId",
                "sequence",
                "previousRevisionHash",
                "trustStoreContentHash",
                "authorityId",
                "keyId",
                "algorithm",
                "signatureBase64",
            )
        }
        require(
            revision["contentHash"] == sha256(compact(revision_payload)),
            "revision content hash drift",
        )

        require(
            verification["trustDomainId"] == revision["trustDomainId"],
            "verification trust-domain mismatch",
        )
        require(verification["sequence"] == sequence, "verification sequence drift")
        require(
            verification["revisionHash"] == revision["contentHash"],
            "verification revision hash drift",
        )
        require(
            verification["previousRevisionHash"] == previous_revision_hash,
            "verification predecessor hash drift",
        )
        require(
            verification["trustStoreContentHash"]
            == revision["trustStoreContentHash"],
            "verification trust-store hash drift",
        )
        require(
            verification["rootTrustStoreContentHash"] == root_hash,
            "verification root trust-store hash drift",
        )
        require(
            verification["previousCheckpointHash"] == previous_checkpoint_hash,
            "verification checkpoint predecessor drift",
        )
        require(
            verification["status"] == "VERIFIED_TRUSTED",
            "verification status drift",
        )
        require(
            verification["signatureVerified"] is True,
            "revision signature was not verified",
        )
        require(verification["trusted"] is True, "revision is not trusted")
        require(verification["replaySafe"] is True, "revision is not replay-safe")
        require(
            verification["authorityId"] == revision["authorityId"],
            "verification authority mismatch",
        )
        require(
            verification["keyId"] == revision["keyId"],
            "verification key mismatch",
        )
        verification_material = b"".join(
            [
                field("schema", verification["schema"]),
                field("trustDomainId", verification["trustDomainId"]),
                field("sequence", verification["sequence"]),
                field("revisionHash", verification["revisionHash"]),
                field(
                    "previousRevisionHash", verification["previousRevisionHash"]
                ),
                field(
                    "trustStoreContentHash", verification["trustStoreContentHash"]
                ),
                field(
                    "rootTrustStoreContentHash",
                    verification["rootTrustStoreContentHash"],
                ),
                field(
                    "previousCheckpointHash", verification["previousCheckpointHash"]
                ),
                field("status", verification["status"]),
                field("signatureVerified", "true"),
                field("trusted", "true"),
                field("replaySafe", "true"),
                field("authorityId", verification["authorityId"]),
                field("keyId", verification["keyId"]),
                struct.pack(">I", len(verification["warnings"])),
                b"".join(
                    field("warning", warning)
                    for warning in verification["warnings"]
                ),
            ]
        )
        require(
            verification["contentHash"] == sha256(verification_material),
            "verification content hash drift",
        )

        checkpoint_payload = {
            key: checkpoint[key]
            for key in ("schema", "trustDomainId", "sequence", "revisionHash")
        }
        require(
            checkpoint["trustDomainId"] == revision["trustDomainId"],
            "checkpoint trust-domain mismatch",
        )
        require(checkpoint["sequence"] == sequence, "checkpoint sequence drift")
        require(
            checkpoint["revisionHash"] == revision["contentHash"],
            "checkpoint revision hash drift",
        )
        require(
            checkpoint["contentHash"] == sha256(compact(checkpoint_payload)),
            "checkpoint content hash drift",
        )

        previous_revision_hash = revision["contentHash"]
        previous_checkpoint_hash = checkpoint["contentHash"]

    invalid_genesis = deepcopy(load(report_dir / "revision-1.json"))
    invalid_genesis["previousRevisionHash"] = "sha256:" + "0" * 64
    require_rejection(
        validators["revision"],
        invalid_genesis,
        "genesis revision with predecessor",
    )
    invalid_successor = deepcopy(load(report_dir / "revision-2.json"))
    invalid_successor["previousRevisionHash"] = ""
    require_rejection(
        validators["revision"],
        invalid_successor,
        "non-genesis revision without predecessor",
    )
    invalid_flags = deepcopy(load(report_dir / "verification-2.json"))
    invalid_flags["replaySafe"] = False
    require_rejection(
        validators["verification"],
        invalid_flags,
        "trusted revision without replay-safe flag",
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("."))
    args = parser.parse_args()
    try:
        validate(args.root.resolve())
    except (
        RuntimeError,
        ValidationError,
        OSError,
        KeyError,
        TypeError,
        ValueError,
        subprocess.SubprocessError,
    ) as error:
        print(f"plugin trust-store revision evidence invalid: {error}", file=sys.stderr)
        return 1
    print("plugin-trust-store-revision-evidence=valid")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
