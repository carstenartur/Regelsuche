#!/usr/bin/env python3
"""A self-consistent forged result must fail independent execution, despite rehashed reports."""
import argparse
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

from protocol import PROFILES, SURFACES, canonical, content_hash, derive_report, load_manifest, read_json, require


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("root", "manifest", "schema", "app-home", "inputs"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--revision", required=True)
    args = parser.parse_args()
    manifest = load_manifest(args.manifest.read_bytes())
    report = read_json((args.root / "qualification.json").read_bytes())
    with tempfile.TemporaryDirectory(prefix="safe-product-forged-report-") as temporary:
        forged = Path(temporary) / "forged"
        shutil.copytree(args.root, forged, ignore=shutil.ignore_patterns("diagnostics"))
        command = [sys.executable, "-B", str(Path(__file__).with_name("verify.py")), "--root", str(forged),
                   "--revision", args.revision]
        for name in ("manifest", "schema", "app_home", "inputs"):
            command.extend(["--" + name.replace("_", "-"), str(getattr(args, name).resolve())])
        # All real observations remain unchanged. A valid rehash alone must not
        # authorize replacing their externally supplied source revision.
        provenance = read_json(canonical(report))
        provenance["evidence"]["repositoryRevision"] = "0" * 40
        provenance["contentHash"] = content_hash(canonical(provenance["evidence"]))
        (forged / "qualification.json").write_bytes(canonical(provenance) + b"\n")
        result = subprocess.run(command, capture_output=True, timeout=120)
        require(result.returncode != 0 and b"repository revision differs from external source authority" in result.stderr,
                "independent verifier accepted a correctly rehashed false source revision")
        for profile in PROFILES:
            prefix = forged / "artifacts/direct-add-zero" / profile
            artifact = read_json((prefix / "cli-analyze.json").read_bytes())
            candidate = artifact["evidence"]["outcomes"][0]["candidate"]
            candidate["expression"] = "forged_result"
            execution = read_json(candidate["execution"])
            execution["transformedExpression"] = "forged_result"
            provenance = read_json(execution["provenance"])
            provenance["steps"][0]["output"] = "forged_result"
            execution["provenance"] = canonical(provenance).decode()
            candidate["execution"] = canonical(execution).decode()
            artifact["contentHash"] = content_hash(canonical(artifact["evidence"]))
            for surface in SURFACES:
                (prefix / f"{surface}.json").write_bytes(canonical(artifact) + b"\n")
        # This attack satisfies the paired decisions, inventory, work, hashes and
        # four recorded surfaces. A verifier that only trusts those would accept it.
        evidence = report["evidence"]
        replacement = derive_report(forged, manifest, args.revision, evidence["inputs"], evidence["physicalRuntime"])
        require(replacement["evidence"]["status"] == "QUALIFIED_BOUNDED_PUBLIC_CASES",
                "forgery must reach the independent execution boundary")
        (forged / "qualification.json").write_bytes(canonical(replacement) + b"\n")
        result = subprocess.run(command, capture_output=True, timeout=120)
        require(result.returncode != 0 and b"independent fresh product replay failed for direct-add-zero/DIRECT_V1" in result.stderr,
                "independent verifier accepted a self-consistent forged product result or failed at another boundary")
    print("Rejected rehashed false source revision; rejected coherent forged results only at independent actual product execution")


if __name__ == "__main__":
    main()
