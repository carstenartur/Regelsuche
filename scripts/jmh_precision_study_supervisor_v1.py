"""One private Linux subreaper per command, including detached JVM descendants."""

from __future__ import annotations

import ctypes
import json
import os
import select
import signal
import subprocess
import sys
import time
from pathlib import Path

SUPERVISION = "linux-subreaper-pidfd/v1"


def fields(path):
    return {key: value.strip() for key, value in
            (line.split(":", 1) for line in Path(path).read_text().splitlines() if ":" in line)}


def start_time(proc_pid):
    return Path(f"/proc/{proc_pid}/stat").read_text().rsplit(")", 1)[1].split()[19]


def signalable_pid(nspids, namespace_depth):
    if len(nspids) < namespace_depth:
        raise ValueError("descendant PID namespace cannot be translated")
    return nspids[namespace_depth - 1]


class Descendants:
    """Only children of this otherwise child-free supervisor are owned here."""

    def __init__(self):
        own = fields("/proc/self/status")
        pids = list(map(int, own["NSpid"].split()))
        if pids[-1] != os.getpid():
            raise ValueError("unsupported /proc PID namespace view")
        self.proc_pid = int(own["Pid"])
        self.depth = len(pids)
        self.start = start_time(self.proc_pid)
        self.child_exit_count = 0
        self.child_failure_count = 0
        self.child_failure_codes = set()
        fd = os.pidfd_open(os.getpid())
        try:
            if int(fields(f"/proc/self/fdinfo/{fd}")["Pid"]) != self.proc_pid:
                raise ValueError("pidfd and /proc namespace views differ")
        finally:
            os.close(fd)
        libc = ctypes.CDLL(None, use_errno=True)
        if libc.prctl(36, 1, 0, 0, 0) != 0:  # PR_SET_CHILD_SUBREAPER, unprivileged and process-local.
            raise OSError(ctypes.get_errno(), "cannot become a child subreaper")

    def collect(self):
        handles = []
        visited = set()
        children_by_parent = {}
        # /proc/<pid>/task/<tid>/children needs CONFIG_CHECKPOINT_RESTORE and is
        # absent on some supported kernels. Read only stat ancestry here; status
        # and pidfds are opened only after the edge is in this supervisor's tree.
        for path in Path("/proc").iterdir():
            if not path.name.isdecimal():
                continue
            try:
                values = (path / "stat").read_text().rsplit(")", 1)[1].split()
                children_by_parent.setdefault(int(values[1]), []).append((int(path.name), values[19]))
            except (FileNotFoundError, ProcessLookupError, PermissionError):
                pass

        def walk(parent, parent_start):
            try:
                if start_time(parent) != parent_start:
                    return
            except (FileNotFoundError, ProcessLookupError):
                return
            for proc_pid, snapshot_start in children_by_parent.get(parent, []):
                if proc_pid in visited:
                    continue
                visited.add(proc_pid)
                fd = None
                try:
                    status = fields(f"/proc/{proc_pid}/status")
                    if int(status["PPid"]) != parent:
                        continue  # Adoption races are picked up from the supervisor on the next scan.
                    started = start_time(proc_pid)
                    if started != snapshot_start:
                        continue
                    local_pid = signalable_pid(list(map(int, status["NSpid"].split())), self.depth)
                    fd = os.pidfd_open(local_pid)
                    bound = fields(f"/proc/self/fdinfo/{fd}")
                    if int(bound["Pid"]) != proc_pid or start_time(proc_pid) != started:
                        continue  # Never signal a reused or incorrectly translated PID.
                    handles.append((fd, local_pid))
                    fd = None
                    walk(proc_pid, started)
                except (FileNotFoundError, ProcessLookupError):
                    pass
                finally:
                    if fd is not None:
                        os.close(fd)

        try:
            walk(self.proc_pid, self.start)
            return handles
        except BaseException:
            for fd, _ in handles:
                os.close(fd)
            raise

    def retain_child_exit(self, status):
        self.child_exit_count += 1
        code = os.waitstatus_to_exitcode(status)
        if code != 0:
            self.child_failure_count += 1
            self.child_failure_codes.add(code)

    def remaining(self, process):
        process.poll()  # Let Popen reap its own direct child and preserve its actual exit code.
        live = 0
        for fd, local_pid in self.collect():
            try:
                if select.select([fd], [], [], 0)[0]:
                    if local_pid != process.pid:
                        try:
                            child, status = os.waitpid(local_pid, os.WNOHANG)
                            if child:
                                self.retain_child_exit(status)
                        except ChildProcessError:
                            pass
                else:
                    live += 1
            finally:
                os.close(fd)
        if process.returncode is None:
            return max(live, 1)
        # A child can be adopted while /proc is being scanned. The kernel's
        # child wait state closes that race; a private supervisor has no unrelated
        # children whose status could be consumed here.
        while True:
            try:
                child, status = os.waitpid(-1, os.WNOHANG)
                if child == 0:
                    return max(live, 1)
                self.retain_child_exit(status)
            except ChildProcessError:
                break
        return live

    def send(self, signum):
        handles = self.collect()
        for fd, _ in reversed(handles):
            try:
                signal.pidfd_send_signal(fd, signum)
            except ProcessLookupError:
                pass
            finally:
                os.close(fd)

    def finish(self, process, deadline):
        while self.remaining(process):
            if time.monotonic() >= deadline:
                return False
            time.sleep(.01)
        return True

    def terminate(self, process):
        self.send(signal.SIGTERM)
        if self.finish(process, time.monotonic() + .5):
            return True
        deadline = time.monotonic() + 1
        while self.remaining(process):
            self.send(signal.SIGKILL)
            if time.monotonic() >= deadline:
                return False
            time.sleep(.01)
        return True


