"""Stage only pinned public materials, verify the development input, and package the JVM runtime."""

import hashlib
import json
from pathlib import Path
import shutil
import sys
import tarfile
import urllib.request
import zipfile

ROOT = Path('stage')
OUT = Path('evidence')
GRAALPY = 'a88321e8b4440a08abaa7b163a85e038a39ec9a0'
GRAAL = 'd9de8206fff02d7a99bf82043b5ba8b392aa09ee'
BASE = 'graalpython/com.oracle.graal.python/src/com/oracle/graal/python/builtins/'
SOURCES = {
    'modules/json/JSONScannerBuiltins.java': 'ba2b705988adf9c234b26e256f1a8b804f3dfed2',
    'modules/json/JSONUtils.java': '0893e33b9aff7ec82197f41b9ca6b997c15d0245',
    'modules/json/JSONModuleBuiltins.java': '0b6af411fc928d6c33ceb0011585b51198f95ff3',
    'objects/random/PRandom.java': '81be875bb7909a389942a10691d21b56f23a76d6',
    'objects/random/RandomBuiltins.java': '33b12a60408594b37f5c19cdbf851283a43831d5',
    'objects/common/SortNodes.java': '65fe319642df62b2e469675c2b98b179b50bd1a4',
}
WHEELS = {
    'mpmath-1.3.0-py3-none-any.whl': 'a0b2b9fe80bbcd81a6647ff13108738cfb482d481d826cc0e02f5b35e5c88d2c',
    'sympy-1.14.0-py3-none-any.whl': 'e091cc3e99d2141a0ba2847328f5479b05d94a6635cb96148ccb3f34671bd8f5',
}


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def write(name, value):
    (OUT/name).write_text(json.dumps(value, indent=2, sort_keys=True) + '\n')


def stage():
    if digest(ROOT/'bridge.py') != 'd67e266e11c92fdd3e5e2c85c30a89a7b5292f56e291bc86a9d036ac7aab23c4':
        raise ValueError('original bridge digest differs')
    for filename, expected in WHEELS.items():
        wheel = ROOT/'wheels'/filename
        if digest(wheel) != expected:
            raise ValueError('wrong public wheel: ' + filename)
        with zipfile.ZipFile(wheel) as archive:
            archive.extractall(ROOT/'packages')
    sources = []
    for relative, expected in SOURCES.items():
        url = 'https://raw.githubusercontent.com/oracle/graalpython/' + GRAALPY + '/' + BASE + relative
        with urllib.request.urlopen(url, timeout=60) as response:
            content = response.read()
        blob = hashlib.sha1(b'blob ' + str(len(content)).encode() + b'\0' + content).hexdigest()
        if blob != expected:
            raise ValueError('historical source differs: ' + relative)
        target = ROOT/'upstream'/relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
        sources.append({'path':relative, 'url':url, 'gitBlob':blob, 'sha256':digest(target)})
    write('source-bindings.json', {'graalpython':GRAALPY,'truffleRegexSdk':GRAAL,'sources':sources,
                                   'wheels':WHEELS,'bridgeSha256':digest(ROOT/'bridge.py')})


def inventory():
    files = []
    for folder in ('runtime','sources'):
        for path in sorted((ROOT/folder).rglob('*')):
            if not path.is_file() or path.suffix not in ('.jar','.pom'):
                continue
            relative = path.relative_to(ROOT/folder)
            group = '.'.join(relative.parts[:-3])
            files.append({'path':str(path.relative_to(ROOT)), 'groupId':group, 'artifactId':relative.parts[-3],
                          'version':relative.parts[-2], 'filename':path.name, 'bytes':path.stat().st_size,
                          'sha256':digest(path)})
    write('maven-artifacts.json', files)
    jars={x['artifactId'] for x in files if x['path'].startswith('runtime/') and x['filename'].endswith('.jar')}
    if not {'python-language','polyglot','truffle-api','regex'} <= jars:
        raise ValueError('incomplete resolved historical JVM classpath')
    for entry in files:
        if entry['artifactId'] in ('python','python-language','polyglot','truffle-api','regex') and entry['version'] != '25.1.3':
            raise ValueError('different GraalVM coordinate: ' + entry['path'])


def verify():
    response = json.loads((OUT/'original-output.json').read_bytes())
    info = json.loads((OUT/'bridge-runtime.json').read_bytes())
    def pair(n):
        return {'numerator':str(n),'denominator':'1'}
    def term(power, n):
        return {**pair(n),'exponents':[power]}
    expected = [{'multiplicity':1,'terms':[term(2,1),term(0,n)]} for n in (1,2,3)]
    math = {k:response[k] for k in ('protocol','domain','unit','factors')}
    if math != {'protocol':'regelsuche.sympy-factorization/v1','domain':'ZZ','unit':pair(1),'factors':expected}:
        raise ValueError('public factors differ')
    if info['pythonImplementation']!='graalpy' or info['sympyVersion']!='1.14.0':
        raise ValueError('public runtime metadata differs')
    write('public-control.json', {'passed':True, 'mathematicalOutput':math,
          'scope':'PUBLIC_JVM_CLASS_AND_METHOD_ENTRIES', 'studyAuthorized':False,
          'completeBackendWork':False,'inputSha256':digest(OUT/'public-input.json')})


def package():
    archive = Path('historical-jvm.tar.gz')
    with tarfile.open(archive, 'w:gz', compresslevel=1) as tar:
        for name in ('runtime','sources','wheels','upstream','bridge.py','probe-src','agent.jar'):
            target = ROOT/name
            if target.exists(): tar.add(target, arcname=name)
        for item in OUT.glob('*.json'):
            tar.add(item, arcname='evidence/'+item.name)
    directory=Path('parts')
    directory.mkdir(exist_ok=True)
    pieces=[]
    with archive.open('rb') as stream:
        while chunk := stream.read(72 * 1024 * 1024):
            if len(pieces) >= 10:
                raise ValueError('runtime exceeds ten 72-MiB public artifact slots')
            target=directory/f'part{len(pieces):02d}'
            target.write_bytes(chunk)
            pieces.append({'name':target.name,'bytes':len(chunk),'sha256':digest(target)})
    write('archive-index.json', {'archive':archive.name,'bytes':archive.stat().st_size,'sha256':digest(archive),'parts':pieces})


if __name__ == '__main__':
    OUT.mkdir(exist_ok=True)
    {'stage':stage,'inventory':inventory,'verify':verify,'package':package}[sys.argv[1]]()
