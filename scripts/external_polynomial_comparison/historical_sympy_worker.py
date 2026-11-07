"""Declared general SymPy formation procedures. No historical answer or query target."""
import hashlib
import itertools
import json
import sys
import time
import sympy as s
from .historical_judge import check_payload, require, shape
from .native_worker import construct
from .polynomial import parse
from .run import canonical


VERSION = '1.14.0'


def invariant(task):
    n, degree = len(task['initial']), task['degree']
    variables = s.symbols(f'x0:{n}', real=True)
    powers = [p for p in itertools.product(range(degree + 1), repeat=n) if sum(p) <= degree]
    basis = [s.prod(x**k for x, k in zip(variables, p)) for p in powers]
    images = {x: s.Rational(task['offset'][i]) + sum(s.Rational(c)*y for c, y in zip(task['linear'][i], variables))
              for i, x in enumerate(variables)}
    candidates = []
    for sign, evolution in [(1, 'FIXED'), (-1, 'ALTERNATING')]:
        columns = [s.Poly(s.expand(m.xreplace(images) - sign*m), *variables) for m in basis]
        matrix = s.Matrix([[p.coeff_monomial(m) for p in columns] for m in basis])
        for vector in matrix.nullspace():
            if not any(c and m != 1 for c, m in zip(vector, basis)):
                continue
            first = next(c for c in vector if c)
            law = s.expand(sum(c*m/first for c, m in zip(vector, basis)))
            value = law.subs(dict(zip(variables, map(s.Rational, task['initial']))), simultaneous=True)
            candidates.append(dict(polynomial=str(law), evolution=evolution, initialValue=str(value)))
    return candidates[0] if candidates else None


def factor(expression):
    coefficient, factors = s.factor_list(expression)
    terms = ([coefficient] if coefficient != 1 else []) + [f for f, count in factors for _ in range(count)]
    if not terms:
        return str(coefficient)
    result = terms[0]
    for term in terms[1:]:
        result = s.Mul(result, term, evaluate=False)
    return str(result)


def two_squares(expression):
    coefficient, factors = s.factor_list(expression, extension=s.I)
    scalar = s.sqrt(coefficient)
    if scalar.is_Rational is not True:
        return None
    remaining = {s.expand(f): count for f, count in factors}
    product = scalar
    for f in sorted(remaining, key=str):
        count = remaining[f]
        if not count:
            continue
        conjugate = s.expand(s.conjugate(f))
        if conjugate == f:
            if count % 2:
                return None
            product *= f**(count//2)
        else:
            if remaining.get(conjugate) != count:
                return None
            product *= f**count
            remaining[conjugate] = 0
        remaining[f] = 0
    real, imaginary = s.expand(product).as_real_imag()
    return str(s.Add(s.Pow(s.expand(real), 2, evaluate=False),
                     s.Pow(s.expand(imaginary), 2, evaluate=False), evaluate=False))


def evaluate(task):
    require(s.__version__ == VERSION, 'pinned SymPy version required')
    started, cpu = time.perf_counter_ns(), time.process_time_ns()
    # Keep normal library caches, just as the native procedures keep their JIT/internal caches.
    # This adapter adds no complete-query answer cache.
    check_payload(task)
    result = dict(status='RESULT', task=task, withinBudget=True, reused=False)
    if task['kind'] == 'INVARIANT':
        law = invariant(task['recurrence'])
        result.update(solved=law is not None, law=law, outcome='VERIFIED' if law else 'NO_LAW_IN_ANSATZ')
    else:
        expression = construct(parse(task['source']))
        output = factor(expression) if task['kind'] == 'FACTOR' else two_squares(expression)
        solved = output is not None and shape(task['kind'], output)
        result.update(solved=solved, outcome='VERIFIED' if solved else 'INCONCLUSIVE')
        if output is not None:
            result['output'] = output
    result.update(nativeWallNanos=time.perf_counter_ns()-started, nativeCpuNanos=time.process_time_ns()-cpu)
    return result


def main():
    print(json.dumps(dict(status='READY', sympy=s.__version__)), flush=True)
    initialized = False
    for line in sys.stdin:
        try:
            request = json.loads(line)
            if request.get('op') == 'initialize' and not initialized:
                require(set(request) == {'op', 'profile', 'prelude', 'directory'} and request['profile'] == 'SYMPY', 'setup fields')
                # The identical prelude is genuinely supplied; this stateless procedure declines learning.
                digest = 'sha256:' + hashlib.sha256(canonical(request['prelude'])).hexdigest()
                response = dict(status='INITIALIZED', profile='SYMPY', preludeHash=digest, generationId='STATELESS')
                initialized = True
            else:
                require(initialized and set(request) == {'op', 'task'} and request['op'] == 'run', 'source-only query required')
                response = evaluate(request['task'])
                response.update(profile='SYMPY', generationId='STATELESS')
        except Exception as error:
            response = dict(status='ERROR', error=f'{type(error).__name__}: {error}')
        print(json.dumps(response, sort_keys=True, allow_nan=False), flush=True)


if __name__ == '__main__':
    main()
