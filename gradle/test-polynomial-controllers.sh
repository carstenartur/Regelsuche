#!/usr/bin/env bash
set -euo pipefail
repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_root"
export PYTHONPATH="$repository_root/scripts"
python3 -m venv build/typed-external-polynomial-venv
pilot_python=build/typed-external-polynomial-venv/bin/python
"$pilot_python" -m pip install sympy==1.14.0 mpmath==1.3.0
"$pilot_python" -m unittest discover -s scripts/external_polynomial_comparison -p 'test_*.py' -v
