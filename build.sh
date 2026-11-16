#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
build_tools="$sdk_dir/build-tools/${BUILD_TOOLS_VERSION:-36.1.0}"
android_jar="$sdk_dir/platforms/${ANDROID_PLATFORM:-android-36}/android.jar"
build_dir="$project_dir/build"
signing_dir="$project_dir/.signing"
version="1.3.7"
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

mkdir -p "$build_dir"
work_dir="$(mktemp -d "$build_dir/.build.XXXXXX")"
trap 'rm -r -- "$work_dir"' EXIT
mkdir -p "$work_dir/classes" "$work_dir/dex"

"$build_tools/aapt2" compile --dir "$project_dir/app/res" -o "$work_dir/resources.zip"
"$build_tools/aapt2" link -I "$android_jar" --manifest "$project_dir/app/AndroidManifest.xml" \
    -o "$work_dir/unsigned.apk" "$work_dir/resources.zip"

sources=()
while IFS= read -r -d '' source_file; do
    sources+=("$source_file")
done < <(find "$project_dir/app/src" -name '*.java' -print0)
"${java_bin}javac" --release 8 -encoding UTF-8 -Xlint:all -cp "$android_jar" \
    -d "$work_dir/classes" "${sources[@]}"
"${java_bin}jar" cf "$work_dir/classes.jar" -C "$work_dir/classes" .
"$build_tools/d8" --release --lib "$android_jar" --min-api 26 \
    --output "$work_dir/dex" "$work_dir/classes.jar"
zip -q -j "$work_dir/unsigned.apk" "$work_dir/dex/classes.dex"
"$build_tools/zipalign" -f -p 4 "$work_dir/unsigned.apk" "$work_dir/aligned.apk"

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
mv "$work_dir/signed.apk" "$target_apk"
printf '%s\n' "$target_apk"
