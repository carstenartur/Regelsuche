"""Data-only bridge for trusted Python-authored Regelsuche discovery domains.

The domain receives opaque canonical state/candidate strings. Exact integers and
fractions belong in these strings, not JSON floating point. The host separately
checks witnesses and issues mathematical certificates. This bridge never does.
"""
import hashlib
import json

REGELSUCHE_PYTHON_DOMAIN_PROTOCOL = "regelsuche.python-domain/v1"


def _regelsuche_unique_fields(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate protocol field")
        result[key] = value
    return result


def _regelsuche_wire_value(value, depth=0):
    if depth > 16:
        raise ValueError("protocol nesting limit")
    if type(value) is str:
        value.encode("utf-8", errors="strict")
    elif type(value) is int:
        if not -(2**31) <= value < 2**31:
            raise ValueError("use text for exact large integers")
    elif type(value) is bool:
        pass
    elif type(value) is list:
        for item in value:
            _regelsuche_wire_value(item, depth + 1)
    elif type(value) is dict:
        for key, item in value.items():
            if type(key) is not str or not key or not key[0].isascii() or not key[0].isalpha() \
                    or not key.isascii() or not key.isalnum():
                raise ValueError("protocol field name")
            _regelsuche_wire_value(item, depth + 1)
    else:
        raise ValueError("unsupported protocol value")


def _regelsuche_canonical(value):
    _regelsuche_wire_value(value)
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


def regelsuche_bind_domain(domain):
    """Bind six deployment-owned callables; requests never name Python code."""
    handlers = {
        "initial": (domain.initial, {"seed"}),
        "invariant": (domain.invariant, {"state"}),
        "successors": (domain.successors, {"state"}),
        "objective": (domain.objective, {"state"}),
        "candidate": (domain.candidate, {"state"}),
        "counterexamples": (domain.counterexamples, {"candidate", "budget"}),
    }
    if any(not callable(function) for function, _ in handlers.values()):
        raise ValueError("all six callbacks are required")

    def invoke_payload(request):
        if type(request) is not str or len(request) > 1_000_000 \
                or len(request.encode("utf-8")) > 1_000_000:
            raise ValueError("request byte limit")
        decoded = json.loads(request, object_pairs_hook=_regelsuche_unique_fields)
        if type(decoded) is not dict or set(decoded) != {
                "protocol", "binding", "operation", "configuration", "arguments"}:
            raise ValueError("request fields")
        if _regelsuche_canonical(decoded) != request \
                or decoded["protocol"] != REGELSUCHE_PYTHON_DOMAIN_PROTOCOL:
            raise ValueError("request canonical form/protocol")
        if type(decoded["binding"]) is not str or len(decoded["binding"]) != 64 \
                or any(c not in "0123456789abcdef" for c in decoded["binding"]):
            raise ValueError("binding hash")
        operation, arguments = decoded["operation"], decoded["arguments"]
        if type(operation) is not str or operation not in handlers:
            raise ValueError("unsupported callback")
        function, expected = handlers[operation]
        if type(arguments) is not dict or set(arguments) != expected \
                or type(decoded["configuration"]) is not str:
            raise ValueError("callback argument fields")
        if any(type(v) is not str for k, v in arguments.items() if k != "budget"):
            raise ValueError("opaque payload strings required")
        if "budget" in arguments and (type(arguments["budget"]) is not int or arguments["budget"] < 0):
            raise ValueError("counterexample budget")
        result = function(configuration=decoded["configuration"], **arguments)
        if type(result) is not dict:
            raise ValueError("callback result object required")
        response = _regelsuche_canonical({
            "protocol": REGELSUCHE_PYTHON_DOMAIN_PROTOCOL,
            "binding": decoded["binding"], "operation": operation,
            "requestSha256": hashlib.sha256(request.encode("utf-8")).hexdigest(),
            "result": result,
        })
        if len(response.encode("utf-8")) > 1_000_000:
            raise ValueError("response byte limit")
        return response

    return invoke_payload
