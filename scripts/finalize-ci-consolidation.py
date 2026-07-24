#!/usr/bin/env python3
"""One-shot migration helper for PR #510.

The temporary CI workflow runs this helper in two phases. ``prepare`` updates
checkout-owned capability contracts and switches the existing generator task to
write mode. After Gradle has regenerated the derived files, ``finalize``
restores strict check mode, deletes this helper, commits the migration result
and pushes the feature branch. The final workflow is restored separately via
the repository API because the Actions token must not rewrite workflow files.
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BRANCH = "chore/consolidate-ci-workflows"


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(
            f"expected one occurrence in {path.relative_to(ROOT)}; "
            f"found {count}: {old!r}"
        )
    path.write_text(text.replace(old, new), encoding="utf-8")


def prepare() -> None:
    generator = ROOT / "scripts" / "generate-capability-status.py"
    replacements = {
        ".github/workflows/plugin-artifact-trust.yml":
            "scripts/verify-plugin-artifact-trust-evidence.py",
        ".github/workflows/plugin-artifact-index.yml":
            "scripts/verify-plugin-artifact-index-evidence.py",
        ".github/workflows/plugin-trust-store-revision.yml":
            "scripts/verify-plugin-trust-store-revision-evidence.py",
        "Required source, schema and dedicated workflow files are hash-bound by this status artifact.":
            "Required source, schema and checkout-local validator files are hash-bound by this status artifact.",
        "software contracts, schemas and dedicated workflow are present and hash-bound":
            "software contracts, schemas and checkout-local validators are present and hash-bound",
    }
    for old, new in replacements.items():
        replace_once(generator, old, new)

    replace_once(
        ROOT / "build.gradle",
        "'--check', '--check-docs'",
        "'--rewrite-docs'",
    )


def run(command: list[str]) -> None:
    subprocess.run(command, cwd=ROOT, check=True)


def finalize() -> None:
    replace_once(
        ROOT / "build.gradle",
        "'--rewrite-docs'",
        "'--check', '--check-docs'",
    )
    Path(__file__).unlink()

    run(["git", "config", "user.name", "github-actions[bot]"])
    run([
        "git", "config", "user.email",
        "github-actions[bot]@users.noreply.github.com",
    ])
    run(["git", "add", "-A"])
    status = subprocess.run(
        ["git", "diff", "--cached", "--quiet"],
        cwd=ROOT,
        check=False,
    )
    if status.returncode == 0:
        print("No migration changes to commit.")
        return
    if status.returncode != 1:
        raise SystemExit("cannot inspect staged migration changes")
    run([
        "git", "commit", "-m",
        "Fix capability contracts after workflow consolidation",
    ])
    run(["git", "push", "origin", f"HEAD:{BRANCH}"])


def main() -> int:
    if len(sys.argv) != 2 or sys.argv[1] not in {"prepare", "finalize"}:
        print(f"usage: {Path(sys.argv[0]).name} prepare|finalize", file=sys.stderr)
        return 2
    if sys.argv[1] == "prepare":
        prepare()
    else:
        finalize()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
