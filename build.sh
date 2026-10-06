#!/usr/bin/env bash
# Builds build/userswitch.apk with the bare SDK tools (no Gradle).
set -euo pipefail
cd "$(dirname "$0")"

SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
BT=${BUILD_TOOLS:-/opt/android-sdk-update-manager/build-tools/36}
JAR=$SDK/platforms/android-36/android.jar
KS=${KEYSTORE:-$HOME/.android/debug.keystore}

rm -rf build
mkdir -p build/classes build/dex

"$BT/aapt2" link -o build/base.apk -I "$JAR" --manifest AndroidManifest.xml \
    --min-sdk-version 30 --target-sdk-version 36

LIBS=$(ls libs/*.jar | paste -sd:)
javac --release 17 -Xlint:-options -classpath "$JAR:$LIBS" -d build/classes $(find src -name '*.java')

"$BT/d8" --release --min-api 30 --lib "$JAR" --output build/dex $(find build/classes -name '*.class') libs/*.jar

cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk

if [ ! -f "$KS" ]; then
    mkdir -p "$(dirname "$KS")"
    keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias androiddebugkey \
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out build/userswitch.apk build/aligned.apk

echo "built build/userswitch.apk"
