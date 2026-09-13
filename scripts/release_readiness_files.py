"""Descriptor-owned, no-follow reads for one retained release evidence root.

Requires POSIX openat-style operations. There is deliberately no weaker fallback
on platforms without O_NOFOLLOW and directory-relative descriptors.
"""

from __future__ import annotations

import os
from pathlib import Path
import stat


class EvidenceFiles:
    def __init__(self, root: Path):
        root = Path(root)
        if ".." in root.parts:
            raise ValueError("parent traversal in evidence root is forbidden")
        self.root = root.absolute()  # Lexical only: never resolve a symbolic root.
        self._root_fd = None
        self._bytes = {}

    def __enter__(self):
        if self._root_fd is not None:
            raise ValueError("evidence root is already open")
        self._bytes.clear()
        required = ("O_NOFOLLOW", "O_DIRECTORY", "O_CLOEXEC", "O_NONBLOCK")
        if (os.name != "posix" or any(not hasattr(os, flag) for flag in required)
                or os.open not in os.supports_dir_fd
                or os.stat not in os.supports_dir_fd
                or os.stat not in os.supports_follow_symlinks):
            raise ValueError(
                "UNSUPPORTED_PLATFORM: release evidence verification requires "
                "POSIX directory-relative opens and O_NOFOLLOW")
        descriptor = os.open(self.root.anchor, self._directory_flags())
        try:
            for part in self.root.parts[1:]:
                child = os.open(part, self._directory_flags(), dir_fd=descriptor)
                os.close(descriptor)
                descriptor = child
            self._root_fd = descriptor
            return self
        except BaseException:
            os.close(descriptor)
            raise

    def __exit__(self, *_):
        if self._root_fd is not None:
            os.close(self._root_fd)
            self._root_fd = None

    @staticmethod
    def _directory_flags():
        return os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC

    def _parent(self, relative):
        path = Path(relative)
        if path.is_absolute() or ".." in path.parts or not path.parts:
            raise ValueError("evidence member must be a relative child path")
        if self._root_fd is None:
            raise ValueError("evidence root is not open")
        descriptor = os.dup(self._root_fd)
        try:
            for part in path.parts[:-1]:
                child = os.open(part, self._directory_flags(), dir_fd=descriptor)
                os.close(descriptor)
                descriptor = child
            return descriptor, path.name
        except BaseException:
            os.close(descriptor)
            raise

    def read_bytes(self, relative):
        """Parse and hash the same immutable first-read bytes throughout a decision."""
        if self._root_fd is None:
            raise ValueError("evidence root is not open")
        relative = Path(relative)
        if relative not in self._bytes:
            parent, name = self._parent(relative)
            try:
                descriptor = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC
                                     | os.O_NONBLOCK, dir_fd=parent)
                with os.fdopen(descriptor, "rb") as stream:
                    if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):
                        raise ValueError("evidence member is not a regular file: " + str(relative))
                    self._bytes[relative] = stream.read()
            finally:
                os.close(parent)
        return self._bytes[relative]

    def present(self, relative):
        parent, name = self._parent(relative)
        try:
            try:
                os.stat(name, dir_fd=parent, follow_symlinks=False)
                return True
            except FileNotFoundError:
                return False
        finally:
            os.close(parent)
