#!/usr/bin/env python3
"""Keep Regelsuche release-related metadata files aligned."""

import argparse
import datetime as dt
import json
from pathlib import Path
import re

ROOT = Path.cwd()
ZENODO_RELEASE_DATE_KEY = 'publication' + '_date'
CODEMETA_RELEASE_DATE_KEY = 'date' + 'Published'


def set_cff_key(text, key, value):
    line = f'{key}: "{value}"'
    pattern = r'^' + re.escape(key) + r': .*$'
    if re.search(pattern, text, flags=re.MULTILINE):
        return re.sub(pattern, line, text, flags=re.MULTILINE)
    if not text.endswith('\n'):
        text += '\n'
    return text + line + '\n'


def remove_cff_key(text, key):
    pattern = r'^' + re.escape(key) + r': .*\n?'
    return re.sub(pattern, '', text, flags=re.MULTILINE)


def read_json(path):
    return json.loads(path.read_text(encoding='utf-8'))


def write_json(path, data):
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')


def update_citation(version, release_day):
    path = ROOT / 'CITATION.cff'
    text = path.read_text(encoding='utf-8')
    text = set_cff_key(text, 'version', version)
    if release_day:
        text = set_cff_key(text, 'date-released', release_day)
    else:
        text = remove_cff_key(text, 'date-released')
    path.write_text(text, encoding='utf-8')


def update_zenodo(version, release_day):
    path = ROOT / '.zenodo.json'
    data = read_json(path)
    data['version'] = version
    if release_day:
        data[ZENODO_RELEASE_DATE_KEY] = release_day
    else:
        data.pop(ZENODO_RELEASE_DATE_KEY, None)
    write_json(path, data)


def update_codemeta(version, release_day):
    path = ROOT / 'codemeta.json'
    data = read_json(path)
    data['version'] = version
    if release_day:
        data[CODEMETA_RELEASE_DATE_KEY] = release_day
    else:
        data.pop(CODEMETA_RELEASE_DATE_KEY, None)
    write_json(path, data)


def update_release_properties(version):
    path = ROOT / 'release.properties'
    lines = path.read_text(encoding='utf-8').splitlines() if path.exists() else []
    updated = []
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


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('version', help='Version to write to release metadata files')
    parser.add_argument('--release', action='store_true')
    args = parser.parse_args()

    release_day = dt.date.today().isoformat() if args.release else None
    update_citation(args.version, release_day)
    update_zenodo(args.version, release_day)
    update_codemeta(args.version, release_day)
    update_release_properties(args.version)


if __name__ == '__main__':
    main()
