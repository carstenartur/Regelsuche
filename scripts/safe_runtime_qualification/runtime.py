"""Bounded process and input boundary for the installed public application."""
from __future__ import annotations

from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import os
from pathlib import Path
import shlex
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import zipfile

from protocol import canonical, content_hash, read_json, require

REPOSITORY = Path(__file__).resolve().parents[2]
INPUT_FILES = ("authorization-bundle.json", "split-manifest.json", "validation-selection.json",
               "final-test-evaluation.json", "counterexample-evidence.json", "authorization-receipt.json")
ANCHORS = ("de/regelsuche/runtime/SafeRuntimeAdapter.class", "de/regelsuche/transform/RewriteRule.class",
           "de/regelsuche/search/reachability/OccurrenceAwareSharedRulePreparationCoordinator.class",
           "de/regelsuche/math/algorithms/linalg/MatrixPreparation.class", "de/regelsuche/egraph/EGraph.class",
           "de/regelsuche/evolution/LearnedPatternRuleAuthorizationService.class",
           "de/regelsuche/plugin/PatternTransformation.class")


def checked(command, **kwargs):
    result = subprocess.run([str(item) for item in command], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            timeout=kwargs.pop("timeout", 120), **kwargs)
    require(result.returncode == 0, f"command failed ({result.returncode}): {command[0]}\n"
            + result.stdout.decode("utf-8", "replace")[-4000:] + result.stderr.decode("utf-8", "replace")[-4000:])
    return result


def tree_hashes(root: Path) -> dict:
    require(root.is_dir(), f"missing input directory {root}")
    result = {}
    for path in sorted(root.rglob("*")):
        require(not path.is_symlink(), f"symlink is not a fixed input: {path}")
        if path.is_file():
            result[path.relative_to(root).as_posix()] = content_hash(path.read_bytes())
    return result


def verify_inputs(root: Path, manifest: dict) -> dict:
    inputs = read_json((root / "inputs.json").read_bytes())
    require(inputs == {"schema": "regelsuche.safe-runtime-public-authority-inputs/v1",
        "fixtureId": manifest["fixtureId"], "subjectRevision": "0123456789abcdef0123456789abcdef01234567",
        "issuedAt": "2026-09-01T00:00:00Z", "authorizedAt": "2026-09-10T00:00:00Z",
        "positiveExpiry": "2100-01-01T00:00:00Z", "negativeExpiry": "2026-09-11T00:00:00Z",
        "explicitContractFixture": True,
        "promotedRuleIds": ["learned.promoted.e6da03293d04583e.mul-one", "learned.promoted.e6da03293d04583e.add-zero"]},
        "public input manifest differs from the fixed authorization contract")
    now = datetime.now(timezone.utc)
    require(datetime(2026, 9, 11, tzinfo=timezone.utc) <= now < datetime(2100, 1, 1, tzinfo=timezone.utc),
            "public authority contract is outside its declared real-clock window")
    before = tree_hashes(root)
    with tempfile.TemporaryDirectory(prefix="safe-public-input-verify-") as temporary:
        for variant in ("valid", "expired"):
            expected = {"schema": "regelsuche.learned-runtime-authority-manifest/v1",
                "repositoryRevision": inputs["subjectRevision"],
                "patterns": [{"id": gene, "root": f"{variant}/leaf-{gene}"} for gene in ("mul-one", "add-zero")],
                "programs": [{"id": "qualification-normalization-program", "root": variant,
                              "leafAuthorityIds": ["mul-one", "add-zero"]}]}
            require(read_json((root / f"runtime-{variant}.json").read_bytes()) == expected,
                    "runtime learned input topology differs from the public manifest")
            for gene in ("mul-one", "add-zero"):
                leaf = root / variant / f"leaf-{gene}"
                view = Path(temporary) / variant / gene
                view.mkdir(parents=True)
                for name in INPUT_FILES:
                    shutil.copyfile(leaf / name, view / name)
                checked([sys.executable, REPOSITORY / "scripts/verify-learned-pattern-authorization.py",
                         "--root", view, "--schemas", REPOSITORY / "docs/schemas"])
            checked([sys.executable, REPOSITORY / "scripts/verify-learned-rewrite-program-authorization.py",
                     "--root", root / variant, "--schemas", REPOSITORY / "docs/schemas"])
    require(tree_hashes(root) == before, "input authority changed during independent verification")
    return {"manifest": inputs, "files": before}


