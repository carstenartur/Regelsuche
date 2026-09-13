#!/usr/bin/env python3
"""Preregistered public reproduction transport; all mathematics stays in Java."""
import argparse
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import platform
import re
import shutil
import subprocess
import sys
import uuid
from types import SimpleNamespace

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("amplification_transport", ROOT / "scripts/run-rule-amplification.py")
transport = importlib.util.module_from_spec(spec)
spec.loader.exec_module(transport)
verify = transport.verifier
require, canonical, digest = verify.require, verify.canonical, verify.digest
SCHEMA = "regelsuche.amplification-cohort-plan/v1"
ROLES = ["host-a", "host-b", "container"]
CORPUS = "regelsuche.amplification-public-three-family/v1"
SCOPE = "PUBLIC_IMPLEMENTATION_QUALIFICATION_NOT_533_FINAL_OR_981_PRECISION"
INPUTS = {"plan.json", "sources.json", "qualification.json", "source.tar", "Dockerfile", "environment.json", "compiled-classes.json"}
ENVIRONMENT = ROOT / "reproduction/amplification-environment.json"
CLASSPATH = ROOT / "regelsuche-experiments/build/amplification-runtime-classpath.txt"


def environment_contract():
    value = json.loads(verify.opaque(ENVIRONMENT), object_pairs_hook=verify.pairs)
    require(value["schema"] == "regelsuche.amplification-environment/v1", "environment schema differs")
    require(value["javaFlags"] == transport.JAVA_FLAGS, "transport JVM flags differ from contract")
    return value


def validate_environment(contract, properties, system, architecture, patchelf, kind="host"):
    observed_build = properties.get("java.runtime.version", "")
    require(observed_build in (contract["javaBuild"], contract["javaBuild"] + "-LTS"), "exact pinned Java build required")
    require(properties.get("java.vendor") == contract["javaVendor"], "pinned Java vendor differs")
    require(system == contract["operatingSystem"] and contract["architecture"] == "amd64" and architecture in ("x86_64", "amd64"), "Linux amd64 reproduction required")
    require(patchelf.strip() == "patchelf " + contract["patchelfVersions"][kind], "pinned patchelf differs")


def observed_environment(kind="host"):
    contract = environment_contract()
    result = transport.command(["java", "-XshowSettings:properties", "-version"])
    properties = dict(re.findall(r"^\s*([\w.]+)\s*=\s*(.+)$", result.stderr.decode(), re.MULTILINE))
    patchelf = transport.command(["patchelf", "--version"]).stdout.decode().strip()
    validate_environment(contract, properties, platform.system(), platform.machine(), patchelf, kind)
    return {"javaRuntimeVersion": properties["java.runtime.version"], "javaVendor": properties["java.vendor"],
            "system": platform.system(), "architecture": platform.machine(), "kernel": platform.release(),
            "osRelease": platform.freedesktop_os_release(), "patchelf": patchelf,
            "javaFlags": contract["javaFlags"], "contractHash": transport.file_hash(ENVIRONMENT),
            "boundary": "OBSERVED_MACHINE_ENVIRONMENT_NOT_PHYSICAL_OR_REMOTE_ATTESTATION"}


def fresh(path):
    path = path.absolute()
    require(not path.exists() and not path.is_symlink(), "output must be fresh")
    path.mkdir(parents=True)
    return path


def compile_authority(output):
    result = transport.run_logged([ROOT / "gradlew", "--no-daemon", "--no-configuration-cache", "--no-build-cache",
                                   "--max-workers=2", "--rerun-tasks", ":regelsuche-experiments:writeAmplificationRuntimeClasspath",
                                   "--console=plain"], output, "compile")
    require(transport.succeeded(result), "classpath preparation failed; formation not started")
    return CLASSPATH.read_text().strip()


def archive_source(destination, revision):
    with destination.open("xb") as stream:
        result = subprocess.run(["git", "archive", "--format=tar", revision], cwd=ROOT, stdout=stream, stderr=subprocess.PIPE)
    require(result.returncode == 0, "committed source archive failed")


