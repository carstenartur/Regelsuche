#!/usr/bin/env python3
import argparse
import json
from pathlib import Path
import re


def set_line(text, key, value):
    line = f'{key}: "{value}"'
    pattern = r'^' + re.escape(key) + r': .*$'
    if re.search(pattern, text, flags=re.MULTILINE):
        return re.sub(pattern, line, text, flags=re.MULTILINE)
    return text.rstrip() + '\n' + line + '\n'

parser = argparse.ArgumentParser()
parser.add_argument('version')
args = parser.parse_args()

citation = Path('CITATION.cff')
text = citation.read_text(encoding='utf-8')
text = set_line(text, 'version', args.version)
citation.write_text(text, encoding='utf-8')

zenodo = Path('.zenodo.json')
data = json.loads(zenodo.read_text(encoding='utf-8'))
data['version'] = args.version
zenodo.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')
