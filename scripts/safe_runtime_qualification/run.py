#!/usr/bin/env python3
"""Execute only the frozen public source-side contract cases against the installed app."""
from __future__ import annotations

import argparse
from copy import deepcopy
from pathlib import Path
import tempfile

from protocol import (PROFILES, canonical, derive_report, load_manifest, mutation_requests, read_json, require)
from runtime import Runtime, compile_plugin, http, physical_identity, verify_inputs


def write(path: Path, raw: bytes):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(raw)


def assert_rejected(runtime: Runtime, url: str, raw: bytes, replay=True) -> tuple[dict, bytes]:
    cli = runtime.cli(raw, replay)
    status, _, body = http(url, read_json(raw), replay)
    phrase = b"runtime artifact differs from fresh trusted execution and replay" if replay else b"is not valid at "
    require(cli.returncode == 1 and status == (409 if replay else 400)
            and phrase in cli.stdout and phrase in body, "negative control did not fail at the expected product authority")
    stable = ({"kind": "FRESH_REPLAY_MUTATION", "cliExit": cli.returncode, "httpStatus": status,
               "reason": "RUNTIME_DIFFERS_FROM_FRESH_TRUSTED_EXECUTION"} if replay else
              {"kind": "REAL_CLOCK_EXPIRED_AUTHORITY", "cliExit": cli.returncode, "httpStatus": status,
               "negativeExpiry": "2026-09-11T00:00:00Z", "clock": "PRODUCT_SYSTEM_UTC"})
    return stable, cli.stdout + b"\n" + cli.stderr + b"\n" + body


def execute(app_home: Path, inputs: Path, manifest_path: Path, revision: str, output: Path):
    manifest = load_manifest(manifest_path.read_bytes())
    input_identity = verify_inputs(inputs, manifest)
    output.mkdir(parents=True, exist_ok=True)
    require(not any(output.iterdir()), "output must be an empty directory; retain prior observations separately")
    observations = {}
    with tempfile.TemporaryDirectory(prefix="safe-product-runtime-") as temporary:
        working = Path(temporary)
        plugin = compile_plugin(app_home, working)
        physical = physical_identity(app_home, plugin)
        runtime = Runtime(app_home, inputs, working)
        with runtime.server() as url:
            for case in manifest["cases"]:
                observations[case["id"]] = {}
                for profile in PROFILES:
                    request = deepcopy(case["commonRequest"])
                    request["profile"] = profile
                    cli = runtime.cli(canonical(request))
                    require(cli.returncode == 0, f"CLI analysis failed: {case['id']}/{profile}: {cli.stdout!r}")
                    status, _, body = http(url, request)
                    require(status == 200, f"HTTP analysis failed: {case['id']}/{profile}: {body!r}")
                    prefix = output / "artifacts" / case["id"] / profile
                    write(prefix / "cli-analyze.json", cli.stdout)
                    write(prefix / "http-analyze.json", body)
                    write(output / "diagnostics" / case["id"] / f"{profile}-analyze.log", cli.stderr)
                    observations[case["id"]][profile] = cli.stdout
                print(f"observed {case['id']}", flush=True)
        # The analysis server has stopped. Both import paths start from new product processes.
        with runtime.server() as url:
            for case in manifest["cases"]:
                for profile in PROFILES:
                    raw = observations[case["id"]][profile]
                    cli = runtime.cli(raw, True)
                    status, headers, body = http(url, read_json(raw), True)
                    require(cli.returncode == 0 and status == 200
                            and any(key.lower() == "x-runtime-replay" and value == "VERIFIED" for key, value in headers.items()),
                            f"fresh product replay refused {case['id']}/{profile}")
                    prefix = output / "artifacts" / case["id"] / profile
                    write(prefix / "cli-replay.json", cli.stdout)
                    write(prefix / "http-replay.json", body)
                    write(output / "diagnostics" / case["id"] / f"{profile}-replay.log", cli.stderr)
            for name, raw in mutation_requests(observations).items():
                result, diagnostic = assert_rejected(runtime, url, raw)
                write(output / "artifacts/controls" / name / "request.json", raw)
                write(output / "artifacts/controls" / name / "result.json", canonical(result) + b"\n")
                write(output / "diagnostics/controls" / f"{name}.log", diagnostic)
        expired = Runtime(app_home, inputs, working, "expired")
        with expired.server() as url:
            case = next(case for case in manifest["cases"] if case["id"] == "learned-program-contract")
            for profile in PROFILES:
                request = {**case["commonRequest"], "profile": profile}
                result, diagnostic = assert_rejected(expired, url, canonical(request), False)
                write(output / "artifacts/controls" / f"expired-{profile}.json", canonical(result) + b"\n")
                write(output / "diagnostics/controls" / f"expired-{profile}.log", diagnostic)
        report = derive_report(output, manifest, revision, input_identity, physical)
        write(output / "qualification.json", canonical(report) + b"\n")
        print(report["evidence"]["status"], report["evidence"]["summary"], flush=True)
        require(report["evidence"]["status"] == "QUALIFIED_BOUNDED_PUBLIC_CASES", "bounded public qualification failed; raw evidence retained")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("app-home", "inputs", "manifest", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--revision", required=True)
    args = parser.parse_args()
    execute(args.app_home.resolve(), args.inputs.resolve(), args.manifest.resolve(), args.revision, args.output.resolve())


if __name__ == "__main__":
    main()
