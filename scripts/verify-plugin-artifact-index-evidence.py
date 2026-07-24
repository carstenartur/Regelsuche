#!/usr/bin/env python3
"""Validate retained signed plugin-index and deterministic resolution evidence."""

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
from urllib.parse import urlsplit

from jsonschema import FormatChecker, ValidationError
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
    return json.dumps(
        value,
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode("utf-8")


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


def require_distribution_uri(value: str) -> None:
    parsed = urlsplit(value)
    require(parsed.fragment == "", f"distribution URI contains a fragment: {value}")
    require(parsed.username is None, f"distribution URI contains a username: {value}")
    require(parsed.password is None, f"distribution URI contains a password: {value}")
    scheme = parsed.scheme.lower()
    if scheme == "https":
        require(bool(parsed.hostname), f"HTTPS distribution URI has no hostname: {value}")
    elif scheme == "file":
        require(parsed.netloc == "", f"file URI has a remote authority: {value}")
        require(parsed.path.startswith("/"), f"file URI is not absolute: {value}")
    else:
        fail(f"unsupported distribution URI scheme: {scheme}")


def validate_signature(key: dict, signature: dict) -> None:
    signed_payload = b"".join(
        [
            field("schema", signature["schema"]),
            field("indexId", signature["indexId"]),
            field("revision", signature["revision"]),
            field("indexContentHash", signature["indexContentHash"]),
            field("curatorId", signature["curatorId"]),
            field("keyId", signature["keyId"]),
            field("algorithm", signature["algorithm"]),
        ]
    )
    signature_bytes = base64.b64decode(signature["signatureBase64"], validate=True)
    require(len(signature_bytes) == 64, "Ed25519 signature is not 64 bytes")
    with tempfile.TemporaryDirectory() as temporary:
        temporary_path = Path(temporary)
        key_path = temporary_path / "curator-key.der"
        payload_path = temporary_path / "payload.bin"
        signature_path = temporary_path / "signature.bin"
        key_path.write_bytes(base64.b64decode(key["publicKeyBase64"], validate=True))
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
            "OpenSSL rejected the retained curator signature: "
            + (completed.stderr.strip() or completed.stdout.strip()),
        )


