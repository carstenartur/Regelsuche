"""Measure the frozen SymPy bridge on one structured formation/development payload.

The source and native operation meters are development evidence. This command
never grants study authority and does not open any qualification resource.
"""

import argparse
import hashlib
import json
from pathlib import Path
import sys

import frozen_graalpy_random_work

from frozen_sympy_coefficient_work import OperationLimit
from frozen_sympy_native_work import UnmeasuredOperation
from frozen_sympy_operation_work import OperationWork, instrument_source
from frozen_sympy_source_patch import _codes, guard_math_calls, patch_sources

SCHEMA = 'regelsuche.frozen-sympy-operation-observation/v1'
BRIDGE = Path('regelsuche-math-sympy/src/main/resources/GRAALPY-VFS/de.regelsuche/regelsuche-math-sympy/src/regelsuche_sympy_factor.py')
BRIDGE_HASH = 'd67e266e11c92fdd3e5e2c85c30a89a7b5292f56e291bc86a9d036ac7aab23c4'
PIN_FILE = Path(__file__).with_name('frozen_sympy_operation_pins.json')
PIN_HASH = '5d1e9fdc4960ffa6112bcbe475700a34c7914518c2c58bb9197ea9d26750a14c'
OWNER = 'regelsuche_frozen_bridge'


def digest(data):
    return hashlib.sha256(data).hexdigest()


METER_FILES = (
    'check-frozen-polynomial-operation-work.py',
    'frozen_graalpy_random_work.py',
    'frozen_sympy_coefficient_work.py',
    'frozen_sympy_native_work.py',
    'frozen_sympy_operation_work.py',
    'frozen_sympy_sequence_work.py',
    'frozen_sympy_source_patch.py',
)


def meter_digest():
    material = ''.join(
        name + '=' + digest(PIN_FILE.with_name(name).read_bytes()) + '\n'
        for name in METER_FILES
    )
    return digest(material.encode('ascii'))


def public_input():
    return {'protocol':'regelsuche.sympy-factorization/v1','domain':'QQ','variableCount':1,
            'terms':[{'exponents':[p],'numerator':'1','denominator':'1'} for p in (4,1,0)]}


def ordinary_bridge(source, path):
    scope={'__name__':OWNER}
    exec(compile(source,str(path),'exec',dont_inherit=True),scope)
    return scope['factor_payload']


def measured_bridge(source, path, work):
    transformed=instrument_source(source.decode('utf-8'),OWNER)
    module=compile(transformed,str(path),'exec',dont_inherit=True)
    # Install and wire the original module outside row authority. Only invocation is charged.
    setup=OperationWork(65536,retain_events=False)
    scope={'__name__':OWNER,'_regelsuche_operation_work':setup}
    exec(module,scope)
    scope['_regelsuche_operation_work']=work
    codes=[code for group in _codes(module).values() for code in group]
    return scope['factor_payload'],codes


