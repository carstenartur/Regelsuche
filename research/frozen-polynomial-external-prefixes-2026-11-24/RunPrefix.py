import hashlib
import json
from pathlib import Path
import runpy
import sys

checkout = Path(sys.argv[1])
packages = sys.argv[2]
mode = sys.argv[3]
sys.path[:0] = [str(checkout / 'scripts'), packages]
meter = runpy.run_path(str(checkout / 'scripts' / 'check-frozen-polynomial-operation-work.py'))
row = json.loads(sys.stdin.read())
payload = row['bridgePayload']
if mode == 'strict':
    result = meter['measure'](payload, checkout, row['requestLimit'], True, False, True)
elif mode == 'raw':
    import mpmath
    import sympy
    from sympy.core.cache import clear_cache
    from sympy.core.random import seed
    assert sys.implementation.name == 'graalpy' and sys.implementation.version[:3] == (25, 1, 3)
    assert (sympy.__version__, mpmath.__version__, sympy.polys.domains.ZZ.dtype) == ('1.14.0', '1.3.0', int)
    path = checkout / meter['BRIDGE']
    source = path.read_bytes()
    assert hashlib.sha256(source).hexdigest() == meter['BRIDGE_HASH']
    original = meter['ordinary_bridge'](source, path)
    clear_cache()
    seed(0)
    value = json.loads(original(payload))
    result = {
        'bridgeSha256': meter['BRIDGE_HASH'],
        'inputSha256': hashlib.sha256(payload.encode()).hexdigest(),
        'mpmath': mpmath.__version__,
        'sympy': sympy.__version__,
        'runtime': sys.implementation.name,
        'implementationVersion': '.'.join(map(str, sys.implementation.version[:3])),
        'status': 'DIAGNOSTIC_OUTPUT',
        'studyAuthorized': False,
        **{k: value[k] for k in ('protocol', 'domain', 'unit', 'factors')},
    }
else:
    raise ValueError(mode)
print(json.dumps({'inputId': row['inputId'], 'observation': result}, sort_keys=True, separators=(',', ':')))
