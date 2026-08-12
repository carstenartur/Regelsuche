#!/usr/bin/env python3
"""Keep Regelsuche release-related metadata and Maven versions aligned."""

from __future__ import annotations

import argparse
import datetime as dt
import json
from pathlib import Path
import re

ROOT = Path.cwd()
ZENODO_RELEASE_DATE_KEY = 'publication' + '_date'
CODEMETA_RELEASE_DATE_KEY = 'date' + 'Published'
ORCID_ID = '0009-0005-1047-6381'
ORCID_URL = 'https://orcid.org/' + ORCID_ID
POM_VERSION_PATTERN = re.compile(
    r'(<artifactId>regelsuche-parent</artifactId>\s*<version>)([^<]+)(</version>)'
)


def set_cff_key(text: str, key: str, value: str) -> str:
    line = f'{key}: "{value}"'
    pattern = r'^' + re.escape(key) + r': .*$'
    if re.search(pattern, text, flags=re.MULTILINE):
        return re.sub(pattern, line, text, flags=re.MULTILINE)
    if not text.endswith('\n'):
        text += '\n'
    return text + line + '\n'


def remove_cff_key(text: str, key: str) -> str:
    pattern = r'^' + re.escape(key) + r': .*\n?'
    return re.sub(pattern, '', text, flags=re.MULTILINE)


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding='utf-8'))


def write_json(path: Path, data: dict) -> None:
    path.write_text(
        json.dumps(data, indent=2, ensure_ascii=False) + '\n',
        encoding='utf-8',
    )


def pom_paths() -> tuple[Path, ...]:
    paths = [ROOT / 'pom.xml']
    paths.extend(sorted(ROOT.glob('*/pom.xml')))
    return tuple(paths)


def declared_pom_version(path: Path) -> str | None:
    match = POM_VERSION_PATTERN.search(path.read_text(encoding='utf-8'))
    return match.group(2).strip() if match else None


def update_maven_versions(version: str) -> None:
    for path in pom_paths():
        text = path.read_text(encoding='utf-8')
        updated, replacements = POM_VERSION_PATTERN.subn(
            rf'\g<1>{version}\g<3>',
            text,
            count=1,
        )
        if replacements != 1:
            raise RuntimeError(
                f'{path.relative_to(ROOT)} does not declare exactly one '
                'regelsuche-parent version'
            )
        path.write_text(updated, encoding='utf-8')


def update_citation(version: str, release_day: str | None) -> None:
    path = ROOT / 'CITATION.cff'
    text = path.read_text(encoding='utf-8')
    text = set_cff_key(text, 'version', version)
    if release_day:
        text = set_cff_key(text, 'date-released', release_day)
    else:
        text = remove_cff_key(text, 'date-released')
    path.write_text(text, encoding='utf-8')


def update_zenodo(version: str, release_day: str | None) -> None:
    path = ROOT / '.zenodo.json'
    data = read_json(path)
    data['version'] = version
    if release_day:
        data[ZENODO_RELEASE_DATE_KEY] = release_day
    else:
        data.pop(ZENODO_RELEASE_DATE_KEY, None)
    write_json(path, data)


def update_codemeta(version: str, release_day: str | None) -> None:
    path = ROOT / 'codemeta.json'
    data = read_json(path)
    data['version'] = version
    if release_day:
        data[CODEMETA_RELEASE_DATE_KEY] = release_day
    else:
        data.pop(CODEMETA_RELEASE_DATE_KEY, None)
    write_json(path, data)


def update_release_properties(version: str) -> None:
    path = ROOT / 'release.properties'
    lines = path.read_text(encoding='utf-8').splitlines() if path.exists() else []
    updated: list[str] = []
    replaced = False
    for line in lines:
        if re.match(r'^\s*version\s*=', line):
            updated.append(f'version={version}')
            replaced = True
        else:
            updated.append(line)
    if not replaced:
        if updated and updated[-1].strip():
            updated.append('')
        updated.append(f'version={version}')
    path.write_text('\n'.join(updated) + '\n', encoding='utf-8')


