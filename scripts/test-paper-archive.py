#!/usr/bin/env python3
"""Offline negative controls for the source and deposit binding."""
import hashlib
import io
import json
from pathlib import Path
import runpy
import subprocess
import tarfile
import tempfile
import unittest
import zipfile

api = runpy.run_path(str(Path(__file__).with_name("build-paper-archive.py")))


class Bindings(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.repo = self.root / "checkout"
        self.repo.mkdir()
        self.git("init", "-q", "--object-format=sha1")
        (self.repo / "evidence").write_bytes(b"retained\n")
        self.git("add", "evidence")
        self.git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "evidence")
        self.revision = self.git("rev-parse", "HEAD").decode().strip()

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.repo), *args])

    def unpack(self, commit, source, revision):
        target = self.root / "unpacked"
        target.mkdir()
        return api["unpack"](commit, source, target, revision)

    def test_unmodified_capture_matches_git_tree(self):
        commit, source = api["capture"](self.repo, self.revision)
        self.assertEqual(self.git("rev-parse", "HEAD^{tree}").decode().strip(), self.unpack(commit, source, self.revision))

    def test_crlf_checkout_filters_do_not_change_archived_blob_bytes(self):
        (self.repo / ".gitattributes").write_text("*.bat text eol=crlf\n")
        (self.repo / "launch.bat").write_bytes(b"echo hello\r\n")
        self.git("add", ".gitattributes", "launch.bat")
        self.git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "batch")
        revision = self.git("rev-parse", "HEAD").decode().strip()
        commit, source = api["capture"](self.repo, revision)
        self.unpack(commit, source, revision)
        self.assertEqual(b"echo hello\n", (self.root / "unpacked/launch.bat").read_bytes())

    def test_working_tree_and_requested_revision_must_match(self):
        with self.assertRaisesRegex(ValueError, "clean tracked checkout"):
            api["capture"](self.repo, "0" * 40)
        (self.repo / "evidence").write_text("edited")
        with self.assertRaisesRegex(ValueError, "clean tracked checkout"):
            api["capture"](self.repo, self.revision)

    def test_substituted_commit_rejected(self):
        commit, source = api["capture"](self.repo, self.revision)
        with self.assertRaisesRegex(ValueError, "commit differs"):
            self.unpack(commit + b"x", source, self.revision)

    def test_changed_evidence_or_executable_bit_rejected_by_tree(self):
        commit, _ = api["capture"](self.repo, self.revision)
        for data, mode in ((b"tampered\n", 0o644), (b"retained\n", 0o755)):
            with self.subTest(mode=mode):
                buf = io.BytesIO()
                with tarfile.open(fileobj=buf, mode="w:gz") as archive:
                    item = tarfile.TarInfo("evidence")
                    item.mode, item.size = mode, len(data)
                    archive.addfile(item, io.BytesIO(data))
                target = self.root / str(mode)
                target.mkdir()
                with self.assertRaisesRegex(ValueError, "committed tree"):
                    api["unpack"](commit, buf.getvalue(), target, self.revision)

    def test_link_and_path_escape_rejected_before_extraction(self):
        commit, _ = api["capture"](self.repo, self.revision)
        for name, kind in (("../outside", tarfile.REGTYPE), ("link", tarfile.SYMTYPE)):
            buf = io.BytesIO()
            with tarfile.open(fileobj=buf, mode="w:gz") as archive:
                item = tarfile.TarInfo(name)
                item.type = kind
                archive.addfile(item)
            with self.assertRaisesRegex(ValueError, "source (path|member)"):
                api["unpack"](commit, buf.getvalue(), self.root, self.revision)

    def test_stale_pdf_and_extra_member_rejected(self):
        archive = self.root / "archive.zip"
        for extra in (False, True):
            with zipfile.ZipFile(archive, "w") as bundle:
                bundle.writestr("manuscript.pdf", b"edited")
                bundle.writestr("manifest.json", json.dumps({"files": {"manuscript.pdf": hashlib.sha256(b"expected").hexdigest()}}))
                if extra:
                    bundle.writestr("unlisted", b"x")
            with self.assertRaisesRegex(ValueError, "inventory|digest"):
                api["replay_inputs"](archive)


if __name__ == "__main__":
    unittest.main()
