#!/usr/bin/env python3
"""Compare baseline SDK contracts and validate newly introduced API revisions.

The historical japicmp side is pinned to release 0.4.0. Packages introduced
later remain governed public API, but are validated as current artifacts rather
than being compared to classes that did not exist in that baseline.
"""
import argparse
from collections import Counter
import json
import os
import re
from pathlib import Path
import subprocess
import tarfile
import tempfile
import zipfile

BASELINE = 'a5f17cfe7ce9a6bed71c4a56f80254f509eebc46'
MODULES = ('regelsuche-core', 'regelsuche-egraph', 'regelsuche-search',
           'regelsuche-validation', 'regelsuche-discovery', 'regelsuche-discovery-sdk')
# Every public revision-2 type, including public nested types, belongs to its own JAR.
REQUIRED_EXTENSION_CLASSES_BY_MODULE = {
    'regelsuche-extension-api': {
        'de/regelsuche/extension/' + name + '.class' for name in (
            'ExtensionApi', 'ExtensionCatalog', 'ExtensionCatalogs', 'ExtensionContext',
            'ExtensionDescriptor', 'ExtensionOrigin', 'ExtensionOrigin$OriginKind',
            'ExtensionPoint', 'PluginDependency', 'PluginDescriptor', 'RegelsuchePlugin',
            'RegisteredExtension',
        )
    },
    'regelsuche-extension-runtime': {
        'de/regelsuche/extension/runtime/' + name + '.class' for name in (
            'AdmittedPluginArtifact', 'CatalogReloadResult', 'ExtensionRuntime',
            'ExtensionRuntimeConfig', 'PluginArtifactAdmission',
        )
    },
}
REQUIRED_EXTENSION_CLASSES = set().union(*REQUIRED_EXTENSION_CLASSES_BY_MODULE.values())
REQUIRED_EXTENSION_PACKAGES = {
    'de.regelsuche.extension',
    'de.regelsuche.extension.runtime',
}


def run(command, cwd):
    subprocess.run(command, cwd=cwd, check=True)


