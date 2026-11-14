#!/usr/bin/env python3
"""Build a verified paper package from an exact Git revision or its retained source snapshot."""
import argparse
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import platform
import re
import runpy
import subprocess
import sys
import tarfile
import tempfile
import zipfile
from importlib.metadata import version

COMMIT = "provenance/commit.raw"
SOURCE = "provenance/source.tar.gz"
BUILD = "scripts/build-paper-archive.py"
PROVENANCE = "manifest.json"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def git_id(kind, payload):
    return hashlib.sha1(kind.encode() + b" " + str(len(payload)).encode() + b"\0" + payload).hexdigest()


def run_git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args])


def capture(root, revision):
    """Read committed objects. Untracked files never enter the snapshot."""
    current = run_git(root, "rev-parse", "--verify", "HEAD").decode().strip()
    if revision != current or run_git(root, "status", "--porcelain", "--untracked-files=no"):
        raise ValueError("the requested revision must match a clean tracked checkout")
    commit = run_git(root, "cat-file", "commit", revision)
    # Git's archive export can apply checkout EOL filters (e.g. *.bat).
    # Build the source snapshot from the exact blob bytes and executable modes.
    entries = run_git(root, "ls-tree", "-rz", "--full-tree", revision).split(b"\0")[:-1]
    names = []
    for entry in entries:
        metadata, path = entry.split(b"\t", 1)
        mode, kind, object_id = metadata.split()
        if kind != b"blob" or mode not in (b"100644", b"100755"):
            raise ValueError(f"unsupported Git member {path!r}")
        names.append((path.decode("utf-8"), mode, object_id))
    packed = io.BytesIO()
    with tempfile.TemporaryFile() as requests:
        requests.writelines(object_id + b"\n" for _, _, object_id in names)
        requests.seek(0)
        with subprocess.Popen(["git", "-C", str(root), "cat-file", "--batch"], stdin=requests,
                              stdout=subprocess.PIPE) as reader:
            with gzip.GzipFile(fileobj=packed, mode="wb", compresslevel=6, mtime=0) as compressed:
                with tarfile.open(fileobj=compressed, mode="w|", format=tarfile.PAX_FORMAT) as tar:
                    for name, mode, object_id in names:
                        actual, kind, size = reader.stdout.readline().split()
                        if actual != object_id or kind != b"blob":
                            raise ValueError(f"unexpected object for {name}")
                        item = tarfile.TarInfo(name)
                        item.mode, item.size = int(mode[-3:], 8), int(size)
                        tar.addfile(item, reader.stdout)
                        if reader.stdout.read(1) != b"\n":
                            raise ValueError(f"incomplete Git blob for {name}")
            if reader.wait() != 0:
                raise ValueError("failed to read committed source objects")
    return commit, packed.getvalue()


def tree_hash(directory):
    entries = []
    for path in directory.iterdir():
        name = os.fsencode(path.name)
        if path.is_dir():
            entries.append((name + b"/", b"40000 " + name, tree_hash(path)))
        else:
            mode = b"100755 " if path.stat().st_mode & 0o111 else b"100644 "
            entries.append((name, mode + name, git_id("blob", path.read_bytes())))
    return git_id("tree", b"".join(label + b"\0" + bytes.fromhex(digest)
                                  for _, label, digest in sorted(entries)))


def unpack(commit, compressed, root, revision):
    if git_id("commit", commit) != revision:
        raise ValueError("source commit differs from the required revision")
    tree_line = re.fullmatch(rb"tree ([0-9a-f]{40})", commit.splitlines()[0])
    if not tree_line:
        raise ValueError("missing Git tree in source commit")
    with tarfile.open(fileobj=io.BytesIO(compressed), mode="r:gz") as tar:
        for item in tar:
            path = Path(item.name)
            if path.is_absolute() or ".." in path.parts or ".git" in path.parts or not path.parts:
                raise ValueError(f"invalid source path {path}")
            target = root / path
            if item.isdir():
                target.mkdir(parents=True, exist_ok=True)
            elif item.isfile():
                target.parent.mkdir(parents=True, exist_ok=True)
                with target.open("xb") as dest:
                    dest.write(tar.extractfile(item).read())
                target.chmod(0o755 if item.mode & 0o111 else 0o644)
            else:
                raise ValueError(f"unexpected source member {path}")
    tree = tree_hash(root)
    if tree != tree_line.group(1).decode():
        raise ValueError("source contents or file modes differ from the committed tree")
    return tree


