#!/usr/bin/env python3
"""Check published metadata and licensing for the executable, sources and docs."""
from pathlib import Path
import argparse, subprocess, zipfile, xml.etree.ElementTree as ET
parser=argparse.ArgumentParser();parser.add_argument('--signatures',action='store_true');args=parser.parse_args()
root=Path(__file__).resolve().parents[1];ns={'m':'http://maven.apache.org/POM/4.0.0'};pom=ET.parse(root/'pom.xml').getroot()
value=lambda key:pom.findtext('m:'+key,namespaces=ns)
version=value('version');assert value('groupId')=='io.github.ghosthack' and value('artifactId')=='harness-stim'
assert version and not version.endswith('-SNAPSHOT');assert value('scm/m:tag')=='v'+version
assert value('licenses/m:license/m:name')=='MIT License'
license_bytes=(root/'LICENSE').read_bytes();files=[]
for suffix in ['.jar','-sources.jar','-javadoc.jar']:
    path=root/'target'/('harness-stim-'+version+suffix);files.append(path)
    with zipfile.ZipFile(path) as jar:
        prefix='doc-files/' if suffix=='-javadoc.jar' else 'META-INF/'
        assert jar.read(prefix+'LICENSE')==license_bytes, str(path)+' license mismatch'
        assert jar.read(prefix+'THIRD-PARTY.md')==(root/'THIRD-PARTY.md').read_bytes()
        if suffix=='.jar':
            assert 'io/github/ghosthack/harnessstimulator/Main.class' in jar.namelist()
            manifest=jar.read('META-INF/MANIFEST.MF').decode()
            assert 'Main-Class: io.github.ghosthack.harnessstimulator.Main' in manifest
            assert 'Implementation-Version: '+version in manifest
            assert not any(name.startswith(('org/junit/','org/opentest4j/')) for name in jar.namelist())
        elif suffix=='-sources.jar':assert 'io/github/ghosthack/harnessstimulator/Main.java' in jar.namelist()
        else:assert 'index.html' in jar.namelist()
if args.signatures:
    deployed_pom=root/'target'/('harness-stim-'+version+'.pom');files.append(deployed_pom)
    for path in files:subprocess.run(['gpg','--batch','--verify',str(path)+'.asc',str(path)],check=True)
print('Verified io.github.ghosthack:harness-stim:'+version+' release packaging')
