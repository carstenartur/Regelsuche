#!/usr/bin/env python3
"""Local/CI transport to the same Java authority. Never selects candidates or judges math."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile

# Importing the checkout-owned verifier must not dirty the clean source boundary.
sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("amplification_verifier", ROOT / "scripts/verify-rule-amplification-reproduction.py")
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)
require, canonical, digest = verifier.require, verifier.canonical, verifier.digest
MAIN = "de.regelsuche.benchmark.amplification.AblatableRuleAmplificationExperiment"
ANCHORS = {"de/regelsuche/search/reachability/AblatableRulePreparationRunner.class",
           "de/regelsuche/benchmark/amplification/AblatableRuleAmplificationExperiment.class",
           "de/regelsuche/math/sympy/SymPyNamedOperationEngine.class",
           "de/regelsuche/transform/RulePreparationPlanner.class",
           "de/regelsuche/search/reachability/PatternTargetedLocalBridgeSearch.class"}


def command(args, timeout=1800, cwd=ROOT):
    result = subprocess.run([str(arg) for arg in args], cwd=cwd, capture_output=True, timeout=timeout)
    require(result.returncode == 0, "authority command failed: " + str(args[0]) + "\n" + result.stderr.decode(errors="replace")[-3000:])
    return result


def clean_revision():
    require(not command(["git", "status", "--porcelain"]).stdout.strip(), "a clean committed checkout is required")
    return command(["git", "rev-parse", "HEAD"]).stdout.decode().strip()


def implementation(classpath):
    classes = {}
    for entry in classpath.split(os.pathsep):
        path = Path(entry)
        require(path.exists(), "runtime classpath entry missing")
        if path.is_dir():
            items = ((item.relative_to(path).as_posix(), item.read_bytes()) for item in (path / "de/regelsuche").rglob("*.class"))
            archive = None
        else:
            archive = zipfile.ZipFile(path)
            items = ((name, archive.read(name)) for name in archive.namelist() if name.startswith("de/regelsuche/") and name.endswith(".class"))
        for name, raw in items:
            value = digest(raw)
            require(name not in classes or classes[name] == value, "ambiguous compiled authority: " + name)
            classes[name] = value
        if archive:
            archive.close()
    require(ANCHORS <= classes.keys(), "required compiled authority anchor missing")
    return digest(canonical(classes))


def execute_local(args, kind):
    classpath = args.classpath_file.resolve().read_text().strip()
    require(classpath and "\n" not in classpath, "one runtime classpath line required")
    code_hash = implementation(classpath)
    revision = os.environ["REGELSUCHE_REVISION"] if kind == "container" else clean_revision()
    if kind == "container":
        require(Path("/.dockerenv").is_file(), "container adapter must execute in Docker")
    output = args.output.resolve()
    require(not output.exists(), "output must be fresh")
    output.mkdir(parents=True)
    for name in ("plan.json", "sources.json", "qualification.json"):
        # Transfer sealed qualification bytes without parsing or projecting labels.
        raw = verifier.opaque(args.inputs.resolve() / name) if name == "qualification.json" else verifier.read(args.inputs.resolve() / name)[0]
        (output / name).write_bytes(raw)
    result = command(["java", "--enable-native-access=ALL-UNNAMED", "-cp", classpath, MAIN, "run", revision, output])
    (output / "execution.stdout.txt").write_bytes(result.stdout)
    (output / "execution.stderr.txt").write_bytes(result.stderr)
    machine = ""
    if kind == "host":
        candidates = [Path("/etc/machine-id"), Path("/var/lib/dbus/machine-id")]
        raw_machine = next((path.read_bytes().strip() for path in candidates if path.is_file() and path.read_bytes().strip()), b"")
        require(raw_machine, "observed machine identity unavailable; cannot claim independent host")
        machine = digest(raw_machine)
        require(clean_revision() == revision, "checkout changed during execution")
    receipt = {"schema": "regelsuche.amplification-execution-receipt/v1", "kind": kind,
               "repositoryRevision": revision, "cleanCheckout": True, "implementationHash": code_hash,
               "observedMachineHash": machine, "pinnedImageId": "", "imageInspection": {},
               "javaVersion": command(["java", "-version"]).stderr.decode().strip(),
               "files": {name: digest(verifier.read(output / name)[0]) for name in verifier.FILES},
               "sourceAuthority": "CLEAN_CHECKOUT" if kind == "host" else "IMAGE_BUILT_FROM_DECLARED_COMMIT_ARCHIVE"}
    (output / "execution-receipt.json").write_bytes(canonical(receipt))
    return output


def execute_container(args):
    require(args.image.startswith("sha256:") and len(args.image) == 71, "use an immutable built image ID, never a mutable tag")
    inspected = json.loads(command(["docker", "image", "inspect", args.image]).stdout)[0]
    require(inspected["Id"] == args.image, "actual image ID differs")
    revision = clean_revision()
    require(inspected["Config"]["Labels"]["org.opencontainers.image.revision"] == revision, "image source revision differs")
    output = args.output.resolve()
    require(not output.exists(), "container output must be fresh")
    output.mkdir(parents=True)
    command(["docker", "run", "--rm", "--network=none", "--mount", f"type=bind,src={args.inputs.resolve()},dst=/inputs,readonly",
             "--mount", f"type=bind,src={output},dst=/out", args.image], timeout=3600)
    bundle = output / "bundle"
    _, receipt = verifier.read(bundle / "execution-receipt.json")
    require(receipt["repositoryRevision"] == revision and receipt["kind"] == "container", "container receipt binding differs")
    receipt["pinnedImageId"] = args.image
    receipt["imageInspection"] = {"Id": inspected["Id"], "Config": {"Labels": inspected["Config"]["Labels"]}}
    (bundle / "execution-receipt.json").write_bytes(canonical(receipt))
    for path in bundle.iterdir():
        require(path.is_file() and not path.is_symlink(), "unexpected container output")
        path.rename(output / path.name)
    bundle.rmdir()
    require(clean_revision() == revision, "checkout changed during container execution")
    return output


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("plan", "host", "container", "inside"))
    parser.add_argument("--classpath-file", type=Path)
    parser.add_argument("--inputs", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--image")
    args = parser.parse_args()
    if args.mode == "plan":
        require(args.classpath_file is not None, "compiled runtime classpath required")
        classpath = args.classpath_file.resolve().read_text().strip()
        implementation(classpath)
        revision = clean_revision()
        command(["java", "-cp", classpath, MAIN, "plan", revision, args.output.resolve()])
        require(clean_revision() == revision, "checkout changed during plan freeze")
        print("Frozen public input plan; no candidate formation executed")
    else:
        require(args.inputs is not None, "preregistered input directory required")
        require(args.mode == "container" or args.classpath_file is not None, "compiled runtime classpath required")
        output = execute_container(args) if args.mode == "container" else execute_local(args, "container" if args.mode == "inside" else "host")
        verifier.bundle(output)
        print("Retained bound authority observations; three-environment comparison remains a separate explicit step")