def update_citation_md(version: str, release_day: str | None) -> None:
    path = ROOT / 'CITATION.md'
    text = path.read_text(encoding='utf-8')
    text = re.sub(
        r'(Carsten Hammer\. \*\*Regelsuche\*\*\. Version )[0-9A-Za-z.-]+'
        r'(\. \d{4}\.)',
        rf'\g<1>{version}\2',
        text,
    )
    text = re.sub(r'(  version\s+= \{)[^}]+(\},)', rf'\g<1>{version}\2', text)
    if release_day:
        if re.search(r'^  date\s+= \{[^}]+\},$', text, flags=re.MULTILINE):
            text = re.sub(
                r'^  date\s+= \{[^}]+\},$',
                f'  date         = {{{release_day}}},',
                text,
                flags=re.MULTILINE,
            )
        else:
            text = re.sub(
                r'^(  version\s+= \{[^}]+\},)$',
                rf'\1\n  date         = {{{release_day}}},',
                text,
                flags=re.MULTILINE,
            )
    else:
        text = re.sub(r'^  date\s+= \{[^}]+\},\n', '', text, flags=re.MULTILINE)
    if 'ORCID' not in text:
        text = text.replace(
            '## What to cite\n',
            f"## Author identifier\n\nCarsten Hammer's ORCID iD is "
            f'[{ORCID_URL}]({ORCID_URL}).\n\n## What to cite\n',
        )
    if '  orcid' not in text:
        text = re.sub(
            r'(  author\s+= \{Hammer, Carsten\},)',
            rf'\1\n  orcid        = {{{ORCID_URL}}},',
            text,
            flags=re.MULTILINE,
        )
    path.write_text(text, encoding='utf-8')


def release_property_versions() -> list[str]:
    text = (ROOT / 'release.properties').read_text(encoding='utf-8')
    return re.findall(r'^\s*version\s*=\s*(\S+)\s*$', text, flags=re.MULTILINE)


def validate_version_alignment(version: str) -> None:
    errors: list[str] = []

    versions = release_property_versions()
    if versions != [version]:
        errors.append(
            'release.properties must contain exactly one version='
            f'{version!s}; found {versions!r}'
        )

    citation = (ROOT / 'CITATION.cff').read_text(encoding='utf-8')
    if not re.search(
        rf'^version:\s*["\']?{re.escape(version)}["\']?\s*$',
        citation,
        flags=re.MULTILINE,
    ):
        errors.append(f'CITATION.cff does not declare version {version}')

    citation_md = (ROOT / 'CITATION.md').read_text(encoding='utf-8')
    if f'Version {version}.' not in citation_md:
        errors.append(f'CITATION.md prose does not declare version {version}')
    if not re.search(
        rf'^\s*version\s+=\s+\{{{re.escape(version)}\}},\s*$',
        citation_md,
        flags=re.MULTILINE,
    ):
        errors.append(f'CITATION.md BibTeX does not declare version {version}')

    for path in (ROOT / '.zenodo.json', ROOT / 'codemeta.json'):
        actual = read_json(path).get('version')
        if actual != version:
            errors.append(
                f'{path.relative_to(ROOT)} declares version {actual!r}, '
                f'expected {version!r}'
            )

    for path in pom_paths():
        actual = declared_pom_version(path)
        if actual != version:
            errors.append(
                f'{path.relative_to(ROOT)} declares Maven version {actual!r}, '
                f'expected {version!r}'
            )

    if errors:
        raise SystemExit(
            'Release metadata alignment failed:\n- ' + '\n- '.join(errors)
        )
    print(
        f'Release metadata alignment valid for {version}: '
        f'{len(pom_paths())} Maven POMs checked'
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('version', help='Version to write to or verify in release metadata')
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--release', action='store_true')
    mode.add_argument('--check', action='store_true')
    args = parser.parse_args()

    if args.check:
        validate_version_alignment(args.version)
        return

    release_day = dt.date.today().isoformat() if args.release else None
    update_citation(args.version, release_day)
    update_zenodo(args.version, release_day)
    update_codemeta(args.version, release_day)
    update_release_properties(args.version)
    update_citation_md(args.version, release_day)
    update_maven_versions(args.version)
    validate_version_alignment(args.version)


if __name__ == '__main__':
    main()