def validate_extension_revision(policy, jars):
    if policy.get('extensionApiVersion') != '2':
        raise RuntimeError('public API policy must declare extensionApiVersion=2')
    stable = set(policy.get('stablePackages', ()))
    missing_packages = sorted(REQUIRED_EXTENSION_PACKAGES - stable)
    if missing_packages:
        raise RuntimeError(
            f'extension API packages missing from stablePackages: {missing_packages}'
        )
    baseline = set(policy.get('baselineCompatibilityPackages', ()))
    overlap = sorted(REQUIRED_EXTENSION_PACKAGES & baseline)
    if overlap:
        raise RuntimeError(
            'new extension packages must not be compared against the 0.4.0 baseline: '
            f'{overlap}'
        )

    contents = []
    for jar in jars:
        with zipfile.ZipFile(jar) as archive:
            entries = archive.namelist()
            duplicates = sorted(name for name, count in Counter(entries).items() if count > 1)
            if duplicates:
                raise RuntimeError(f'duplicate ZIP entries in API artifact {jar}: {duplicates}')
            contents.append((Path(jar), set(entries)))
    for module, required in REQUIRED_EXTENSION_CLASSES_BY_MODULE.items():
        owned = [(jar, entries) for jar, entries in contents
                 if re.fullmatch(re.escape(module) + r'-[0-9][A-Za-z0-9.+_-]*\.jar', jar.name)
                 and not jar.name.endswith(('-sources.jar', '-javadoc.jar'))]
        if len(owned) != 1:
            raise RuntimeError(f'expected exactly one current {module} binary artifact')
        owner, observed = owned[0]
        missing = sorted(required - observed)
        if missing:
            raise RuntimeError(f'current {module} API artifact is incomplete: {missing}')
        for jar, entries in contents:
            misplaced = sorted(required & entries) if jar != owner else []
            if misplaced:
                raise RuntimeError(f'extension API classes in the wrong artifact {jar}: {misplaced}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--japicmp', type=Path, required=True)
    parser.add_argument('--external-classpath', required=True)
    parser.add_argument('--new-jars', nargs='+', type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    policy = json.loads((root / 'config/sdk/public-api.json').read_text())
    new_jars = [jar.resolve() for jar in args.new_jars]
    validate_extension_revision(policy, new_jars)

    # The argument is computed from Gradle's external ModuleComponentIdentifiers.
    if any('regelsuche' in Path(part).name.lower()
           for part in args.external_classpath.split(os.pathsep)):
        raise RuntimeError('old API compilation must not use current Regelsuche artifacts')

    report = root / 'build/reports/sdk-api-compatibility'
    report.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='sdk-api-', dir=root / 'build') as temporary:
        work = Path(temporary)
        archive = work / 'baseline.tar'
        with archive.open('wb') as output:
            subprocess.run(
                ['git', 'archive', BASELINE, *MODULES,
                 'app/src/main/java/de/regelsuche/plugin'],
                cwd=root, stdout=output, check=True)
        old = work / 'old'
        old.mkdir()
        with tarfile.open(archive) as source:
            source.extractall(old, filter='data')
        classes = work / 'classes'
        classes.mkdir()
        sources = sorted(
            (old / 'regelsuche-discovery-sdk/src/main/java/de/regelsuche/sdk/discovery')
            .glob('*.java')
        )
        for name in policy['stablePluginClasses']:
            file = old / f'app/src/main/java/de/regelsuche/plugin/{name}.java'
            if file.exists():
                sources.append(file)
        sourcepath = os.pathsep.join(
            str(old / module / 'src/main/java') for module in MODULES
        )
        # Plugin dependencies are explicit sources; do not put app on sourcepath.
        run([
            'javac', '--release', '25', '-cp', args.external_classpath,
            '-sourcepath', sourcepath, '-d', str(classes), *map(str, sources)
        ], root)
        old_jar = work / 'api-0.4.0.jar'
        run(['jar', '--create', '--file', str(old_jar), '-C', str(classes), '.'], root)

        baseline_packages = policy.get('baselineCompatibilityPackages')
        if not isinstance(baseline_packages, list) or not baseline_packages:
            raise RuntimeError(
                'public API policy must declare non-empty baselineCompatibilityPackages'
            )
        if not set(baseline_packages).issubset(set(policy['stablePackages'])):
            raise RuntimeError(
                'baselineCompatibilityPackages must be a subset of stablePackages'
            )
        include = ';'.join([
            *baseline_packages,
            *('de.regelsuche.plugin.' + name for name in policy['stablePluginClasses'])
        ])

        # Filtering the compared API does not place its excluded dependency
        # classes in japicmp's class pools. Resolve each side from its own
        # complete archives, while keeping current code off the old classpath.
        old_classpath = os.pathsep.join([str(old_jar), args.external_classpath])
        new_classpath = os.pathsep.join([
            *(str(jar) for jar in new_jars), args.external_classpath
        ])
        command = [
            'java', '-jar', str(args.japicmp),
            '--old', str(old_jar),
            '--new', ';'.join(str(jar) for jar in new_jars),
            '--old-classpath', old_classpath,
            '--new-classpath', new_classpath,
            '--include', include,
            '--include-exclusively',
            '--access-modifier', 'protected',
            '--error-on-binary-incompatibility',
            '--error-on-source-incompatibility',
            '--html-file', str(report / 'api-diff.html'),
            '--xml-file', str(report / 'api-diff.xml')
        ]
        # CLI uses -a for access; the long spelling is not part of its contract.
        command[command.index('--access-modifier')] = '-a'
        output_path = report / 'api-diff.txt'
        with output_path.open('w') as output:
            result = subprocess.run(
                command, cwd=root, stdout=output, stderr=subprocess.STDOUT
            )
        if result.returncode:
            print(output_path.read_text(), flush=True)
            result.check_returncode()
        (report / 'baseline.json').write_text(json.dumps({
            'release': '0.4.0',
            'commit': BASELINE,
            'result': 'compatible',
            'checked': ['binary', 'source'],
            'tool': 'japicmp-0.26.2',
            'baselineCompatibilityPackages': baseline_packages,
            'extensionApiVersion': policy['extensionApiVersion'],
            'newExtensionPackages': sorted(REQUIRED_EXTENSION_PACKAGES),
        }, indent=2) + '\n')


if __name__ == '__main__':
    main()
