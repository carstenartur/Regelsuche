"""Attest the loaded #748 backend on a public development input (under GraalPy)."""

import argparse
import hashlib
import json
from pathlib import Path
import sys

PLAN_SHA256 = "6cfac16d65611820b713cf1f2aca0fdb724fc542ccef6b9de80981dc290af619"
BRIDGE = Path("regelsuche-math-sympy/src/main/resources/GRAALPY-VFS/de.regelsuche/"
              "regelsuche-math-sympy/src/regelsuche_sympy_factor.py")


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def check(condition, message):
    if not condition:
        raise ValueError(message)


def term(power, numerator):
    return {"exponents": [power], "numerator": str(numerator), "denominator": "1"}


def run(plan_bytes, bridge):
    import mpmath
    import sympy

    implementation = sys.implementation
    check(sha256(plan_bytes) == PLAN_SHA256, "execution plan differs from the frozen bytes")
    plan = json.loads(plan_bytes)
    version = ".".join(map(str, implementation.version[:3]))
    check(implementation.version.releaselevel == "final", "expected a final interpreter release")
    observed = f"{implementation.name}-{version}_sympy-{sympy.__version__}_mpmath-{mpmath.__version__}"
    expected = plan["sharedAuthorities"]["externalRuntimeId"]
    check(observed == expected, f"runtime mismatch: expected {expected}, observed {observed}")

    script = bridge.read_bytes()
    # Execute the exact bytes bound in the receipt, as the production module does.
    module = {"__name__": "regelsuche_runtime_probe"}
    exec(compile(script, str(bridge), "exec"), module)
    info = json.loads(module["runtime_info"]())
    check(info["pythonImplementation"] == implementation.name and info["sympyVersion"] == sympy.__version__,
          "bridge runtime disagrees with loaded interpreter or SymPy")
    request = {"protocol": module["PROTOCOL"], "domain": "ZZ", "variableCount": 1,
               "terms": [term(2, 1), term(0, -1)]}
    response = json.loads(module["factor_payload"](json.dumps(request)))
    expected_factors = [{"multiplicity": 1, "terms": [term(1, 1), term(0, sign)]} for sign in (-1, 1)]
    math = {key: response[key] for key in ("domain", "unit", "factors", "protocol")}
    check(math == {"domain": "ZZ", "unit": {"numerator": "1", "denominator": "1"},
                   "factors": expected_factors, "protocol": request["protocol"]},
          "public development polynomial did not return its two exact factors")
    check(all(info[key] == response[key] for key in info), "factor invocation runtime changed")

    return {"schema": "regelsuche.polynomial-theory-runtime-probe/v1", "studyId": plan["studyId"],
            "planSha256": PLAN_SHA256, "bridgeSha256": sha256(script), "runtimeId": observed,
            "pythonLanguageVersion": info["pythonVersion"], "publicRequest": request, "observedResult": math,
            "checkedScope": "LOADED_RUNTIME_AND_PUBLIC_BRIDGE_FACTORISATION"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("plan", type=Path, help="canonical execution plan produced by the checkout")
    parser.add_argument("--checkout", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    result = run(args.plan.read_bytes(), args.checkout / BRIDGE)
    print(json.dumps(result, ensure_ascii=True, sort_keys=True, separators=(",", ":")))


if __name__ == "__main__":
    main()
