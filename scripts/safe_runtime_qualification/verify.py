#!/usr/bin/env python3
"""Independently reconstruct the decision, then replay every retained product artifact."""
from __future__ import annotations

import argparse
import importlib.metadata
from pathlib import Path
import tempfile

import jsonschema

from protocol import PROFILES, canonical, derive_report, load_manifest, read_json, require
from runtime import Runtime, compile_plugin, http, physical_identity, verify_inputs


def verify(root: Path, manifest_path: Path, schema: Path, app_home: Path, inputs: Path, revision: str):
    require(importlib.metadata.version("jsonschema") == "4.25.1", "jsonschema 4.25.1 is required")
    manifest = load_manifest(manifest_path.read_bytes())
    raw = (root / "qualification.json").read_bytes()
    supplied = read_json(raw)
    require(raw == canonical(supplied) + b"\n", "qualification report is not canonical")
    jsonschema.Draft202012Validator(read_json(schema.read_bytes())).validate(supplied)
    require(supplied["evidence"]["repositoryRevision"] == revision,
            "repository revision differs from external source authority")
    input_identity = verify_inputs(inputs, manifest)
    with tempfile.TemporaryDirectory(prefix="safe-product-independent-replay-") as temporary:
        working = Path(temporary)
        plugin = compile_plugin(app_home, working)
        physical = physical_identity(app_home, plugin)
        recomputed = derive_report(root, manifest, revision, input_identity, physical)
        require(supplied == recomputed, "qualification differs from independently reconstructed observations")
        require(recomputed["evidence"]["status"] == "QUALIFIED_BOUNDED_PUBLIC_CASES", "public qualification did not satisfy fixed criteria")
        runtime = Runtime(app_home, inputs, working)
        with runtime.server() as url:
            for case in manifest["cases"]:
                for profile in PROFILES:
                    artifact = (root / "artifacts" / case["id"] / profile / "cli-analyze.json").read_bytes()
                    cli = runtime.cli(artifact, True)
                    status, headers, body = http(url, read_json(artifact), True)
                    require(cli.returncode == 0 and cli.stdout == artifact and status == 200 and body == artifact
                            and any(key.lower() == "x-runtime-replay" and value == "VERIFIED" for key, value in headers.items()),
                            f"independent fresh product replay failed for {case['id']}/{profile}")
            for control in recomputed["evidence"]["controls"]:
                if control["kind"] != "FRESH_REPLAY_MUTATION":
                    continue
                artifact = (root / "artifacts/controls" / control["id"] / "request.json").read_bytes()
                cli = runtime.cli(artifact, True)
                status, _, body = http(url, read_json(artifact), True)
                phrase = b"runtime artifact differs from fresh trusted execution and replay"
                require(cli.returncode == 1 and status == 409 and phrase in cli.stdout and phrase in body,
                        f"independent negative replay was accepted: {control['id']}")
        expired = Runtime(app_home, inputs, working, "expired")
        with expired.server() as url:
            case = next(case for case in manifest["cases"] if case["id"] == "learned-program-contract")
            for profile in PROFILES:
                request = {**case["commonRequest"], "profile": profile}
                cli = expired.cli(canonical(request))
                status, _, body = http(url, request)
                require(cli.returncode == 1 and status == 400 and b"is not valid at " in cli.stdout
                        and b"is not valid at " in body, "independent real-clock expiration control was accepted")
    print("Verified public DIRECT/V4 qualification and fresh CLI/Workbench replay:", supplied["contentHash"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("root", "manifest", "schema", "app-home", "inputs"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--revision", required=True)
    args = parser.parse_args()
    verify(args.root.resolve(), args.manifest.resolve(), args.schema.resolve(), args.app_home.resolve(), args.inputs.resolve(), args.revision)


if __name__ == "__main__":
    main()
