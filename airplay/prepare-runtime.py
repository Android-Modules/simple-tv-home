#!/usr/bin/env python3
"""Extract the pinned Android AirPlay Server native runtime for APK packaging."""

import hashlib
import os
from pathlib import Path
import shutil
import sys
import tempfile
import urllib.request
import zipfile


VERSION = "0.0.36"
APK_URL = (
    "https://github.com/jqssun/android-airplay-server/releases/download/"
    f"v{VERSION}/app-release.apk"
)
APK_SHA256 = "b8c7c595ed93f91562feedce92c27800f08f9c84df424f6ac2045e82324cf2a4"
ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64")
LIBRARIES = ("libairplay_native.so", "libcrypto.so", "liboboe.so", "libc++_shared.so")


def digest(path):
    checksum = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            checksum.update(chunk)
    return checksum.hexdigest()


def runtime_apk(cache_dir):
    cache_dir.mkdir(parents=True, exist_ok=True)
    apk = cache_dir / f"jqssun-v{VERSION}-app-release.apk"
    if apk.is_file() and digest(apk) == APK_SHA256:
        return apk

    with tempfile.TemporaryDirectory(dir=cache_dir) as staging:
        download = Path(staging) / apk.name
        request = urllib.request.Request(APK_URL, headers={"User-Agent": "liuguang-airplay-build"})
        with urllib.request.urlopen(request, timeout=45) as source, download.open("wb") as target:
            shutil.copyfileobj(source, target)
        if digest(download) != APK_SHA256:
            raise ValueError(f"Unexpected SHA-256 for Android AirPlay Server {VERSION}")
        os.replace(download, apk)
    return apk


def extract_runtime(apk, output_dir):
    output_dir.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(apk) as archive, tempfile.TemporaryDirectory(dir=output_dir) as staging:
        for abi in ABIS:
            abi_dir = output_dir / abi
            abi_dir.mkdir(exist_ok=True)
            for library in LIBRARIES:
                temporary = Path(staging) / library
                with archive.open(f"lib/{abi}/{library}") as source, temporary.open("wb") as target:
                    shutil.copyfileobj(source, target)
                final = abi_dir / library
                if not final.is_file() or digest(final) != digest(temporary):
                    os.replace(temporary, final)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("Usage: prepare-runtime.py CACHE_DIR JNI_LIBS_DIR")
    cache_dir, jni_libs_dir = map(Path, sys.argv[1:])
    try:
        extract_runtime(runtime_apk(cache_dir), jni_libs_dir)
    except (OSError, KeyError, ValueError, zipfile.BadZipFile) as error:
        sys.exit(f"Could not prepare Android AirPlay Server {VERSION}: {error}")
