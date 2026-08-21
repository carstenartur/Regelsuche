#!/usr/bin/env python3
"""Verify byte-identical held-out evidence from two host runs and one container."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator

FILES = (
    "target-free-held-out-candidate-freeze.json",
    "target-free-held-out-plan.json",
    "target-free-held-out-post-freeze-qualification.json",
)
BASE_IMAGE = (
    "eclipse-temurin:25.0.3_9-jdk-noble@"
    "sha256:3eb81ed94d8c1a34422f19f8188548bdf02cae69c91d0328afdbb7abed90f617"
)
BASE_DIGEST = BASE_IMAGE.split("@", 1)[1]
SHA = re.compile(r"sha256:[0-9a-f]{64}$")
REVISION = re.compile(r"[0-9a-f]{40}$")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def strict_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        require(key not in result, f"duplicate JSON key: {key}")
        result[key] = value
    return result


def load(path: Path) -> dict[str, Any]:
    require(path.is_file(), f"missing file: {path}")
    value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=strict_object)
    require(isinstance(value, dict), f"JSON root is not an object: {path}")
    return value


def canonical(value: Any) -> bytes:
    return json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")


def digest(value: bytes) -> str:
    return "sha256:" + hashlib.sha256(value).hexdigest()


def run_files(root: Path) -> dict[str, bytes]:
    require(root.is_dir(), f"missing run directory: {root}")
    found = {
        path.relative_to(root).as_posix(): path.read_bytes()
        for path in sorted(root.rglob("*"))
        if path.is_file()
    }
    require(tuple(sorted(found)) == FILES, f"unexpected files under {root}: {sorted(found)}")
    return found


def validate_artifact(value: dict[str, Any], label: str) -> dict[str, Any]:
    require(set(value) == {"content", "contentHash"}, f"invalid {label} envelope")
    content = value["content"]
    require(isinstance(content, dict), f"invalid {label} content")
    require(value["contentHash"] == digest(canonical(content)), f"invalid {label} hash")
    return content


def validate_run(root: Path, revision: str) -> tuple[dict[str, Any], ...]:
    plan_value = load(root / FILES[1])
    freeze_value = load(root / FILES[0])
    qualification_value = load(root / FILES[2])
    plan = validate_artifact(plan_value, "plan")
    freeze = validate_artifact(freeze_value, "freeze")
    qualification = validate_artifact(qualification_value, "qualification")
    for name, content in (("plan", plan), ("freeze", freeze), ("qualification", qualification)):
        require(content.get("repositoryRevision") == revision, f"{name} revision mismatch")
    require(plan.get("qualificationDisclosure") == "NOT_DISCLOSED", "plan leaked qualification")
    require(freeze.get("qualificationDisclosure") == "NOT_DISCLOSED", "freeze leaked qualification")
    require(
        qualification.get("qualificationDisclosure")
        == "DISCLOSED_AFTER_COMPLETE_CANDIDATE_FREEZE",
        "qualification disclosure boundary mismatch",
    )
    plan_rows = plan.get("rows", [])
    freeze_rows = freeze.get("rows", [])
    qualification_rows = qualification.get("rows", [])
    require(len(plan_rows) == len(freeze_rows) == len(qualification_rows) == 144,
            "matrix does not contain 144 rows in every stage")
    ids = [[row.get("configurationId") for row in rows]
           for rows in (plan_rows, freeze_rows, qualification_rows)]
    require(ids[0] == ids[1] == ids[2] and len(set(ids[0])) == 144,
            "matrix identities differ or are duplicated")
    require(freeze.get("planHash") == plan_value["contentHash"], "freeze-plan binding mismatch")
    require(qualification.get("planHash") == plan_value["contentHash"],
            "qualification-plan binding mismatch")
    require(qualification.get("candidateFreezeHash") == freeze_value["contentHash"],
            "qualification-freeze binding mismatch")
    require(len(plan.get("cases", [])) == 6, "case count mismatch")
    require(len(plan.get("policies", [])) == 4, "policy count mismatch")
    require(plan.get("workMatching", {}).get("checkpoints") == [8, 16, 32, 64, 128, 256],
            "checkpoint contract mismatch")
    require(len(freeze.get("matchedWorkGroups", [])) == 36, "matched-work count mismatch")
    require(len(qualification.get("comparisons", [])) == 36, "comparison count mismatch")
    return plan, freeze, qualification


def evidence(files: dict[str, bytes]) -> list[dict[str, Any]]:
    return [
        {"path": name, "byteLength": len(files[name]), "sha256": digest(files[name])}
        for name in FILES
    ]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository-root", type=Path, required=True)
    parser.add_argument("--repository-revision", required=True)
    parser.add_argument("--host-a", type=Path, required=True)
    parser.add_argument("--host-b", type=Path, required=True)
    parser.add_argument("--container-run", type=Path, required=True)
    parser.add_argument("--dockerfile", type=Path, required=True)
    parser.add_argument("--image-id-file", type=Path, required=True)
    parser.add_argument("--platform", required=True)
    parser.add_argument("--schema", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        revision = args.repository_revision.strip()
        require(REVISION.fullmatch(revision) is not None, "invalid repository revision")
        roots = [args.host_a.resolve(), args.host_b.resolve(), args.container_run.resolve()]
        byte_sets = [run_files(root) for root in roots]
        parsed = [validate_run(root, revision) for root in roots]
        for name in FILES:
            require(byte_sets[0][name] == byte_sets[1][name], f"host repeat differs: {name}")
            require(byte_sets[0][name] == byte_sets[2][name], f"host/container differs: {name}")
        dockerfile_text = args.dockerfile.read_text(encoding="utf-8")
        first_from = next(line.split()[1] for line in dockerfile_text.splitlines()
                          if line.strip().startswith("FROM "))
        require(first_from == BASE_IMAGE, f"unexpected base image: {first_from}")
        image_id = args.image_id_file.read_text(encoding="utf-8").strip()
        require(SHA.fullmatch(image_id) is not None, f"invalid image ID: {image_id}")
        require(args.platform == "linux/amd64", f"unexpected platform: {args.platform}")
        wrapper = (args.repository_root / "gradle/wrapper/gradle-wrapper.properties")
        wrapper_sha = next(
            line.split("=", 1)[1].strip() for line in wrapper.read_text().splitlines()
            if line.startswith("distributionSha256Sum=")
        )
        artifacts = evidence(byte_sets[0])
        artifact_set = digest(canonical(artifacts))
        plan, freeze, qualification = parsed[0]
        manifest_body = {
            "schema": "regelsuche.target-free-held-out-container-reproduction/v1",
            "evidenceStatus": "HOST_REPEAT_AND_PINNED_CONTAINER_BYTE_IDENTICAL",
            "repository": "carstenartur/Regelsuche",
            "repositoryRevision": revision,
            "matrix": {
                "caseCount": len(plan["cases"]),
                "policyCount": len(plan["policies"]),
                "checkpointCount": len(plan["workMatching"]["checkpoints"]),
                "configuredRows": len(plan["rows"]),
                "matchedWorkGroups": len(freeze["matchedWorkGroups"]),
                "exactCheckpointRows": freeze["summary"]["exactCheckpointRows"],
                "eligibleMatchedWorkGroups": freeze["summary"]["eligibleMatchedWorkGroups"],
                "qualifiedPositiveRows": qualification["summary"]["qualifiedPositiveRows"],
                "qualifiedCandidates": qualification["summary"]["qualifiedCandidates"],
                "negativeControlPassedRows": qualification["summary"]["negativeControlPassedRows"],
                "negativeControlViolations": qualification["summary"]["negativeControlViolations"],
            },
            "container": {
                "definitionPath": args.dockerfile.resolve().relative_to(
                    args.repository_root.resolve()).as_posix(),
                "definitionSha256": digest(args.dockerfile.read_bytes()),
                "baseImage": BASE_IMAGE,
                "baseImageDigest": BASE_DIGEST,
                "builtImageId": image_id,
                "platform": args.platform,
                "runtimeUser": "reproducer:10001",
                "buildNetworkPolicy": "DEPENDENCY_RESOLUTION_ONLY",
                "evaluatedRunNetworkPolicy": "DISABLED",
                "gradleDistributionSha256": wrapper_sha,
            },
            "runs": [
                {"id": "HOST_A", "environment": "CHECKOUT_JVM", "artifactSetHash": artifact_set},
                {"id": "HOST_B", "environment": "CHECKOUT_JVM", "artifactSetHash": artifact_set},
                {"id": "PINNED_CONTAINER", "environment": "DIGEST_PINNED_LINUX_CONTAINER", "artifactSetHash": artifact_set},
            ],
            "artifacts": artifacts,
            "comparison": {
                "hostRepeat": "BYTE_IDENTICAL",
                "hostContainer": "BYTE_IDENTICAL",
                "expectedFiles": list(FILES),
            },
            "claimBoundary": (
                "Exact byte reproduction for one frozen repository revision in two checkout JVM runs "
                "and one digest-pinned Linux container; no claim of external mathematical novelty, "
                "global optimality, universal policy superiority, cross-platform reproduction or equal CPU cost."
            ),
        }
        manifest = dict(manifest_body)
        manifest["contentHash"] = digest(canonical(manifest_body))
        schema = load(args.schema)
        Draft202012Validator.check_schema(schema)
        errors = list(Draft202012Validator(schema).iter_errors(manifest))
        require(not errors, "; ".join(error.message for error in errors))
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_bytes(canonical(manifest) + b"\n")
    except (RuntimeError, OSError, ValueError, KeyError, StopIteration, json.JSONDecodeError) as error:
        print(f"held-out container reproduction invalid: {error}", file=sys.stderr)
        return 1
    print("target-free-held-out-reproduction=HOST_REPEAT_AND_PINNED_CONTAINER_BYTE_IDENTICAL")
    print(f"target-free-held-out-artifact-set={artifact_set}")
    print(f"target-free-held-out-container-image={image_id}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
