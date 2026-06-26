#!/usr/bin/env python3
import json
from pathlib import Path

data = json.loads(Path('.zenodo.json').read_text(encoding='utf-8'))
data['publication' + '_date'] = '2026-01-01'
Path('.zenodo.json').write_text(json.dumps(data) + '\n', encoding='utf-8')
