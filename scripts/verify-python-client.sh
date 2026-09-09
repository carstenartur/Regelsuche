#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
task_reports="$task_root/build/reports/python-client"
task_work_root="$task_root/build/tmp/python-client-validation"
mkdir -p "$task_reports/wheels" "$task_work_root"
task_work="$(mktemp -d "$task_work_root/run.XXXXXX")"
python3 -m venv "$task_work/build-venv"
"$task_work/build-venv/bin/python" -m pip wheel "$task_root/python" --no-deps --wheel-dir "$task_reports/wheels" > "$task_reports/build.log" 2>&1
python3 -m venv "$task_work/venv"
"$task_work/venv/bin/python" -m pip install --no-index "$task_reports"/wheels/regelsuche_client-0.1.0-py3-none-any.whl > "$task_reports/install.log" 2>&1
"$task_work/venv/bin/python" -m unittest discover -s "$task_root/python/tests" -v > "$task_reports/unit.log" 2>&1
"$task_work/venv/bin/python" -m regelsuche verify "$task_root/docs/examples/linear-solution.json" > "$task_reports/offline.json"
task_distribution="$(python3 - "$task_root" "$task_work" <<'PY'
from pathlib import Path
import sys, zipfile
archives = list((Path(sys.argv[1]) / 'app/target').glob('regelsuche-*.zip'))
if len(archives) != 1:
    raise SystemExit('Expected exactly one current Maven distribution ZIP')
target = Path(sys.argv[2]) / 'distribution'
target.mkdir(exist_ok=True)
with zipfile.ZipFile(archives[0]) as archive:
    for entry in archive.infolist():
        path = (target / entry.filename).resolve()
        if not path.is_relative_to(target.resolve()):
            raise SystemExit('Invalid distribution path')
    archive.extractall(target)
launcher = next(target.glob('regelsuche-*/bin/regelsuche'))
launcher.chmod(0o755)
print(launcher.parent.parent)
PY
)"
REGELSUCHE_TEST_LAUNCHER="$task_distribution/bin/regelsuche" "$task_work/venv/bin/python" "$task_root/python/tests/live_client.py" > "$task_reports/live.json"
java --class-path "$task_distribution/lib/*" "$task_distribution/examples/LinearSolve.java" > "$task_reports/java-example.json"
"$task_work/venv/bin/python" -m regelsuche verify "$task_reports/java-example.json" > "$task_reports/java-example-verification.json"
printf '%s\n' 'Python wheel, offline proof, live Java server and external Java example verified.'
