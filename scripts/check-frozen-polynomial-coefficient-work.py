"""Run public coefficient-meter controls in the isolated historical GraalPy."""

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path

from frozen_sympy_coefficient_work import CoefficientWork, OperationLimit, measure_coefficient_kernels


def require(condition, message):
    if not condition:
        raise ValueError(message)


def load_probe():
    path = Path(__file__).with_name("probe-frozen-polynomial-runtime.py")
    spec = importlib.util.spec_from_file_location("frozen_polynomial_runtime_probe", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def public_request(probe, protocol, constant):
    return {"protocol": protocol, "domain": "QQ", "variableCount": 1,
            "terms": [probe.term(4, 1), probe.term(1, 1), probe.term(0, constant)]}


def factor(call, request):
    from sympy.core.cache import clear_cache
    from sympy.core.random import seed

    # Reset independently for the instrumented and reference development calls.
    clear_cache()
    seed(0)
    response = json.loads(call(json.dumps(request)))
    return {key: response[key] for key in ("domain", "unit", "factors", "protocol")}


def observed(call, request, limit):
    work = CoefficientWork(limit)
    try:
        with measure_coefficient_kernels(work):
            result = factor(call, request)
    except OperationLimit as exhausted:
        return {"result": None, "status": "COEFFICIENT_LIMIT", "refusedStage": str(exhausted),
                "used": work.used, "stages": dict(work.stages)}
    return {"result": result, "status": "COMPLETED", "used": work.used, "stages": dict(work.stages)}


def check_exact_kernel_prefix():
    from sympy import ZZ
    from sympy.polys.densearith import dup_mul, dup_sqr
    from sympy.polys.galoistools import gf_mul, gf_sqr

    # 2x2 coefficients yield four products, four accumulated sums and three reductions.
    calls = ["mul", "iadd", "mod", "mul", "iadd", "mul", "iadd", "mod", "mul", "iadd", "mod"]
    for cap in range(len(calls) + 1):
        work = CoefficientWork(cap)
        try:
            with measure_coefficient_kernels(work):
                value = gf_mul([1, 2], [3, 4], 5, ZZ)
        except OperationLimit as exhausted:
            require(cap < len(calls) and str(exhausted) == f"sympy.gf_mul.{calls[cap]}",
                    "incorrect first refused operation")
        else:
            require(cap == len(calls) and value == [3, 0, 3], "wrong result or missing budget stop")
        expected = {f"sympy.gf_mul.{op}": calls[:cap].count(op) for op in set(calls[:cap])}
        require(dict(work.stages) == expected and work.used == cap, "incorrect charged prefix")
        require(gf_mul([1, 2], [3, 4], 5, ZZ) == [3, 0, 3], "kernel not restored after measurement")

    cases = [
        (dup_mul, ([1, 2], [3, 4], ZZ), [3, 10, 8], {"mul": 4, "iadd": 4}),
        (dup_sqr, ([1, 2], ZZ), [1, 4, 4], {"mul": 1, "iadd": 6, "square": 2}),
        (gf_sqr, ([1, 2], 5, ZZ), [1, 4, 4], {"mul": 1, "iadd": 6, "square": 2, "mod": 3}),
    ]
    for kernel, arguments, value, counts in cases:
        work = CoefficientWork(sum(counts.values()))
        with measure_coefficient_kernels(work):
            require(kernel(*arguments) == value, "incorrect dense or modular kernel result")
        require(dict(work.stages) == {f"sympy.{kernel.__name__}.{k}": n for k, n in counts.items()},
                "incorrect dense or modular operation totals")

    # At the first operation, even user-visible scalar arithmetic must not run with zero credit.
    class CheckedInt(int):
        def __mul__(self, other):
            raise AssertionError("refused multiplication executed")

    try:
        with measure_coefficient_kernels(CoefficientWork(0)):
            gf_mul([CheckedInt(2)], [3], 5, ZZ)
    except OperationLimit as exhausted:
        require(str(exhausted) == "sympy.gf_mul.mul", "wrong zero-budget refusal")
    else:
        raise ValueError("zero-budget multiplication was not refused")


def run(plan, checkout):
    probe = load_probe()
    bridge = checkout / probe.BRIDGE
    authority = probe.run(plan.read_bytes(), bridge)
    module = {}
    exec(compile(bridge.read_bytes(), str(bridge), "exec"), module)
    check_exact_kernel_prefix()
    rows = []
    for constant in (-1, 1):
        request = public_request(probe, module["PROTOCOL"], constant)
        expected = factor(module["factor_payload"], request)
        complete = observed(module["factor_payload"], request, 1000000)
        require(complete["status"] == "COMPLETED" and complete["result"] == expected,
                "instrumented result differs from reference")
        require(complete["used"] == sum(complete["stages"].values()), "unbalanced coefficient ledger")
        top = complete["used"]
        exhausted_prefixes = []
        for cap in sorted({0, top // 2, max(0, top - 1), top}):
            partial = observed(module["factor_payload"], request, cap)
            require(partial["used"] == cap, "unexpected consumed prefix")
            if cap < top:
                exhausted_prefixes.append({"limit": cap, **partial})
                require(partial["status"] == "COEFFICIENT_LIMIT" and partial["result"] is None,
                        "exhausted operation published a result")
                require(all(count <= complete["stages"].get(k, 0) for k, count in partial["stages"].items()),
                        "prefix exceeds complete ledger")
            else:
                require(partial == complete, "exact ceiling changed the result")
        rows.append({"publicRequest": request, "observation": complete, "exhaustedPrefixes": exhausted_prefixes})
    require(rows[0]["observation"]["used"] == 0 < rows[1]["observation"]["used"],
            "the public controls must distinguish kernel coverage")
    return {"schema": "regelsuche.sympy-coefficient-kernel-development/v1",
            "runtimeId": authority["runtimeId"], "bridgeSha256": authority["bridgeSha256"],
            "planSha256": authority["planSha256"], "meterSha256": hashlib.sha256(
                Path(__file__).with_name("frozen_sympy_coefficient_work.py").read_bytes()).hexdigest(),
            "coverage": ["dup_mul", "dup_sqr", "gf_mul", "gf_sqr"],
            "completeBackendWork": False, "studyRowsExecuted": 0, "publicCases": rows}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("plan", type=Path)
    parser.add_argument("--checkout", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    print(json.dumps(run(args.plan, args.checkout), sort_keys=True, separators=(",", ":")))


if __name__ == "__main__":
    main()
