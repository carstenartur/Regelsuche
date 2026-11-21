"""Compare the entire ordered operation receipt with an independent original-bytecode trace."""

import argparse
import hashlib
import json
from pathlib import Path
import random
import subprocess
import tempfile

from frozen_graalpy_random_work import checked_random
from frozen_sympy_operation_work import OperationWork


def opcode(stage):
    operation = stage.rsplit('.', 1)[1]
    specific = {'state-field-read': 'GETFIELD.state', 'index-read': 'GETFIELD.index',
                'index-write': 'PUTFIELD.index', 'mag01-field-read': 'GETSTATIC.MAG01',
                'state-write': 'IASTORE', 'state-read': 'IALOAD', 'mag01-read': 'IALOAD',
                'loop-lt': 'IF_ICMPGE', 'loop-visit': 'LOOP_VISIT', 'ge': 'IF_ICMPLT',
                'int-to-double': 'I2D', 'ushr': 'IUSHR', 'shl': 'ISHL', 'xor': 'IXOR',
                'and': 'IAND', 'or': 'IOR', 'mul': 'DMUL'}
    if operation == 'increment':
        return 'IINC' if '.twist.' in stage else 'IADD'
    if operation == 'add':
        return 'DADD' if '.nextDouble.' in stage else 'IADD'
    return specific[operation]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path, help='exact upstream PRandom.java')
    parser.add_argument('--jdk', type=Path, required=True)
    args = parser.parse_args()
    source = args.source.read_bytes()
    oid = hashlib.sha1(b'blob ' + str(len(source)).encode() + b'\0' + source).hexdigest()
    if oid != '81be875bb7909a389942a10691d21b56f23a76d6':
        raise ValueError('PRandom.java is not the original Git blob')
    seeds = []
    observed = []
    for seed, consumed_words in [(0, 0), (17, 2), (31, 623)]:
        rng = random.Random(seed)
        for _ in range(consumed_words):
            rng.getrandbits(1)
        seeds.append(','.join(map(str, rng.getstate()[1])))
        work = OperationWork(100000)
        value = checked_random(work, rng)
        observed.append((value, rng.getstate()[1], [opcode(event) for event in work.events]))
    stubs = {
        'Shape.java': 'package com.oracle.truffle.api.object; public class Shape {}',
        'PythonBuiltinObject.java': ('package com.oracle.graal.python.builtins.objects.object; '
                                    'import com.oracle.truffle.api.object.Shape; '
                                    'public class PythonBuiltinObject { public PythonBuiltinObject(Object c, Shape s) {} }')
    }
    with tempfile.TemporaryDirectory() as temp:
        root = Path(temp)
        for name, content in stubs.items():
            (root/name).write_text(content)
        (root/'seeds').write_text('\n'.join(seeds))
        tracer = Path(__file__).with_name('FrozenRandomBytecodeControl.java')
        subprocess.run([args.jdk/'bin/javac', '-J-XX:-UsePerfData', '-d', root, *[root/name for name in stubs],
                        args.source, tracer], check=True)
        result = subprocess.run([args.jdk/'bin/java', '-XX:-UsePerfData', '-cp', root, 'FrozenRandomBytecodeControl',
                                 root, root/'seeds'], check=True, capture_output=True, text=True)
    lines = result.stdout.splitlines()
    if len(lines) != len(observed) * 3:
        raise ValueError('unexpected number of Java traces')
    receipts = []
    for index, (expected, state, steps) in enumerate(observed):
        value, words, encoded = lines[index * 3:index * 3 + 3]
        if float.fromhex(value) != expected or tuple(map(int, words.split(','))) != state:
            raise ValueError('original Java result or generator state differs')
        actual = encoded.split(',')
        if actual != steps:
            mismatch = next((j for j,(a,b) in enumerate(zip(actual,steps)) if a != b),min(len(actual),len(steps)))
            raise ValueError(f'ordered operation {mismatch}: JVM {actual[mismatch:mismatch+5]}, meter {steps[mismatch:mismatch+5]}')
        receipts.append({'operations':len(actual),'sequenceSha256':hashlib.sha256(encoded.encode()).hexdigest()})
    print(json.dumps({'sourceGitBlob':oid,'draws':receipts,'allValuesStatesAndOperationsEqual':True},sort_keys=True))


if __name__ == '__main__':
    main()
