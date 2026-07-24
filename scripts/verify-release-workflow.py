#!/usr/bin/env python3
"""Verify the release workflow and metadata updater from a plain checkout."""

from __future__ import annotations

import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / '.github/workflows/release.yml'
UPDATER = ROOT / '.github/scripts/update-release-metadata.py'
METADATA = (
    'CITATION.cff',
    'CITATION.md',
    '.zenodo.json',
    'codemeta.json',
    'release.properties',
)


def require(text: str, needle: str) -> None:
    if needle not in text:
        raise ValueError(
            f'release workflow is missing required contract text: {needle!r}'
        )


def forbid(text: str, needle: str) -> None:
    if needle in text:
        raise ValueError(f'release workflow contains forbidden bypass: {needle!r}')


def verify_workflow() -> None:
    text = WORKFLOW.read_text(encoding='utf-8')
    for needle in (
        "runs-on: ubuntu-22.04",
        "z3=4.8.12-1",
        "AI_KNOWLEDGE_EXTRACTOR_ENABLED: 'true'",
        "GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}",
        "--no-configuration-cache",
        "ciCheck",
        "assemble",
        "--release-date",
        "name: Full QA, Build & Verify",
        "name: Retain release QA evidence",
        "name: Smoke-test assembled distribution",
        "*/bin/app",
        "git diff HEAD --name-only",
        "verified_tree: ${{ steps.record.outputs.verified_tree }}",
        "git write-tree",
        "git rev-parse 'HEAD^{tree}'",
        "gh release view",
        "gh release upload",
        "--clobber",
        "if: needs.preflight.outputs.dry_run != 'true'",
        "if: needs.preflight.outputs.dry_run == 'true'",
    ):
        require(text, needle)
    for needle in (
        'skip_tests',
        '-x test',
        '--exclude-task test',
        'Reject if tag already exists',
    ):
        forbid(text, needle)
    if text.count('python3 .github/scripts/update-release-metadata.py') < 3:
        raise ValueError(
            'release workflow must prepare, commit and advance metadata '
            'through the versioned updater'
        )
    if text.count('ciCheck') != 1:
        raise ValueError(
            'release workflow must expose exactly one authoritative ciCheck '
            'invocation'
        )


def snapshot(directory: Path) -> dict[str, bytes]:
    return {name: (directory / name).read_bytes() for name in METADATA}


def invoke(
    directory: Path,
    *arguments: str,
    expect_success: bool = True,
) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        [
            sys.executable,
            str(directory / '.github/scripts/update-release-metadata.py'),
            *arguments,
        ],
        cwd=directory,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if expect_success and result.returncode != 0:
        raise ValueError(f'metadata updater failed: {result.stderr.strip()}')
    if not expect_success and result.returncode == 0:
        raise ValueError(
            'metadata updater unexpectedly accepted an invalid invocation'
        )
    return result


def verify_metadata_updater() -> None:
    with tempfile.TemporaryDirectory(
        prefix='regelsuche-release-contract-'
    ) as raw:
        checkout = Path(raw)
        (checkout / '.github/scripts').mkdir(parents=True)
        shutil.copy2(
            UPDATER,
            checkout / '.github/scripts/update-release-metadata.py',
        )
        for name in METADATA:
            shutil.copy2(ROOT / name, checkout / name)

        release_arguments = (
            '0.1.4',
            '--release',
            '--release-date',
            '2026-07-24',
        )
        invoke(checkout, *release_arguments)
        first = snapshot(checkout)
        invoke(checkout, *release_arguments)
        if snapshot(checkout) != first:
            raise ValueError('release metadata update is not byte-idempotent')

        cff = (checkout / 'CITATION.cff').read_text(encoding='utf-8')
        zenodo = json.loads(
            (checkout / '.zenodo.json').read_text(encoding='utf-8')
        )
        codemeta = json.loads(
            (checkout / 'codemeta.json').read_text(encoding='utf-8')
        )
        properties = (checkout / 'release.properties').read_text(
            encoding='utf-8'
        )
        if (
            'version: "0.1.4"' not in cff
            or 'date-released: "2026-07-24"' not in cff
        ):
            raise ValueError('CITATION.cff release metadata is incomplete')
        if (
            zenodo.get('version') != '0.1.4'
            or zenodo.get('publication_date') != '2026-07-24'
        ):
            raise ValueError('.zenodo.json release metadata is incomplete')
        if (
            codemeta.get('version') != '0.1.4'
            or codemeta.get('datePublished') != '2026-07-24'
        ):
            raise ValueError('codemeta.json release metadata is incomplete')
        if 'version=0.1.4\n' not in properties:
            raise ValueError('release.properties release version is incomplete')

        invoke(checkout, '0.1.5-SNAPSHOT')
        cff = (checkout / 'CITATION.cff').read_text(encoding='utf-8')
        zenodo = json.loads(
            (checkout / '.zenodo.json').read_text(encoding='utf-8')
        )
        codemeta = json.loads(
            (checkout / 'codemeta.json').read_text(encoding='utf-8')
        )
        if (
            'date-released:' in cff
            or 'publication_date' in zenodo
            or 'datePublished' in codemeta
        ):
            raise ValueError('development metadata retained release-only dates')

        invoke(
            checkout,
            '0.1.4',
            '--release-date',
            '2026-07-24',
            expect_success=False,
        )
        invoke(
            checkout,
            '0.1.4',
            '--release',
            '--release-date',
            '24-07-2026',
            expect_success=False,
        )


def main() -> int:
    try:
        verify_workflow()
        verify_metadata_updater()
    except (OSError, ValueError) as error:
        print(f'Release workflow verification failed: {error}', file=sys.stderr)
        return 1
    print(
        'OK: release workflow requires full QA and deterministic release metadata'
    )
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
