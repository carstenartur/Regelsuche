#!/usr/bin/env python3
import argparse
import json
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('version')
args = parser.parse_args()
path = Path('.zenodo.json')
data = json.loads(path.read_text(encoding='utf-8'))
data['version'] = args.version
path.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')
