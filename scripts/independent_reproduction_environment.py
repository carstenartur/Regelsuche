"""Fixed identities for the independent-reproduction toolchain transition."""

from hashlib import sha256
import json
from pathlib import Path

ARTIFACT_V1 = "regelsuche.independent-reproduction-artifact/v1"
ARTIFACT_V2 = "regelsuche.independent-reproduction-artifact/v2"
RECEIPT_V1 = "regelsuche.independent-reproduction-receipt/v1"
RECEIPT_V2 = "regelsuche.independent-reproduction-receipt/v2"
ENVIRONMENT_MODULE = "scripts/independent_reproduction_environment.py"
DESCRIPTOR_SOURCE = "reproduction/environments/v2/environment.json"
DESCRIPTOR_BUNDLE = "environment/environment.json"
DESCRIPTOR_SHA256 = (
    "sha256:5bef9ba990903fc48eea7aa18955bc7d87d16c1278712459285a9f4138cab348"
)
DOCKERFILE_V2 = "reproduction/environments/v2/Dockerfile.reproduction"
ARTIFACT_SCHEMA_V2 = (
    "docs/schemas/regelsuche-independent-reproduction-artifact-v2.schema.json"
)
RECEIPT_SCHEMA_V2 = (
    "docs/schemas/regelsuche-independent-reproduction-receipt-v2.schema.json"
)


def checked_descriptor(data: bytes) -> dict:
    if "sha256:" + sha256(data).hexdigest() != DESCRIPTOR_SHA256:
        raise ValueError("v2 reproduction environment descriptor bytes differ")
    return json.loads(data)


def repository_descriptor(repository: Path) -> dict:
    path = repository / DESCRIPTOR_SOURCE
    if path.is_symlink() or not path.is_file():
        raise ValueError("v2 reproduction environment descriptor is missing or unsafe")
    return checked_descriptor(path.read_bytes())
