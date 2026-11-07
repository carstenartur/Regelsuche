"""Pinned targeted polynomial procedure under the same source-only node goal."""
import json
import sys
import sympy as s
from .native_worker import construct
from .polynomial import parse, polynomial
from .run import canonical
from .run_knowledge_lifecycle import digest, node_count, require


def evaluate(task):
    require(set(task) == {'source', 'maximumNodes'}, 'source-only task fields')
    require(s.__version__ == '1.14.0', 'pinned SymPy version required')
    polynomial(task['source'])
    expression = construct(parse(task['source']))
    candidates = [str(expression), str(s.factor(expression))]
    output = min(candidates, key=lambda value: (node_count(value), value))
    return dict(status='RESULT', profile='SYMPY', generationId='STATELESS', task=task, output=output,
                solved=node_count(output) <= task['maximumNodes'], withinBudget=True)


def main():
    print(json.dumps(dict(status='READY', sympy=s.__version__)), flush=True)
    initialized = False
    for line in sys.stdin:
        try:
            request = json.loads(line)
            if request.get('op') == 'initialize' and not initialized:
                require(set(request) == {'op', 'prelude'}, 'initialization fields')
                initialized = True
                result = dict(status='INITIALIZED', profile='SYMPY', generationId='STATELESS',
                              preludeHash=digest(canonical(request['prelude'])), declinedLearning=True)
            elif request.get('op') == 'load' and initialized:
                require(set(request) == {'op'}, 'load fields')
                result = dict(status='LOADED', profile='SYMPY', generationId='STATELESS')
            elif request.get('op') == 'run' and initialized:
                require(set(request) == {'op', 'task'}, 'query fields')
                result = evaluate(request['task'])
            else:
                raise ValueError('configured source-only operation required')
        except Exception as error:
            result = dict(status='ERROR', error=f'{type(error).__name__}: {error}')
        print(json.dumps(result), flush=True)


if __name__ == '__main__': main()
