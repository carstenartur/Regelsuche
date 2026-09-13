#!/usr/bin/env python3
"""Local/CI transport to the same Java authority. Never selects candidates or judges math."""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import uuid
import zipfile

# Importing the checkout-owned verifier must not dirty the clean source boundary.
sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("amplification_verifier", ROOT / "scripts/verify-rule-amplification-reproduction.py")
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)
require, canonical, digest = verifier.require, verifier.canonical, verifier.digest
MAIN = "de.regelsuche.benchmark.amplification.AblatableRuleAmplificationExperiment"
JAVA_FLAGS = ["--enable-native-access=ALL-UNNAMED", "-Xms512m", "-Xmx2g"]
ANCHORS = {"de/regelsuche/search/reachability/AblatableRulePreparationRunner.class",
           "de/regelsuche/benchmark/amplification/AblatableRuleAmplificationExperiment.class",
           "de/regelsuche/math/sympy/SymPyNamedOperationEngine.class",
           "de/regelsuche/transform/RulePreparationPlanner.class",
           "de/regelsuche/search/reachability/PatternTargetedLocalBridgeSearch.class"}


def command(args, timeout=1800, cwd=None):
    result = subprocess.run([str(arg) for arg in args], cwd=cwd or ROOT, capture_output=True, timeout=timeout)
    require(result.returncode == 0, "authority command failed: " + str(args[0]) + "\n" + result.stderr.decode(errors="replace")[-3000:])
    return result


def write_new(path, value):
    with path.open("xb") as stream:
        stream.write(canonical(value))


def file_hash(path):
    require(not any(parent.is_symlink() for parent in path.absolute().parents), "symlinked owned path component: " + path.name)
    require(path.is_file() and not path.is_symlink(), "regular owned file required: " + path.name)
    hashed = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            hashed.update(chunk)
    return "sha256:" + hashed.hexdigest()


def run_logged(args, output, prefix, timeout=1800):
    """Retain actual process diagnostics, including a failed start or watchdog kill."""
    require(os.name == "posix", "amplification transport requires POSIX process-group termination")
    result = {"schema": "regelsuche.amplification-process/v1", "argv": [str(arg) for arg in args],
              "status": "START_FAILED", "returnCode": None, "timeoutSeconds": timeout,
              "startedAt": datetime.now(timezone.utc).isoformat()}
    with (output / (prefix + ".stdout.txt")).open("xb") as stdout, (output / (prefix + ".stderr.txt")).open("xb") as stderr:
        try:
            process = subprocess.Popen(result["argv"], cwd=ROOT, stdin=subprocess.DEVNULL,
                                       stdout=stdout, stderr=stderr, start_new_session=True)
            try:
                process.wait(timeout=timeout)
                result["status"] = "EXITED"
            except subprocess.TimeoutExpired:
                result["status"] = "TIMED_OUT"
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.wait()
            result["returnCode"] = process.returncode
        except OSError as failure:
            result["failureClass"] = type(failure).__name__
        finally:
            result["finishedAt"] = datetime.now(timezone.utc).isoformat()
            stdout.flush()
            stderr.flush()
            result["logs"] = {name: file_hash(output / name) for name in
                              (prefix + ".stdout.txt", prefix + ".stderr.txt")}
            write_new(output / (prefix + "-process.json"), result)
    return result


def succeeded(result):
    return result["status"] == "EXITED" and result["returnCode"] == 0


def clean_revision():
    require(not command(["git", "status", "--porcelain"]).stdout.strip(), "a clean committed checkout is required")
    return command(["git", "rev-parse", "HEAD"]).stdout.decode().strip()


def implementation_manifest(classpath):
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
    return classes


def implementation(classpath):
    return digest(canonical(implementation_manifest(classpath)))


def observed_machine():
    for path in (Path("/etc/machine-id"), Path("/var/lib/dbus/machine-id")):
        if path.is_file() and (raw := path.read_bytes().strip()):
            return digest(raw)
    raise ValueError("observed machine identity unavailable; cannot claim independent host")


def source_snapshot(kind):
    if kind == "container":
        require(Path("/.dockerenv").is_file(), "container adapter must execute in Docker")
        return {"repositoryRevision": os.environ["REGELSUCHE_REVISION"],
                "repositoryTree": os.environ["REGELSUCHE_TREE"], "cleanCheckout": True,
                "sourceAuthority": "IMAGE_BUILT_FROM_DECLARED_COMMIT_ARCHIVE", "observedMachineHash": ""}
    return {"repositoryRevision": clean_revision(), "repositoryTree": command(["git", "rev-parse", "HEAD^{tree}"]).stdout.decode().strip(),
            "cleanCheckout": True, "sourceAuthority": "CLEAN_CHECKOUT", "observedMachineHash": observed_machine()}


