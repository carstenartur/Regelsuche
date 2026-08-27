#!/usr/bin/env python3
"""Validate the specialist control and capability-matched factorization JMH matrix."""
from __future__ import annotations

import argparse, html, json, math
from pathlib import Path
from typing import Any

SP = "de.regelsuche.math.sympy.SymPyFactorizationBenchmarks."
S = {
    "nativeBackendWarm": "native-backend-warm",
    "nativeEndToEndWarm": "native-end-to-end-warm",
    "graalPyBackendWarm": "graalpy-backend-warm",
    "graalPyEndToEndWarm": "graalpy-end-to-end-warm",
    "graalPyEndToEndCold": "graalpy-end-to-end-cold",
    "cpythonOneShotEndToEnd": "cpython-one-shot-end-to-end",
}
GP = "de.regelsuche.math.sympy.GeneralUnivariateFactorizationBenchmarks."
G = {
    "nativeGeneralBackendWarm": "native-general-backend-warm",
    "nativeGeneralEndToEndWarm": "native-general-end-to-end-warm",
    "graalPyGeneralBackendWarm": "graalpy-general-backend-warm",
    "graalPyGeneralEndToEndWarm": "graalpy-general-end-to-end-warm",
}
C = {
    "z-linear-pair-degree2": ("Z[x]", 2, "dense", "small-integer", "reducible", "square-free"),
    "z-content-mixed-degree4": ("Z[x]", 4, "dense", "integer-content", "reducible", "square-free"),
    "z-large-coefficient-degree4": ("Z[x]", 4, "dense", "larger-integer", "reducible", "square-free"),
    "z-eisenstein-irreducible-degree5": ("Z[x]", 5, "sparse", "small-integer", "irreducible", "square-free"),
    "z-repeated-degree6": ("Z[x]", 6, "dense", "small-integer", "reducible", "repeated"),
    "z-sparse-cyclotomic-degree6": ("Z[x]", 6, "sparse", "small-integer", "reducible", "square-free"),
    "q-linear-pair-degree2": ("Q[x]", 2, "dense", "rational-content", "reducible", "square-free"),
    "q-eisenstein-irreducible-degree4": ("Q[x]", 4, "sparse", "rational", "irreducible", "square-free"),
    "q-repeated-degree5": ("Q[x]", 5, "dense", "rational-content", "reducible", "repeated"),
}


def req(ok: bool, message: str) -> None:
    if not ok:
        raise SystemExit("SymPy factorization benchmark invalid: " + message)


def num(value: Any, label: str) -> float:
    req(isinstance(value, (int, float)) and not isinstance(value, bool), label + " must be numeric")
    value = float(value)
    req(math.isfinite(value) and value >= 0, label + " must be finite and nonnegative")
    return value


def integer(value: Any, label: str) -> int:
    req(isinstance(value, int) and not isinstance(value, bool), label + " must be an integer")
    return value


def item(entry: dict[str, Any], identity: str) -> dict[str, Any]:
    req(entry.get("mode") == "avgt" and entry.get("threads") == 1, identity + " must use one-thread AverageTime")
    metric = entry.get("primaryMetric")
    req(isinstance(metric, dict) and metric.get("scoreUnit") == "ms/op", identity + " must report ms/op")
    return {
        "id": identity,
        "benchmark": entry["benchmark"],
        "scoreMillis": num(metric.get("score"), identity + ".score"),
        "scoreErrorMillis": num(metric.get("scoreError"), identity + ".scoreError"),
        "forks": integer(entry.get("forks"), identity + ".forks"),
        "warmupIterations": integer(entry.get("warmupIterations"), identity + ".warmups"),
        "measurementIterations": integer(entry.get("measurementIterations"), identity + ".measurements"),
    }


def parse(raw: Any) -> tuple[dict[str, Any], dict[str, Any]]:
    req(isinstance(raw, list) and raw, "JMH result must be a nonempty array")
    specialist: dict[str, Any] = {}
    general: dict[str, Any] = {case: {} for case in C}
    seen: set[tuple[str, str | None]] = set()
    for entry in raw:
        req(isinstance(entry, dict) and isinstance(entry.get("benchmark"), str), "invalid JMH entry")
        benchmark = entry["benchmark"]
        if benchmark.startswith(SP):
            method = benchmark.removeprefix(SP)
            req(method in S, "undeclared specialist track: " + benchmark)
            key = (benchmark, None)
            value = item(entry, S[method])
            req((value["forks"], value["warmupIterations"], value["measurementIterations"]) == (1, 2, 3),
                benchmark + " changed the specialist sampling contract")
            specialist[method] = value
        elif benchmark.startswith(GP):
            method = benchmark.removeprefix(GP)
            req(method in G, "undeclared general track: " + benchmark)
            params = entry.get("params")
            req(isinstance(params, dict) and set(params) == {"caseId"}, benchmark + " must declare only caseId")
            case = params["caseId"]
            req(case in C, "undeclared general case: " + str(case))
            key = (benchmark, str(case))
            value = item(entry, G[method])
            req(value["forks"] >= 3 and value["warmupIterations"] >= 3 and value["measurementIterations"] >= 5,
                benchmark + "/" + str(case) + " misses the general sampling floor")
            general[str(case)][method] = value
        else:
            req(False, "undeclared benchmark: " + benchmark)
        req(key not in seen, "duplicate benchmark/case: " + str(key))
        seen.add(key)
    req(set(specialist) == set(S), "specialist matrix is incomplete")
    for case, tracks in general.items():
        req(set(tracks) == set(G), "general matrix is incomplete for " + case)
    return specialist, general