def prepare(output, previous_result_hash=""):
    output = fresh(output)
    receipt = {"schema": "regelsuche.amplification-cohort-preparation/v1", "status": "PREPARATION_FAILED"}
    try:
        require(not previous_result_hash or re.fullmatch(r"sha256:[0-9a-f]{64}", previous_result_hash), "previous result hash malformed")
        before = transport.source_snapshot("host")
        receipt["environment"] = observed_environment()
        classpath = compile_authority(output)
        classes = transport.implementation_manifest(classpath)
        inputs = output / "inputs"
        result = transport.run_logged(["java", *transport.JAVA_FLAGS, "-cp", classpath, transport.MAIN,
                                       "plan", before["repositoryRevision"], inputs], output, "plan")
        require(transport.succeeded(result), "public plan authority failed; no formation executed")
        archive_source(inputs / "source.tar", before["repositoryRevision"])
        shutil.copyfile(ROOT / "reproduction/Dockerfile.amplification", inputs / "Dockerfile")
        shutil.copyfile(ENVIRONMENT, inputs / "environment.json")
        transport.write_new(inputs / "compiled-classes.json", classes)
        after = transport.source_snapshot("host")
        require(before == after and transport.implementation(classpath) == digest(canonical(classes)), "preparation authority changed")
        cohort = {"schema": SCHEMA, "cohortId": uuid.uuid4().hex, "previousCohortResultHash": previous_result_hash,
                  "repositoryRevision": before["repositoryRevision"], "repositoryTree": before["repositoryTree"],
                  "roles": ROLES, "corpus": CORPUS, "sourceCount": 16,
                  "nativeRows": 64, "externalRows": 96, "implementationHash": digest(canonical(classes)),
                  "environment": environment_contract(), "files": {name: transport.file_hash(inputs / name) for name in sorted(INPUTS)},
                  "scope": SCOPE,
                  "retries": "FIRST_ATTEMPT_RETAINED_NEW_COHORT_REQUIRED_FOR_RERUN", "comparativeGainClaim": "NOT_AUTHORIZED"}
        transport.write_new(inputs / "cohort-plan.json", cohort)
        cohort_hash = transport.file_hash(inputs / "cohort-plan.json")
        read_cohort(inputs, cohort_hash)
        receipt.update(status="INPUTS_PREREGISTERED_NO_FORMATION", cohortHash=cohort_hash, source=before)
        return cohort_hash
    except Exception as failure:
        receipt.update(failureClass=type(failure).__name__, detail=str(failure)[-3000:])
        raise
    finally:
        transport.retain_observation(output, "preparation-receipt.json", receipt)


