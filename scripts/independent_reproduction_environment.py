"""Fixed identities for the independent-reproduction toolchain transition."""

from hashlib import sha256
import json
from pathlib import Path

ARTIFACT_V1 = "regelsuche.independent-reproduction-artifact/v1"
ARTIFACT_V2 = "regelsuche.independent-reproduction-artifact/v2"
ARTIFACT_V3 = "regelsuche.independent-reproduction-artifact/v3"
RECEIPT_V1 = "regelsuche.independent-reproduction-receipt/v1"
RECEIPT_V2 = "regelsuche.independent-reproduction-receipt/v2"
RECEIPT_V3 = "regelsuche.independent-reproduction-receipt/v3"
RECEIPT_BY_ARTIFACT = {
    ARTIFACT_V1: RECEIPT_V1,
    ARTIFACT_V2: RECEIPT_V2,
    ARTIFACT_V3: RECEIPT_V3,
}
ENVIRONMENT_MODULE = "scripts/independent_reproduction_environment.py"
DESCRIPTOR_SOURCE = "reproduction/environments/v3/environment.json"
DESCRIPTOR_BUNDLE = "environment/environment.json"
DESCRIPTOR_SHA256 = (
    "sha256:a6694702c8abe3d4ff1ac2008c4b2f6bc8dc806e0dc5cfb849d02ae954a83fba"
)
DOCKERFILE_V2 = "reproduction/environments/v2/Dockerfile.reproduction"
ARTIFACT_SCHEMA_V2 = (
    "docs/schemas/regelsuche-independent-reproduction-artifact-v2.schema.json"
)
RECEIPT_SCHEMA_V2 = (
    "docs/schemas/regelsuche-independent-reproduction-receipt-v2.schema.json"
)
DOCKERFILE_V3 = "reproduction/environments/v3/Dockerfile.reproduction"
ARTIFACT_SCHEMA_V3 = (
    "docs/schemas/regelsuche-independent-reproduction-artifact-v3.schema.json"
)
RECEIPT_SCHEMA_V3 = (
    "docs/schemas/regelsuche-independent-reproduction-receipt-v3.schema.json"
)
ENVIRONMENT_CONTRACTS = {
    ARTIFACT_V2: {
        "descriptorSource": "reproduction/environments/v2/environment.json",
        "descriptorSha256": "sha256:5bef9ba990903fc48eea7aa18955bc7d87d16c1278712459285a9f4138cab348",
        "dockerfile": DOCKERFILE_V2,
        "artifactSchemaSource": ARTIFACT_SCHEMA_V2,
        "receiptSchemaSource": RECEIPT_SCHEMA_V2,
    },
    ARTIFACT_V3: {
        "descriptorSource": DESCRIPTOR_SOURCE,
        "descriptorSha256": DESCRIPTOR_SHA256,
        "dockerfile": DOCKERFILE_V3,
        "artifactSchemaSource": ARTIFACT_SCHEMA_V3,
        "receiptSchemaSource": RECEIPT_SCHEMA_V3,
    },
}


def environment_contract(artifact_schema: str) -> dict:
    try:
        return ENVIRONMENT_CONTRACTS[artifact_schema]
    except KeyError as error:
        raise ValueError(f"unsupported versioned reproduction environment: {artifact_schema}") from error


def checked_descriptor(data: bytes, artifact_schema: str = ARTIFACT_V3) -> dict:
    contract = environment_contract(artifact_schema)
    if "sha256:" + sha256(data).hexdigest() != contract["descriptorSha256"]:
        version = artifact_schema.rsplit("/", 1)[1]
        raise ValueError(f"{version} reproduction environment descriptor bytes differ")
    return json.loads(data)


def repository_descriptor(repository: Path, artifact_schema: str = ARTIFACT_V3) -> dict:
    path = repository / environment_contract(artifact_schema)["descriptorSource"]
    if path.is_symlink() or not path.is_file():
        version = artifact_schema.rsplit("/", 1)[1]
        raise ValueError(f"{version} reproduction environment descriptor is missing or unsafe")
    return checked_descriptor(path.read_bytes(), artifact_schema)
