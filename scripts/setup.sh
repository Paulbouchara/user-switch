#!/usr/bin/env bash
# Gets everything build.sh needs, downloading what is missing:
#   - Android build-tools 36 and platform android-36: taken from an SDK that already
#     has them ($ANDROID_HOME, $ANDROID_SDK_ROOT, ~/Android/Sdk), otherwise downloaded
#     from Google into .sdk/ (about 130 MB, once);
#   - the Shizuku client libraries: committed in libs/, fetched again from Maven
#     Central if one is missing.
# Every download and every jar in libs/ is checked against the SHA-256 pinned below.
#
# build.sh sources it and gets BT (build-tools directory) and JAR (android.jar).
# Run on its own, it only fetches and checks (e.g. before going offline).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

SDK_URL=https://dl.google.com/android/repository
BT_VERSION=36.0.0
case $(uname -s)-$(uname -m) in
    Linux-x86_64) BT_ZIP=build-tools_r36_linux.zip  BT_SHA=5d9ac77fb6ff43d9da518a337b4fcf8f9097113df531d99ccefe80ef7ce8250b ;;
    Darwin-*)     BT_ZIP=build-tools_r36_macosx.zip BT_SHA=04e7f3a72044de4926fa038fa0e251a37bba1e1c3fb8beab6f8401bfd9eb4bf3 ;;
    *)            BT_ZIP= BT_SHA= ;;
esac
PLATFORM=android-36
PLATFORM_ZIP=platform-36_r02.zip
PLATFORM_SHA=37607369a28c5b640b3a7998868d45898ebcb777565a0e85f9acf36f29631d2e

MAVEN=https://repo1.maven.org/maven2/dev/rikka/shizuku
SHIZUKU_VERSION=13.1.5

TMP=.sdk/.tmp

die() { echo "setup: $*" >&2; exit 1; }

need() { command -v "$1" >/dev/null || die "$1 not found: $2"; }

sha256() {
    if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1
}

check() { # file sha256
    local got
    got=$(sha256 "$1")
    [ "$got" = "$2" ] || die "$1: SHA-256 is $got, expected $2"
}

fetch() { # url file
    echo "setup: downloading $1" >&2
    if command -v curl >/dev/null; then
        curl -fL --retry 3 --progress-bar -o "$2" "$1"
    elif command -v wget >/dev/null; then
        wget -q --show-progress -O "$2" "$1"
    else
        die "curl or wget is needed to download $1"
    fi
}

sdk_get() { # zip sha256 dest: the zip holds a single folder, which becomes dest
    need unzip "install unzip to unpack $1"
    rm -rf "$TMP"
    mkdir -p "$TMP/x" "$(dirname "$3")"
    fetch "$SDK_URL/$1" "$TMP/$1"
    check "$TMP/$1" "$2"
    unzip -q "$TMP/$1" -d "$TMP/x"
    rm -rf "$3"
    mv "$TMP"/x/* "$3"
    rm -rf "$TMP"
}

need javac "install a JDK 17 or later"
need keytool "install a JDK 17 or later"
need zip "install zip"
jdk=$(javac -version 2>&1 | sed -n 's/^javac \([0-9]*\).*/\1/p')
[ "${jdk:-0}" -ge 17 ] || die "javac ${jdk:-?} found, a JDK 17 or later is needed"

# An SDK that has both parts is used as is; .sdk/ comes last.
BT=
for SDK in ${ANDROID_HOME:+"$ANDROID_HOME"} ${ANDROID_SDK_ROOT:+"$ANDROID_SDK_ROOT"} "$HOME/Android/Sdk" .sdk; do
    for d in "$SDK"/build-tools/36*; do
        if [ -x "$d/aapt2" ]; then BT=$d; fi
    done
    if [ -n "$BT" ] && [ -f "$SDK/platforms/$PLATFORM/android.jar" ]; then break; fi
    BT=
done
if [ -z "$BT" ]; then
    SDK=.sdk
    [ -n "$BT_ZIP" ] || die "Google ships no build-tools for $(uname -s) $(uname -m):" \
        "set ANDROID_HOME to an SDK with build-tools 36 and platforms/$PLATFORM"
    echo "setup: no SDK with build-tools 36 and platforms/$PLATFORM, fetching what is missing into $SDK/" >&2
    BT=$SDK/build-tools/$BT_VERSION
    [ -x "$BT/aapt2" ] || sdk_get "$BT_ZIP" "$BT_SHA" "$BT"
    [ -f "$SDK/platforms/$PLATFORM/android.jar" ] ||
        sdk_get "$PLATFORM_ZIP" "$PLATFORM_SHA" "$SDK/platforms/$PLATFORM"
fi
JAR=$SDK/platforms/$PLATFORM/android.jar

# libs/shizuku-<name>-<version>.jar is the classes.jar of the .aar on Maven Central.
#   name     SHA-256 of the .aar                                              SHA-256 of its classes.jar
while read -r name aar_sha jar_sha; do
    jar=libs/shizuku-$name-$SHIZUKU_VERSION.jar
    if [ ! -f "$jar" ]; then
        need unzip "install unzip to unpack $name-$SHIZUKU_VERSION.aar"
        rm -rf "$TMP"
        mkdir -p "$TMP" libs
        fetch "$MAVEN/$name/$SHIZUKU_VERSION/$name-$SHIZUKU_VERSION.aar" "$TMP/$name.aar"
        check "$TMP/$name.aar" "$aar_sha"
        unzip -p "$TMP/$name.aar" classes.jar > "$TMP/classes.jar"
        check "$TMP/classes.jar" "$jar_sha"
        mv "$TMP/classes.jar" "$jar"
        rm -rf "$TMP"
    fi
    check "$jar" "$jar_sha"
done <<'EOF'
api      4def9bde498ef8626614c2fc5db9af4749c86f16f6c33e3f5658d35e70bab59b 4315fcd853f487321548be5a997749fe43105fa29aaaa6f6e7a8cdc958a38357
provider b0f18cd9812464ec171c53cac93a819fe411718a3965c311f01eb4de265381b3 35aee86b938cb8d06b315c9d73c30f41ec7cfdc10009a5de83733bc5c4db5051
shared   4659642c9339be0a26e9c65bb8648f7ad6d8f4a465f557993ccbc78802381635 8557b8c715d374f8da18e148ca5b971d1c7435e91447e6d4f7664403c7328760
aidl     33fe7191cdd69fcb66d649264f3b0c47acb2f3d6343afc05b98dbbff6f221963 584945c98f21b0c4e3ccdb74b51528f89852174c30bb2a4786c43a8ca456e496
EOF

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
    echo "build-tools: $BT"
    echo "android.jar: $JAR"
    echo "Shizuku $SHIZUKU_VERSION: libs/"
fi
