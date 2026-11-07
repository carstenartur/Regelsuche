#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python_executable="${PYTHON_EXECUTABLE:-python3}"
venv="${REGELSUCHE_KNOWLEDGE_VENV:-build/knowledge-lifecycle-venv}"
output="${REGELSUCHE_KNOWLEDGE_OUTPUT:-build/reports/knowledge-lifecycle-improvement}"
"$python_executable" -m venv "$venv"
"$venv/bin/python" -m pip install 'sympy==1.14.0' 'mpmath==1.3.0'
mvn -B -ntp -pl regelsuche-learning -am -DskipTests clean package \
  org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath \
  -DincludeScope=runtime -Dmdep.outputFile=target/knowledge-lifecycle-classpath.txt
mkdir -p build
"$venv/bin/python" -c 'from pathlib import Path; p=Path("regelsuche-learning"); Path("build/knowledge-lifecycle-classpath.txt").write_text(str((p/"target/classes").resolve())+":"+(p/"target/knowledge-lifecycle-classpath.txt").read_text().strip())'
PYTHONPATH=scripts "$venv/bin/python" -B -m external_polynomial_comparison.run_knowledge_lifecycle \
  --classpath build/knowledge-lifecycle-classpath.txt --output "$output"
PYTHONPATH=scripts "$venv/bin/python" -B -m external_polynomial_comparison.run_knowledge_lifecycle --verify --output "$output"
