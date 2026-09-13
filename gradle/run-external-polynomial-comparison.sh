#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_root"
export PYTHONPATH="$repository_root/scripts"

python3 -m venv build/external-polynomial-venv
pilot_python=build/external-polynomial-venv/bin/python
"$pilot_python" -m pip install sympy==1.14.0 mpmath==1.3.0
"$pilot_python" -m unittest discover -s scripts/external_polynomial_comparison -p 'test_*.py' -v

env -u GITHUB_SHA ./gradlew --no-daemon --no-configuration-cache --max-workers=2 \
  -I scripts/external-polynomial-classpath.gradle \
  :regelsuche-learning:externalPolynomialClasspath \
  :regelsuche-learning:test --tests de.regelsuche.evolution.ExternalPolynomialComparisonWorkerTest \
  --console=plain --stacktrace

"$pilot_python" -m external_polynomial_comparison.run \
  --classpath regelsuche-learning/build/external-polynomial-classpath.txt \
  --output build/reports/external-polynomial-comparison
"$pilot_python" -m external_polynomial_comparison.run \
  --verify --output build/reports/external-polynomial-comparison