def local_snapshot(classpath, kind):
    return {**source_snapshot(kind), "implementationHash": implementation(classpath),
            "javaVersion": command(["java", "-version"]).stderr.decode().strip()}


def retain_observation(output, name, value):
    value["retainedFiles"] = {path.relative_to(output).as_posix(): file_hash(path) for path in sorted(output.rglob("*"))
                              if path.is_file() and path != output / name}
    write_new(output / name, value)


def execute_local(args, kind, expected=None):
    classpath = args.classpath_file.resolve().read_text().strip()
    require(classpath and "\n" not in classpath, "one runtime classpath line required")
    before = local_snapshot(classpath, kind)
    if expected is not None:
        require(all(before[key] == value for key, value in expected.items()), "preregistered execution identity differs")
    revision = before["repositoryRevision"]
    output = args.output.resolve()
    require(not output.exists(), "output must be fresh")
    output.mkdir(parents=True)
    observation = {"schema": "regelsuche.amplification-transport-observation/v1", "kind": kind,
                   "status": "INCOMPLETE_EXECUTION", "before": before, "after": None,
                   "cohortHash": getattr(args, "cohort_hash", None)}
    write_new(output / "execution-start.json", {**before, "cohortHash": observation["cohortHash"]})
    try:
        for name in ("plan.json", "sources.json", "qualification.json"):
            # Transfer sealed qualification bytes without parsing or projecting labels.
            raw = verifier.opaque(args.inputs.resolve() / name) if name == "qualification.json" else verifier.read(args.inputs.resolve() / name)[0]
            (output / name).write_bytes(raw)
        result = run_logged(["java", *JAVA_FLAGS, "-cp", classpath, MAIN, "run", revision, output], output, "execution")
        observation["after"] = local_snapshot(classpath, kind)
        require(before == observation["after"], "source, machine, Java or compiled authority changed during execution")
        require(succeeded(result), "Java authority did not finish: " + result["status"])
        receipt = {"schema": "regelsuche.amplification-execution-receipt/v1", "kind": kind,
                   **before, "pinnedImageId": "", "imageInspection": {},
                   "files": {name: digest(verifier.read(output / name)[0]) for name in verifier.FILES}}
        write_new(output / "execution-receipt.json", receipt)
        verifier.bundle(output)
        observation["status"] = "COMPLETE_BUNDLE"
    except Exception as failure:
        observation["failureClass"] = type(failure).__name__
        observation["detail"] = str(failure)[-3000:]
        raise
    finally:
        retain_observation(output, "execution-observation.json", observation)
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
    container_name = "regelsuche-amplification-" + uuid.uuid4().hex
    observation = {"schema": "regelsuche.amplification-container-transport/v1", "status": "INCOMPLETE_EXECUTION",
                   "repositoryRevision": revision, "imageInspection": inspected, "containerName": container_name}
    try:
        cohort_environment = ["--env", "REGELSUCHE_AMPLIFICATION_COHORT_HASH=" + args.cohort_hash] if getattr(args, "cohort_hash", None) else []
        result = run_logged(["docker", "run", "--rm", "--platform", "linux/amd64", "--name", container_name, "--network=none", *cohort_environment, "--mount",
                             f"type=bind,src={args.inputs.resolve()},dst=/inputs,readonly", "--mount",
                             f"type=bind,src={output},dst=/out", args.image], output, "docker", timeout=3600)
        require(succeeded(result), "container authority did not finish: " + result["status"])
        bundle = output / "bundle"
        _, receipt = verifier.read(bundle / "execution-receipt.json")
        require(receipt["repositoryRevision"] == revision and receipt["kind"] == "container", "container receipt binding differs")
        receipt["pinnedImageId"] = args.image
        receipt["imageInspection"] = {"Id": inspected["Id"], "Config": {"Labels": inspected["Config"]["Labels"]}}
        # Keep the inner bundle byte-for-byte; add outer image evidence separately.
        for name in verifier.FILES:
            (output / name).write_bytes(verifier.read(bundle / name)[0])
        write_new(output / "execution-receipt.json", receipt)
        require(clean_revision() == revision, "checkout changed during container execution")
        verifier.bundle(output)
        observation["status"] = "COMPLETE_BUNDLE"
    except Exception as failure:
        observation.update(failureClass=type(failure).__name__, detail=str(failure)[-3000:])
        raise
    finally:
        # Killing the Docker client does not kill its daemon-owned container.
        if observation["status"] != "COMPLETE_BUNDLE":
            observation["cleanup"] = run_logged(["docker", "rm", "--force", container_name], output, "docker-cleanup", timeout=60)
        retain_observation(output, "container-observation.json", observation)
    return output


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("plan", "host", "container", "inside"))
    parser.add_argument("--classpath-file", type=Path)
    parser.add_argument("--inputs", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--image")
    parser.add_argument("--cohort-hash")
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