def ratio(a: float, b: float) -> float | None:
    return None if b == 0 else a / b


def report(specialist: dict[str, Any], general: dict[str, Any]) -> dict[str, Any]:
    sb = {v["id"]: v for v in specialist.values()}
    sratio = {
        "graalpyWarmToNativeWarmEndToEnd": ratio(sb["graalpy-end-to-end-warm"]["scoreMillis"], sb["native-end-to-end-warm"]["scoreMillis"]),
        "graalpyColdToWarmEndToEnd": ratio(sb["graalpy-end-to-end-cold"]["scoreMillis"], sb["graalpy-end-to-end-warm"]["scoreMillis"]),
        "cpythonOneShotToGraalpyWarmEndToEnd": ratio(sb["cpython-one-shot-end-to-end"]["scoreMillis"], sb["graalpy-end-to-end-warm"]["scoreMillis"]),
    }
    cases = []
    for case, metadata in C.items():
        by_id = {v["id"]: v for v in general[case].values()}
        nb, ne = by_id["native-general-backend-warm"]["scoreMillis"], by_id["native-general-end-to-end-warm"]["scoreMillis"]
        gb, ge = by_id["graalpy-general-backend-warm"]["scoreMillis"], by_id["graalpy-general-end-to-end-warm"]["scoreMillis"]
        domain, degree, density, coefficients, reducibility, multiplicity = metadata
        cases.append({
            "caseId": case, "domain": domain, "degree": degree, "density": density,
            "coefficientClass": coefficients, "reducibility": reducibility, "multiplicity": multiplicity,
            "measurements": sorted(by_id.values(), key=lambda x: x["id"]),
            "ratios": {
                "graalpyToNativeWarmBackend": ratio(gb, nb),
                "graalpyToNativeWarmEndToEnd": ratio(ge, ne),
                "nativeVerifierInclusiveToBackend": ratio(ne, nb),
                "graalpyVerifierInclusiveToBackend": ratio(ge, gb),
            },
        })
    return {
        "schema": "regelsuche.sympy-factorization-performance/v2",
        "claimPolicy": "DIAGNOSTIC_TRACKS_NO_RELATIVE_WINNER_GATE",
        "specialistControl": {
            "classification": "SPECIALIZED_BINARY_QUARTIC_CONTROL",
            "sharedCase": "binary-homogeneous-quartic-A4-plus-4B4",
            "measurements": sorted(sb.values(), key=lambda x: x["id"]),
            "ratios": sratio,
            "claimBoundary": "Operational specialist control only; not representative of general factorization.",
        },
        "generalComparison": {
            "classification": "GENERAL_UNIVARIATE_CAPABILITY_MATCHED",
            "corpusId": "regelsuche-general-univariate-factorization-v1",
            "caseCount": len(cases),
            "cases": cases,
            "claimBoundary": "Track-scoped engineering claims over this exact Z[x]/Q[x] corpus; no universal CAS ranking.",
        },
    }


def rows(values: list[dict[str, Any]]) -> str:
    return "".join(
        f"<tr><td><code>{html.escape(v['id'])}</code></td><td>{v['scoreMillis']:.6g}</td>"
        f"<td>{v['scoreErrorMillis']:.3g}</td><td>{v['forks']}</td>"
        f"<td>{v['warmupIterations']}</td><td>{v['measurementIterations']}</td></tr>"
        for v in values
    )


def render(data: dict[str, Any], path: Path) -> None:
    sections = []
    for case in data["generalComparison"]["cases"]:
        sections.append(
            f"<h3><code>{html.escape(case['caseId'])}</code></h3>"
            "<table><tr><th>track</th><th>ms/op</th><th>error</th><th>forks</th><th>warmups</th><th>measurements</th></tr>"
            + rows(case["measurements"]) + "</table>"
        )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "<!doctype html><meta charset='utf-8'><title>Regelsuche factorization performance</title>"
        "<style>body{font-family:system-ui;margin:2rem;max-width:1200px}table{border-collapse:collapse;width:100%;margin-bottom:1rem}"
        "td,th{border:1px solid #ddd;padding:.4rem;text-align:left}th{background:#f6f8fa}</style>"
        "<h1>Polynomial factorization performance</h1><p>No relative winner gate.</p>"
        "<h2>General capability-matched comparison</h2>" + "".join(sections)
        + "<h2>Specialized binary-quartic control</h2><table><tr><th>track</th><th>ms/op</th><th>error</th>"
        "<th>forks</th><th>warmups</th><th>measurements</th></tr>"
        + rows(data["specialistControl"]["measurements"]) + "</table>", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--result", required=True, type=Path)
    parser.add_argument("--summary-output", required=True, type=Path)
    parser.add_argument("--report-output", required=True, type=Path)
    args = parser.parse_args()
    req(args.result.is_file(), "missing JMH result: " + str(args.result))
    try:
        raw = json.loads(args.result.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        req(False, f"cannot read {args.result}: {error}")
    data = report(*parse(raw))
    args.summary_output.parent.mkdir(parents=True, exist_ok=True)
    args.summary_output.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    render(data, args.report_output)
    print("sympy-factorization-benchmark-contract=valid")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