def validate(root: Path) -> None:
    schema_paths = {
        "index": root / "docs/schemas/regelsuche-plugin-artifact-index-v1.schema.json",
        "signature": root
        / "docs/schemas/regelsuche-plugin-artifact-index-signature-v1.schema.json",
        "verification": root
        / "docs/schemas/regelsuche-plugin-artifact-index-verification-v1.schema.json",
        "resolution": root
        / "docs/schemas/regelsuche-plugin-artifact-resolution-v1.schema.json",
    }
    validators = {}
    for name, path in schema_paths.items():
        schema = load(path)
        validator_for(schema).check_schema(schema)
        require(
            schema.get("additionalProperties") is False,
            f"schema does not reject additional properties: {path}",
        )
        validators[name] = validator_for(schema)(
            schema,
            format_checker=FormatChecker(),
        )

    report_dir = root / "app/build/reports/plugin-artifact-index"
    index_path = report_dir / "index.json"
    signature_path = report_dir / "index-signature.json"
    verification_path = report_dir / "index-verification.json"
    trust_store_path = report_dir / "index-trust-store.json"
    index = load(index_path)
    signature = load(signature_path)
    verification = load(verification_path)
    trust_store = load(trust_store_path)
    resolved = load(report_dir / "resolved.json")
    unresolved = load(report_dir / "unresolved.json")

    validators["index"].validate(index)
    validators["signature"].validate(signature)
    validators["verification"].validate(verification)
    validators["resolution"].validate(resolved)
    validators["resolution"].validate(unresolved)

    inconsistent_verification = deepcopy(verification)
    inconsistent_verification["trusted"] = False
    require_rejection(
        validators["verification"],
        inconsistent_verification,
        "verified status without trusted flag",
    )

    missing_dependencies = deepcopy(index)
    del missing_dependencies["entries"][0]["dependencies"]
    require_rejection(
        validators["index"],
        missing_dependencies,
        "entry without dependencies",
    )

    remote_file_uri = deepcopy(index)
    remote_file_uri["entries"][0]["artifactUri"] = (
        "file://remote.example.test/artifacts/not-local.jar"
    )
    require_rejection(validators["index"], remote_file_uri, "remote-host file URI")

    leading_zero_prerelease = deepcopy(index)
    leading_zero_prerelease["entries"][0]["version"] = "1.0.0-alpha.01"
    require_rejection(
        validators["index"],
        leading_zero_prerelease,
        "numeric prerelease with leading zero",
    )

    unsigned_java_plan = deepcopy(resolved)
    java_step = next(
        item for item in unsigned_java_plan["plan"] if item["kind"] == "JAVA_PLUGIN"
    )
    java_step["signatureManifestUri"] = ""
    require_rejection(
        validators["resolution"],
        unsigned_java_plan,
        "Java plan step without signature manifest",
    )

    artifact_ids: set[str] = set()
    coordinates: set[tuple[str, str, str]] = set()
    artifact_hashes: set[str] = set()
    for entry in index["entries"]:
        identity_payload = {key: value for key, value in entry.items() if key != "identityHash"}
        require(
            entry["identityHash"] == sha256(compact(identity_payload)),
            f"artifact identity hash drift: {entry['artifactId']}",
        )
        require(entry["artifactId"] not in artifact_ids, "duplicate artifactId")
        artifact_ids.add(entry["artifactId"])
        coordinate = (entry["kind"], entry["componentId"], entry["version"])
        require(coordinate not in coordinates, f"duplicate coordinate: {coordinate}")
        coordinates.add(coordinate)
        require(entry["artifactSha256"] not in artifact_hashes, "duplicate artifact hash")
        artifact_hashes.add(entry["artifactSha256"])
        require_distribution_uri(entry["artifactUri"])
        require_distribution_uri(entry["provenanceUri"])
        if entry["kind"] == "JAVA_PLUGIN":
            require_distribution_uri(entry["signatureManifestUri"])

    index_payload = {key: value for key, value in index.items() if key != "contentHash"}
    require(
        index["contentHash"] == sha256(compact(index_payload) + b"\n"),
        "index content hash drift",
    )

    require(signature["indexId"] == index["indexId"], "signature/index id mismatch")
    require(signature["revision"] == index["revision"], "signature/index revision mismatch")
    require(
        signature["indexContentHash"] == index["contentHash"],
        "signature/index content-hash mismatch",
    )
    require(signature["curatorId"] == index["curatorId"], "signature/index curator mismatch")
    require(signature["algorithm"] == "Ed25519", "unexpected signature algorithm")
    matching_keys = [
        key
        for key in trust_store["keys"]
        if key["publisherId"] == signature["curatorId"]
        and key["keyId"] == signature["keyId"]
    ]
    require(len(matching_keys) == 1, "curator key is missing or ambiguous")
    key = matching_keys[0]
    require(key["algorithm"] == signature["algorithm"], "curator algorithm mismatch")
    require(key["status"] in {"ACTIVE", "RETIRED"}, "curator key is not trusted")
    validate_signature(key, signature)

    require(verification["indexId"] == index["indexId"], "verification/index id mismatch")
    require(
        verification["revision"] == index["revision"],
        "verification/index revision mismatch",
    )
    require(
        verification["indexContentHash"] == index["contentHash"],
        "verification/index content-hash mismatch",
    )
    require(
        verification["signatureContentHash"] == sha256(signature_path.read_bytes()),
        "verification signature-content hash drift",
    )
    require(
        verification["trustStoreContentHash"] == sha256(trust_store_path.read_bytes()),
        "verification trust-store hash drift",
    )
    require(verification["status"] == "VERIFIED_TRUSTED", "verification status drift")
    require(verification["signatureVerified"] is True, "signature was not verified")
    require(verification["trusted"] is True, "verification is not trusted")
    require(
        verification["curatorId"] == signature["curatorId"],
        "verification curator mismatch",
    )
    require(verification["keyId"] == signature["keyId"], "verification key mismatch")
    verification_material = b"".join(
        [
            field("schema", verification["schema"]),
            field("indexId", verification["indexId"]),
            field("revision", verification["revision"]),
            field("indexContentHash", verification["indexContentHash"]),
            field("signatureContentHash", verification["signatureContentHash"]),
            field("trustStoreContentHash", verification["trustStoreContentHash"]),
            field("status", verification["status"]),
            field("signatureVerified", str(verification["signatureVerified"]).lower()),
            field("trusted", str(verification["trusted"]).lower()),
            field("curatorId", verification["curatorId"]),
            field("keyId", verification["keyId"]),
            struct.pack(">I", len(verification["warnings"])),
            *[field("warning", warning) for warning in verification["warnings"]],
        ]
    )
    require(
        verification["contentHash"] == sha256(verification_material),
        "verification content hash drift",
    )

    for receipt in (resolved, unresolved):
        request = receipt["request"]
        request_payload = {
            key: value for key, value in request.items() if key != "contentHash"
        }
        require(
            request["contentHash"] == sha256(compact(request_payload)),
            "resolution-request content hash drift",
        )
        receipt_payload = {
            key: value for key, value in receipt.items() if key != "contentHash"
        }
        require(
            receipt["contentHash"] == sha256(compact(receipt_payload)),
            "resolution receipt content hash drift",
        )
        require(
            receipt["indexContentHash"] == index["contentHash"],
            "resolution receipt references a different index",
        )
        require(
            receipt["networkAccessStatus"] == "NOT_PERFORMED",
            "resolution performed network access",
        )
        require(
            receipt["installationStatus"] == "NOT_PERFORMED",
            "resolution performed installation",
        )
        require(
            receipt["trustVerificationStatus"] == "NOT_EVALUATED",
            "resolution unexpectedly evaluated artifact trust",
        )
        for step in receipt["plan"]:
            require_distribution_uri(step["artifactUri"])
            require_distribution_uri(step["provenanceUri"])
            if step["kind"] == "JAVA_PLUGIN":
                require_distribution_uri(step["signatureManifestUri"])

    require(resolved["status"] == "RESOLVED", "resolved receipt status drift")
    require(resolved["blockers"] == [], "resolved receipt contains blockers")
    require(bool(resolved["plan"]), "resolved receipt has an empty plan")
    require(
        [item["order"] for item in resolved["plan"]]
        == list(range(1, len(resolved["plan"]) + 1)),
        "resolution plan order is not contiguous",
    )
    require(
        resolved["plan"][-1]["identityHash"] == resolved["rootArtifactIdentityHash"],
        "resolution root identity does not match final plan step",
    )
    require(
        len({(item["kind"], item["componentId"]) for item in resolved["plan"]})
        == len(resolved["plan"]),
        "resolution plan repeats a component",
    )

    require(unresolved["status"] == "UNRESOLVED", "unresolved receipt status drift")
    require(
        unresolved["rootArtifactIdentityHash"] == "",
        "unresolved receipt has a root artifact identity",
    )
    require(unresolved["plan"] == [], "unresolved receipt contains a plan")
    require(bool(unresolved["blockers"]), "unresolved receipt has no blockers")


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
        print(f"plugin artifact index evidence invalid: {error}", file=sys.stderr)
        return 1
    print("plugin-artifact-index-evidence=valid")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
