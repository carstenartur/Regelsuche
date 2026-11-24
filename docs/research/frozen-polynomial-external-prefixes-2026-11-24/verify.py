"""Check digests, source/request bindings, and returned factor products independently of GraalPy and SymPy."""

import argparse
import collections
from fractions import Fraction
import hashlib
import json
from pathlib import Path

AREA = Path(__file__).resolve().parent


def rows(file, expected):
    raw = file.read_bytes()
    if hashlib.sha256(raw).hexdigest() != expected:
        raise ValueError(f'retained file digest differs: {file}')
    return [json.loads(line) for line in raw.splitlines()]

def as_map(terms):
    result = {}
    for value in terms:
        assert len(value['exponents']) == 1
        exponent, = value['exponents']
        assert type(exponent) is int and exponent >= 0 and exponent not in result
        coefficient = Fraction(int(value['numerator']), int(value['denominator']))
        assert coefficient != 0
        result[exponent] = coefficient
    return result

def mul(left, right):
    result = collections.defaultdict(Fraction)
    for degree, a in left.items():
        for other_degree, b in right.items():
            result[degree + other_degree] += a * b
    return {degree:c for degree,c in result.items() if c}

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--result-directory', type=Path, default=AREA)
args = parser.parse_args()
protocol = json.loads((AREA / 'protocol.json').read_bytes())
for filename, digest in protocol['tools'].items():
    if hashlib.sha256((AREA / filename).read_bytes()).hexdigest() != digest:
        raise ValueError(f'reproducer digest differs: {filename}')

prefixes = rows(AREA / 'prefix-rows.jsonl', protocol['files']['prefix-rows.jsonl'])
reachable = [prefix for prefix in prefixes if 'bridgePayload' in prefix]
strict = rows(args.result_directory / 'strict.jsonl', protocol['files']['strict.jsonl'])
raw = rows(args.result_directory / 'raw.jsonl', protocol['files']['raw.jsonl'])
assert len(prefixes) == 120 and len(reachable) == len(raw) == len(strict) == 19
count = collections.defaultdict(lambda: [0,0,0])
not_reached = collections.Counter()
for row in prefixes:
    if 'bridgePayload' not in row:
        assert row['prefixStatuses']
        not_reached[tuple(row['prefixStatuses'])] += 1
for prefix, a, b in zip(reachable, strict, raw, strict=True):
    assert prefix['inputId'] == a['inputId'] == b['inputId']
    a, b = a['observation'], b['observation']
    request = json.loads(prefix['bridgePayload'])
    digest = hashlib.sha256(prefix['bridgePayload'].encode()).hexdigest()
    assert a['inputSha256'] == b['inputSha256'] == digest
    assert a['workLimit'] == prefix['requestLimit'] and a['workLimit'] <= prefix['factorizationLimit']
    assert a['coverageGoal'] == 'COMPLETE_BACKEND_WORK' and a['strictNative']
    assert a['status'] == 'UNMEASURED' and a['used'] == 0 and a['result'] is None
    assert a['reason'] == 'transport.json.loads' and a['boundaryGaps'] == {'transport.json.loads': 1}
    assert a['bridgeSha256'] == b['bridgeSha256'] == protocol['bridgeSha256']
    assert a['meterSha256'] == protocol['meterSha256']
    assert a['runtime'] == b['runtime'] == 'graalpy' and a['implementationVersion'] == b['implementationVersion'] == '25.1.3'
    assert a['sympy'] == b['sympy'] == '1.14.0' and a['mpmath'] == b['mpmath'] == '1.3.0'
    assert a['studyAuthorized'] == b['studyAuthorized'] == False
    assert b['status'] == 'DIAGNOSTIC_OUTPUT'
    assert request['variableCount'] == 1 and request['protocol'] == b['protocol'] and request['domain'] == b['domain'] == 'QQ'
    computed = {0: Fraction(int(b['unit']['numerator']), int(b['unit']['denominator']))}
    factors = 0
    for factor in b['factors']:
        polynomial = as_map(factor['terms'])
        assert max(polynomial) > 0
        multiplicity = factor['multiplicity']
        assert type(multiplicity) is int and multiplicity > 0
        factors += multiplicity
        for _ in range(multiplicity):
            computed = mul(computed, polynomial)
    assert computed == as_map(request['terms'])
    value = count[prefix['checkpoint']]
    value[0] += 1
    value[1] += factors > 1
    value[2] += factors == 1
print(json.dumps({'byCheckpoint': count, 'prefixTerminalStatusCombinations': {str(k):v for k,v in not_reached.items()}}, sort_keys=True))