def compile_plugin(app_home: Path, directory: Path) -> Path:
    classes = directory / "plugin-classes"
    classes.mkdir(parents=True)
    checked(["javac", "--release", "25", "-encoding", "UTF-8", "-cp", str(app_home / "lib/*"), "-d", classes,
             REPOSITORY / "reproduction/safe-runtime/QualificationPlugin.java"])
    plugin = directory / "plugins/public-qualification.jar"
    plugin.parent.mkdir()
    with zipfile.ZipFile(plugin, "w", compression=zipfile.ZIP_STORED) as archive:
        entries = {path.relative_to(classes).as_posix(): path.read_bytes() for path in classes.rglob("*.class")}
        entries["META-INF/services/de.regelsuche.plugin.RegelsuchePlugin"] = b"de.regelsuche.qualification.QualificationPlugin\n"
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data)
    return plugin


def physical_identity(app_home: Path, plugin: Path) -> dict:
    jars = sorted((app_home / "lib").glob("*.jar"))
    require(jars and (app_home / "bin/app").is_file(), "expected the installed application distribution")
    sources, found = {plugin}, set()
    for jar in jars:
        with zipfile.ZipFile(jar) as archive:
            anchors = set(ANCHORS) & set(archive.namelist())
            if anchors:
                sources.add(jar)
                found |= anchors
    require(found == set(ANCHORS), "installed application lacks an implementation anchor")
    classes = {}
    for source in sorted(sources):
        with zipfile.ZipFile(source) as archive:
            for name in archive.namelist():
                if name.endswith(".class") and not name.endswith("module-info.class"):
                    raw = archive.read(name)
                    require(name not in classes or classes[name] == raw, f"ambiguous implementation class {name}")
                    classes[name] = raw
    digest = hashlib.sha256(b"regelsuche.runtime-class-content/v1")
    for name, raw in sorted(classes.items()):
        digest.update(name.encode("utf-8") + b"\0" + hashlib.sha256(raw).digest())
    return {"implementation": {"revision": "regelsuche.runtime-class-content/v1",
        "contentHash": "sha256:" + digest.hexdigest(), "authorityRevisionKind": "RUNTIME_CLASS_SHA256_PREFIX_160"},
        "applicationFiles": {path.name: content_hash(path.read_bytes()) for path in jars},
        "pluginHash": content_hash(plugin.read_bytes())}


class Runtime:
    def __init__(self, app_home: Path, inputs: Path, working: Path, variant="valid"):
        self.home, self.working = app_home.resolve(), working.resolve()
        self.env = {key: value for key, value in os.environ.items()
                    if key in {"PATH", "JAVA_HOME", "HOME", "TMPDIR", "SYSTEMROOT"}}
        self.env.update(LANG="C.UTF-8", LC_ALL="C.UTF-8", TZ="UTC", REGELSUCHE_PERSISTENCE_MODE="IN_MEMORY",
                        JAVA_OPTS=shlex.join(["-Xmx384m", "-Dfile.encoding=UTF-8",
                                             f"-Dregelsuche.runtime.learnedManifest={inputs.resolve() / ('runtime-' + variant + '.json')}"]))

    def cli(self, value: bytes, replay=False):
        with tempfile.NamedTemporaryFile(dir=self.working, suffix=".json") as request:
            request.write(value)
            request.flush()
            return subprocess.run([str(self.home / "bin/app"), "transform",
                                   "--runtime-replay" if replay else "--runtime-request", request.name],
                                  cwd=self.working, env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)

    @contextmanager
    def server(self):
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
        with tempfile.TemporaryFile() as log:
            process = subprocess.Popen([str(self.home / "bin/app"), "serve", "--host", "127.0.0.1", "--port", str(port)],
                                       cwd=self.working, env=self.env, stdout=log, stderr=log)
            try:
                deadline = time.monotonic() + 60
                while time.monotonic() < deadline:
                    if process.poll() is not None:
                        log.seek(0)
                        raise ValueError("workbench process stopped: " + log.read().decode("utf-8", "replace")[-4000:])
                    try:
                        with socket.create_connection(("127.0.0.1", port), timeout=.1):
                            break
                    except OSError:
                        time.sleep(.1)
                else:
                    raise ValueError("bounded workbench startup timed out")
                yield f"http://127.0.0.1:{port}/api/search"
            finally:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)


def http(url: str, value: dict, replay=False):
    request = urllib.request.Request(url, canonical({"runtimeArtifact" if replay else "runtimeRequest": value}),
                                     {"Content-Type": "application/json"}, method="POST")
    # An ambient proxy must not redirect the actual local Workbench boundary.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        response = opener.open(request, timeout=120)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, dict(response.headers), response.read()
