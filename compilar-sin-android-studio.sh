#!/bin/bash
# Compila los dos APK sin Android Studio ni Gradle, con las herramientas de Debian/Ubuntu:
#   sudo apt install aapt dalvik-exchange zipalign apksigner android-sdk-platform-23 openjdk-17-jdk
# y el jar de la plataforma Android 14 de Robolectric (Maven Central):
#   curl -LO https://repo.maven.apache.org/maven2/org/robolectric/android-all/14-robolectric-10818077/android-all-14-robolectric-10818077.jar
# y un jar con java/util/function sacado de tu JDK (solo para compilar):
#   jimage extract --dir x --include 'regex:/java.base/java/util/function/.*' $JAVA_HOME/lib/modules
#   (cd x/java.base && jar cf ../../java-function.jar java/util/function)
# Uso: ANDROID_ALL=... JAVA_FUNCTION=... bash compilar-sin-android-studio.sh
# Firma: KEYSTORE (fichero .jks) y KEYSTORE_PASS. Versión: VERSION_CODE y VERSION_NAME
# (GitHub Actions las pone solas; en local se usan las de abajo).
set -euo pipefail
cd "$(dirname "$0")"
ANDROID_ALL=${ANDROID_ALL:-/opt/aa/aa14.jar}
ANDROID_23=${ANDROID_23:-/usr/lib/android-sdk/platforms/android-23/android.jar}
JAVA_FUNCTION=${JAVA_FUNCTION:-/opt/aa/java-function.jar}
KS=${KEYSTORE:-firma/subelo.jks}
KS_PASS=${KEYSTORE_PASS:?Falta KEYSTORE_PASS (contraseña de la firma)}
BOX_CODE=${VERSION_CODE:-6}; BOX_NAME=${VERSION_NAME:-1.5}
MOVIL_CODE=${VERSION_CODE:-6}; MOVIL_NAME=${VERSION_NAME:-1.5}
mkdir -p apk

build() {
  local mod=$1 pkg=$2 min=$3 target=$4 vcode=$5 vname=$6 out=$7
  local B=build/$mod
  rm -rf "$B"; mkdir -p "$B/gen" "$B/classes" "$B/dex"
  sed "s|<manifest |<manifest package=\"$pkg\" android:versionCode=\"$vcode\" android:versionName=\"$vname\" |" \
    "$mod/src/main/AndroidManifest.xml" > "$B/AndroidManifest.xml"
  local assets=()
  [ -d "$mod/src/main/assets" ] && assets=(-A "$mod/src/main/assets")
  aapt package -f -m -J "$B/gen" -M "$B/AndroidManifest.xml" -S "$mod/src/main/res" "${assets[@]}" \
    -I "$ANDROID_ALL" --min-sdk-version "$min" --target-sdk-version "$target" -F "$B/res.apk"
  javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 -bootclasspath "$ANDROID_ALL:$ANDROID_23:$JAVA_FUNCTION" \
    -d "$B/classes" $(find "$mod/src/main/java" "$B/gen" -name '*.java')
  dalvik-exchange --dex --min-sdk-version="$min" --output="$B/dex/classes.dex" "$B/classes"
  cp "$B/res.apk" "$B/unsigned.apk"
  (cd "$B/dex" && zip -q ../unsigned.apk classes.dex)
  zipalign -f -p 4 "$B/unsigned.apk" "$B/aligned.apk"
  apksigner sign --ks "$KS" --ks-pass env:KS_PASS --key-pass env:KS_PASS --v4-signing-enabled false --out "$out" "$B/aligned.apk"
  echo "OK -> $out"
}

export KS_PASS
build box   es.subelo.box   21 30 "$BOX_CODE" "$BOX_NAME" apk/Subelo-Servidor-box.apk
build movil es.subelo.movil 24 34 "$MOVIL_CODE" "$MOVIL_NAME" apk/Subelo-movil.apk