def read_cohort(inputs, expected_hash):
    require(re.fullmatch(r"sha256:[0-9a-f]{64}", expected_hash or ""), "the preregistered cohort hash is required")
    raw, cohort = verify.read(inputs / "cohort-plan.json")
    require(digest(raw) == expected_hash, "preregistered cohort binding differs")
    require(cohort["schema"] == SCHEMA and cohort["roles"] == ROLES and set(cohort["files"]) == INPUTS, "cohort contract differs")
    require(cohort["corpus"] == CORPUS and cohort["scope"] == SCOPE and cohort["comparativeGainClaim"] == "NOT_AUTHORIZED", "public cohort scope differs")
    require(re.fullmatch(r"[0-9a-f]{32}", cohort["cohortId"]) and
            (not cohort["previousCohortResultHash"] or re.fullmatch(r"sha256:[0-9a-f]{64}", cohort["previousCohortResultHash"])), "cohort attempt identity malformed")
    require(re.fullmatch(r"[0-9a-f]{40}", cohort["repositoryRevision"]) and re.fullmatch(r"[0-9a-f]{40}", cohort["repositoryTree"]), "source identity malformed")
    require(canonical(cohort["environment"]) == canonical(environment_contract()), "checkout environment contract differs")
    for name, expected in cohort["files"].items():
        require(transport.file_hash(inputs / name) == expected, "cohort file binding differs: " + name)
    for relative, name in (("reproduction/amplification-environment.json", "environment.json"), ("reproduction/Dockerfile.amplification", "Dockerfile")):
        require(transport.file_hash(ROOT / relative) == cohort["files"][name], "checkout transport source differs: " + name)
    require(re.findall(r"^FROM (\S+)", (inputs / "Dockerfile").read_text(), re.MULTILINE) == [cohort["environment"]["baseImage"]], "Docker base pin differs from contract")
    properties = (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text()
    require("distributionSha256Sum=" + cohort["environment"]["wrapperSha256"] in properties, "wrapper pin differs")
    _, plan = verify.read(inputs / "plan.json")
    _, sources = verify.read(inputs / "sources.json")
    require(plan["schema"] == "regelsuche.amplification-plan/v1" and plan["repositoryRevision"] == cohort["repositoryRevision"], "Java plan subject differs")
    require(plan["sourceHash"] == cohort["files"]["sources.json"] and plan["qualificationHash"] == cohort["files"]["qualification.json"], "sealed Java inputs differ")
    require([row["profile"] for row in plan["nativeProfiles"]] == list(verify.PROFILES) and
            [row["operation"] for row in plan["externalOperations"]] == list(verify.OPERATIONS), "planned profile/operation order differs")
    require(cohort["sourceCount"] == len(sources["sources"]) == 16 and cohort["nativeRows"] == 64 and cohort["externalRows"] == 96, "complete public corpus cardinality differs")
    classes_raw, classes = verify.read(inputs / "compiled-classes.json")
    require(cohort["implementationHash"] == digest(classes_raw), "compiled manifest differs")
    require(transport.ANCHORS <= classes.keys() and all(re.fullmatch(r"sha256:[0-9a-f]{64}", value) for value in classes.values()), "compiled anchors or hashes missing")
    return cohort


def expected_identity(cohort):
    return {key: cohort[key] for key in ("repositoryRevision", "repositoryTree", "implementationHash")}


def assemble_context(inputs, output, cohort):
    for name in ("source.tar", "Dockerfile"):
        require(transport.file_hash(inputs / name) == cohort["files"][name], "Docker input binding differs")
    output = fresh(output)
    for name in ("source.tar", "Dockerfile"):
        shutil.copyfile(inputs / name, output / name)
    return output


def execute_container(inputs, output, cohort, cohort_hash):
    context = assemble_context(inputs, output / "context", cohort)
    image_file = output / "image-id.txt"
    result = transport.run_logged(["docker", "build", "--platform", "linux/amd64", "--pull=false", "--no-cache",
        "--build-arg", "REGELSUCHE_REVISION=" + cohort["repositoryRevision"],
        "--build-arg", "REGELSUCHE_TREE=" + cohort["repositoryTree"],
        "--build-arg", "REGELSUCHE_SOURCE_SHA256=" + cohort["files"]["source.tar"].removeprefix("sha256:"),
        "--iidfile", image_file, context], output, "image-build")
    require(transport.succeeded(result), "container build failed; formation not started")
    args = SimpleNamespace(inputs=inputs, output=output / "run", image=image_file.read_text().strip(), cohort_hash=cohort_hash)
    return transport.execute_container(args)


def execute_role(role, inputs, output, cohort_hash):
    require(role in ROLES, "unknown execution role")
    output = fresh(output)
    receipt = {"schema": "regelsuche.amplification-cohort-execution/v1", "role": role, "cohortHash": cohort_hash,
               "status": "INCOMPLETE_EXECUTION", "start": None, "finish": None}
    try:
        cohort = read_cohort(inputs, cohort_hash)
        before = transport.source_snapshot("host")
        require(all(before[key] == cohort[key] for key in ("repositoryRevision", "repositoryTree")), "clean checkout subject differs")
        environment = observed_environment()
        if role == "container":
            # The container's own start receipt binds its compiled classes and JDK before formation.
            receipt["start"] = {"source": before, "environment": environment}
            transport.write_new(output / "role-start.json", {"cohortHash": cohort_hash, "role": role, **receipt["start"]})
            run = execute_container(inputs, output, cohort, cohort_hash)
        else:
            classpath = compile_authority(output)
            start = transport.local_snapshot(classpath, "host")
            require(all(start[key] == value for key, value in before.items()), "checkout or machine changed during compilation")
            require(all(start[key] == value for key, value in expected_identity(cohort).items()), "independently compiled authority differs")
            receipt["start"] = {"source": start, "environment": environment}
            transport.write_new(output / "role-start.json", {"cohortHash": cohort_hash, "role": role, **receipt["start"]})
            run = transport.execute_local(SimpleNamespace(classpath_file=CLASSPATH, inputs=inputs, output=output / "run", cohort_hash=cohort_hash), "host", expected_identity(cohort))
        receipt["finish"] = {"source": transport.source_snapshot("host"), "environment": observed_environment()}
        require(receipt["finish"] == {"source": before, "environment": environment}, "host source/environment changed during execution")
        read_cohort(inputs, cohort_hash)
        verify.bundle(run)
        receipt.update(status="COMPLETE_BUNDLE", authorityFiles={name: transport.file_hash(run / name) for name in verify.FILES},
                       executionReceiptHash=transport.file_hash(run / "execution-receipt.json"))
    except Exception as failure:
        receipt.update(failureClass=type(failure).__name__, detail=str(failure)[-3000:])
        raise
    finally:
        transport.retain_observation(output, "cohort-execution.json", receipt)


def inside(inputs, output, cohort_hash):
    cohort = read_cohort(inputs, cohort_hash)
    environment = observed_environment("container")
    classpath = CLASSPATH.read_text().strip()
    before = transport.local_snapshot(classpath, "container")
    require(all(before[key] == value for key, value in expected_identity(cohort).items()), "container compiled/source identity differs")
    run = transport.execute_local(SimpleNamespace(classpath_file=CLASSPATH, inputs=inputs, output=output, cohort_hash=cohort_hash), "container", expected_identity(cohort))
    require(observed_environment("container") == environment, "container runtime changed")
    transport.write_new(run / "cohort-inside.json", {"schema": "regelsuche.amplification-cohort-inside/v1", "cohortHash": cohort_hash,
                        "before": before, "environment": environment, "executionObservationHash": transport.file_hash(run / "execution-observation.json")})


def verify_retained(root, filename, later_files=()):
    _, value = verify.read(root / filename)
    actual = {path.relative_to(root).as_posix() for path in root.rglob("*")
              if path.is_file() and path != root / filename and path.relative_to(root).as_posix() not in later_files}
    require(set(value["retainedFiles"]) == actual, "retained file set differs: " + filename)
    for name, expected in value["retainedFiles"].items():
        path = PurePosixPath(name)
        require(not path.is_absolute() and ".." not in path.parts and path.as_posix() == name and name != ".", "retained filename is not local")
        require(transport.file_hash(root / name) == expected, "retained diagnostic binding differs: " + name)
    return value


def verify_process(root, prefix, watchdog, label):
    _, process = verify.read(root / (prefix + "-process.json"))
    require(transport.succeeded(process) and type(process["returnCode"]) is int and
            type(process["timeoutSeconds"]) is int and process["timeoutSeconds"] == watchdog,
            label + " process did not complete under declared watchdog")
    require(process.get("schema") == "regelsuche.amplification-process/v1", "process schema differs")
    logs = {name: transport.file_hash(root / name) for name in (prefix + ".stdout.txt", prefix + ".stderr.txt")}
    require(process.get("logs") == logs, "process log binding differs: " + prefix)
    require(isinstance(process.get("argv"), list) and all(isinstance(arg, str) and arg for arg in process["argv"]), "process argv malformed")
    return process["argv"]


def verify_java_process(root, cohort):
    argv = verify_process(root, "execution", cohort["environment"]["javaWatchdogSeconds"], "Java")
    prefix = ["java", *transport.JAVA_FLAGS, "-cp"]
    require(len(argv) == len(prefix) + 5 and argv[:len(prefix)] == prefix and
            argv[-4:-1] == [transport.MAIN, "run", cohort["repositoryRevision"]] and
            "\n" not in argv[len(prefix)] and Path(argv[-1]).is_absolute(), "Java invocation differs from declared authority")


def verify_container_process(root, container, cohort, cohort_hash):
    argv = verify_process(root, "docker", cohort["environment"]["containerWatchdogSeconds"], "container")
    name = container.get("containerName", "")
    require(re.fullmatch(r"regelsuche-amplification-[0-9a-f]{32}", name), "container name malformed")
    prefix = ["docker", "run", "--rm", "--platform", "linux/amd64", "--name", name, "--network=none",
              "--env", "REGELSUCHE_AMPLIFICATION_COHORT_HASH=" + cohort_hash, "--mount"]
    require(len(argv) == len(prefix) + 4 and argv[:len(prefix)] == prefix and argv[-3] == "--mount" and
            argv[-1] == container["imageInspection"]["Id"], "container invocation differs from declared authority")
    for mount, suffix in ((argv[-4], ",dst=/inputs,readonly"), (argv[-2], ",dst=/out")):
        require(mount.startswith("type=bind,src=") and mount.endswith(suffix) and
                Path(mount[len("type=bind,src="):-len(suffix)]).is_absolute() and
                "," not in mount[len("type=bind,src="):-len(suffix)], "container mount binding differs")


def verify_environment_observation(value, cohort, kind="host"):
    validate_environment(cohort["environment"], {"java.runtime.version": value["javaRuntimeVersion"], "java.vendor": value["javaVendor"]},
                         value["system"], value["architecture"], value["patchelf"], kind)
    require(value["contractHash"] == cohort["files"]["environment.json"] and value["javaFlags"] == transport.JAVA_FLAGS, "observed environment binding differs")


def verify_source_authority(value, kind):
    authority = "CLEAN_CHECKOUT" if kind == "host" else "IMAGE_BUILT_FROM_DECLARED_COMMIT_ARCHIVE"
    require(value["cleanCheckout"] is True and value["sourceAuthority"] == authority, "observed source authority differs from role")


def verify_role(root, receipt, cohort, cohort_hash):
    require(receipt["schema"] == "regelsuche.amplification-cohort-execution/v1" and receipt["cohortHash"] == cohort_hash and
            receipt["status"] == "COMPLETE_BUNDLE", "cohort execution incomplete or substituted")
    _, start = verify.read(root / "role-start.json")
    require(start == {"cohortHash": cohort_hash, "role": receipt["role"], **receipt["start"]}, "pre-execution role binding differs")
    verify_environment_observation(receipt["start"]["environment"], cohort)
    require(receipt["start"]["environment"] == receipt["finish"]["environment"], "role environment changed")
    require(all(receipt["start"]["source"][key] == value for key, value in receipt["finish"]["source"].items()), "role source observation changed")
    verify_source_authority(receipt["finish"]["source"], "host")
    require(all(receipt["finish"]["source"][key] == cohort[key] for key in ("repositoryRevision", "repositoryTree")), "role source differs from cohort")
    run = root / "run"
    require(receipt["authorityFiles"] == {name: transport.file_hash(run / name) for name in verify.FILES} and
            receipt["executionReceiptHash"] == transport.file_hash(run / "execution-receipt.json"), "complete authority bindings differ")
    for name in ("plan.json", "sources.json", "qualification.json"):
        require(transport.file_hash(run / name) == cohort["files"][name], "executed preregistered input differs")
    kind = "container" if receipt["role"] == "container" else "host"
    native_root = run / "bundle" if kind == "container" else run
    # The inner cohort receipt is added after native observation, then separately bound below and by the outer manifest.
    observed = verify_retained(native_root, "execution-observation.json", ("cohort-inside.json",) if kind == "container" else ())
    require(observed["kind"] == kind and observed["cohortHash"] == cohort_hash and observed["status"] == "COMPLETE_BUNDLE" and
            observed["before"] == observed["after"], "execution observation changed or incomplete")
    require(all(observed["before"][key] == value for key, value in expected_identity(cohort).items()), "execution identity differs from cohort")
    verify_source_authority(observed["before"], kind)
    _, issued = verify.read(native_root / "execution-start.json")
    require(issued == {**observed["before"], "cohortHash": cohort_hash}, "pre-execution source binding differs")
    verify_java_process(native_root, cohort)
    _, execution = verify.read(run / "execution-receipt.json")
    require(execution["kind"] == kind and all(execution[key] == value for key, value in observed["before"].items()), "execution receipt differs from observed authority")
    if kind == "host":
        require(re.fullmatch(r"sha256:[0-9a-f]{64}", observed["before"]["observedMachineHash"]), "observed host machine identity missing or malformed")
        require(receipt["start"]["source"] == observed["before"], "host start differs from actual execution")
    else:
        _, bound_inside = verify.read(native_root / "cohort-inside.json")
        require(bound_inside["schema"] == "regelsuche.amplification-cohort-inside/v1" and bound_inside["cohortHash"] == cohort_hash and
                bound_inside["before"] == observed["before"] and bound_inside["executionObservationHash"] ==
                transport.file_hash(native_root / "execution-observation.json"), "inner cohort binding differs")
        verify_environment_observation(bound_inside["environment"], cohort, "container")
        require(all((run / name).read_bytes() == (native_root / name).read_bytes() for name in verify.FILES), "outer container projection differs")
        container = verify_retained(run, "container-observation.json")
        require(container["status"] == "COMPLETE_BUNDLE" and container["imageInspection"]["Id"] == execution["pinnedImageId"], "observed container differs")
        verify_container_process(run, container, cohort, cohort_hash)
        labels = container["imageInspection"]["Config"]["Labels"]
        require(labels["org.opencontainers.image.revision"] == cohort["repositoryRevision"] and labels["de.regelsuche.source-tree"] == cohort["repositoryTree"] and
                labels["de.regelsuche.source-archive-sha256"] == cohort["files"]["source.tar"].removeprefix("sha256:"), "container source archive binding differs")


def compare(inputs, roots, output, cohort_hash, require_conclusive=False):
    require(not output.exists(), "comparison output must be fresh")
    result = {"schema": "regelsuche.amplification-cohort-result/v1", "cohortHash": cohort_hash,
              "status": "NOT_REPRODUCED", "comparativeGainClaim": "NOT_AUTHORIZED"}
    try:
        cohort = read_cohort(inputs, cohort_hash)
        require(transport.clean_revision() == cohort["repositoryRevision"], "verifier checkout subject differs")
        receipts = [verify_retained(root, "cohort-execution.json") for root in roots]
        require(len(receipts) == 3 and sorted(receipt["role"] for receipt in receipts) == sorted(ROLES), "exactly one of each planned role required")
        for root, receipt in zip(roots, receipts):
            verify_role(root, receipt, cohort, cohort_hash)
        reproduced = verify.verify([root / "run" for root in roots])
        result.update(status=reproduced["status"], reproduction=reproduced,
                      executionReceipts={receipt["role"]: transport.file_hash(root / "cohort-execution.json") for root, receipt in zip(roots, receipts)})
    except Exception as failure:
        result.update(failureClass=type(failure).__name__, detail=str(failure)[-3000:])
        raise
    finally:
        output.parent.mkdir(parents=True, exist_ok=True)
        transport.write_new(output, result)
    require(not require_conclusive or result["status"] == "REPRODUCED", "required conclusive reproduction unavailable; diagnostic retained")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("prepare", "host-a", "host-b", "container", "inside", "compare"))
    parser.add_argument("--inputs", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--expected-cohort-hash", default=os.environ.get("REGELSUCHE_AMPLIFICATION_COHORT_HASH"))
    parser.add_argument("--roots", nargs=3, type=Path)
    parser.add_argument("--require-conclusive", action="store_true")
    parser.add_argument("--previous-result-hash", default="")
    args = parser.parse_args()
    require(os.environ.get("GITHUB_RUN_ATTEMPT", "1") == "1", "rerun requires a fresh explicitly linked cohort, not replacement job attempts")
    if args.mode == "prepare":
        cohort_hash = prepare(args.output, args.previous_result_hash)
        print("Preregistered cohort: " + cohort_hash + "; no formation executed")
        if os.environ.get("GITHUB_OUTPUT"):
            with Path(os.environ["GITHUB_OUTPUT"]).open("a") as stream:
                stream.write("cohort_hash=" + cohort_hash + "\n")
    else:
        require(args.inputs is not None, "preregistered inputs required")
        if args.mode == "inside":
            inside(args.inputs, args.output, args.expected_cohort_hash)
        elif args.mode == "compare":
            require(args.roots is not None, "three actual execution roots required")
            print(compare(args.inputs, args.roots, args.output, args.expected_cohort_hash, args.require_conclusive)["status"])
        else:
            execute_role(args.mode, args.inputs, args.output, args.expected_cohort_hash)
