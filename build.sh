#!/usr/bin/env bash
# Builds SamsungRevive.apk with aapt2 + javac + d8 + apksig (v1, SHA-1), the HTC Revive toolchain.
# TOOLS must contain android-17.jar (or android-19.jar), aapt2(.exe), r8.jar and apksig.jar.
set -euo pipefail
cd "$(dirname "$0")"
TOOLS="${TOOLS:?set TOOLS to the directory with android-17.jar, aapt2, r8.jar and apksig.jar}"
AAPT2="$TOOLS/aapt2"; [ -x "$AAPT2.exe" ] && AAPT2="$AAPT2.exe"
ANDROID_JAR="$TOOLS/android-17.jar"; [ -f "$ANDROID_JAR" ] || ANDROID_JAR="$TOOLS/android-19.jar"

# The local CA and *.accu-weather.com leaf are per-build secrets and never committed.
if [ ! -f assets/server.p12 ] || [ ! -f assets/ca.der ] || [ ! -f assets/ca.hash ]; then
    python3 tools/gen-certs.py
fi

rm -rf build && mkdir -p build/classes build/dex build/res
"$AAPT2" compile --dir res -o build/res/res.zip
"$AAPT2" link -o build/base.apk --manifest AndroidManifest.xml -I "$ANDROID_JAR" -A assets \
    --min-sdk-version 8 --target-sdk-version 17 build/res/res.zip
javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 -bootclasspath "$ANDROID_JAR" \
    -d build/classes $(find src -name '*.java')
java -cp "$TOOLS/r8.jar" com.android.tools.r8.D8 --release --min-api 8 --lib "$ANDROID_JAR" \
    --output build/dex $(find build/classes -name '*.class')
cp build/base.apk build/unsigned.apk
(cd build/dex && jar uf ../unsigned.apk classes.dex)
# HTC Revive's wolfSSL bridge (TLS 1.2 for upstream HTTPS); ndk-build layout libs/<abi> -> lib/<abi>.
if [ -d libs ]; then
    rm -rf build/apklib && mkdir -p build/apklib/lib
    cp -r libs/* build/apklib/lib/
    (cd build/apklib && jar uf ../unsigned.apk lib)
fi

mkdir -p certs
if [ ! -f certs/release.keystore ]; then
    keytool -genkeypair -storetype PKCS12 -keystore certs/release.keystore -storepass samsungrevive -keypass samsungrevive \
        -alias samsungrevive -keyalg RSA -keysize 2048 -sigalg SHA256withRSA -validity 10000 -dname "CN=SamsungRevive"
fi
# jarsigner's CMS signed attributes break PackageManager on Android < 4.3, so sign with apksig (SHA-1 for minSdk 8).
java -cp "$TOOLS/apksig.jar" tools/ApkSign.java certs/release.keystore samsungrevive samsungrevive \
    build/unsigned.apk SamsungRevive.apk 8
echo "built $(pwd)/SamsungRevive.apk"