def toolchain():
    manifest = {"python": platform.python_version(),
                "pandoc": subprocess.check_output(["pandoc", "--version"], text=True).splitlines()[0],
                "inkscape": subprocess.check_output(["inkscape", "--version"], text=True).splitlines()[0]}
    for package in ("jsonschema", "reportlab", "Pillow"):
        manifest[package] = version(package)
    return manifest


def json_bytes(value):
    return (json.dumps(value, indent=2, sort_keys=True, ensure_ascii=False) + "\n").encode()


def replay_inputs(source):
    with zipfile.ZipFile(source) as bundle:
        names = bundle.namelist()
        if len(names) != len(set(names)):
            raise ValueError("duplicate archive member")
        receipt = json.loads(bundle.read(PROVENANCE))
        if set(names) != set(receipt["files"]) | {PROVENANCE}:
            raise ValueError("package inventory differs")
        for name, digest in receipt["files"].items():
            if sha(bundle.read(name)) != digest:
                raise ValueError(f"package digest differs: {name}")
        return bundle.read(COMMIT), bundle.read(SOURCE), receipt


def write_bundle(output, files):
    with zipfile.ZipFile(output, "w") as bundle:
        for name, data in sorted(files.items()):
            info = zipfile.ZipInfo(name, (2000, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            bundle.writestr(info, data, compress_type=zipfile.ZIP_STORED)


def build(revision, output, source=None):
    if not re.fullmatch(r"[a-f0-9]{40}", revision):
        raise ValueError("expected the full Git commit SHA")
    checkout = Path(__file__).resolve().parents[1]
    previous = None
    if source:
        commit, archive, previous = replay_inputs(source)
    else:
        commit, archive = capture(checkout, revision)
    output = output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists():
        raise ValueError("choose a fresh archive output path")
    with tempfile.TemporaryDirectory(dir=output.parent, prefix=".paper-") as tmp:
        staging = Path(tmp)
        root = staging / "src"
        root.mkdir()
        tree = unpack(commit, archive, root, revision)
        if Path(__file__).read_bytes() != (root / BUILD).read_bytes():
            raise ValueError("archive driver differs from the committed revision")
        tools = toolchain()
        required = json.loads((root / "paper/archive-toolchain.json").read_bytes())
        if tools != required:
            raise ValueError(f"paper toolchain differs: expected {required}, found {tools}")
        # The foundation verifier compares two fresh generations with the tracked outputs,
        # checks the corpus and raw matrix bindings, and exercises its negative controls.
        env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1", TZ="UTC", SOURCE_DATE_EPOCH="946684800")
        subprocess.run([sys.executable, "-B", "scripts/verify-paper-foundation-reproduction.py",
                        "--repository-root", str(root)], cwd=root, env=env, check=True)
        pdf = staging / "manuscript.pdf"
        renderer = runpy.run_path(str(root / "scripts/render-paper-pdf.py"))
        renderer["render"](root, revision, pdf)
        font_license = Path(renderer["reportlab"].__file__).parent / "fonts/bitstream-vera-license.txt"
        files = {COMMIT: commit, SOURCE: archive, "manuscript.pdf": pdf.read_bytes(),
                 "licenses/bitstream-vera-license.txt": font_license.read_bytes()}
        for name in ("LICENSE", "CITATION.cff", "THIRD_PARTY_NOTICES.md", "paper/README.md",
                     "paper/paper-artifact-manifest.json", "paper/archive-toolchain.json"):
            files[name] = (root / name).read_bytes()
        for item in sorted((root / "paper/generated").iterdir()):
            files[item.relative_to(root).as_posix()] = item.read_bytes()
        receipt = {"schema": "regelsuche.paper-archive/v1", "status": "FOUNDATION", "revision": revision,
                   "sourceTree": tree, "environment": tools, "files": {name: sha(data) for name, data in files.items()}}
        if previous is not None and receipt != previous:
            raise ValueError("reproduced package differs from the retained receipt")
        files[PROVENANCE] = json_bytes(receipt)
        staged = staging / "package.zip"
        write_bundle(staged, files)
        if source and sha(source.read_bytes()) != sha(staged.read_bytes()):
            raise ValueError("replayed package bytes differ")
        os.link(staged, output)
    return output


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--revision", required=True, help="full 40-character Git commit SHA")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--replay", type=Path, help="read and verify the retained source without a Git checkout")
    args = parser.parse_args()
    print(build(args.revision, args.output, args.replay))
