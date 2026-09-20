#!/usr/bin/env bash
set -euo pipefail
repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_root"
export PYTHONPATH="$repository_root/scripts"
python3 -m venv build/typed-external-polynomial-venv
pilot_python=build/typed-external-polynomial-venv/bin/python
"$pilot_python" -m pip install sympy==1.14.0 mpmath==1.3.0
"$pilot_python" -m unittest discover -s scripts/external_polynomial_comparison -p 'test_*.py' -v

env -u GITHUB_SHA ./gradlew --no-daemon --no-configuration-cache --max-workers=2 \
  -I scripts/external-polynomial-classpath.gradle \
  :regelsuche-learning:externalPolynomialClasspath \
  :regelsuche-search:test --tests de.regelsuche.search.moves.TypedSourceOnlyContextTest \
  --tests de.regelsuche.search.moves.TypedSourceOnlySearchTest \
  --tests de.regelsuche.search.moves.TypedProgramMoveProviderTest \
  :regelsuche-learning:test --tests de.regelsuche.evolution.TypedExternalPolynomialComparisonWorkerTest \
  --tests de.regelsuche.evolution.TypedPolynomialSurfaceCostTest \
  --tests de.regelsuche.evolution.TypedLearnedMoveInventoryTest \
  --tests de.regelsuche.evolution.TypedLearningWorkStudyTest \
  --console=plain --stacktrace

"$pilot_python" -m external_polynomial_comparison.run_typed \
  --classpath regelsuche-learning/build/external-polynomial-classpath.txt \
  --output build/reports/typed-external-polynomial-comparison
"$pilot_python" -m external_polynomial_comparison.run_typed \
  --verify --output build/reports/typed-external-polynomial-comparison
