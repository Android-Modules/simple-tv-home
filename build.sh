#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
build_tools="$sdk_dir/build-tools/${BUILD_TOOLS_VERSION:-36.1.0}"
android_jar="$sdk_dir/platforms/${ANDROID_PLATFORM:-android-36}/android.jar"
build_dir="$project_dir/build"
signing_dir="$project_dir/.signing"
dependency_dir="$build_dir/dependencies"
version="1.3.12"
target_apk="$build_dir/TVHome-$version.apk"

if [[ -z "${JAVA_HOME:-}" && -x "$HOME/.local/share/mise/installs/java/17/bin/java" ]]; then
    export JAVA_HOME="$HOME/.local/share/mise/installs/java/17"
fi
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}"
for file in "$android_jar" "$build_tools/aapt2" "$build_tools/d8" "$build_tools/apksigner" "$build_tools/zipalign"; do
    if [[ ! -r "$file" ]]; then
        printf '找不到 Android SDK 文件 %s\n' "$file" >&2
        exit 1
    fi
done

mkdir -p "$dependency_dir"
work_dir="$(mktemp -d "$build_dir/.build.XXXXXX")"
trap 'rm -r -- "$work_dir"' EXIT
mkdir -p "$work_dir/classes" "$work_dir/dex" "$work_dir/generated" \
    "$work_dir/assets/airplay" "$work_dir/lib" "$build_dir/airplay-runtime"

resolve_dependency() {
    local artifact="$1" checksum="$2"
    local name="${artifact##*/}"
    local cached="$dependency_dir/$name" downloaded="$work_dir/$name"
    if [[ -f "$cached" ]] && printf '%s  %s\n' "$checksum" "$cached" | shasum -a 256 --status -c; then
        printf '%s\n' "$cached"
        return
    fi
    curl --fail --silent --show-error --retry 2 --connect-timeout 10 --max-time 90 \
        "https://repo.maven.apache.org/maven2/$artifact" --output "$downloaded" || return 1
    if ! printf '%s  %s\n' "$checksum" "$downloaded" | shasum -a 256 --status -c; then
        printf '依赖校验失败 %s\n' "$name" >&2
        return 1
    fi
    mv "$downloaded" "$cached" || return 1
    printf '%s\n' "$cached"
}

zxing_jar="$(resolve_dependency "com/google/zxing/core/3.5.3/core-3.5.3.jar" \
    "8d8064c1636fdaef7189dd9055c7d59950a8940a12f2293956446ec3c109fd82")"
bcprov_jar="$(resolve_dependency "org/bouncycastle/bcprov-jdk18on/1.80.2/bcprov-jdk18on-1.80.2.jar" \
    "b33227f07dce264daf1aac2e798ef109a4901f31abedac53635746dd93679d3b")"

python3 "$project_dir/airplay/prepare-runtime.py" "$build_dir/airplay-runtime" "$work_dir/lib"
cp "$project_dir/airplay/LICENSE" "$work_dir/assets/airplay/LICENSE"

"$build_tools/aapt2" compile --dir "$project_dir/app/res" -o "$work_dir/resources.zip"
"$build_tools/aapt2" link -I "$android_jar" --manifest "$project_dir/app/AndroidManifest.xml" \
    -A "$work_dir/assets" --java "$work_dir/generated" \
    -o "$work_dir/unsigned.apk" "$work_dir/resources.zip"

sources=()
while IFS= read -r -d '' source_file; do
    sources+=("$source_file")
done < <(find "$project_dir/app/src" "$work_dir/generated" -name '*.java' -print0)
"${java_bin}javac" --release 17 -encoding UTF-8 -Xlint:all -cp "$android_jar:$zxing_jar:$bcprov_jar" \
    -d "$work_dir/classes" "${sources[@]}"
"${java_bin}jar" cf "$work_dir/classes.jar" -C "$work_dir/classes" .
"$build_tools/d8" --release --lib "$android_jar" --min-api 26 \
    --output "$work_dir/dex" "$work_dir/classes.jar" "$zxing_jar" "$bcprov_jar"
zip -q -j "$work_dir/unsigned.apk" "$work_dir/dex/"classes*.dex
(cd -- "$work_dir" && zip -0 -qr "unsigned.apk" "lib")
"$build_tools/zipalign" -f -P 16 4 "$work_dir/unsigned.apk" "$work_dir/aligned.apk"

# Keep the local signing key so later versions can update the installed app.
if [[ ! -f "$signing_dir/tv-home.p12" ]]; then
    (umask 077
    mkdir -p "$signing_dir"
    "${java_bin}keytool" -genkeypair -keystore "$signing_dir/tv-home.p12" \
        -storetype PKCS12 -storepass android -keypass android -alias tv-home \
        -keyalg RSA -keysize 3072 -validity 10950 -dname "CN=TV Home Local")
fi
"$build_tools/apksigner" sign --ks "$signing_dir/tv-home.p12" --ks-key-alias tv-home \
    --ks-pass pass:android --key-pass pass:android --out "$work_dir/signed.apk" "$work_dir/aligned.apk"
"$build_tools/apksigner" verify "$work_dir/signed.apk"
"$build_tools/zipalign" -c -P 16 4 "$work_dir/signed.apk"
mv "$work_dir/signed.apk" "$target_apk"
printf '%s\n' "$target_apk"
