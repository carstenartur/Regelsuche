"""Historical goals checked with sparse exact Fractions, independently of both competitors."""
import ast
from fractions import Fraction
from .polynomial import _add, _multiply, parse, polynomial
from .run_recurrence_knowledge import check_task, rational


def require(condition, message):
    if not condition:
        raise ValueError(message)


def natural(value):
    require(type(value) is int and value >= 0, 'nonnegative integer required')
    return value


def check_payload(task):
    require(isinstance(task, dict), 'task object required')
    kind = task.get('kind')
    if kind == 'INVARIANT':
        require(set(task) == {'kind', 'recurrence'}, 'recurrence task fields')
        check_task(task['recurrence'])
    else:
        require(kind in ('FACTOR', 'TWO_SQUARES') and set(task) == {'kind', 'source'}, 'structural task fields')
        polynomial(task['source'])


def nonconstant(node):
    return any(polynomial(ast.unparse(node)))


def shape(kind, output):
    node = parse(output)
    if not isinstance(node, ast.BinOp):
        return False
    if kind == 'FACTOR':
        return isinstance(node.op, ast.Mult) and nonconstant(node.left) and nonconstant(node.right)
    def square(term):
        return (isinstance(term, ast.BinOp) and isinstance(term.op, ast.Pow)
                and isinstance(term.right, ast.Constant) and type(term.right.value) is int
                and term.right.value == 2 and nonconstant(term.left))
    return isinstance(node.op, ast.Add) and square(node.left) and square(node.right)


def audit_law(task, law):
    require(isinstance(law, dict) and set(law) == {'polynomial', 'evolution', 'initialValue'}, 'law fields')
    p = polynomial(law['polynomial'])
    n = len(task['initial'])
    require(any(p), 'nonconstant invariant required')
    require(all(name in {f'x{i}' for i in range(n)} for m in p for name, _ in m), 'foreign invariant variable')
    require(all(sum(power for _, power in m) <= task['degree'] for m in p), 'invariant degree bound')
    sign = {'FIXED': 1, 'ALTERNATING': -1}.get(law['evolution'])
    require(sign is not None, 'invariant evolution')
    images = {}
    for i in range(n):
        image = {(): rational(task['offset'][i])}
        for j, coefficient in enumerate(task['linear'][i]):
            image = _add(image, {((f'x{j}', 1),): rational(coefficient)})
        images[f'x{i}'] = image
    transformed, initial = {}, Fraction(0)
    for monomial, coefficient in p.items():
        term, value = {(): coefficient}, coefficient
        for variable, power in monomial:
            for _ in range(power):
                term = _multiply(term, images[variable])
            value *= rational(task['initial'][int(variable[1:])]) ** power
        transformed = _add(transformed, term)
        initial += value
    require(transformed == {m: sign * c for m, c in p.items()}, 'universal simultaneous recurrence step failed')
    require(isinstance(law['initialValue'], str) and str(initial) == law['initialValue'], 'recurrence base case failed')


def audit_witness(source, output, response):
    if 'witness' not in response:
        return
    require(isinstance(response['witness'], list), 'witness must be a list')
    normalized = response['normalizedInput']
    identity = lambda text: ast.dump(parse(text), include_attributes=False)
    require(identity(source) == identity(normalized), 'parsed native input changed source syntax')
    current = normalized
    for edge in response['witness']:
        require(identity(current) == identity(edge['source']), 'selected path is not source-bound or continuous')
        require(polynomial(edge['source']) == polynomial(edge['target']), 'false selected edge')
        current = edge['target']
    require(identity(current) == identity(output), 'selected output differs from witness endpoint')


def audit_task(task, response):
    check_payload(task)
    require(response.get('status') == 'RESULT' and response.get('task') == task, 'response/task binding')
    solved = response.get('solved')
    require(type(solved) is bool and type(response.get('withinBudget')) is bool, 'explicit boolean outcomes required')
    require(not solved or response['withinBudget'], 'success outside the task budget')
    if task['kind'] == 'INVARIANT':
        if solved:
            audit_law(task['recurrence'], response['law'])
        else:
            require(response.get('law') is None, 'unsolved recurrence claims a law')
    else:
        if 'output' in response:
            output = response['output']
            require(polynomial(task['source']) == polynomial(output), 'false polynomial identity')
            audit_witness(task['source'], output, response)
            require(shape(task['kind'], output) == solved, 'reported success does not match requested structural shape')
        else:
            require(not solved, 'successful structural result omitted output')
    return dict(valid=True, solved=solved, outcome='VERIFIED' if solved else response.get('outcome', 'INCONCLUSIVE'))
