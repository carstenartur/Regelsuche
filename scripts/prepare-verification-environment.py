#!/usr/bin/env python3
"""Create the pinned Python environment used by Gradle verification tasks."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import venv
from pathlib import Path

REQUIRED = {"jsonschema": "4.25.1"}


def python_path(root: Path) -> Path:
    return root / ("Scripts/python.exe" if os.name == "nt" else "bin/python")


def installed_versions(python: Path) -> dict[str, str]:
    program = (
        "import importlib.metadata as m, json; "
        "names=" + repr(sorted(REQUIRED)) + "; "
        "print(json.dumps({n:m.version(n) for n in names}))"
    )
    result = subprocess.run(
        [str(python), "-c", program],
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
    )
    if result.returncode != 0:
        return {}
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError:
        return {}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--venv", required=True, type=Path)
    args = parser.parse_args()

    root = args.venv.resolve()
    python = python_path(root)
    if not python.is_file():
        venv.EnvBuilder(with_pip=True, clear=False).create(root)

    versions = installed_versions(python)
    if versions != REQUIRED:
        requirements = [f"{name}=={version}" for name, version in REQUIRED.items()]
        subprocess.run(
            [str(python), "-m", "pip", "install", "--disable-pip-version-check", "--quiet", *requirements],
            check=True,
        )
        versions = installed_versions(python)
    if versions != REQUIRED:
        raise SystemExit(f"verification environment version mismatch: {versions!r}")

    marker = root / ".regelsuche-verification-environment.json"
    marker.write_text(json.dumps(versions, sort_keys=True) + "\n", encoding="utf-8")
    print(f"verification-python={python}")
    print(f"verification-packages={versions}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
