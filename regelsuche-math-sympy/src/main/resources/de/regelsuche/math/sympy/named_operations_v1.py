"""One predeclared operation per call over a closed structural scalar IR.

No parser/eval, reference form, expected verdict or result-based operation selection.
Uses the module's existing pinned SymPy installation and serialized lifecycle.
"""
import hashlib
import inspect
import json
import sys
import sympy as sp
from sympy.simplify.fu import fu

PROTOCOL = "regelsuche.sympy-named-operation/v1"
OPERATIONS = {"TRIGSIMP": sp.trigsimp, "FU": fu, "FACTOR": sp.factor,
              "CANCEL": sp.cancel, "TOGETHER": sp.together, "APART": sp.apart}


def runtime_info():
    return json.dumps({"pythonImplementation": sys.implementation.name,
                       "pythonVersion": sys.version.split()[0], "sympyVersion": sp.__version__})


def operation_payload(raw):
    value = json.loads(raw)
    if sp.__version__ != "1.14.0" or value["protocol"] != PROTOCOL:
        raise ValueError("pinned SymPy/protocol mismatch")
    operation = value["operation"]
    function = OPERATIONS[operation]
    symbols = {}
    for name in value["nonzeroSymbols"]:
        # nonzero=True would additionally assert realness in SymPy.
        symbols[name] = sp.Symbol(name, zero=False)

    def build(node, depth=0):
        if depth > 48:
            raise ValueError("expression depth exceeded")
        kind = node[0]
        if kind == "number":
            return sp.Rational(int(node[1]), int(node[2]))
        if kind == "symbol":
            return symbols.setdefault(node[1], sp.Symbol(node[1]))
        if kind in ("sin", "cos"):
            return {"sin": sp.sin, "cos": sp.cos}[kind](build(node[1], depth + 1), evaluate=False)
        a, b = build(node[1], depth + 1), build(node[2], depth + 1)
        if kind == "ADD":
            return sp.Add(a, b, evaluate=False)
        if kind == "SUB":
            return sp.Add(a, sp.Mul(-1, b, evaluate=False), evaluate=False)
        if kind == "MUL":
            return sp.Mul(a, b, evaluate=False)
        if kind == "DIV":
            return sp.Mul(a, sp.Pow(b, -1, evaluate=False), evaluate=False)
        if kind == "POW":
            return sp.Pow(a, b, evaluate=False)
        raise ValueError("unsupported structural scalar IR")

    source = build(value["sourceIr"])
    try:
        result = function(source)
        status, detail = "COMPLETED", "ONE_DECLARED_NATIVE_OPERATION"
    except NotImplementedError:
        result = None
        status, detail = "UNSUPPORTED", "NATIVE_OPERATION_NOT_IMPLEMENTED"
    source_file = inspect.getsourcefile(function)
    with open(source_file, "rb") as stream:
        source_hash = "sha256:" + hashlib.sha256(stream.read()).hexdigest()
    answer = {"protocol": PROTOCOL, "operation": operation, "status": status, "detail": detail,
              "output": "" if result is None else str(result).replace("**", "^"),
              "outputSrepr": "" if result is None else sp.srepr(result),
              "sourceSrepr": sp.srepr(source), "sourceModule": function.__module__,
              "function": function.__name__, "sourceModuleHash": source_hash,
              "sympyVersion": sp.__version__, "primitiveProof": "UNAVAILABLE",
              "internalWork": "UNAVAILABLE", "introducedAssumptions": [],
              "domain": "DECLARED_SCALAR_COMPLEX_SOURCE_DOMAIN_RETAINED",
              "selection": "NO_ALTERNATIVE_OPERATION_OR_BEST_OF"}
    return json.dumps(answer, separators=(",", ":"), sort_keys=True)


type("_RegelsucheNamedSymPyAdapter", (object,),
     {"operation_payload": staticmethod(operation_payload), "runtime_info": staticmethod(runtime_info)})()
