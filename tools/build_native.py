#!/usr/bin/env python3
"""Build the exact SDK and its JNI bridge; no device operations."""
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
PIN = '6248da9f5a6ccdca345f45858f4a0c3fe8ef5a76'
def run(*args):
    subprocess.run([str(a) for a in args], cwd=ROOT, check=True)
def main():
    revision = subprocess.check_output(['git', '-C', str(ROOT/'xdb'), 'rev-parse', 'HEAD'], text=True).strip()
    if revision != PIN or subprocess.check_output(['git','-C',str(ROOT/'xdb'),'diff','HEAD','--'],text=True):
        raise SystemExit('xdb must be an unmodified v1.0.1 checkout: '+PIN)
    sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home()/'Library/Android/sdk')))
    ndk = Path(os.environ.get('ANDROID_NDK_ROOT', str(sdk/'ndk/29.0.14206865')))
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
               ndk/'toolchains/llvm/prebuilt/darwin-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so']
    libraries += [deps/f'lib/lib{name}.so' for name in ['avcodec','avutil','swscale']]
    for lib in libraries: shutil.copy2(lib,target/lib.name)
if __name__=='__main__': main()