def measure(encoded, root, limit, strict_native, verify_result, require_complete=False):
    import sympy, mpmath
    from sympy.core.cache import clear_cache
    from sympy.core.random import seed
    path=root/BRIDGE
    source=path.read_bytes()
    pins=PIN_FILE.read_bytes()
    if digest(source)!=BRIDGE_HASH or digest(pins)!=PIN_HASH:
        raise ValueError('frozen bridge or operation-source pins differ')
    if sympy.__version__!='1.14.0' or mpmath.__version__!='1.3.0':
        raise ValueError('requires frozen SymPy and mpmath versions')
    if sympy.polys.domains.ZZ.dtype is not int:
        raise ValueError('ground arithmetic requires Python integers')
    original=ordinary_bridge(source,path)
    expected=None
    if verify_result:
        clear_cache();seed(0)
        expected=json.loads(original(encoded))
    clear_cache();seed(0)
    work=OperationWork(limit,retain_events=False,require_native_coverage=strict_native, require_complete_work=require_complete)
    run,bridge_codes=measured_bridge(source,path,work)
    result=None;reason=None
    package_pins = json.loads(pins)
    if sys.implementation.name == 'graalpy':
        release = Path(sys.executable).resolve().parents[1] / 'release'
        if digest(release.read_bytes()) != 'c4008c81d3b5a48d87b0c0590631f3fd9894f85314c105ed2fc4de0b6fc09305':
            raise ValueError('PRandom instrumentation requires the audited historical GraalPy build')
        random_pin = '0693d4ded36916f5b07d6c395cc331dbf1011bb70e90daaa29eaa32490a09425'
    else:
        random_pin = 'de48263a837339c7e01cf9f3c4d756696e0e0775f7778eb9cf81ad5b00134569'
    with patch_sources(work, {**package_pins, 'random': random_pin}) as installed:
        try:
            with guard_math_calls((*installed,*bridge_codes),('sympy','mpmath','random',OWNER), strict_foreign=strict_native) as coverage:
                raw=run(encoded)
                work.require_uninterrupted()
            result=json.loads(raw)
            if expected is not None and any(result[k] != expected[k] for k in ('protocol','domain','unit','factors')):
                raise ValueError('measured mathematical output differs from the original frozen bridge')
            status='OBSERVED'
        except OperationLimit as stopped:
            status='WORK_EXHAUSTED';reason=str(stopped)
        except UnmeasuredOperation as stopped:
            status='UNMEASURED';reason=str(stopped)
    info=sys.implementation
    return {'schema':SCHEMA,'status':status,'reason':reason,'runtime':info.name,
            'implementationVersion':'.'.join(map(str,info.version[:3])), 'pythonVersion':sys.version.split()[0],
            'sympy':sympy.__version__,'mpmath':mpmath.__version__,
            'inputSha256':digest(encoded.encode()),'bridgeSha256':BRIDGE_HASH,'pinsSha256':PIN_HASH,
            'meterSha256':meter_digest(),'stdlibRandomSha256':random_pin,'packageModules':len(package_pins),'pythonVisits':coverage.visits,
            'coverageGoal':'COMPLETE_BACKEND_WORK' if require_complete else 'DECLARED_OPERATIONS',
            'boundaryGaps':dict(sorted(work.boundary_gaps.items())),
            'foreignCallbacks':dict(sorted(coverage.foreign_calls.items())),'workLimit':limit,'used':work.used,'stages':dict(sorted(work.stages.items())),
            'opaqueCalls':[{'stage':key[0],'native':key[1],'calls':count} for key,count in sorted(work.opaque_calls.items())],
            'strictNative':strict_native,'studyAuthorized':False,
            'result':None if result is None else {k:result[k] for k in ('protocol','domain','unit','factors')}}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--checkout',type=Path,default=Path(__file__).resolve().parents[1])
    source = p.add_mutually_exclusive_group()
    source.add_argument('--input', type=Path, help='structured public or formation-only polynomial payload')
    source.add_argument('--stdin', action='store_true', help='read the exact UTF-8 payload from stdin')
    p.add_argument('--frozen-runtime', action='store_true', help='require historical GraalPy 25.1.3')
    p.add_argument('--limit',type=int,default=1000000)
    p.add_argument('--strict-native',action='store_true')
    p.add_argument('--complete-work',action='store_true',help='refuse any uncovered semantic boundary before entry')
    p.add_argument('--compare-original',action='store_true',help='development-only unmetered reference run')
    a=p.parse_args()
    if a.frozen_runtime and (sys.implementation.name != 'graalpy' or
                             sys.implementation.version[:3] != (25,1,3) or
                             sys.implementation.version.releaselevel != 'final'):
        p.error('historical GraalPy 25.1.3 is required')
    if a.stdin:
        encoded = sys.stdin.buffer.read().decode('utf-8')
    elif a.input:
        encoded = a.input.read_bytes().decode('utf-8')
    else:
        encoded = json.dumps(public_input(), sort_keys=True, separators=(',', ':'))
    print(json.dumps(measure(encoded,a.checkout,a.limit,a.strict_native or a.complete_work,a.compare_original,a.complete_work),sort_keys=True,separators=(',',':')))


if __name__=='__main__':
    main()
