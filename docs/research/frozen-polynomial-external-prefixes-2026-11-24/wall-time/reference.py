"""Cold historical GraalPy worker for the elapsed-time comparison."""

import hashlib
import json
from pathlib import Path
import sys

bridge = Path(sys.argv[1])
sys.path.insert(0, sys.argv[2])
source = bridge.read_bytes()
if hashlib.sha256(source).hexdigest() != 'd67e266e11c92fdd3e5e2c85c30a89a7b5292f56e291bc86a9d036ac7aab23c4':
    raise ValueError('bridge digest differs')

import mpmath
import sympy
from sympy.core.random import seed

if (sys.implementation.name, sys.implementation.version[:3], sympy.__version__, mpmath.__version__) != (
        'graalpy', (25, 1, 3), '1.14.0', '1.3.0'):
    raise ValueError('reference runtime differs')
seed(0)
scope = {'__name__': 'regelsuche_wall_time'}
exec(compile(source, str(bridge), 'exec', dont_inherit=True), scope)
payload = sys.stdin.buffer.read()
output = json.loads(scope['factor_payload'](payload.decode('utf-8')))
result = {k: output[k] for k in ('unit', 'factors', 'factorNanos', 'totalNanos')}
result['inputSha256'] = hashlib.sha256(payload).hexdigest()
result['outcome'] = 'FACTORS_RETURNED'
print(json.dumps(result, sort_keys=True, separators=(',', ':')))
