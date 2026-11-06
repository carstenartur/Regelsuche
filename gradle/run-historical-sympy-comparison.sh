#!/usr/bin/env bash
set -euo pipefail
repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_root"
mkdir -p build
export PYTHONPATH="$repository_root/scripts"
python3 -m venv build/historical-sympy-venv
pilot_python=build/historical-sympy-venv/bin/python
"$pilot_python" -m pip install sympy==1.14.0 mpmath==1.3.0
mvn -B -ntp -pl regelsuche-learning -am -DskipTests package \
  org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath \
  -DincludeScope=runtime -Dmdep.outputFile=target/historical-runtime-classpath.txt
"$pilot_python" -B -c 'from pathlib import Path; root=Path.cwd(); deps=(root/"regelsuche-learning/target/historical-runtime-classpath.txt").read_text().strip(); (root/"build/historical-classpath.txt").write_text(str(root/"regelsuche-learning/target/classes")+":"+deps+"\n")'
"$pilot_python" -B -m external_polynomial_comparison.run_historical \
  --classpath build/historical-classpath.txt --output build/reports/historical-sympy-comparison
"$pilot_python" -B -m external_polynomial_comparison.run_historical --verify \
  --output build/reports/historical-sympy-comparison
