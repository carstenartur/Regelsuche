"""Coefficient-operation metering for four univariate kernels in the pinned SymPy.

This development component covers multiplication and squaring in dense and
prime-field coefficient arrays. It does not issue a full factorization ledger.
Use one isolated Python process per measured row.
"""

from collections import Counter
from contextlib import contextmanager, ExitStack
import hashlib
import inspect
import operator
from pathlib import Path


class OperationLimit(BaseException):
    """Abort the measured call without entering a backend mathematical fallback."""


class CoefficientWork:
    def __init__(self, limit):
        if type(limit) is not int or limit < 0:
            raise ValueError("expected a nonnegative integer limit")
        self.limit = limit
        self.used = 0
        self.stages = Counter()

    def perform(self, kernel, operation, left, right):
        stage = f"sympy.{kernel}.{operation}"
        if self.used >= self.limit:
            raise OperationLimit(stage)
        self.used += 1
        self.stages[stage] += 1
        return OPERATORS[operation](left, right)


OPERATORS = {"iadd": operator.iadd, "mul": operator.mul,
             "square": operator.pow, "mod": operator.mod}
PINS = {
    "densearith": ("eb007a0c958a341d1103b4dd83b8cc81120446365db40a77d8e421210eb0995e", ("dup_mul", "dup_sqr")),
    "galoistools": ("67779dfc0d0da218fd473b8cde4a26efe6b2e0c9f3207a69c2057642938db93a", ("gf_mul", "gf_sqr")),
}


def instrument(kernel):
    name = kernel.__name__
    source = inspect.getsource(kernel)
    coefficient = "c" if name == "dup_sqr" else "coeff"
    rhs = "g[i - j]" if name.endswith("_mul") else "f[i - j]"

    def measured(op, left, right):
        return f"_regelsuche_coefficient_op('{name}', '{op}', {left}, {right})"

    def replace(line, replacement):
        nonlocal source
        if source.count(line) != 1:
            raise ValueError(f"unexpected {name} coefficient operation: {line}")
        source = source.replace(line, replacement)

    product = measured("mul", "f[j]", rhs)
    replace(f"{coefficient} += f[j]*{rhs}", f"{coefficient} = " + measured("iadd", coefficient, product))
    if name.endswith("_sqr"):
        replace(f"{coefficient} += {coefficient}", f"{coefficient} = " + measured("iadd", coefficient, coefficient))
        square = measured("square", "elem", "2")
        replace(f"{coefficient} += elem**2", f"{coefficient} = " + measured("iadd", coefficient, square))
    if name.startswith("gf_"):
        replace("h[i] = coeff % p", "h[i] = " + measured("mod", "coeff", "p"))
    namespace = {}
    exec(compile(source, kernel.__code__.co_filename, "exec"), kernel.__globals__, namespace)
    return namespace[name].__code__


@contextmanager
def measure_coefficient_kernels(work):
    from sympy.polys import densearith, galoistools

    with ExitStack() as restore:
        for module in (densearith, galoistools):
            digest, names = PINS[module.__name__.split(".")[-1]]
            if hashlib.sha256(Path(module.__file__).read_bytes()).hexdigest() != digest:
                raise ValueError(f"unexpected source for {module.__name__}")
            callback = "_regelsuche_coefficient_op"
            if callback in vars(module):
                raise RuntimeError("coefficient meter already installed")
            vars(module)[callback] = work.perform
            restore.callback(vars(module).pop, callback)
            for name in names:
                original = getattr(module, name)
                replacement_code = instrument(original)
                restore.callback(setattr, original, "__code__", original.__code__)
                # Existing SymPy imports share this function object.
                original.__code__ = replacement_code
        yield
