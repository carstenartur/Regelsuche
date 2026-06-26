#!/usr/bin/env python3
import argparse
import datetime as dt
from pathlib import Path
import re


def set_line(text, key, value):
    line = f'{key}: "{value}"'
    pattern = r'^' + re.escape(key) + r': .*$'
    if re.search(pattern, text, flags=re.MULTILINE):
        return re.sub(pattern, line, text, flags=re.MULTILINE)
    return text.rstrip() + '\n' + line + '\n'


def remove_line(text, key):
    pattern = r'^' + re.escape(key) + r': .*\n?'
    return re.sub(pattern, '', text, flags=re.MULTILINE)

parser = argparse.ArgumentParser()
parser.add_argument('version')
parser.add_argument('--release', action='store_true')
args = parser.parse_args()
release_day = dt.date.today().isoformat() if args.release else None

citation = Path('CITATION.cff')
text = citation.read_text(encoding='utf-8')
text = set_line(text, 'version', args.version)
if release_day:
    text = set_line(text, 'date-released', release_day)
else:
    text = remove_line(text, 'date-released')
citation.write_text(text, encoding='utf-8')
