#!/usr/bin/env bash
set -euo pipefail
repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_root"
export PYTHONPATH="$repository_root/scripts"
python3 -m venv build/typed-external-polynomial-venv
pilot_python=build/typed-external-polynomial-venv/bin/python
"$pilot_python" -m pip install sympy==1.14.0 mpmath==1.3.0
"$pilot_python" -m unittest discover -s scripts/external_polynomial_comparison -p 'test_*.py' -v

# Run both affected module suites, not just the new cache/integration classes.
env -u GITHUB_SHA ./gradlew --no-daemon --no-configuration-cache --max-workers=2 \
  -I scripts/external-polynomial-classpath.gradle \
  :regelsuche-learning:externalPolynomialClasspath \
  :regelsuche-search:test :regelsuche-learning:test \
  --console=plain --stacktrace

"$pilot_python" - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
for project in ('regelsuche-search', 'regelsuche-learning'):
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    reports = sorted(Path(project, 'build/test-results/test').glob('TEST-*.xml'))
    if not reports:
        raise SystemExit('missing test receipts: ' + project)
    for report in reports:
        suite = ET.parse(report).getroot()
        for key in totals:
            totals[key] += int(suite.get(key, '0'))
        for output in suite.iter('system-out'):
            for line in (output.text or '').splitlines():
                if line.startswith('REUSE_DIAGNOSTIC '):
                    print(line)
    print('MODULE_TEST_TOTALS', project, totals)
PY

"$pilot_python" -m external_polynomial_comparison.run_typed \
  --classpath regelsuche-learning/build/external-polynomial-classpath.txt \
  --output build/reports/typed-external-polynomial-comparison
"$pilot_python" -m external_polynomial_comparison.run_typed \
  --verify --output build/reports/typed-external-polynomial-comparison
"$pilot_python" -m external_polynomial_comparison.audit_typed \
  --output build/reports/typed-external-polynomial-comparison
