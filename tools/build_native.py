#!/usr/bin/env python3
"""Build the exact SDK and its JNI bridge; no device operations."""
import os
import hashlib
import json
import platform
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
PIN = 'd65d8d48a934f96f56e696aead8629d1ceeb82e6'
def run(*args):
    subprocess.run([str(a) for a in args], cwd=ROOT, check=True)
def main():
    revision = subprocess.check_output(['git', '-C', str(ROOT/'xdb'), 'rev-parse', 'HEAD'], text=True).strip()
    if revision != PIN or subprocess.check_output(['git','-C',str(ROOT/'xdb'),'diff','HEAD','--'],text=True):
        raise SystemExit('xdb must be an unmodified pinned SDK checkout: '+PIN)
    sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home()/'Library/Android/sdk')))
    ndk = Path(os.environ.get('ANDROID_NDK_ROOT', str(sdk/'ndk/29.0.14206865')))
    host = 'darwin-x86_64' if platform.system() == 'Darwin' else 'linux-x86_64'
    deps = ROOT/'.native/deps'
    if not (deps/'lib/libswscale.so').exists():
        env = dict(os.environ, ANDROID_NDK_ROOT=str(ndk), JOBS='4')
        subprocess.run(['bash',str(ROOT/'xdb/tools/android-deps.sh'),str(deps)],env=env,check=True)
    flags = [f'-DCMAKE_TOOLCHAIN_FILE={ndk}/build/cmake/android.toolchain.cmake',
             '-DANDROID_ABI=arm64-v8a','-DANDROID_PLATFORM=android-26','-DANDROID_STL=c++_shared',
             '-DCMAKE_BUILD_TYPE=Release',f'-DCMAKE_FIND_ROOT_PATH={deps}',
             f'-DOPENSSL_ROOT_DIR={deps}',f'-DOPENSSL_INCLUDE_DIR={deps}/include',
             f'-DOPENSSL_CRYPTO_LIBRARY={deps}/lib/libcrypto.a',f'-DOPENSSL_SSL_LIBRARY={deps}/lib/libssl.a',
             f'-DCURL_INCLUDE_DIR={deps}/include',f'-DCURL_LIBRARY={deps}/lib/libcurl.a',
             f'-DXDB_AV_INCLUDE={deps}/include']
    for name in ['avcodec','avutil','swscale']:
        flags.append(f'-DXDB_{name.upper()}_LIBRARY={deps}/lib/lib{name}.so')
    run('cmake','-S',ROOT,'-B',ROOT/'.native/build','-G','Ninja',*flags)
    run('cmake','--build',ROOT/'.native/build','--parallel','4')
    target=ROOT/'app/build/native-libs/arm64-v8a';target.mkdir(parents=True,exist_ok=True)
    # Package one shared C++ runtime for all native DSOs.
    libraries=[ROOT/'.native/build/libscrctl_jni.so', ROOT/'.native/build/xdb/libxdb_client.so',
               ROOT/'.native/build/xdb/libxdb_server.so',
               ndk/f'toolchains/llvm/prebuilt/{host}/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so']
    libraries += [deps/f'lib/lib{name}.so' for name in ['avcodec','avutil','swscale']]
    for lib in libraries: shutil.copy2(lib,target/lib.name)
    assets=ROOT/'app/build/native-assets'; notices=assets/'licenses'; notices.mkdir(parents=True,exist_ok=True)
    def notice(source, name):
        if source.is_file():
            destination=notices/name;destination.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,destination)
    for name in ['LICENSE','COPYING.GPLv3','THIRD_PARTY_NOTICES.md']:
        notice(ROOT/'xdb'/name,Path('XDeviceBridge')/name)
    for component in ['openssl-3.5.9','curl-8.23.0','ffmpeg-9.0.2']:
        for source in (ROOT/'.native/src'/component).glob('*'):
            if source.name.startswith(('LICENSE','COPYING','NOTICE')): notice(source,Path(component)/source.name)
    for component in ['nlohmann_json','pugixml','mdns','lwip']:
        for source in (ROOT/'.native/build/_deps'/f'{component}-src').glob('*'):
            if source.name.startswith(('LICENSE','COPYING','NOTICE')): notice(source,Path(component)/source.name)
    notice(ndk/'NOTICE.toolchain',Path('NDK/NOTICE.toolchain'))
    metadata={'sdk_version':'1.4.0','sdk_commit':PIN,'ndk':'29.0.14206865',
              'libraries':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in target.glob('*.so')}}
    (assets/'native-build.json').write_text(json.dumps(metadata,indent=2)+'\n')
if __name__=='__main__': main()