def supervise(request):
    ownership = Descendants()
    process = None
    status, code, remaining = "START_ERROR", None, 0
    try:
        process = subprocess.Popen(request["command"], cwd=request["root"], close_fds=True,
                                   stderr=subprocess.STDOUT)
        try:
            code = process.wait(timeout=max(.001, request["deadline"] - time.monotonic()))
            status = "COMPLETED" if code == 0 else "PROCESS_ERROR"
            if code == 0 and not ownership.finish(process, request["deadline"]):
                status = "TIMEOUT"
            elif code == 0 and ownership.child_failure_count:
                status = "CHILD_PROCESS_ERROR"
        except subprocess.TimeoutExpired:
            status = "TIMEOUT"
        if status != "COMPLETED":
            if not ownership.terminate(process):
                status = "CLEANUP_ERROR"
            code = process.poll()
    except BaseException as error:
        print(f"STUDY SUPERVISOR ERROR: {type(error).__name__}: {error}", flush=True)
        if process is not None and not ownership.terminate(process):
            status = "CLEANUP_ERROR"
        else:
            status = "START_ERROR"
    if process is not None:
        remaining = ownership.remaining(process)
        if remaining:
            status = "CLEANUP_ERROR"
    if status != "COMPLETED":
        print(f"STUDY {status}: owned descendants terminated; no retry.", flush=True)
    return dict(status=status, exitCode=code, remainingDescendants=remaining,
                supervision=SUPERVISION, pidNamespaceDepth=ownership.depth,
                adoptedChildExitCount=ownership.child_exit_count,
                adoptedChildFailureCount=ownership.child_failure_count,
                adoptedChildFailureExitCodes=sorted(ownership.child_failure_codes))


def main():
    output_fd = int(sys.argv[1])
    def interrupted(*_):
        raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM, interrupted)
    try:
        result = supervise(json.load(sys.stdin))
    except BaseException as error:
        result = dict(status="SUPERVISOR_ERROR", exitCode=None, remainingDescendants=None,
                      supervision=SUPERVISION, error=f"{type(error).__name__}: {error}")
    os.write(output_fd, json.dumps(result).encode())
    os.close(output_fd)


if __name__ == "__main__":
    main()
