"""Run original-bridge pre-entry and native string controls on the fifteen pinned embedded JVM artifacts."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile


def digest(file):
    with file.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def verify_packages(root, wheels):
    for name, expected in wheels.items():
        wheel = root / 'wheels' / name
        if digest(wheel) != expected:
            raise ValueError('historical wheel identity differs: ' + name)
        with zipfile.ZipFile(wheel) as archive:
            for member in archive.namelist():
                if member.endswith('.py') and (root / 'packages' / member).read_bytes() != archive.read(member):
                    raise ValueError('installed Python source differs: ' + member)


def run():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--provision', type=Path, required=True)
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--mode', choices=('bom', 'cold', 'warm', 'bulk', 'bulk-survey'), default='bom')
    args = parser.parse_args()
    scripts = Path(__file__).resolve().parent
    manifest = json.loads((scripts / 'frozen_sympy_jvm_runtime.json').read_text())
    root = args.provision.resolve()
    jars = sorted((root / 'runtime').rglob('*.jar'))
    identities = {jar.name: digest(jar) for jar in jars}
    if len(jars) != len(identities) or identities != manifest['artifacts']:
        raise ValueError('runtime does not have precisely the pinned historical Maven JARs')
    verify_packages(root, manifest['wheels'])
    release = (args.jdk / 'release').read_text().splitlines()
    if f'JAVA_RUNTIME_VERSION="{manifest["java"]}"' not in release:
        raise ValueError('historical Java runtime identity differs')
    if digest(args.jdk / 'lib/modules') != manifest['jdkModulesSha256'] or digest(args.jdk / 'lib/src.zip') != manifest['jdkSourcesSha256']:
        raise ValueError('original JDK platform classes or sources differ')
    classpath = os.pathsep.join(map(str, jars))
    with tempfile.TemporaryDirectory() as directory:
        temp = Path(directory)
        subprocess.run([args.jdk / 'bin/javac', '-cp', classpath, '-d', temp,
                        scripts / 'FrozenGraalPyJsonAdmission.java', scripts / 'FrozenGraalPyJsonAdmissionControl.java',
                        scripts / 'FrozenGraalPyRegexAdmission.java', *sorted((scripts / 'frozen-jvm').glob('*.java'))],
                       check=True)
        bootstrap = temp / 'bootstrap.jar'
        subprocess.run([args.jdk / 'bin/jar', 'cf', bootstrap, '-C', temp, 'de'], check=True)
        (temp / 'manifest').write_text('Premain-Class: FrozenGraalPyJsonAdmission\nBoot-Class-Path: bootstrap.jar\nCan-Retransform-Classes: true\n')
        agent = temp / 'agent.jar'
        subprocess.run([args.jdk / 'bin/jar', 'cfm', agent, temp / 'manifest',
                        *[p.name for p in sorted(temp.glob('*.class'))]], check=True, cwd=temp)
        if args.mode.startswith('bulk'):
            subprocess.run([args.jdk / 'bin/java', '-cp', temp, 'FrozenStringPrimitivesControl'], check=True)
        subprocess.run([args.jdk / 'bin/java', '-Xmx896m', '-Dpolyglot.engine.WarnInterpreterOnly=false',
                        '-javaagent:' + str(agent) + ('=' + args.mode if args.mode.startswith('bulk') else ''), '-cp', str(agent) + os.pathsep + classpath,
                        'FrozenGraalPyJsonAdmissionControl', root, scripts / 'frozen_graalpy_json_admission.py',
                        args.input.resolve(), args.mode], check=True)


if __name__ == '__main__':
    run()
