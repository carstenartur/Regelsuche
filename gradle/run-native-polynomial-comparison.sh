#!/usr/bin/env bash
set -euo pipefail
repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_root"
export PYTHONPATH="$repository_root/scripts"
python3 -m venv build/native-polynomial-venv
pilot_python=build/native-polynomial-venv/bin/python
"$pilot_python" -m pip install sympy==1.14.0 mpmath==1.3.0
env -u GITHUB_SHA ./gradlew --no-daemon --no-configuration-cache --max-workers=2 \
  -I scripts/external-polynomial-classpath.gradle :regelsuche-learning:externalPolynomialClasspath
"$pilot_python" -m external_polynomial_comparison.run_native \
  --classpath regelsuche-learning/build/external-polynomial-classpath.txt \
  --output build/reports/native-polynomial-comparison
"$pilot_python" -m external_polynomial_comparison.run_native --verify \
  --output build/reports/native-polynomial-comparison
