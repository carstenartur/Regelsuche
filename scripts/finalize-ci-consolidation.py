#!/usr/bin/env python3
"""One-shot migration helper for PR #510.

The temporary CI workflow runs this helper in two phases. ``prepare`` updates
checkout-owned capability contracts and switches the existing generator task to
write mode. After Gradle has regenerated the derived files, ``finalize``
restores strict check mode, restores the final thin workflow, deletes this
helper, commits the migration result and pushes the feature branch.
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BRANCH = "chore/consolidate-ci-workflows"

FINAL_WORKFLOW = """name: CI

on:
  push:
    branches: [ main ]
  pull_request:
    branches: [ main ]
  workflow_dispatch:

concurrency:
  group: ci-${{ github.repository }}-${{ github.ref }}
  cancel-in-progress: true

permissions:
  contents: read
  packages: read

jobs:
  verification:
    name: Checkout-local ciCheck
    runs-on: ubuntu-22.04
    timeout-minutes: 300
    env:
      AI_KNOWLEDGE_EXTRACTOR_ENABLED: 'true'
      GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
    steps:
      - uses: actions/checkout@v7
        with:
          fetch-depth: 0

      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: '21'

      - uses: gradle/actions/setup-gradle@v6.1.0
        with:
          gradle-home-cache-cleanup: true

      - name: Install checkout prerequisites
        run: |
          sudo apt-get update
          sudo apt-get install -y --no-install-recommends \\
            python3-venv \\
            z3
          ./gradlew --no-daemon \\
            :app:installPlaywrightHostDependencies \\
            --console=plain

      - name: Run the checkout-owned CI lifecycle
        shell: bash
        run: |
          set -o pipefail
          mkdir -p build/logs
          env -u GITHUB_SHA ./gradlew \\
            --no-daemon \\
            --no-configuration-cache \\
            ciCheck \\
            --console=plain \\
            --stacktrace \\
            2>&1 | tee build/logs/ci-check.log

      - name: Retain verification evidence
        if: always()
        uses: actions/upload-artifact@v7
        with:
          name: repository-verification
          path: |
            build/logs/**
            build/reports/**
            build/ai-knowledge/**
            build/independent-reproduction/**
            public/**
            **/build/reports/**
            **/build/test-results/**
            **/build/discovery-artifacts/**
            docs/benchmark-report.md
            docs/assets/benchmark-summary.json
            docs/generated/**
          include-hidden-files: true
          if-no-files-found: warn

  publish-pages:
    name: Publish generated reports
    needs: verification
    if: github.event_name == 'push' && github.ref == 'refs/heads/main'
    runs-on: ubuntu-latest
    concurrency:
      group: pages-${{ github.repository }}-main
      cancel-in-progress: false
    permissions:
      actions: read
      contents: read
      pages: write
      id-token: write
    environment:
      name: github-pages
      url: ${{ steps.deployment.outputs.page_url }}
    steps:
      - uses: actions/checkout@v7

      - uses: actions/download-artifact@v8.0.1
        with:
          name: repository-verification
          path: .

      - name: Assemble the static site
        run: |
          rm -rf build/pages-site
          mkdir -p build/pages-site
          cp -R docs/. build/pages-site/
          cp -R public/. build/pages-site/
          touch build/pages-site/.nojekyll

      - uses: actions/configure-pages@v6

      - uses: actions/upload-pages-artifact@v5
        with:
          path: build/pages-site

      - name: Deploy GitHub Pages
        id: deployment
        uses: actions/deploy-pages@v5
"""


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
    (ROOT / ".github" / "workflows" / "gradle.yml").write_text(
        FINAL_WORKFLOW,
        encoding="utf-8",
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
