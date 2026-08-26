"""Exact structured factorization entry point for the Regelsuche adapter.

The wire contract contains only coefficient domains, exponent vectors and exact
numerator/denominator pairs. It never parses a rendered mathematical expression.
"""

from __future__ import annotations

import json
import sys
import time
from typing import Any

import sympy

PROTOCOL = "regelsuche.sympy-factorization/v1"


def runtime_info() -> str:
    return json.dumps(
        {
            "pythonImplementation": sys.implementation.name,
            "pythonVersion": sys.version.split()[0],
            "sympyVersion": sympy.__version__,
        },
        separators=(",", ":"),
        sort_keys=True,
    )


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def _pair(value: Any) -> dict[str, str]:
    rational = sympy.Rational(value)
    return {
        "denominator": str(rational.q),
        "numerator": str(rational.p),
    }


def _coefficient(term: dict[str, Any]) -> sympy.Rational:
    numerator = int(term["numerator"])
    denominator = int(term["denominator"])
    _require(denominator > 0, "coefficient denominator must be positive")
    return sympy.Rational(numerator, denominator)


def factor_payload(payload_json: str) -> str:
    total_started = time.perf_counter_ns()
    payload = json.loads(payload_json)
    _require(isinstance(payload, dict), "payload must be an object")
    _require(payload.get("protocol") == PROTOCOL, "unsupported protocol")

    domain_id = payload.get("domain")
    _require(domain_id in ("ZZ", "QQ"), "unsupported coefficient domain")
    variable_count = payload.get("variableCount")
    _require(
        isinstance(variable_count, int) and variable_count > 0,
        "variableCount must be positive",
    )
    raw_terms = payload.get("terms")
    _require(isinstance(raw_terms, list) and raw_terms, "terms must be non-empty")

    generators = sympy.symbols(f"x0:{variable_count}")
    if variable_count == 1 and not isinstance(generators, tuple):
        generators = (generators,)
    domain = sympy.ZZ if domain_id == "ZZ" else sympy.QQ

    terms: dict[tuple[int, ...], sympy.Rational] = {}
    for raw_term in raw_terms:
        _require(isinstance(raw_term, dict), "term must be an object")
        exponents = raw_term.get("exponents")
        _require(
            isinstance(exponents, list)
            and len(exponents) == variable_count
            and all(isinstance(value, int) and value >= 0 for value in exponents),
            "term exponent vector is invalid",
        )
        monomial = tuple(exponents)
        _require(monomial not in terms, "duplicate exponent vector")
        coefficient = _coefficient(raw_term)
        _require(coefficient != 0, "zero coefficients are not canonical terms")
        if domain_id == "ZZ":
            _require(coefficient.q == 1, "ZZ coefficient must be integral")
        terms[monomial] = coefficient

    polynomial = sympy.Poly.from_dict(terms, *generators, domain=domain)
    _require(not polynomial.is_zero, "zero polynomial is unsupported")

    factor_started = time.perf_counter_ns()
    unit, raw_factors = sympy.factor_list(polynomial)
    factor_nanos = time.perf_counter_ns() - factor_started

    factors: list[dict[str, Any]] = []
    for factor, multiplicity in raw_factors:
        factor_polynomial = sympy.Poly(factor, *generators, domain=domain)
        factor_terms: list[dict[str, Any]] = []
        for monomial, coefficient in factor_polynomial.terms():
            pair = _pair(coefficient)
            factor_terms.append(
                {
                    "denominator": pair["denominator"],
                    "exponents": list(monomial),
                    "numerator": pair["numerator"],
                }
            )
        factor_terms.sort(
            key=lambda item: tuple(item["exponents"]),
            reverse=True,
        )
        factors.append(
            {
                "multiplicity": int(multiplicity),
                "terms": factor_terms,
            }
        )

    factors.sort(
        key=lambda item: json.dumps(item, separators=(",", ":"), sort_keys=True)
    )
    result = {
        "domain": domain_id,
        "factorNanos": factor_nanos,
        "factors": factors,
        "protocol": PROTOCOL,
        "pythonImplementation": sys.implementation.name,
        "pythonVersion": sys.version.split()[0],
        "sympyVersion": sympy.__version__,
        "totalNanos": time.perf_counter_ns() - total_started,
        "unit": _pair(unit),
    }
    return json.dumps(result, separators=(",", ":"), sort_keys=True)
