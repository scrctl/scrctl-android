#!/usr/bin/env python3
"""Collect exact application/SDK sources and original native dependency archives."""
from pathlib import Path
import subprocess
import tarfile
import tempfile
ROOT=Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory() as temporary:
    staging=Path(temporary)
    for directory,name in [(ROOT,'scrctl-android'),(ROOT/'xdb','XDeviceBridge')]:
        with (staging/f'{name}.tar').open('wb') as stream:
            subprocess.run(['git','-C',str(directory),'archive','HEAD'],stdout=stream,check=True)
    sources=list((ROOT/'.native/src').glob('*.tar.*'))
    if len(sources)!=3: raise SystemExit('Expected exact OpenSSL, curl and FFmpeg source archives')
    target=ROOT/'build/scrctl-android-sources.tar.gz';target.parent.mkdir(exist_ok=True)
    with tarfile.open(target,'w:gz') as archive:
        for p in staging.iterdir(): archive.add(p,arcname=p.name)
        for p in sources: archive.add(p,arcname='dependencies/'+p.name)
        archive.add(ROOT/'app/build/native-assets/native-build.json',arcname='native-build.json')
        archive.add(ROOT/'.native/build/CMakeCache.txt',arcname='build/CMakeCache.txt')
print(target)
