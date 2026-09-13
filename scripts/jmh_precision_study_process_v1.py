"""Bounded subprocess execution and provenance for the preregistered JMH study."""

from __future__ import annotations

import datetime
import hashlib
import os
import platform
import json
import sys
import subprocess
import time
import zipfile
from pathlib import Path

from jmh_precision_study_v1 import canonical, load_json, require, sha256, verify_launch


def utc_now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def execute(command, root, log, timeout):
    """One command in a private Linux supervisor; detached children remain owned."""
    require(sys.platform == "linux", "study process supervision requires Linux")
    require(timeout > 0, "process timeout must be positive")
    started = time.monotonic()
    receipt = dict(command=command, startedAt=utc_now(), timeoutSeconds=timeout)
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open("xb") as stream:
        read_fd, write_fd = os.pipe()
        supervisor = None
        try:
            supervisor = subprocess.Popen(
                [sys.executable, "-B", str(Path(__file__).with_name("jmh_precision_study_supervisor_v1.py")),
                 str(write_fd)], stdin=subprocess.PIPE, stdout=stream, stderr=subprocess.STDOUT,
                pass_fds=(write_fd,))
            os.close(write_fd)
            write_fd = None
            request = dict(command=command, root=str(root), deadline=started + timeout)
            supervisor.communicate(json.dumps(request).encode(), timeout=timeout + 3)
            result = os.read(read_fd, 4096)
            receipt.update(json.loads(result) if result else dict(status="SUPERVISOR_ERROR", exitCode=None))
        except BaseException as error:
            if supervisor is not None and supervisor.poll() is None:
                supervisor.terminate()
                try:
                    supervisor.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    supervisor.kill()
                    supervisor.wait()
            receipt.update(status="SUPERVISOR_ERROR", exitCode=None, error=str(error))
            stream.write(f"\nSTUDY SUPERVISOR_ERROR: {error}\n".encode())
            if isinstance(error, (KeyboardInterrupt, SystemExit)):
                raise
        finally:
            os.close(read_fd)
            if write_fd is not None:
                os.close(write_fd)
    receipt.update(finishedAt=utc_now(), elapsedSeconds=time.monotonic() - started, logSha256=sha256(log))
    return receipt


def git(root, *arguments):
    return subprocess.check_output(["git", "-C", str(root), *arguments], text=True).strip()


def source_identity(root):
    require(not git(root, "status", "--porcelain"), "study requires a clean committed checkout")
    return dict(sourceRevision=git(root, "rev-parse", "HEAD"),
                sourceTree=git(root, "rev-parse", "HEAD^{tree}"))


def jar_identity(path):
    """Retain raw bytes and a content identity independent of ZIP entry timestamps."""
    try:
        with zipfile.ZipFile(path) as jar:
            names = [entry.filename for entry in jar.infolist() if not entry.is_dir()]
            require(len(names) == len(set(names)), "duplicate jar entry")
            content = {name: hashlib.sha256(jar.read(name)).hexdigest() for name in sorted(names)}
    except zipfile.BadZipFile as error:
        raise ValueError(f"invalid benchmark jar: {error}") from error
    return dict(path=str(path), sha256=sha256(path),
                contentSha256=hashlib.sha256(canonical(content).encode()).hexdigest())


def shared_launch_identity(runner, policy):
    event_path = os.environ.get("GITHUB_EVENT_PATH")
    require(event_path, "shared launch event file missing")
    event = load_json(event_path)
    try:
        head = event["pull_request"]["head"]
        launch = dict(repository=runner.get("GITHUB_REPOSITORY"), eventName=runner.get("GITHUB_EVENT_NAME"),
                      eventAction=event["action"], headBranch=head["ref"], headRepository=head["repo"]["full_name"],
                      headSha=head["sha"], pullRequestNumber=event["number"],
                      runAttempt=runner.get("GITHUB_RUN_ATTEMPT"), job=runner.get("GITHUB_JOB"))
    except (KeyError, TypeError) as error:
        raise ValueError("shared launch event identity is incomplete") from error
    verify_launch(launch, runner, policy)
    return launch


def runner_identity():
    keys = ("GITHUB_ACTIONS", "GITHUB_REPOSITORY", "GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT",
            "GITHUB_JOB", "GITHUB_SHA", "GITHUB_EVENT_NAME", "GITHUB_HEAD_REF",
            "RUNNER_NAME", "RUNNER_ENVIRONMENT", "RUNNER_OS",
            "RUNNER_ARCH", "ImageOS", "ImageVersion")
    result = {key: os.environ.get(key) for key in keys}
    result["platform"] = platform.platform()
    result["logicalCpuCount"] = os.cpu_count()
    boot_id = Path("/proc/sys/kernel/random/boot_id")
    result["bootId"] = boot_id.read_text().strip() if boot_id.is_file() else None
    for name, path in (("cpuInfo", "/proc/cpuinfo"), ("memoryInfo", "/proc/meminfo"),
                       ("osRelease", "/etc/os-release"), ("cpuQuota", "/sys/fs/cgroup/cpu.max"),
                       ("memoryLimit", "/sys/fs/cgroup/memory.max")):
        candidate = Path(path)
        result[name] = candidate.read_text() if candidate.is_file() else None
    result["evidenceKind"] = ("GITHUB_SHARED_RUNNER" if
                              result["GITHUB_ACTIONS"] == "true" and
                              result["RUNNER_ENVIRONMENT"] == "github-hosted" else "LOCAL_CONTROL")
    return result
